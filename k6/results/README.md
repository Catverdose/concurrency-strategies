# 실험 결과 데이터

`run-experiments.ps1`이 만든 원본 결과와, 보고서용으로 합친 통합본이 있습니다. 해석은 [docs/](../../docs/)의 보고서를 보세요. 실패하거나 경고가 붙은 실행도 지우지 않고 남겼습니다.

## 파일 종류

| 파일 | 만든 곳 | 내용 |
|---|---|---|
| `{stage}-{strategy}-{couponId}.json` | k6 `--summary-export` | 전략 1회 측정의 k6 summary |
| `experiment-results.csv` · `.json` | `run-experiments.ps1` 자동 생성 | 그 폴더에서 실행한 전략만 담은 원본 |
| `duplicate-user-results.*`, `duplicate-user-reverse-results.*`, `vu-scaling-results.*` | 보고서 작성 시 수동 통합 | 다른 폴더의 단독 실행 결과를 합치고, DB 교차 확인과 비교 가능 여부(`ComparableLatency`, `MeasurementNote`) 열을 추가한 **보고서 기준 데이터** |

한 폴더에 `experiment-results.csv`와 통합본이 함께 있으면 **보고서는 통합본을 기준으로 합니다.** 결과 CSV의 `K6ExitCode`가 99이면 k6 threshold 실패입니다.

## Run index

폴더 이름은 `r{월일}{설명}{회차}` 형식입니다. `w`는 `REDIS_WATCH` 단독 실행, `rev`는 역순을 뜻합니다.

| Run | 날짜 | 내용 | 보고서에서 쓰는 곳 |
|---|---|---|---|
| `r0814full1` | 08-14 | 첫 실행. `basic-direct` summary 1건만 남음 (예상 밖 결과 44건). CSV 없음 | 쓰지 않음 |
| `r0814full2` | 08-14 | Unique user 3단계 × 9개 전략 (27회) | [01 Unique user](../../docs/01-unique-user.md) |
| `r0816dup1` | 08-16 | Duplicate user 정순, VU 50, 9개 전략. `experiment-results.csv`의 `REDIS_WATCH` 행(성공 897)은 순서 실행 중의 값이라 통합본에서는 `r0816dup2w` 값으로 바꿈 | [02](../../docs/02-duplicate-user.md) 정순 (`duplicate-user-results.csv`) |
| `r0816dup1w` | 08-16 | `REDIS_WATCH` 단독 재실행 (성공 954). 실행 조건(TIME_WAIT 초기 상태) 기록 없음 | 쓰지 않음 |
| `r0816dup2w` | 08-16 | `REDIS_WATCH` 단독 재실행, Redis TIME_WAIT 0에서 시작 (성공 945) | 02 정순의 `REDIS_WATCH` 행 |
| `r0816duprev1` | 08-16 | Duplicate user 역순, VU 50, `REDIS_WATCH` 제외 8개 전략 + 역순 통합본 | 02 역순 (`duplicate-user-reverse-results.csv`), [03](../../docs/03-vu-scaling.md) VU 50 |
| `r0816duprev1w` | 08-16 | 역순 첫 순서로 `REDIS_WATCH` 단독 실행 (성공 946). summary JSON은 `r0816duprev1`에도 같은 파일이 들어 있음 | 02 역순, 03 VU 50의 `REDIS_WATCH` 행 |
| `r0816vu10r1` · `r0816vu100r1` · `r0816vu200r1` | 08-16 | VU 10 / 100 / 200, `REDIS_WATCH` 제외 8개 전략 | 03 |
| `r0816watchvu10r1` · `r0816watchvu100r1` · `r0816watchvu200r1` | 08-16 | VU별 `REDIS_WATCH` 단독 (Redis 포트 6381 / 6382 / 6383) | 03 |
| `r0816vuscale1` | 08-16 | VU 확장 통합본 CSV · JSON · XLSX (36행, DB 교차 확인 포함) | 03 |
