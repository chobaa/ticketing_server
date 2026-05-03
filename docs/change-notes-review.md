# 작업·수정 기록 (검토용)

이 파일은 채팅·세션에서 반영된 변경 사항을 **검토·이관용**으로 한곳에 모아 둔 것입니다. 필요하면 섹션 단위로 이슈/위키/PR 설명으로 옮겨 정리하면 됩니다.

---

## 1. nGrinder 부하 테스트 — 대시보드 모니터링 강화

**파일:** `frontend/src/components/TrafficAnalyticsDashboard.tsx`

**목적:** `Requested (Δ)`가 nGrinder의 “TESTING(진행 중)”과 동일한 의미가 아니라, 서버의 결제 발행 카운터 증가분임을 보완하고, 진행 중 파이프라인 상태를 같은 화면에서 볼 수 있게 함.

**변경 요약**

- `useRef`로 `Requested (Δ)`가 연속 갱신에서도 값이 변하지 않는지 추적.
- `TESTING`이면서 Δ가 여러 번(약 4회 이상) 동일할 때 **안내 배너** 표시 (큐 적체, progress 타임아웃, 로컬 rate limit 등 안내).
- `testId` 변경 시 stale 추적 ref 초기화.
- `dashboardBusinessMetrics` 응답을 `setBiz`에 더 반영: `paymentQueueDepth`, `paymentProcessing`, `paymentWorkersSleeping`, `paymentWorkerSleepMsTotal`, `paymentRequestedMismatch`.
- **새 Status 카드 행**
  - Inflight(관측), Queue depth, Processing(DB), Workers sleeping
  - Requested mismatch, TPS(nGrinder, 차트와 동일한 샘플 평균)
- nGrinder 패널 폴링 간격: **2500ms → 2000ms**.

---

## 2. 비즈니스 메트릭 API — `paymentWorkersSleeping` 수정

**파일:** `backend/src/main/java/com/ticketing/api/DashboardRealtimeController.java`

**문제:** `/api/dashboard/business-metrics`에서 `paymentWorkersSleeping`이 항상 `0.0`으로 고정되어 있었음.

**수정:** Micrometer 게이지 `ticketing.payment.worker.sleeping` 값을 읽어 반환하도록 변경 (`gauge("ticketing.payment.worker.sleeping")`).

---

## 3. nGrinder “발행(requested) 건수” 테스트 — progress 대기 시간

**파일:** `backend/src/main/java/com/ticketing/api/NgrinderDashboardController.java`  
**엔드포인트:** `POST /api/dashboard/ngrinder/payment-requests/start`

**목적:** 스크립트 `05_all_in_one.groovy`의 기본 `progressMaxWaitSec`이 30초라, 결제 워커 큐가 길면 progress 폴링이 타임아웃되어 스레드가 줄고 `Requested (Δ)` 증가가 끊기는 현상 완화.

**수정:** 위 엔드포인트에서 생성하는 테스트의 `param`에 **`progressMaxWaitSec=120`** 추가.

---

## 4. 참고만 (코드 변경 없음) — Redis / Docker / Grafana

**Docker에서 Redis 클러스터가 “비활성”처럼 보일 때**

- `redis-cluster-init`은 **일회성 작업**이라 완료 후 컨테이너가 **Exited**로 보이는 것이 정상 (`restart: "no"`).
- 실제 클러스터는 **`redis-node-1` ~ `redis-node-6`** 이 Running인지 보면 됨.

**Grafana “Redis 클러스터(연결)”**

- `ticketing_redis_up`은 **백엔드가 Redis에 PING 성공 여부**이며, 6노드 컨테이너 가동과 1:1이 아님.
- 로컬에서 백엔드만 띄우고 `redis-cluster` 프로필/노드 주소가 맞지 않으면 0으로 보일 수 있음.

**`Requested (Δ)` vs nGrinder 진행 표시**

- Δ는 **`ticketing.payment.requested.total`** (Kafka→Rabbit 브릿지에서 큐에 넣을 때 증가).
- 스크립트는 예약 후 결제 종료까지 progress 대기 → **워커 슬립·큐·스레드 수**에 따라 증가 속도가 느리거나 잠시 멈춘 것처럼 보일 수 있음.
- Docker 백엔드는 `RATE_LIMIT_*`를 넉넉히 두었으나, **로컬 단독 실행** 시 기본 IP rate limit(초당 10회 등)으로 예약이 429 나면 Δ가 일찍 멈출 수 있음.

---

## 5. nGrinder “발행(requested) 건수” 테스트 — 실행 시간 상한

**파일:** `backend/src/main/java/com/ticketing/api/NgrinderDashboardController.java` (`payment-requests/start`)

**배경:** `durationMs = max(5분, requestedCount×1.5초)`이면 `requestedCount=200`일 때 약 5분뿐이라, 백엔드가 `requested` 목표(200)에 도달하기 전에 Groovy의 `while (now < endAtMs)`가 끝나 nGrinder만 **FINISHED** 되는 경우가 있었음.

**수정 (1차):** `durationMs = min(1시간, max(20분, requestedCount×60초))`.

**수정 (2차, 대량 건수):** `testDurationSec`이 **`min(..., 3600)`으로 고정**되어 Groovy 루프가 **항상 1시간**에 끊기던 문제를 `maxWindowSec`와 맞춤.

**수정 (3차):** 사용자 설정에 따라 **`maxWindowMs = 15분`** — `durationMs = min(15분, max(1분, requestedCount×60초))`, `testDurationSec`도 동일 상한(최소 60초). 고건수는 백엔드 `stop`으로 조기 종료되지만, 스크립트는 최대 15분 후 자연 종료할 수 있음.

---

## 6. nGrinder 스크립트 — `paymentTarget` 모드 좌석 고정 문제

**파일:** `load-tests/ngrinder/scripts/05_all_in_one.groovy`

**문제:** `paymentTarget > 0`일 때 스레드마다 `beforeThread`에서 고정한 `chosenSeatId`만 예약. 한 좌석이 **CONFIRMED → SOLD** 되면 같은 좌석으로는 더 이상 예약이 안 되어, `requested`·정산이 **수십 건대에서 정지**하는 현상 발생.

**수정:** `paymentTarget > 0`일 때만, `reserveAttempted`·스레드 번호로 풀(`seatPoolSize`·`seatIds`) 안에서 **좌석 ID를 매 시도마다 순환**하도록 `pickSeatIdForPaymentTargetAttempt()` 사용.

**운영:** 스크립트 변경 후 컨트롤러에 반영하려면 `load-tests/ngrinder/upload-scripts.ps1` 실행 필요.

---

## 7. 실행 기록 — `requestedCount=200` 검증 (에이전트가 수행한 호출)

| 항목 | 내용 |
|------|------|
| 환경 | `docker compose` 백엔드 `localhost:8080`, nGrinder `localhost:19080` |
| 테스트 #66 (스크립트 수정 전) | 약 5분 제한으로 종료, `paymentRequestedTotal` 87 부근에서 정체 후 nGrinder **FINISHED** — 목표 200 미달 |
| 테스트 #67 (시간만 연장) | `requestedTotal` 39에서 장시간 정체 — 위 **좌석 SOLD 고정** 원인 |
| 테스트 #68 | `upload-scripts.ps1`로 수정 스크립트 업로드 후 재실행. 시작 시점 `baselinePaymentRequestedTotal=39`. 약 1분 30초 내 `reqDelta` 200+ 도달, 최종 `settled=323` 등 — **성공+실패 정산은 “이번 테스트 구간”만 보려면 시작 시점의 succeeded/failed 베이스라인을 빼서 계산**할 것 |

**정리:** “200에 가깝게 성공/실패가 생기나”는 **엔드포인트가 맞추는 것은 `paymentRequested` 증가분(≈200 근처에서 백엔드가 stop)** 이고, 성공/실패 **건수**는 시뮬 성공률·큐 적체에 따라 달라짐. 스크립트·시간 수정 후에는 한 번에 **수백 건 규모**로 늘어날 수 있음(오버슛 포함).

---

## 8. 이후 정리 시 체크리스트 (선택)

- [x] 루트 `README.md`에 본 문서 링크 추가됨
- [ ] nGrinder 쪽 상세 운영(`progressMaxWaitSec`, 좌석 순환, 15분 창 한계)을 [load-tests/ngrinder/README.md](../load-tests/ngrinder/README.md)로 옮길지 결정
- [ ] 본 파일을 팀 위키로 흡수한 뒤 `docs/` 정리(삭제/축약) 여부

---

*작성 기준: 저장소 내 위 경로 파일 기준 변경. 날짜는 로컬 작업일에 맞춰 기록해 두세요.*
