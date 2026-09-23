# Duplicate User reverse-order k6 experiment report

실행일: 2026-08-16 (Asia/Seoul)

## 목적과 조건

동일한 회원이 서로 다른 `requestId`로 쿠폰을 3번 요청할 때 회원당 최대 한 건만 발급되는지, 모든 대상 회원이 한 건씩 받을 수 있는지, 최종 재고가 맞는지를 전략 역순으로 다시 확인했다.

- 쿠폰 수량: 10,000
- 대상 회원: 10,000명
- 회원당 요청: 3건
- 전략별 총 요청: 30,000건
- VU: 50
- 애플리케이션: Spring Boot 4.1.0, Java 21, 단일 인스턴스
- 격리 인프라: MySQL 8.4, Redis 7
- 부하 도구: k6 v2.2.0, `shared-iterations`
- 기대 응답: `SUCCESS=10,000`, `DUPLICATE_USER=20,000`, 나머지 결과 0
- 실행 순서: `REDIS_WATCH → REDIS_LUA → REDIS_DECR → REDIS_LOCK → CONDITIONAL → OPTIMISTIC → PESSIMISTIC → JVM_LOCK → DIRECT`

원본 데이터는 [역순 통합 CSV](./duplicate-user-reverse-results.csv), [역순 통합 JSON](./duplicate-user-reverse-results.json), 전략별 k6 summary JSON에 있다. `REDIS_WATCH`는 역순의 첫 번째로 단독 실행한 뒤 로컬 포트와 Redis API가 회복된 것을 확인하고 나머지 8개를 이어서 실행했다. WATCH 원본은 [단독 실행 결과](../r0816duprev1w/experiment-results.csv)와 [summary](./duplicate-user-redis-watch-6.json)에 보존했다.

## 결과 요약

| 순서 | 전략 | 성공 | 중복 회원 | 품절 | 내부 오류 | 전송/응답 누락 | 10,000명 모두 발급 | status 정합성 | 전체 통과 |
|---:|---|---:|---:|---:|---:|---:|---|---|---|
| 1 | REDIS_WATCH | 946 | 1,796 | 0 | 267 | 26,991 | false | true | false |
| 2 | REDIS_LUA | 9,998 | 19,989 | 13 | 0 | 0 | false | true | false |
| 3 | REDIS_DECR | 9,999 | 19,988 | 13 | 0 | 0 | false | true | false |
| 4 | REDIS_LOCK | 9,999 | 19,708 | 6 | 287 | 0 | false | true | false |
| 5 | CONDITIONAL | 10,000 | 19,998 | 2 | 0 | 0 | true | true | false |
| 6 | OPTIMISTIC | 9,997 | 19,992 | 0 | 11 | 0 | false | true | false |
| 7 | PESSIMISTIC | 10,000 | 19,998 | 2 | 0 | 0 | true | true | false |
| 8 | JVM_LOCK | 10,000 | 20,000 | 0 | 0 | 0 | true | true | **true** |
| 9 | DIRECT | 10,000 | 20,000 | 0 | 0 | 0 | true | **false** | false |

DB에서 각 쿠폰의 `COUNT(*)`, `COUNT(DISTINCT user_id)`, `COUNT(DISTINCT request_id)`와 회원별 최대 발급 건수를 별도로 확인했다. 모든 전략에서 세 건수가 서로 같고 회원별 최대 발급 수는 1이므로 실제 초과 중복 발급은 없었다.

다만 모든 대상 회원에게 한 건씩 발급됐는지는 별개다. `OPTIMISTIC`은 3명, `REDIS_LOCK`과 `REDIS_DECR`은 각각 1명, `REDIS_LUA`는 2명이 발급받지 못했다. `REDIS_WATCH`는 연결 자원 고갈 때문에 946명만 발급됐다.

`DIRECT`는 응답 분포와 1인 1매는 맞았지만 DB 재고에는 2,502건만 반영됐다. 최종 값은 `remaining=7,498`, `issueCount=10,000`, `consistent=false`로 lost update가 다시 재현됐다.

`PESSIMISTIC`과 `CONDITIONAL`은 각각 10,000명 발급과 최종 재고는 맞았지만 중복 요청 2건이 `DUPLICATE_USER` 대신 `SOLD_OUT`으로 분류됐다.

Redis 최종 잔여 재고는 `WATCH=9,054`, `LUA=2`, `DECR=1`, `LOCK=1`이었다. 각 Redis 잔여량과 MySQL 발급 건수의 합은 10,000으로 최종 저장소 정합성은 맞지만, 중복 요청이 Redis 재고를 일시 예약했다가 보상하는 동안 다른 회원 요청이 품절 또는 오류를 받은 결과가 남았다.

## P90 / P95 / P99

단위는 ms다. 전체 지연과 성공 응답, `DUPLICATE_USER` 응답을 분리했다.

| 순서 | 전략 | 전체 P90 | 전체 P95 | 전체 P99 | 성공 P90 | 성공 P95 | 성공 P99 | 중복 P90 | 중복 P95 | 중복 P99 |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | REDIS_WATCH | — | — | — | 296.82 | 317.07 | 359.17 | 365.52 | 386.49 | 448.38 |
| 2 | REDIS_LUA | 100.84 | 104.46 | 123.11 | 96.42 | 99.63 | 111.21 | 102.17 | 105.86 | 137.79 |
| 3 | REDIS_DECR | 95.72 | 98.16 | 109.16 | 91.47 | 93.59 | 99.98 | 96.80 | 99.32 | 134.50 |
| 4 | REDIS_LOCK | 190.00 | 211.05 | 250.82 | 155.05 | 161.12 | 180.61 | 198.33 | 216.10 | 246.72 |
| 5 | CONDITIONAL | 200.37 | 204.51 | 217.53 | 199.15 | 203.20 | 213.49 | 200.88 | 205.12 | 219.47 |
| 6 | OPTIMISTIC | 453.56 | 561.39 | 815.34 | 437.88 | 524.72 | 776.77 | 472.00 | 567.72 | 818.75 |
| 7 | PESSIMISTIC | 219.66 | 225.87 | 243.90 | 218.72 | 224.79 | 240.61 | 220.12 | 226.20 | 246.04 |
| 8 | JVM_LOCK | 332.26 | 337.45 | 360.65 | 334.33 | 338.69 | 361.80 | 331.15 | 336.61 | 359.38 |
| 9 | DIRECT | 131.78 | 134.74 | 150.48 | 130.14 | 133.03 | 149.40 | 132.39 | 135.37 | 151.23 |

`REDIS_WATCH`의 전체 P90/P95/P99와 req/s는 비교 대상에서 제외했다. 26,991건의 전송 실패가 매우 짧은 실패 시간으로 포함돼 전체 통계가 왜곡되기 때문이다. 표의 성공/중복 지연은 실제 서버 응답을 받은 요청만의 값이다.

## REDIS_WATCH 재현과 후속 격리

역순 첫 번째 전략으로 Redis 관련 `TIME_WAIT=0` 상태에서 `REDIS_WATCH`를 실행했다. 이번에도 애플리케이션 로그에 Lettuce `Unable to connect to Redis`와 `BindException: Address already in use`가 나타났고, 실행 직후 PowerShell의 8080 상태 프로브도 로컬 소켓 할당 오류로 실패했다.

- 전략 요청 완료: 30,000건
- 실제 결과 응답: 3,009건
- 전송/응답 누락: 26,991건
- 성공: 946건
- 중복 회원: 1,796건
- 내부 오류: 267건
- Redis 잔여 재고: 9,054

후속 전략이 영향을 받지 않도록 WATCH를 단독 실행한 뒤 OS 연결 수가 감소할 때까지 기다렸다. 이후 애플리케이션 health, 새 Redis 쿠폰 초기화, Redis status 정합성 프로브가 모두 정상인 것을 확인하고 나머지 8개를 실행했다.

## 이전 정순 실행과 비교

- 전체 조건을 통과한 전략은 두 실행 모두 단일 인스턴스의 `JVM_LOCK`뿐이다.
- `DIRECT`는 두 실행 모두 응답 분포는 정확했지만 lost update로 재고 정합성에 실패했다.
- `PESSIMISTIC`과 `CONDITIONAL`은 두 실행 모두 정확히 2건의 `SOLD_OUT` 오분류가 발생했다.
- `REDIS_DECR`은 두 실행 모두 9,999명, `REDIS_LUA`는 두 실행 모두 9,998명에게 발급됐다.
- `REDIS_WATCH`는 정순 단독 재실행 945명, 이번 역순 첫 실행 946명만 발급돼 연결 자원 고갈이 다시 재현됐다.
- 실행 순서를 뒤집어도 정합성 실패 유형과 최종 결론은 바뀌지 않았다. 지연시간 수치는 단일 실행 노이즈와 실행 시점의 시스템 상태 영향을 받으므로 절대 순위로 해석하지 않는다.

## 해석 제한

- 조건별 단일 재실행이므로 절대 성능 순위로 일반화하면 안 된다.
- JVM Lock 결과는 단일 애플리케이션 인스턴스에서만 유효하다.
- Redis 전략도 중복 확인과 발급 이력 저장에 MySQL을 사용하므로 Redis 명령 자체의 순수 벤치마크가 아니다.
- 응답 분포 불일치, 모든 대상 회원 발급 실패, 회원당 발급 정책, 재고 정합성을 서로 다른 문제로 해석해야 한다.
