# Coupon concurrency k6 experiment report

실행 시각: 2026-08-14 (Asia/Seoul)

## 실행 조건

- 애플리케이션: Spring Boot 4.1.0, Java 21, 단일 인스턴스
- 부하 도구: k6 v2.2.0, `shared-iterations`, VU 50
- 격리 인프라: MySQL 8.4 (`localhost:3307`), Redis 7 (`localhost:6380`)
- 전략마다 별도 쿠폰을 생성하고 20건 warm-up 후 reset
- 10,000명의 서로 다른 사용자를 사용
- 총 요청: 100,800건, 완료: 100,800건

| 단계 | 쿠폰 재고 | 전략별 요청 | 전략 수 | 전체 요청 |
|---|---:|---:|---:|---:|
| Basic | 100 | 200 | 9 | 1,800 |
| Contention | 100 | 1,000 | 9 | 9,000 |
| Performance | 1,000 | 10,000 | 9 | 90,000 |

원본 결과는 [CSV](./experiment-results.csv), [JSON](./experiment-results.json), 전략별 k6 summary JSON에 있다.

## 핵심 결과

- `DIRECT`는 세 단계 모두 정합성에 실패했다. 재고 100/1,000개에 각각 200, 999, 9,997건이 발급됐다.
- `JVM_LOCK`, `PESSIMISTIC`, `CONDITIONAL`, `REDIS_DECR`, `REDIS_LUA`는 모든 단계에서 정확히 재고 수만큼 발급했고 `INTERNAL_ERROR=0`, `consistent=true`였다.
- `OPTIMISTIC`, `REDIS_LOCK`, `REDIS_WATCH`도 초과 발급은 없고 최종 정합성은 유지했다. 다만 제한된 재시도/락 획득 횟수를 소진해 일부 요청이 `INTERNAL_ERROR`가 됐다.
- 성능 단계에서 오류 없이 끝난 전략 중 전체 처리량은 `REDIS_LUA` 1,335.7 req/s, `REDIS_DECR` 1,187.0 req/s가 가장 높았다.
- 27개 회차 중 24개는 최종 정합성이 맞았다. 불일치 3개는 모두 의도적인 기준선인 `DIRECT`였다.
- 엄격한 k6 응답 검증까지 통과한 회차는 19/27개였다. 나머지 8개는 정합성 문제가 아니라 총 587건의 `INTERNAL_ERROR` 때문에 실패 처리됐다.
- 종료 후 Redis를 교차 확인한 결과 실험 재고 키 12개는 모두 0이었고 잔류 lock 키는 없었다.

## Performance 단계

재고 1,000개, 요청 10,000건의 단일 실행 결과다. `p95`는 성공과 품절을 합친 전체 응답 지연이며, 품절 응답 비중이 90%라는 점을 함께 고려해야 한다.

| 전략 | 성공 | 품절 | 내부 오류 | req/s | 전체 p95(ms) | 성공 p95(ms) | 정합성 |
|---|---:|---:|---:|---:|---:|---:|---|
| REDIS_LUA | 1,000 | 9,000 | 0 | 1,335.7 | 101.1 | 126.1 | true |
| REDIS_DECR | 1,000 | 9,000 | 0 | 1,187.0 | 103.2 | 164.8 | true |
| REDIS_WATCH | 1,000 | 8,997 | 3 | 620.8 | 206.6 | 267.6 | true |
| PESSIMISTIC | 1,000 | 9,000 | 0 | 594.0 | 431.1 | 460.4 | true |
| OPTIMISTIC | 1,000 | 8,668 | 332 | 467.1 | 325.9 | 682.3 | true |
| CONDITIONAL | 1,000 | 9,000 | 0 | 443.3 | 401.3 | 432.4 | true |
| REDIS_LOCK | 1,000 | 8,815 | 185 | 391.1 | 211.6 | 239.5 | true |
| JVM_LOCK | 1,000 | 9,000 | 0 | 202.8 | 623.2 | 686.0 | true |
| DIRECT | 9,997 | 3 | 0 | 148.5 | 358.2 | 358.2 | **false** |

## 재시도/락 획득 한도 초과

| 단계 | 전략 | 성공 | 품절 | 내부 오류 | 최종 잔여 | 발급 이력 | 정합성 |
|---|---|---:|---:|---:|---:|---:|---|
| Basic | OPTIMISTIC | 100 | 77 | 23 | 0 | 100 | true |
| Basic | REDIS_LOCK | 100 | 97 | 3 | 0 | 100 | true |
| Basic | REDIS_WATCH | 100 | 99 | 1 | 0 | 100 | true |
| Contention | OPTIMISTIC | 100 | 879 | 21 | 0 | 100 | true |
| Contention | REDIS_LOCK | 100 | 881 | 19 | 0 | 100 | true |
| Performance | OPTIMISTIC | 1,000 | 8,668 | 332 | 0 | 1,000 | true |
| Performance | REDIS_LOCK | 1,000 | 8,815 | 185 | 0 | 1,000 | true |
| Performance | REDIS_WATCH | 1,000 | 8,997 | 3 | 0 | 1,000 | true |

이 오류들은 재고 예약 전의 충돌/락 획득 실패로 나타났으며 최종 재고와 DB 발급 이력은 일치했다. 즉 이번 실행에서는 데이터 정합성보다 가용성 저하로 해석해야 한다.

## 해석 제한

- 각 조건을 한 번씩만 실행한 결과이므로 절대 성능 순위로 일반화하면 안 된다. 신뢰 구간을 얻으려면 전략 순서를 회전하며 최소 3회 이상 반복해야 한다.
- 단일 애플리케이션 인스턴스 결과이므로 `JVM_LOCK`은 다중 인스턴스 환경을 대표하지 않는다.
- Redis 전략도 중복 확인과 발급 이력 저장을 위해 MySQL을 사용한다. Redis 연산 자체의 순수 벤치마크가 아니다.
- 전체 p95에는 빠른 품절 응답이 많이 포함된다. 발급 경로 성능 비교에는 `SuccessP95Ms`를 함께 사용해야 한다.
