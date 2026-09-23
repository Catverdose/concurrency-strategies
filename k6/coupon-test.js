import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const strategy = __ENV.STRATEGY || 'direct';
const couponId = requiredPositiveInteger('COUPON_ID');
const runId = __ENV.RUN_ID || 'r01';
const requestCount = positiveInteger(__ENV.REQUESTS, 200);
const vus = positiveInteger(__ENV.VUS, Math.min(requestCount, 200));
const userIdStart = positiveInteger(__ENV.USER_ID_START, 1);
const repeatsPerUser = positiveInteger(__ENV.REPEATS_PER_USER, 1);
const maxDuration = __ENV.MAX_DURATION || '5m';

if (requestCount % repeatsPerUser !== 0) {
    throw new Error('REQUESTS must be divisible by REPEATS_PER_USER');
}

const uniqueUserCount = requestCount / repeatsPerUser;
const expectedDuplicateUserCount = requestCount - uniqueUserCount;
const duplicateUserScenario = repeatsPerUser > 1;
const scenarioName = duplicateUserScenario ? 'duplicate-user' : 'unique-user';

const supportedStrategies = [
    'direct',
    'jvm-lock',
    'pessimistic',
    'optimistic',
    'conditional',
    'redis-lock',
    'redis-decr',
    'redis-lua',
    'redis-watch',
];

if (!supportedStrategies.includes(strategy)) {
    throw new Error(`Unsupported STRATEGY: ${strategy}`);
}
if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/.test(runId)) {
    throw new Error(`Invalid RUN_ID: ${runId}`);
}

const strategyCodes = {
    direct: 'd',
    'jvm-lock': 'j',
    pessimistic: 'p',
    optimistic: 'o',
    conditional: 'c',
    'redis-lock': 'rl',
    'redis-decr': 'rd',
    'redis-lua': 'ru',
    'redis-watch': 'rw',
};

const attempts = new Counter('coupon_attempt');
const success = new Counter('coupon_success');
const soldOut = new Counter('coupon_sold_out');
const duplicateRequest = new Counter('coupon_duplicate_request');
const duplicateUser = new Counter('coupon_duplicate_user');
const internalError = new Counter('coupon_internal_error');
const setupError = new Counter('coupon_setup_error');
const unexpectedResult = new Counter('coupon_unexpected_result');
const recognizedResult = new Rate('coupon_recognized_result');
const successDuration = new Trend('coupon_success_duration', true);
const soldOutDuration = new Trend('coupon_sold_out_duration', true);
const duplicateUserDuration = new Trend('coupon_duplicate_user_duration', true);

http.setResponseCallback(http.expectedStatuses(200, 409));

const thresholds = {
    coupon_attempt: [`count==${requestCount}`],
    coupon_internal_error: ['count==0'],
    coupon_setup_error: ['count==0'],
    coupon_unexpected_result: ['count==0'],
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
};

if (duplicateUserScenario) {
    thresholds.coupon_success = [`count==${uniqueUserCount}`];
    thresholds.coupon_duplicate_user = [`count==${expectedDuplicateUserCount}`];
    thresholds.coupon_sold_out = ['count==0'];
    thresholds.coupon_duplicate_request = ['count==0'];
}

export const options = {
    scenarios: {
        issue: {
            executor: 'shared-iterations',
            vus,
            iterations: requestCount,
            maxDuration,
        },
    },
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    tags: {
        strategy,
        run_id: runId,
        scenario: scenarioName,
    },
};

export default function () {
    attempts.add(1);
    const iteration = exec.scenario.iterationInTest;
    const userId = userIdStart + Math.floor(iteration / repeatsPerUser);
    const requestId = `${runId}-${strategyCodes[strategy]}-c${couponId.toString(36)}-u${userId.toString(36)}-i${iteration.toString(36)}`;
    if (requestId.length > 64) {
        throw new Error(`Generated requestId exceeds 64 characters: ${requestId}`);
    }
    const redisQuery = strategy.startsWith('redis-')
        ? `?runId=${encodeURIComponent(runId)}`
        : '';
    const url = `${baseUrl}/experiment/coupons/${couponId}/${strategy}${redisQuery}`;
    const response = http.post(
        url,
        JSON.stringify({ userId, requestId }),
        {
            headers: { 'Content-Type': 'application/json' },
            tags: { strategy, run_id: runId },
        },
    );

    let result;
    try {
        result = response.json('result');
    } catch (error) {
        unexpectedResult.add(1);
        recognizedResult.add(false);
        check(response, { 'response contains a result': () => false });
        return;
    }

    let recognized = true;
    switch (result) {
        case 'SUCCESS':
            success.add(1);
            successDuration.add(response.timings.duration);
            break;
        case 'SOLD_OUT':
            soldOut.add(1);
            soldOutDuration.add(response.timings.duration);
            if (duplicateUserScenario) {
                unexpectedResult.add(1);
                recognized = false;
            }
            break;
        case 'DUPLICATE_REQUEST':
            duplicateRequest.add(1);
            unexpectedResult.add(1);
            recognized = false;
            break;
        case 'DUPLICATE_USER':
            duplicateUser.add(1);
            duplicateUserDuration.add(response.timings.duration);
            if (!duplicateUserScenario) {
                unexpectedResult.add(1);
                recognized = false;
            }
            break;
        case 'INTERNAL_ERROR':
            internalError.add(1);
            recognized = false;
            break;
        case 'COUPON_NOT_FOUND':
        case 'INVALID_REQUEST':
        case 'REDIS_NOT_INITIALIZED':
            setupError.add(1);
            recognized = false;
            break;
        default:
            unexpectedResult.add(1);
            recognized = false;
            break;
    }

    recognizedResult.add(recognized);
    check(response, {
        'result is recognized': () => recognized,
        'status matches result': (res) => statusMatches(result, res.status),
    });
}

function statusMatches(result, status) {
    if (result === 'SUCCESS') {
        return status === 200;
    }
    if (result === 'SOLD_OUT'
            || result === 'DUPLICATE_REQUEST'
            || result === 'DUPLICATE_USER'
            || result === 'REDIS_NOT_INITIALIZED') {
        return status === 409;
    }
    if (result === 'COUPON_NOT_FOUND') {
        return status === 404;
    }
    if (result === 'INVALID_REQUEST') {
        return status === 400;
    }
    if (result === 'INTERNAL_ERROR') {
        return status === 500;
    }
    return false;
}

function requiredPositiveInteger(name) {
    const value = Number.parseInt(__ENV[name], 10);
    if (!Number.isInteger(value) || value <= 0) {
        throw new Error(`${name} must be a positive integer`);
    }
    return value;
}

function positiveInteger(value, fallback) {
    const parsed = Number.parseInt(value, 10);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}
