# 실행 환경과 해석 제한

모든 시나리오에 공통으로 적용되는 환경, 전략별 설정, 격리 방법, 해석 제한을 여기에 한 번만 적습니다.

## 실행 환경

| 항목 | 값 |
|---|---|
| CPU | AMD Ryzen 7 5800X, 8코어 16스레드, 최대 3,801 MHz |
| 메모리 | 47.93 GiB |
| OS | Windows 11 Education (build 26200) |
| 동적 TCP 포트 | IPv4·IPv6 모두 49152~65535 (16,384개) |
| Java | Temurin 21.0.11, 최대 힙 자동 설정 (약 11.98 GiB) |
| 애플리케이션 | Spring Boot 4.1.0, 단일 인스턴스 |
| 서버 | Tomcat 11.0.22, worker 최대 200 (기본값) |
| DB 커넥션 풀 | HikariCP 7.0.2, 최대 10, connection timeout 30초 (기본값) |
| 데이터 | MySQL 8.4 (VU 확장 실험 시 8.4.11), Redis 7. 둘 다 Docker |
| 부하 | k6 v2.2.0 (windows/amd64), `shared-iterations`, 기본 VU 50 |
| 배치 | k6·애플리케이션·MySQL·Redis를 모두 같은 PC에서 실행 |

Tomcat worker와 Hikari pool은 따로 설정하지 않아 기본값이 적용됐습니다.

## 전략별 설정

| 전략 | 설정 |
|---|---|
| `JVM_LOCK` | 쿠폰별 `ReentrantLock`. 락 안에서 `DIRECT`의 발급 흐름 전체(중복 검사부터 커밋까지)를 실행 |
| `PESSIMISTIC` | `SELECT … FOR UPDATE`로 재고 행을 잠근 뒤 차감 |
| `OPTIMISTIC` | version 조건 UPDATE. 충돌 시 최대 20회 즉시 재시도 (backoff 없음), 시도마다 `REQUIRES_NEW` 트랜잭션 |
| `CONDITIONAL` | `UPDATE … WHERE remaining_quantity > 0`, affected rows로 성공/품절 판정 |
| `REDIS_LOCK` | `SET NX` 락 (TTL 3초), 획득 시도 최대 20회 (시도 사이 5ms 대기), 토큰을 확인하는 Lua script로 해제 |
| `REDIS_DECR` | `DECR` 결과가 음수면 `INCR`로 되돌리고 품절 |
| `REDIS_LUA` | GET·조건 검사·DECR을 Lua script 하나로 실행 |
| `REDIS_WATCH` | `WATCH / MULTI / EXEC`, 충돌 시 최대 20회 즉시 재시도 |
| Redis 공통 | 중복 검사와 발급 이력 저장은 MySQL. DB 저장이 실패하면 Redis 재고를 `INCR`로 보상 |

## 전략 간 격리

- 전략마다 새 쿠폰을 만들고, `requestId`와 Redis key를 `runId`로 분리했습니다. Redis key는 `experiment:{runId}:{strategy}:coupon:{couponId}:stock` 형식입니다.
- 전략별로 최대 20명분 요청을 warm-up으로 보낸 뒤 해당 전략만 reset했습니다. Duplicate user에서는 20명 × 3회 = 60건입니다.
- 전략 사이 cooldown은 15초입니다 (스크립트 기본값).
- 정식 측정은 한 번에 한 전략만 실행했습니다.
- 실행 후 status API로 잔여 재고·발급 수·원장 일치를 확인했습니다. Duplicate user는 DB를 직접 교차 확인했습니다 (`COUNT(*)`, `COUNT(DISTINCT user_id)`, `COUNT(DISTINCT request_id)`, 회원별 최대 발급 수).
- `REDIS_WATCH`는 연결 고갈이 다음 전략을 오염시키므로 단독으로 실행했습니다. VU 확장 실험에서는 VU마다 Redis 포트(6381/6382/6383)와 애플리케이션 프로세스를 새로 띄웠습니다.

## 해석 제한

> [!WARNING]
> 아래 제한은 모든 결과 문서에 적용됩니다. 시나리오별 제한은 각 문서 끝에 따로 적었습니다.

- **조건별 1회 실행입니다.** 절대 성능 순위로 쓰지 않습니다. 순위를 말하려면 실행 순서를 회전하며 최소 3회 반복해야 합니다.
- **모든 구성 요소가 같은 PC에서 돌았습니다.** 전략마다 다른 네트워크 왕복 비용이 빠져 있고, CPU·메모리·Docker 자원 경합이 섞여 있습니다.
- **`JVM_LOCK`은 단일 인스턴스에서만 유효합니다.** 다중 인스턴스 해법으로 평가할 수 없습니다.
- **Redis 전략 결과는 end-to-end 수치입니다.** 중복 검사와 발급 이력 저장에 MySQL을 쓰므로 Redis 명령 자체의 벤치마크가 아닙니다.
- **폐쇄형 부하입니다.** VU 증가는 도착률 증가가 아니라 동시 worker 증가입니다.
- **req/s와 전체 지연은 결과 구성에 좌우됩니다.** 품절·중복 응답처럼 빠른 경로의 비중이 크면 수치가 좋아 보입니다. 발급 경로를 비교할 때는 성공 응답의 지연을 따로 봅니다.
- **실패 유형을 구분합니다.** 응답 분류 오류, 대상자 미발급, 1인 1매 위반, 재고 원장 불일치는 서로 다른 문제로 봅니다.
