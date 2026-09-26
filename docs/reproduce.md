# 재현 방법

실험은 Windows 11 PowerShell에서 실행했습니다. 명령은 저장소 루트(`concurrency-strategies/`)에서 실행한다고 가정합니다.

## 준비물

- Java 21
- Docker (MySQL 8.4, Redis 7)
- [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) v2.2.0
- `mysql` 클라이언트

## 1. MySQL과 Redis 실행

실험에서는 기존 로컬 서버와 겹치지 않도록 MySQL을 3307, Redis를 6380 포트에 띄웠습니다. 아래는 같은 구성을 만드는 예시입니다.

```powershell
docker run -d --name coupon-mysql -p 3307:3306 -e MYSQL_ROOT_PASSWORD=<password> -e MYSQL_DATABASE=coupon_poc mysql:8.4
docker run -d --name coupon-redis -p 6380:6379 redis:7
```

## 2. 스키마 생성

애플리케이션은 `spring.jpa.hibernate.ddl-auto=validate`라서 스키마를 만들지 않고 검증만 합니다. 기동 전에 스키마를 먼저 만들어야 합니다.

```powershell
Get-Content -Raw src/main/resources/sql/test_schema.sql | mysql -h 127.0.0.1 -P 3307 -u root -p coupon_poc
```

> [!CAUTION]
> `test_schema.sql`은 기존 테이블을 `DROP`한 뒤 다시 만듭니다. 실험에 쓰지 않는 `issue_attempt_log` 테이블, 더미 쿠폰 5개, 회원 200명도 함께 만듭니다. 실험용 쿠폰은 API로 따로 만듭니다.

PowerShell은 `<` 리다이렉션을 지원하지 않아서 `Get-Content`로 넘깁니다.

## 3. 부하용 회원 10,000명 생성

```powershell
Get-Content -Raw k6/seed-load-users.sql | mysql -h 127.0.0.1 -P 3307 -u root -p coupon_poc
```

`user_id` 1~10,000을 `INSERT IGNORE`로 넣으므로 여러 번 실행해도 됩니다.

## 4. 애플리케이션 기동

접속 정보는 환경변수로 넘깁니다. 기본값은 실험 포트와 다르므로 **`DB_URL`과 `REDIS_PORT`는 반드시 지정해야 합니다.**

| 변수 | 기본값 | 실험 값 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/coupon_poc` | `jdbc:mysql://localhost:3307/coupon_poc` |
| `DB_USERNAME` | `root` | |
| `DB_PASSWORD` | (빈 값) | |
| `REDIS_HOST` | `localhost` | |
| `REDIS_PORT` | `6379` | `6380` |
| `REDIS_DATABASE` | `0` | |
| `JPA_SHOW_SQL` | `false` | |

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3307/coupon_poc'
$env:DB_PASSWORD = '<password>'
$env:REDIS_PORT = '6380'
.\gradlew.bat bootRun
```

기동 로그에 `Redis Test = hello`가 찍히면 Redis 연결이 된 것입니다. `http://localhost:8080/actuator/health`로 상태를 확인할 수 있습니다.

## 5. 시나리오 실행

[`k6/run-experiments.ps1`](../k6/run-experiments.ps1)이 전략마다 쿠폰 생성 → (Redis 전략은 initialize) → warm-up → reset → 측정 → status 확인 → cooldown을 반복하고, 결과를 `k6/results/<RunPrefix>/`에 저장합니다.

| 인자 | 값 | 기본값 |
|---|---|---|
| `-Stage` | `basic`, `contention`, `performance`, `duplicate-user`, `all` | `duplicate-user` |
| `-Strategies` | [전략 경로 이름](glossary.md#전략-이름) 목록. 적은 순서대로 실행 | 9개 전략 정순 |
| `-Vus` | 측정 VU 수 | 50 |
| `-RunPrefix` | 결과 폴더 이름이자 `runId` 접두사 | `r` + 월일시분 |
| `-CooldownSeconds` | 전략 사이 대기 (0~600) | 15 |
| `-SkipWarmup` | warm-up과 reset 생략 | 꺼짐 |
| `-BaseUrl` | 애플리케이션 주소 | `http://localhost:8080` |

| Stage | 재고 | 회원 × 회원당 요청 | 전략별 요청 | 최대 시간 |
|---|---:|---|---:|---|
| `basic` | 100 | 200 × 1 | 200 | 3분 |
| `contention` | 100 | 1,000 × 1 | 1,000 | 5분 |
| `performance` | 1,000 | 10,000 × 1 | 10,000 | 10분 |
| `duplicate-user` | 10,000 | 10,000 × 3 | 30,000 | 20분 |

### Unique user (3단계)

```powershell
foreach ($s in 'basic', 'contention', 'performance') { ./k6/run-experiments.ps1 -Stage $s }
```

`-RunPrefix`를 주지 않으면 단계마다 결과 폴더가 따로 생깁니다.

### Duplicate user 정순

```powershell
./k6/run-experiments.ps1 -Stage duplicate-user -Vus 50
```

### Duplicate user 역순

`REDIS_WATCH`는 로컬 포트를 고갈시켜 다음 전략을 오염시키므로 먼저 단독으로 실행합니다. TIME_WAIT가 빠질 때까지 기다린 뒤 나머지를 실행합니다.

```powershell
./k6/run-experiments.ps1 -Stage duplicate-user -Vus 50 -Strategies redis-watch
(Get-NetTCPConnection -State TimeWait -RemotePort 6380 -ErrorAction SilentlyContinue | Measure-Object).Count
./k6/run-experiments.ps1 -Stage duplicate-user -Vus 50 -Strategies redis-lua, redis-decr, redis-lock, conditional, optimistic, pessimistic, jvm-lock, direct
```

### VU 확장

```powershell
foreach ($v in 10, 100, 200) {
    ./k6/run-experiments.ps1 -Stage duplicate-user -Vus $v -Strategies redis-lua, redis-decr, redis-lock, conditional, optimistic, pessimistic, jvm-lock, direct
}
```

VU별 `REDIS_WATCH`는 앞선 TIME_WAIT의 영향을 피하려고 VU마다 새 Redis 포트(6381/6382/6383)를 띄우고, `REDIS_PORT`를 바꿔 애플리케이션을 다시 기동한 뒤 `-Strategies redis-watch`로 실행했습니다.

### 스크립트 종료 코드

측정 중 k6 검증(오류 응답 0, 기대 응답 일치 등)에 실패한 전략이 있으면 스크립트는 경고를 출력하고 종료 코드 1로 끝납니다. 이때도 결과 파일은 모두 저장됩니다. 결과 CSV의 `K6ExitCode`가 99이면 k6 threshold 실패입니다.

## k6 스크립트 직접 실행

[`k6/coupon-test.js`](../k6/coupon-test.js)는 환경변수로 설정합니다.

| 변수 | 뜻 | 기본값 |
|---|---|---|
| `COUPON_ID` | 대상 쿠폰 (필수) | |
| `STRATEGY` | 전략 경로 이름 | `direct` |
| `RUN_ID` | Redis key와 `requestId` 접두사 | `r01` |
| `REQUESTS` | 전체 요청 수 | 200 |
| `VUS` | VU 수 | `min(REQUESTS, 200)` |
| `REPEATS_PER_USER` | 회원당 요청 수. 1보다 크면 Duplicate user 검증을 적용 | 1 |
| `USER_ID_START` | 첫 회원 ID | 1 |
| `MAX_DURATION` | 최대 실행 시간 | `5m` |
| `BASE_URL` | 애플리케이션 주소 | `http://localhost:8080` |

```powershell
k6 run -e COUPON_ID=12 -e STRATEGY=pessimistic -e REQUESTS=1000 -e VUS=50 k6/coupon-test.js
```

## 테스트

```powershell
.\gradlew.bat test
```

기본 실행에서는 DB·Redis 없이 도는 단위 테스트(Mockito 등)만 실행됩니다. `CouponConcurrencyExperimentTest`(`PESSIMISTIC`, 재고 100 / 요청 200 / worker 200)와 `ExperimentCouponApplicationTests`는 실제 MySQL·Redis가 필요하고 환경변수를 켜야 실행됩니다. 접속 정보는 4단계와 같은 환경변수를 씁니다.

```powershell
$env:RUN_CONCURRENCY_TESTS = 'true'
.\gradlew.bat test
```

## 차트 다시 그리기

VU 확장 차트는 결과 CSV에서 생성합니다. Node 18 이상이 필요하고 외부 패키지는 없습니다.

```powershell
node docs/images/render-vu-scaling.mjs
```
