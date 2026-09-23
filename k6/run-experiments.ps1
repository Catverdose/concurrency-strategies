param(
    [ValidateSet('basic', 'contention', 'performance', 'duplicate-user', 'all')]
    [string]$Stage = 'duplicate-user',

    [string]$BaseUrl = 'http://localhost:8080',

    [int]$Vus = 50,

    [ValidateSet(
        'direct',
        'jvm-lock',
        'pessimistic',
        'optimistic',
        'conditional',
        'redis-lock',
        'redis-decr',
        'redis-lua',
        'redis-watch'
    )]
    [string[]]$Strategies = @(
        'direct',
        'jvm-lock',
        'pessimistic',
        'optimistic',
        'conditional',
        'redis-lock',
        'redis-decr',
        'redis-lua',
        'redis-watch'
    ),

    [string]$RunPrefix = ('r' + (Get-Date -Format 'MMddHHmm')),

    [ValidateRange(0, 600)]
    [int]$CooldownSeconds = 15,

    [switch]$SkipWarmup
)

$ErrorActionPreference = 'Stop'

function Invoke-Main {
$BaseUrl = $BaseUrl.TrimEnd('/')
if ($Vus -le 0) {
    throw 'Vus must be greater than zero.'
}
if ($RunPrefix -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,26}$') {
    throw 'RunPrefix must match [A-Za-z0-9][A-Za-z0-9_-]{0,26}.'
}

$strategies = @($Strategies)

$stages = @(
    [pscustomobject]@{ Name = 'basic'; Code = 'b'; Quantity = 100; Users = 200; RepeatsPerUser = 1; Requests = 200; MaxDuration = '3m' },
    [pscustomobject]@{ Name = 'contention'; Code = 'c'; Quantity = 100; Users = 1000; RepeatsPerUser = 1; Requests = 1000; MaxDuration = '5m' },
    [pscustomobject]@{ Name = 'performance'; Code = 'p'; Quantity = 1000; Users = 10000; RepeatsPerUser = 1; Requests = 10000; MaxDuration = '10m' },
    [pscustomobject]@{ Name = 'duplicate-user'; Code = 'u'; Quantity = 10000; Users = 10000; RepeatsPerUser = 3; Requests = 30000; MaxDuration = '20m' }
)

if ($Stage -ne 'all') {
    $stages = @($stages | Where-Object Name -eq $Stage)
}

$scriptPath = Join-Path $PSScriptRoot 'coupon-test.js'
$resultRoot = Join-Path $PSScriptRoot (Join-Path 'results' $RunPrefix)
New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null

$results = [System.Collections.Generic.List[object]]::new()
$strategyIndex = 0

foreach ($stageConfig in $stages) {
    foreach ($strategy in $strategies) {
        $strategyIndex++
        $runId = '{0}-{1}-{2:D2}' -f $RunPrefix, $stageConfig.Code, $strategyIndex
        $coupon = Invoke-RestMethod `
                -Method Post `
                -Uri "$BaseUrl/experiment/coupons" `
                -ContentType 'application/json' `
                -Body (@{ quantity = $stageConfig.Quantity } | ConvertTo-Json -Compress)
        $couponId = [long]$coupon.couponId

        Write-Host "[$($stageConfig.Name)] $strategy coupon=$couponId runId=$runId"

        if (Test-IsRedisStrategy $strategy) {
            $encodedRunId = [uri]::EscapeDataString($runId)
            Invoke-RestMethod `
                    -Method Post `
                    -Uri "$BaseUrl/experiment/coupons/$couponId/$strategy/initialize?runId=$encodedRunId" |
                    Out-Null
        }

        if (-not $SkipWarmup) {
            $warmupUsers = [Math]::Min(20, $stageConfig.Users)
            $warmupRequests = $warmupUsers * $stageConfig.RepeatsPerUser
            $warmupExitCode = Invoke-K6Run `
                    -Strategy $strategy `
                    -CouponId $couponId `
                    -RunId $runId `
                    -Requests $warmupRequests `
                    -RunVus ([Math]::Min(20, $warmupRequests)) `
                    -RepeatsPerUser $stageConfig.RepeatsPerUser `
                    -MaxDuration '2m' `
                    -BaseUrl $BaseUrl `
                    -ScriptPath $scriptPath
            if ($warmupExitCode -ne 0) {
                Write-Warning "Warm-up failed for $($stageConfig.Name)/$strategy with exit code $warmupExitCode. The measured run will still continue."
            }

            Reset-Experiment `
                    -Strategy $strategy `
                    -CouponId $couponId `
                    -RunId $runId `
                    -BaseUrl $BaseUrl
        }

        $summaryPath = Join-Path $resultRoot (
                '{0}-{1}-{2}.json' -f $stageConfig.Name, $strategy, $couponId)
        $exitCode = Invoke-K6Run `
                -Strategy $strategy `
                -CouponId $couponId `
                -RunId $runId `
                -Requests $stageConfig.Requests `
                -RunVus ([Math]::Min($Vus, $stageConfig.Requests)) `
                -RepeatsPerUser $stageConfig.RepeatsPerUser `
                -MaxDuration $stageConfig.MaxDuration `
                -SummaryPath $summaryPath `
                -BaseUrl $BaseUrl `
                -ScriptPath $scriptPath

        $status = Get-ExperimentStatus `
                -Strategy $strategy `
                -CouponId $couponId `
                -RunId $runId `
                -BaseUrl $BaseUrl
        $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
        $attempted = Get-MetricValue $summary 'coupon_attempt' 'count' -Required
        $completed = Get-MetricValue $summary 'iterations' 'count' -Required
        $success = Get-MetricValue $summary 'coupon_success' 'count'
        $soldOut = Get-MetricValue $summary 'coupon_sold_out' 'count'
        $duplicateRequest = Get-MetricValue $summary 'coupon_duplicate_request' 'count'
        $duplicateUser = Get-MetricValue $summary 'coupon_duplicate_user' 'count'
        $internalError = Get-MetricValue $summary 'coupon_internal_error' 'count'
        $setupError = Get-MetricValue $summary 'coupon_setup_error' 'count'
        $unexpected = Get-MetricValue $summary 'coupon_unexpected_result' 'count'
        $effectiveRemaining = if (Test-IsRedisStrategy $strategy) {
            $status.redisRemainingQuantity
        } else {
            $status.dbRemainingQuantity
        }
        $completedRun = $attempted -eq $stageConfig.Requests `
                -and $completed -eq $stageConfig.Requests
        $validRun = $exitCode -eq 0 -and $completedRun
        $expectedSuccess = if ($stageConfig.RepeatsPerUser -gt 1) { $stageConfig.Users } else { $null }
        $expectedDuplicateUser = if ($stageConfig.RepeatsPerUser -gt 1) {
            $stageConfig.Users * ($stageConfig.RepeatsPerUser - 1)
        } else {
            $null
        }
        $expectedRemaining = if ($stageConfig.RepeatsPerUser -gt 1) {
            $stageConfig.Quantity - $stageConfig.Users
        } else {
            $null
        }
        $duplicatePolicyConsistent = if ($stageConfig.RepeatsPerUser -gt 1) {
            $success -eq $expectedSuccess `
                    -and $status.issueCount -eq $stageConfig.Users
        } else {
            $null
        }
        $expectedResultsMatched = if ($stageConfig.RepeatsPerUser -gt 1) {
            $success -eq $expectedSuccess `
                    -and $duplicateUser -eq $expectedDuplicateUser `
                    -and $soldOut -eq 0 `
                    -and $duplicateRequest -eq 0 `
                    -and $internalError -eq 0 `
                    -and $setupError -eq 0 `
                    -and $unexpected -eq 0
        } else {
            $null
        }
        $scenarioPassed = if ($stageConfig.RepeatsPerUser -gt 1) {
            $completedRun `
                    -and $duplicatePolicyConsistent `
                    -and $expectedResultsMatched `
                    -and $status.totalQuantity -eq $stageConfig.Quantity `
                    -and $effectiveRemaining -eq $expectedRemaining `
                    -and $status.consistent
        } else {
            $null
        }

        $results.Add([pscustomobject]@{
            Stage = $stageConfig.Name
            Strategy = $strategy
            RunId = $runId
            CouponId = $couponId
            Quantity = $stageConfig.Quantity
            Users = $stageConfig.Users
            RepeatsPerUser = $stageConfig.RepeatsPerUser
            Requests = $stageConfig.Requests
            Vus = [Math]::Min($Vus, $stageConfig.Requests)
            K6ExitCode = $exitCode
            CompletedRun = $completedRun
            ValidRun = $validRun
            DuplicatePolicyConsistent = $duplicatePolicyConsistent
            ExpectedResultsMatched = $expectedResultsMatched
            ScenarioPassed = $scenarioPassed
            Attempted = $attempted
            Completed = $completed
            ExpectedSuccess = $expectedSuccess
            ExpectedDuplicateUser = $expectedDuplicateUser
            ExpectedRemaining = $expectedRemaining
            Success = $success
            SoldOut = $soldOut
            DuplicateRequest = $duplicateRequest
            DuplicateUser = $duplicateUser
            InternalError = $internalError
            SetupError = $setupError
            Unexpected = $unexpected
            RequestsPerSecond = Get-MetricValue $summary 'http_reqs' 'rate' -Required
            AvgMs = Get-MetricValue $summary 'http_req_duration' 'avg' -Required
            P90Ms = Get-MetricValue $summary 'http_req_duration' 'p(90)' -Required
            P95Ms = Get-MetricValue $summary 'http_req_duration' 'p(95)' -Required
            P99Ms = Get-MetricValue $summary 'http_req_duration' 'p(99)' -Required
            MaxMs = Get-MetricValue $summary 'http_req_duration' 'max' -Required
            SuccessAvgMs = Get-MetricValue $summary 'coupon_success_duration' 'avg' -DefaultValue $null
            SuccessP90Ms = Get-MetricValue $summary 'coupon_success_duration' 'p(90)' -DefaultValue $null
            SuccessP95Ms = Get-MetricValue $summary 'coupon_success_duration' 'p(95)' -DefaultValue $null
            SuccessP99Ms = Get-MetricValue $summary 'coupon_success_duration' 'p(99)' -DefaultValue $null
            SoldOutAvgMs = Get-MetricValue $summary 'coupon_sold_out_duration' 'avg' -DefaultValue $null
            SoldOutP95Ms = Get-MetricValue $summary 'coupon_sold_out_duration' 'p(95)' -DefaultValue $null
            DuplicateUserAvgMs = Get-MetricValue $summary 'coupon_duplicate_user_duration' 'avg' -DefaultValue $null
            DuplicateUserP90Ms = Get-MetricValue $summary 'coupon_duplicate_user_duration' 'p(90)' -DefaultValue $null
            DuplicateUserP95Ms = Get-MetricValue $summary 'coupon_duplicate_user_duration' 'p(95)' -DefaultValue $null
            DuplicateUserP99Ms = Get-MetricValue $summary 'coupon_duplicate_user_duration' 'p(99)' -DefaultValue $null
            DuplicateUserMaxMs = Get-MetricValue $summary 'coupon_duplicate_user_duration' 'max' -DefaultValue $null
            DbRemaining = $status.dbRemainingQuantity
            RedisRemaining = $status.redisRemainingQuantity
            EffectiveRemaining = $effectiveRemaining
            IssueCount = $status.issueCount
            Consistent = $status.consistent
        })

        if ($CooldownSeconds -gt 0) {
            Start-Sleep -Seconds $CooldownSeconds
        }
    }
}

$csvPath = Join-Path $resultRoot 'experiment-results.csv'
$jsonPath = Join-Path $resultRoot 'experiment-results.json'
$results | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8
$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonPath -Encoding utf8

$results |
        Select-Object Stage, Strategy, CompletedRun, ValidRun, DuplicatePolicyConsistent,
                ExpectedResultsMatched, ScenarioPassed, Success,
                DuplicateUser, SoldOut, InternalError, RequestsPerSecond,
                P90Ms, P95Ms, P99Ms, IssueCount, EffectiveRemaining, Consistent |
        Format-Table -AutoSize

Write-Host "CSV: $csvPath"
Write-Host "JSON: $jsonPath"

$invalidRuns = @($results | Where-Object {
    -not $_.ValidRun -or ($null -ne $_.ScenarioPassed -and -not $_.ScenarioPassed)
})
if ($invalidRuns.Count -gt 0) {
    Write-Warning "$($invalidRuns.Count) measured run(s) failed k6 validation. See the result files."
    exit 1
}
}

function Invoke-K6Run {
    param(
        [string]$Strategy,
        [long]$CouponId,
        [string]$RunId,
        [int]$Requests,
        [int]$RunVus,
        [int]$RepeatsPerUser,
        [string]$MaxDuration,
        [string]$SummaryPath,
        [string]$BaseUrl,
        [string]$ScriptPath
    )

    $arguments = @(
        'run',
        '--quiet',
        '--log-output', 'none',
        '-e', "BASE_URL=$BaseUrl",
        '-e', "STRATEGY=$Strategy",
        '-e', "COUPON_ID=$CouponId",
        '-e', "RUN_ID=$RunId",
        '-e', "REQUESTS=$Requests",
        '-e', "VUS=$RunVus",
        '-e', 'USER_ID_START=1',
        '-e', "REPEATS_PER_USER=$RepeatsPerUser",
        '-e', "MAX_DURATION=$MaxDuration"
    )
    if ($SummaryPath) {
        $arguments += @('--summary-export', $SummaryPath)
    }
    $arguments += $ScriptPath

    & k6 @arguments | Out-Host
    $exitCode = $LASTEXITCODE
    return $exitCode
}

function Reset-Experiment {
    param(
        [string]$Strategy,
        [long]$CouponId,
        [string]$RunId,
        [string]$BaseUrl
    )

    if (Test-IsRedisStrategy $Strategy) {
        $encodedRunId = [uri]::EscapeDataString($RunId)
        $uri = "$BaseUrl/experiment/coupons/$CouponId/$Strategy/reset?runId=$encodedRunId"
    } else {
        $uri = "$BaseUrl/experiment/coupons/$CouponId/reset"
    }
    Invoke-RestMethod -Method Post -Uri $uri | Out-Null
}

function Get-ExperimentStatus {
    param(
        [string]$Strategy,
        [long]$CouponId,
        [string]$RunId,
        [string]$BaseUrl
    )

    if (Test-IsRedisStrategy $Strategy) {
        $encodedRunId = [uri]::EscapeDataString($RunId)
        $uri = "$BaseUrl/experiment/coupons/$CouponId/$Strategy/status?runId=$encodedRunId"
    } else {
        $uri = "$BaseUrl/experiment/coupons/$CouponId/status"
    }
    return Invoke-RestMethod -Method Get -Uri $uri
}

function Test-IsRedisStrategy {
    param([string]$Strategy)
    return $Strategy.StartsWith('redis-')
}

function Get-MetricValue {
    param(
        [object]$Summary,
        [string]$MetricName,
        [string]$ValueName,
        [switch]$Required,
        [AllowNull()]
        [object]$DefaultValue = 0
    )

    $metricProperty = $Summary.metrics.PSObject.Properties[$MetricName]
    if ($null -eq $metricProperty) {
        if ($Required) {
            throw "Required metric is missing: $MetricName"
        }
        return $DefaultValue
    }
    $metric = $metricProperty.Value
    $valueContainer = if ($null -ne $metric.PSObject.Properties['values']) {
        $metric.values
    } else {
        $metric
    }
    $valueProperty = $valueContainer.PSObject.Properties[$ValueName]
    if ($null -eq $valueProperty) {
        if ($Required) {
            throw "Required metric value is missing: $MetricName.$ValueName"
        }
        return $DefaultValue
    }
    return $valueProperty.Value
}

Invoke-Main
