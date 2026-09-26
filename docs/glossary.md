# 용어

문서 전체에서 같은 대상은 같은 이름으로 부릅니다. 예전 보고서에서 쓰던 이름은 오른쪽 열에 남겨 두었습니다.

## 응답 결과

| 용어 | 뜻 | 예전 이름 |
|---|---|---|
| 성공 | `SUCCESS` (HTTP 200) | |
| 품절 | `SOLD_OUT` (409) | |
| 중복 회원 | `DUPLICATE_USER` (409). 같은 회원이 이미 발급받음 | |
| 중복 요청 | `DUPLICATE_REQUEST` (409). 같은 `requestId`를 이미 처리함 | |
| 오류 응답 | `INTERNAL_ERROR` (500). 재시도·락 획득 한도 소진, Redis 연결 실패, 처리되지 않은 예외가 모두 이 코드로 나옵니다. 응답만으로는 원인을 구분할 수 없으므로, 원인은 확인된 경우에만 따로 적습니다 | 한도 초과, 내부 오류 |
| 무응답 | k6가 결과 body를 받지 못한 요청 (연결 실패 등) | no-result, 전송/응답 누락, 전송 실패 |
| HTTP 실패 | 무응답 + 오류 응답. k6의 `http_req_failed` | |

전체 결과 코드와 HTTP 상태는 [API 문서](api.md#결과-코드)에 있습니다.

## 판정 기준

| 용어 | 뜻 | 예전 이름 |
|---|---|---|
| 초과 발급 없음 | 발급 이력 수 ≤ 재고 | |
| 재고 원장 일치 | status API의 `consistent`. DB 전략은 `issued_quantity = 발급 이력 수 = total − remaining`, Redis 전략은 `Redis 잔여 + 발급 이력 수 = total` (Redis 잔여 ≥ 0) | 정합성, status 정합성 |
| 1인 1매 | DB 교차 확인에서 회원별 최대 발급 수가 1 | |
| 대상자 전원 발급 | Duplicate user 시나리오에서 10,000명 모두 1건씩 발급 | 10,000명 모두 발급 |
| 응답 분류 정확 | Duplicate user 시나리오에서 `SUCCESS=10,000`, `DUPLICATE_USER=20,000`, 나머지 0 | 기대 응답 일치 |
| 정합성 실패 | 재고 원장이 깨짐 | |
| 가용성 저하 | 원장은 맞지만, 정상 요청이 오류 응답·무응답·잘못된 결과 코드를 받음 | |

## 전략 이름

문서에서는 enum 이름(`REDIS_LUA`)을, API 경로와 스크립트 인자에서는 경로 이름(`redis-lua`)을 씁니다. "Watch"처럼 줄여 부르지 않고, "비-Watch"는 "`REDIS_WATCH` 제외"로 씁니다.

| 전략 | API 경로 · `-Strategies` 값 | requestId 코드 |
|---|---|---|
| `DIRECT` | `direct` | `d` |
| `JVM_LOCK` | `jvm-lock` | `j` |
| `PESSIMISTIC` | `pessimistic` | `p` |
| `OPTIMISTIC` | `optimistic` | `o` |
| `CONDITIONAL` | `conditional` | `c` |
| `REDIS_LOCK` | `redis-lock` | `rl` |
| `REDIS_DECR` | `redis-decr` | `rd` |
| `REDIS_LUA` | `redis-lua` | `ru` |
| `REDIS_WATCH` | `redis-watch` | `rw` |

## 부하와 시나리오

| 용어 | 뜻 |
|---|---|
| VU | k6 가상 사용자. 동시에 요청을 보내는 worker 수 |
| 폐쇄형 부하 | k6 `shared-iterations`. 정해진 요청 수를 VU들이 나눠 처리하고, 각 VU는 응답을 받아야 다음 요청을 보냅니다. 그래서 `req/s × 평균 지연 ≈ VU 수`가 성립하고, VU 증가는 도착률 증가가 아니라 동시 worker 증가입니다 |
| Unique user | 서로 다른 회원이 1회씩 요청 |
| Duplicate user | 회원마다 서로 다른 `requestId`로 3회 요청. 연속된 iteration 3개가 같은 회원이라 3건이 거의 동시에 들어갑니다 |
| 정순 · 역순 | 전략 실행 순서. 정순은 `DIRECT → … → REDIS_WATCH`, 역순은 그 반대 |

## 확실성 등급

원인을 적을 때 근거의 세기를 함께 표시합니다.

| 등급 | 기준 |
|---|---|
| 확정 | 로그, OS 지표, DB 교차 확인 같은 직접 증거로 원인까지 확인 |
| 관측 | 현상은 데이터로 확인했고 코드 구조로 설명되지만, 요청 단위로 추적하지는 않음 |
| 강한 추론 | 여러 간접 증거가 한 원인을 가리키지만 직접 계측이 없음 |
| 추론 | 설정값과 패턴에서 추정했고 직접 근거가 부족함 |
