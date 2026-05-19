# Ops 대시보드 (`/ops`)

운영자가 **단일 부하 run**을 실행·관측하는 화면입니다.

## URL · 인증

- http://localhost/ops (nginx 경유)  
- JWT 로그인 필요 (일반 사용자 UI와 동일 토큰)

## 화면 구성 (README 스크린샷 기준)

| 영역 | 설명 | 스크린샷 |
|------|------|----------|
| **지표 패널** | 시나리오 KPI, 결제 파이프라인, runId, nGrinder 상태 | `docs/assets/screenshots/ops-kpis.png` |
| **좌석 히트맵** | 이벤트별 좌석 `AVAILABLE` / `HELD` / `SOLD` | `docs/assets/screenshots/ops-heatmap.png` |
| Grafana 링크 | SLO · Bottleneck · Scenarios · **Funnel(runId)** | `docs/assets/screenshots/grafana-funnel-runid.png` |

## nGrinder 패널

- 시나리오 **A~F**, `vusers` / `threads` / `testDurationSec` 등  
- 백엔드: `threads = max(threads, vusers)` 보정  
- 응답: `loadTestRunId`, `ngrinderVusers`, `ngrinderThreads`

API: `POST /api/dashboard/ngrinder/scenarios/start`

## 폴링 API

| 엔드포인트 | 간격 | 용도 |
|------------|------|------|
| `GET /api/dashboard/business-metrics` | ~2s | 누적 카운터·큐 depth |
| `GET /api/dashboard/realtime` | ~2s | p99 근사 등 |
| `GET /api/dashboard/run-metrics?runId=` | on demand | runId 한정 집계 |
| 이벤트 요약 | ~2s | 히트맵·점유율 |

## `business-metrics` 주요 필드

| 필드 | Micrometer / 의미 |
|------|-------------------|
| `queueEnteredTotal` | `ticketing.queue.entered.total` |
| `admissionIssuedTotal` | 입장 토큰 발급 |
| `seatLockFailedTotal` | 락 실패 |
| `reservationExpiredTotal` | TTL 만료 |
| `rateLimitRejectedTotal` | 429 (scope 합산) |
| `httpServerRequestTotal` | `http.server.requests` count (runId 태그 시) |
| `paymentRequestedTotal` 등 | 결제 파이프라인 |

카운터 조회: 동일 이름 **모든 시리즈 합산** (`find(name).counters()`).

## 결제 requested vs succeeded/failed

UI 안내 요약:

```text
requested ≈ (succeeded + failed) + processing + queueDepth + dropped + duplicate
```

- **Requested (Δ)** ≠ nGrinder "TESTING" 상태와 1:1이 아님 → 서버 발행 카운터 증분  
- 비동기 지연·큐 적체 시 Δ가 잠시 멈춘 것처럼 보일 수 있음  

## 클러스터 카운터 (옵션)

`ticketing.metrics.cluster-counters.enabled=true`  
→ Redis `INCRBY` 미러, 멀티 인스턴스 Ops 합산.  
기본 `false` = JVM 로컬 Micrometer만.

설정: `application.yml`, env `TICKETING_METRICS_CLUSTER_COUNTERS_ENABLED`

## 코드 위치

| 영역 | 경로 |
|------|------|
| Ops UI | `frontend/src/components/OpsDashboard.tsx` |
| 비즈니스 메트릭 | `backend/.../DashboardRealtimeController.java` |
| runId 필터 | `backend/.../RequestDebugContextFilter.java` |
| nGrinder | `backend/.../NgrinderDashboardController.java` |

## 관련 문서

- [load-test-runbook.md](./load-test-runbook.md)
- [changelog/ops-metrics-notes.md](../changelog/ops-metrics-notes.md)
