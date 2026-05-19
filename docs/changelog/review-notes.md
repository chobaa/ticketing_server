# 변경·검증 메모 (세션 아카이브)

채팅·세션에서 반영된 변경을 **검토·이관용**으로 보관합니다.  
운영 절차는 [operations/troubleshooting.md](../operations/troubleshooting.md), [operations/load-test-runbook.md](../operations/load-test-runbook.md)를 우선 참고하세요.

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

**수정:** Micrometer 게이지 `ticketing.payment.worker.sleeping` 값을 읽어 반환하도록 변경.

---

## 3. nGrinder “발행(requested) 건수” 테스트 — progress 대기 시간

**엔드포인트:** `POST /api/dashboard/ngrinder/payment-requests/start`

**수정:** `param`에 **`progressMaxWaitSec=120`** 추가 (`05_all_in_one.groovy` 기본 30초 타임아웃 완화).

---

## 4. Redis / Docker / Grafana (참고)

- `redis-cluster-init` **Exited** = 정상. `redis-node-1`~`6` Running 확인.
- `ticketing_redis_up` = 백엔드 PING 성공 여부.
- `Requested (Δ)` = `ticketing.payment.requested.total` (Kafka→Rabbit 적재 시점).

---

## 5~6. payment-requests 실행 시간 · 좌석 순환

- 실행 시간 상한: `maxWindowMs = 15분` 등으로 조정 이력.
- `05_all_in_one.groovy`: SOLD 좌석 고정 시 정체 → `pickSeatIdForPaymentTargetAttempt()` 순환.

---

## 7. 실행 기록 — `requestedCount=200`

| 테스트 | 결과 요약 |
|--------|-----------|
| #66 | 5분 제한, requested ~87에서 FINISHED |
| #67 | 좌석 SOLD 고정으로 정체 |
| #68 | 스크립트 업로드 후 reqDelta 200+ |

성공+실패는 **시작 베이스라인을 빼고** 이번 구간만 계산.

---

*아카이브 문서 — 최신 운영 정보는 `docs/operations/` 참고.*
