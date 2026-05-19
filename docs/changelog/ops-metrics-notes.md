# Ops · 부하테스트 · 메트릭 변경 요약

운영 대시보드(`/ops`), nGrinder 시나리오 **A~F**, `business-metrics` API 변경 이력입니다.  
현재 동작 설명: [operations/ops-dashboard.md](../operations/ops-dashboard.md)

---

## 1. `/ops` 운영 대시보드

### 이벤트 vs 클러스터

- **선택 이벤트**: 활성 사용자, 대기열, 결제 대기·처리중, 점유율 + 진행 바.
- **클러스터**: OPEN 이벤트 합산.

### nGrinder

- 시나리오 **A~F**, `threads = max(threads, vusers)` 보정.

### 시나리오 KPI

- 공통: 결제 성공/실패/처리중.
- A~F별 `scenarioExtraKpis` — [load-test-runbook.md](../operations/load-test-runbook.md).

### 결제 requested vs succeeded

```text
requested ≈ (succeeded + failed) + processing + queueDepth + dropped + duplicate
```

---

## 2. `business-metrics` API

### 추가 필드

| 필드 | Micrometer |
|------|------------|
| `queueEnteredTotal` | `ticketing.queue.entered.total` |
| `admissionIssuedTotal` | admission issued |
| `seatLockFailedTotal` | seat lock failed |
| `reservationExpiredTotal` | reservation expired |
| `rateLimitRejectedTotal` | ratelimit rejected (합산) |
| `httpServerRequestTotal` | http.server.requests count |

### Counter 합산

`find(name).counters()` 전 시리즈 합산으로 통일.

### Redis 클러스터 카운터 (옵션)

`ticketing.metrics.cluster-counters.enabled=true` → Redis `INCRBY` 미러.  
기본 `false`.

---

## 3. 시나리오 F

- `15_scenario_f_integrated_random_mixed.groovy`
- A~E 스타일 랜덤 혼합, `ephem_` 일회성 사용자
- 업로드: `upload-scripts.ps1`

---

## 4. 코드 위치

| 영역 | 경로 |
|------|------|
| 비즈니스 메트릭 | `DashboardRealtimeController.java` |
| Redis 미러 | `ClusterBusinessMetricsBridge.java` |
| Ops UI | `OpsDashboard.tsx` |
| nGrinder | `NgrinderDashboardController.java` |

메트릭 계약: [observability/metrics-contract.md](../observability/metrics-contract.md)

---

## 5. 배포

```bash
docker compose up -d --build backend frontend
```

스크립트 변경 시 `upload-scripts.ps1` 재실행.
