# coupon-concurrency-strategies

선착순 쿠폰 발급이라는 같은 문제에 동시성 제어 전략 9개를 적용하고,
**각 전략이 정합성·가용성·처리량·지연 중 무엇을 내주는지** 측정한 실험입니다.

"어떤 구현이 가장 빠른가"보다, 실제 발급 기능에 쓸 전략을 고를 근거를 만드는 것이 목적입니다.

> 상태: 로컬 단일 호스트 1차 결과 (2026-08-14 ~ 08-16). 조건별 1회 실행이라 절대 성능 순위로 쓰지 않습니다.

---

## 핵심 결과

1. **`DIRECT`는 재고 원장이 깨졌습니다.** 락 없는 read-modify-write에서 lost update가 재현됐습니다. 재고 1,000개에 9,997건이 발급됐고, 1인 1매 시나리오에서는 발급 이력 10,000건 중 재고 차감은 2,502건만 반영됐습니다.
2. **초과 발급이 없어도 실패일 수 있습니다.** 한 회원이 3번씩 요청하는 시나리오에서 1인 1매 제약은 모든 전략이 지켰습니다. 하지만 응답 분류, 대상자 전원 발급, 재고 원장까지 모두 통과한 전략은 단일 인스턴스의 `JVM_LOCK`뿐이었습니다.
3. **Redis 선예약 방식은 정상 회원을 탈락시켰습니다.** `REDIS_LUA`·`REDIS_DECR`에서는 같은 회원의 중복 요청이 Redis 재고를 먼저 잡습니다. 이후 DB 유니크 충돌로 보상하는 사이에 다른 회원이 품절 응답을 받았고, 그 결과 최종 원장은 맞지만 1~2명이 쿠폰을 받지 못했습니다.
4. **`REDIS_WATCH`는 연결 관리 때문에 무너졌습니다.** WATCH 충돌 재시도마다 Redis 연결이 새로 생성·종료되면서 TIME_WAIT가 약 14,900개 쌓였고, 로컬 동적 포트(16,384개)가 고갈됐습니다. 그 결과 요청 30,000건 중 약 90%가 응답을 받지 못했습니다. VU 10·50·100·200에서 모두 재현됐습니다.
5. **req/s는 결과 구성에 좌우됩니다.** 이 실험은 폐쇄형 부하(`shared-iterations`)라서 `req/s × 평균 지연 ≈ VU 수`가 성립합니다. 품절 응답이 90%인 시나리오에서는 빠른 품절 경로가 처리량을 결정합니다. 결과 구성이 같은 1인 1매 시나리오에서는 VU 10·50·100·200 모두 `CONDITIONAL`이 `PESSIMISTIC`보다 처리량이 높았습니다.

---

## 실험 설계

### 독립 변수는 재고 예약 전략 하나

```text
Request → Validation → requestId 중복 검사 → (couponId, userId) 중복 검사
        → [Stock Reservation Strategy] → CouponIssue INSERT → Response
```

| 계열 | 전략 | 방식 |
|---|---|---|
| 기준선 | `DIRECT` | SELECT → 재고 확인 → 엔티티 변경 → UPDATE (제어 없음) |
| Application | `JVM_LOCK` | `ReentrantLock`으로 같은 쿠폰 요청 직렬화 |
| Database | `PESSIMISTIC` | `SELECT ... FOR UPDATE` 후 차감 |
| | `OPTIMISTIC` | version CAS UPDATE, 충돌 시 재시도 |
| | `CONDITIONAL` | `UPDATE ... WHERE remaining_quantity > 0`, affected rows로 성공/품절 판정 |
| Redis | `REDIS_LOCK` | `SET NX + TTL` 락 획득 후 GET → DECR |
| | `REDIS_DECR` | `DECR` 후 음수면 `INCR`로 보상 |
| | `REDIS_LUA` | GET·조건 검사·DECR을 Lua script 하나로 실행 |
| | `REDIS_WATCH` | `WATCH / MULTI / EXEC`, 충돌 시 재시도 |

Redis 전략도 중복 검사와 발급 이력 저장에는 MySQL을 씁니다. 결과는 Redis 명령 자체가 아니라 HTTP 발급 경로 전체의 end-to-end 수치입니다.

### 전략 간 격리

- 전략마다 새 쿠폰을 만들고, requestId와 Redis key를 `{runId}:{strategy}` namespace로 분리
- 전략별 warm-up 20건 후 해당 전략만 reset, 전략 사이 cooldown 15초 (스크립트 기본값)
- 정식 측정은 한 번에 한 전략만 실행
- 실행 후 status API로 잔여 재고·발급 수·정합성을 확인하고, 1인 1매 시나리오는 DB를 직접 교차 확인 (`COUNT(*)`, `COUNT(DISTINCT user_id)`, `COUNT(DISTINCT request_id)`, 회원별 최대 발급 수)

### 시나리오

| 시나리오 | 재고 | 요청 | 확인하는 것 | 보고서 |
|---|---:|---:|---|---|
| Unique user · Basic | 100 | 200 | 기본 정합성 | [unique-user](docs/unique-user-report.md) |
| Unique user · Contention | 100 | 1,000 | retry·lock 대기·충돌 | 〃 |
| Unique user · Performance | 1,000 | 10,000 | 처리량·지연 | 〃 |
| Duplicate user (정순·역순) | 10,000 | 30,000 (10,000명 × 3회) | 1인 1매, 전원 발급, 응답 분류, 원장 | [정순](k6/results/r0816dup1/duplicate-user-report.md) · [역순](k6/results/r0816duprev1/duplicate-user-reverse-report.md) |
| Duplicate user · VU 확장 | 10,000 | 30,000 × VU 10/50/100/200 | 포화 지점, 병목 | [vu-scaling](k6/results/r0816vuscale1/vu-scaling-report.md) |

---

## 결과

### Unique user · Performance (재고 1,000 / 요청 10,000 / VU 50)

| 전략 | 성공 | 품절 | 한도 초과 | req/s | 성공 평균(ms) | 성공 p95(ms) | 품절 평균(ms) | 정합성 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| REDIS_LUA | 1,000 | 9,000 | 0 | 1,335.7 | 101.4 | 126.1 | 29.9 | true |
| REDIS_DECR | 1,000 | 9,000 | 0 | 1,187.0 | 106.5 | 164.8 | 34.5 | true |
| REDIS_WATCH | 1,000 | 8,997 | 3 | 620.8 | 209.2 | 267.6 | 65.6 | true |
| PESSIMISTIC | 1,000 | 9,000 | 0 | 594.0 | 423.5 | 460.4 | 46.1 | true |
| OPTIMISTIC | 1,000 | 8,668 | 332 | 467.1 | 237.2 | 682.3 | 35.6 | true |
| CONDITIONAL | 1,000 | 9,000 | 0 | 443.3 | 394.4 | 432.4 | 81.0 | true |
| REDIS_LOCK | 1,000 | 8,815 | 185 | 391.1 | 189.1 | 239.5 | 118.0 | true |
| JVM_LOCK | 1,000 | 9,000 | 0 | 202.8 | 614.4 | 686.0 | 204.8 | true |
| DIRECT | 9,997 | 3 | 0 | 148.5 | 335.7 | 358.2 | 268.1 | **false** |

- `DIRECT`의 req/s가 가장 낮은 이유는 락 때문이 아닙니다. 요청의 99.97%가 비싼 성공 경로(재고 UPDATE + 발급 INSERT)를 탔기 때문입니다. 성공 경로의 평균 지연은 335.7ms로, `PESSIMISTIC`(423.5ms)이나 `CONDITIONAL`(394.4ms)보다 짧습니다.
- `PESSIMISTIC`이 `CONDITIONAL`보다 req/s가 높게 나온 것은 품절 경로 차이 때문입니다(평균 46.1ms 대 81.0ms). 성공 경로만 보면 `CONDITIONAL`이 더 빠릅니다.
- 한도 초과 요청은 재고 예약 전에 끝나서 재고와 발급 이력은 맞았습니다. 그래서 이 전략들은 정합성 실패가 아니라 **가용성 저하**로 분류했습니다.

### Duplicate user · 판정 (VU 50, 역순 실행)

| 전략 | 1인 1매 | 대상자 전원 발급 | 응답 분류 | 재고 원장 | 통과 |
|---|---|---|---|---|---|
| JVM_LOCK | ✓ | ✓ 10,000 | ✓ | ✓ | **✓** |
| PESSIMISTIC | ✓ | ✓ 10,000 | ✗ 중복 2건이 `SOLD_OUT` | ✓ | ✗ |
| CONDITIONAL | ✓ | ✓ 10,000 | ✗ 중복 2건이 `SOLD_OUT` | ✓ | ✗ |
| DIRECT | ✓ | ✓ 10,000 | ✓ | ✗ 차감 2,502 / 발급 10,000 | ✗ |
| REDIS_DECR | ✓ | ✗ 9,999 | ✗ 일시 `SOLD_OUT` 13건 | ✓ | ✗ |
| REDIS_LUA | ✓ | ✗ 9,998 | ✗ 일시 `SOLD_OUT` 13건 | ✓ | ✗ |
| REDIS_LOCK | ✓ | ✗ 9,999 | ✗ 한도 초과 287건 | ✓ | ✗ |
| OPTIMISTIC | ✓ | ✗ 9,997 | ✗ 한도 초과 11건 | ✓ | ✗ |
| REDIS_WATCH | ✓ | ✗ 946 | ✗ 무응답 26,991건 | ✓ | ✗ |

정순 실행에서도 통과한 전략은 `JVM_LOCK`뿐이었고 전략별 실패 유형도 같았습니다(세부 건수는 다름). 두 실행 모두 모든 전략에서 `issueCount = COUNT(DISTINCT user_id) = COUNT(DISTINCT request_id)`였고, 회원별 최대 발급 수는 1이었습니다.

### Duplicate user · VU 확장 (VU 10 → 200)

- Watch를 제외한 8개 전략은 VU 50→200에서 req/s가 1.0~8.3%만 늘었습니다. 반면 VU 200의 p95는 VU 10 대비 8.3~18.6배로 커졌습니다. 처리량은 늘지 않고 대기만 길어지는 포화 패턴입니다.
- Watch를 제외한 전략에서 무응답은 VU 10·50·100에서 0건이었고, VU 200에서만 합계 483건이 나왔습니다.
- `OPTIMISTIC`은 VU 10에서 한도 초과가 1,280건으로 VU 50(11건)보다 오히려 나빴습니다. backoff 없이 최대 20회 즉시 재시도하는 설정이 Hikari pool 10과 맞물린 것으로 추정합니다. 요청별 retry 계측이 없어 아직 확정하지 않았습니다.

---

## 알게 된 실패 경계

| 현상 | 원인 | 확실성 |
|---|---|---|
| 재고 원장 불일치 (`DIRECT`) | 읽은 값을 기준으로 절대값을 다시 쓰는 lost update | 확정 |
| 정상 회원 미발급 (`REDIS_LUA`·`DECR`) | 중복 검사보다 재고 예약이 먼저 일어나고, 보상 전까지 재고가 비어 보임 | 관측 |
| 중복 요청의 `SOLD_OUT` 오분류 (`PESSIMISTIC`·`CONDITIONAL`) | 회원 중복 사전 조회가 최초 발급 커밋 전에 통과한 뒤 재고 0을 만남 (check-then-act) | 관측 |
| 연결 고갈 (`REDIS_WATCH`) | 재시도마다 Redis 연결 생성·종료, TIME_WAIT 약 14,900개, `Address already in use` | 확정 |
| 낮은 VU에서의 retry storm (`OPTIMISTIC`) | 즉시 재시도 20회 × Hikari pool 10 | 강한 추론 |
| 포화 | Hikari pool 10, 단일 재고 행 잠금, Tomcat worker 200 | 추론 |

---

## 해석 제한

- 조건별 1회 실행입니다. 실행 순서를 회전하며 최소 3회 반복해야 순위를 말할 수 있습니다.
- k6·애플리케이션·MySQL·Redis가 같은 PC에서 돌았습니다. 전략마다 다른 네트워크 왕복 비용이 빠져 있고 자원 경합이 섞여 있습니다.
- `JVM_LOCK`은 단일 인스턴스에서만 유효합니다. 다중 인스턴스 해법으로 평가할 수 없습니다.
- `shared-iterations`는 폐쇄형 부하입니다. VU 증가는 도착률 증가가 아니라 동시 worker 증가입니다.

## 다음 단계

1. `REDIS_WATCH`: 같은 연결에서 WATCH/MULTI/EXEC를 수행하고 연결을 재사용하도록 수정한 뒤 재측정
2. Redis 전략: 회원 중복 검사와 재고 예약을 Lua script 안에서 원자적으로 처리하는 변형을 추가해 정상 회원 미발급이 사라지는지 확인
3. `OPTIMISTIC`: 요청별 retry 횟수 계측, VU 8/10/12/20 반복, backoff 적용 전후 비교
4. 전략별 3회 이상 반복 + 실행 순서 회전
5. k6·앱·DB·Redis를 분리한 환경에서 재검증, Tomcat worker·Hikari pool·MySQL 연결 한도를 명시적으로 고정

---

## 실행 환경과 설정

| 항목 | 값 |
|---|---|
| CPU / 메모리 | AMD Ryzen 7 5800X (8C/16T) / 48 GiB |
| OS | Windows 11, 동적 TCP 포트 49152~65535 (16,384개) |
| 애플리케이션 | Spring Boot 4.1.0, Java 21 (Temurin 21.0.11), 단일 인스턴스 |
| 서버 / DB pool | Tomcat 11 worker max 200 (기본값), HikariCP max 10, connection timeout 30s (기본값) |
| 데이터 | MySQL 8.4, Redis 7 (Docker, 같은 PC) |
| 부하 | k6 v2.2.0, `shared-iterations`, 기본 VU 50 |
| `OPTIMISTIC` | 최대 20회 즉시 재시도, backoff 없음, 시도마다 `REQUIRES_NEW` 트랜잭션 |

## 재현 방법

```powershell
# 1. MySQL 8.4 (localhost:3307), Redis 7 (localhost:6380) 실행 후 애플리케이션 기동 (localhost:8080)
# 2. 스키마가 생성된 뒤 부하용 회원 10,000명 생성 (PowerShell은 '<' 리다이렉션을 지원하지 않음)
Get-Content -Raw k6/seed-load-users.sql | mysql -h 127.0.0.1 -P 3307 -u <user> -p <database>

# 3. 시나리오 실행 (결과는 k6/results/<RunPrefix>/ 에 저장)
foreach ($s in 'basic', 'contention', 'performance') { ./k6/run-experiments.ps1 -Stage $s }   # Unique user 3단계
./k6/run-experiments.ps1 -Stage duplicate-user -Vus 50 -Strategies redis-lua, redis-decr, redis-lock, conditional, optimistic, pessimistic, jvm-lock, direct
./k6/run-experiments.ps1 -Stage duplicate-user -Vus 50 -Strategies redis-watch   # 연결 고갈이 다음 전략을 오염시키므로 단독 실행
```

실험 API

| Method | Path | 용도 |
|---|---|---|
| POST | `/experiment/coupons` | 실험용 쿠폰 생성 (`{"quantity": n}`) |
| POST | `/experiment/coupons/{couponId}/{strategy}` | 발급 요청 (`{"userId", "requestId"}`), Redis 전략은 `?runId=` |
| POST | `/experiment/coupons/{couponId}/{strategy}/initialize?runId=` | Redis 재고 초기화 |
| POST | `/experiment/coupons/{couponId}/reset`, `.../{strategy}/reset?runId=` | warm-up 후 초기화 |
| GET | `/experiment/coupons/{couponId}/status`, `.../{strategy}/status?runId=` | 잔여 재고·발급 수·정합성 |

응답 결과 코드: `SUCCESS`(200), `SOLD_OUT`·`DUPLICATE_REQUEST`·`DUPLICATE_USER`(409), `INTERNAL_ERROR`(500)

## 결과 파일 (Run index)

| Run | 날짜 | 내용 |
|---|---|---|
| `r0814full1` | 08-14 | 첫 실행. `basic-direct` 1건만 남아 있음 (unexpected 44건). 이후 `r0814full2`로 전체 재실행 |
| `r0814full2` | 08-14 | Unique user 3단계 × 9전략 (27회) |
| `r0816dup1` | 08-16 | Duplicate user 정순, VU 50 |
| `r0816dup1w`, `r0816dup2w` | 08-16 | `REDIS_WATCH` 단독 재실행 (TIME_WAIT 0에서 시작) |
| `r0816duprev1`, `r0816duprev1w` | 08-16 | Duplicate user 역순, VU 50 (Watch 단독 선실행) |
| `r0816vu10r1`, `r0816vu100r1`, `r0816vu200r1` | 08-16 | VU 10 / 100 / 200, Watch 제외 8개 전략 |
| `r0816watchvu10r1`, `r0816watchvu100r1`, `r0816watchvu200r1` | 08-16 | VU별 `REDIS_WATCH` 단독 (Redis 포트 6381/6382/6383 분리) |
| `r0816vuscale1` | 08-16 | VU 확장 통합 결과 (CSV/JSON/XLSX) |

실패하거나 경고가 붙은 실행도 지우지 않고 남겼습니다.
