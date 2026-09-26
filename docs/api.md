# 실험 API

기본 주소는 `http://localhost:8080`입니다. 모든 경로는 `/experiment`로 시작합니다.

## 엔드포인트

| Method | Path | 용도 |
|---|---|---|
| POST | `/experiment/coupons` | 실험용 쿠폰과 재고 행 생성. body `{"quantity": n}`, 201 응답 |
| POST | `/experiment/coupons/{couponId}/{strategy}` | 발급 요청. body `{"userId", "requestId"}`, Redis 전략은 `?runId=` 필요 |
| POST | `/experiment/coupons/{couponId}/{redisStrategy}/initialize?runId=` | Redis 재고 키를 `총량 − 발급 이력 수`로 설정 |
| POST | `/experiment/coupons/{couponId}/reset` | 발급 이력 삭제, DB 재고 초기화 |
| POST | `/experiment/coupons/{couponId}/{redisStrategy}/reset?runId=` | DB reset 후 Redis 키를 지우고 다시 initialize |
| GET | `/experiment/coupons/{couponId}/status` | DB 기준 잔여 재고·발급 수·원장 일치 |
| GET | `/experiment/coupons/{couponId}/{redisStrategy}/status?runId=` | Redis 잔여를 포함한 상태 |

- `{strategy}`는 [전략 경로 이름](glossary.md#전략-이름) 9개 중 하나이고, `{redisStrategy}`는 `redis-`로 시작하는 4개만 받습니다.
- `runId`는 `[A-Za-z0-9][A-Za-z0-9_-]{0,31}` 형식이고, 생략하면 `r01`입니다.
- `userId`는 양수, `requestId`는 공백이 아닌 64자 이하 문자열이어야 합니다.

## 요청과 응답 예

발급 요청:

```http
POST /experiment/coupons/12/redis-lua?runId=r0816dup1-u-08
Content-Type: application/json

{"userId": 3, "requestId": "r0816dup1-u-08-ru-cc-u3-i8"}
```

발급 응답 (실패 응답도 같은 형식이고 `result`만 다릅니다):

```json
{"couponId": 12, "userId": 3, "requestId": "r0816dup1-u-08-ru-cc-u3-i8", "result": "SUCCESS"}
```

상태 응답 (DB 전략은 `redisRemainingQuantity`가 `null`):

```json
{"couponId": 12, "totalQuantity": 10000, "dbRemainingQuantity": 10000, "redisRemainingQuantity": 2, "issueCount": 9998, "consistent": true}
```

`consistent`의 계산 방식은 [용어 › 재고 원장 일치](glossary.md#판정-기준)에 있습니다.

## 결과 코드

| 코드 | HTTP | 뜻 |
|---|---:|---|
| `SUCCESS` | 200 | 발급 |
| `SOLD_OUT` | 409 | 재고 없음 |
| `DUPLICATE_REQUEST` | 409 | 같은 `requestId`를 이미 처리함 (사전 조회 또는 `uq_request_id` 충돌) |
| `DUPLICATE_USER` | 409 | 같은 회원이 이미 발급받음 (사전 조회 또는 `uq_coupon_user` 충돌) |
| `REDIS_NOT_INITIALIZED` | 409 | Redis 재고 키가 없음 (initialize 전) |
| `COUPON_NOT_FOUND` | 404 | 쿠폰 재고 행이 없음 |
| `INVALID_REQUEST` | 400 | 요청 검증 실패, 지원하지 않는 전략, 잘못된 `runId` |
| `INTERNAL_ERROR` | 500 | 재시도·락 획득 한도 소진, Redis 예약 실패, 처리되지 않은 예외 |

## Redis key

| key | 용도 |
|---|---|
| `experiment:{runId}:{strategy}:coupon:{couponId}:stock` | 전략별 재고 |
| `experiment:{runId}:redis-lock:coupon:{couponId}:lock` | `REDIS_LOCK`의 락 |

## k6 requestId 형식

`{runId}-{전략 코드}-c{couponId}-u{userId}-i{iteration}`이고 숫자 세 개는 36진수입니다. 전략 코드는 [용어](glossary.md#전략-이름)에 있습니다. 64자를 넘으면 k6 스크립트가 실패합니다.
