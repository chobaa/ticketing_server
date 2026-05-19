# 트러블슈팅

부하 테스트·로컬 구동 중 자주 나오는 증상과 확인 순서입니다.

## nGrinder · 스크립트

| 증상 | 원인 | 조치 |
|------|------|------|
| `script should exist` | 스크립트 미업로드, DIR 구조 불일치 | `.\load-tests\ngrinder\upload-scripts.ps1`. `docker compose down -v` 후 **필수** |
| `stop_by_error` | 인증 실패, `eventId` 누락, 429 | Controller 로그, `beforeThread`. `RATE_LIMIT_*` 완화 |
| C Rate limit 0 | Docker `RATE_LIMIT_*=10000`만 적용·runId 프로필 없음 | Ops **Scenario C** 재실행( runId당 5/초·IP 10/초 프로필 자동). `X-LoadTest-RunId` 헤더·`vusers` 상향 |
| C/F 지표 0 | 저부하, `scriptRevision: -1` | `vusers`, `testDurationSec` 상향, 스크립트 재업로드 |
| `admin is logined` 로그 반복 | Ops 상태 API Basic 폴링 | 정상; nGrinder 로그 레벨 WARN 조정 |

## 관측 지표 개편 — 겪었던 혼란과 해결 (발표·회고용)

처음에는 **HTTP TPS·p99·nGrinder 차트**만으로 “잘 도는지”를 봤습니다. 시나리오 A~F를 돌리면서 **퍼널 단계별·결제 파이프라인별** 숫자가 없으면 원인을 못 좁히는 경우가 반복되어, `business-metrics`·Ops KPI·runId·Grafana Funnel까지 **관측 축을 여러 번 바꿨습니다**.

### 1단계: “HTTP만 보면 된다” → 한계

| 증상 | 원인 | 바꾼 것 |
|------|------|---------|
| A/B 부하인데 HTTP Δ가 0 | 짧은 구간·저부하에서 `http.server.requests` count가 스냅샷 사이에 안 잡힘 | **도메인 카운터** 추가: `queue_entered`, `admission_issued`, `seat_lock_failed` |
| Grafana rate는 있는데 Ops는 안 움직임 | Ops는 REST 누적값, Grafana는 `rate()` | 시나리오별 **Ops 3~7 KPI** (`scenarioExtraKpis`) 고정 |
| C(조회 폭격)인데 결제 카운터만 봄 | C는 `/seats` 위주, 결제 거의 없음 | C KPI를 **429 · HTTP( runId ) · p99** 로 변경 |

### 2단계: 결제 파이프라인 — 숫자가 “안 맞는 것 같다”

| 증상 | 원인 | 바꾼 것 |
|------|------|---------|
| `paymentWorkersSleeping` 항상 0 | API가 게이지를 읽지 않고 0 고정 반환 | `ticketing.payment.worker.sleeping` 게이지 연동 |
| 성공/실패 누적이 **줄어드는 것처럼** 보임 | `find(name).counter()`가 **태그된 시리즈 하나만** 반환 | `find(name).counters()` **전체 합산** |
| Requested Δ는 멈췄는데 nGrinder는 TESTING | 클라이언트 “진행 중” ≠ 서버 `payment.requested` 증가 | Ops에 **파이프라인 카드** 추가: queue depth, processing, inflight, mismatch, 안내 배너 |
| requested ≠ succeeded+failed | 비동기: 큐·PROCESSING·드롭·중복 존재 | `paymentRequestedMismatch`, `paymentWipFromCounters`, UI에 **식 설명** |

**배운 점:** 결제는 `requested` 한 줄로 끝나지 않는다. **issued(큐 적재) → worker → DB 정산**을 나눠 봐야 한다.

### 3단계: runId — “이번 테스트만” 보고 싶다

| 증상 | 원인 | 바꾼 것 |
|------|------|---------|
| 전역 누적에 이전 run·수동 트래픽이 섞임 | Micrometer는 JVM 전체 누적 | `X-LoadTest-RunId` + `RunScopedMetricsStore` + `GET /run-metrics` |
| runId 있는데도 전역 카운터 표시 | Ops가 `business-metrics`만 사용 | **runId 있으면 `run-metrics`만** (`effectiveBiz`), 전역 fallback 금지 |
| Prometheus에 runId 라벨 붙이고 싶음 | 카디널리티 폭발 | 라벨 대신 **로그 MDC + Funnel 변수 + run-metrics API** |
| HTTP는 전역만 있고 run별이 안 보임 | `http.server.requests`는 Micrometer 전역 | runId 요청마다 `runScoped.incHttpRequest` (필터) |

**배운 점:** “누적 카운터”와 “이번 실험 구간”은 **데이터 소스를 분리**해야 한다.

### 4단계: 시나리오·퍼널 세분화

| 추가 지표 | 용도 |
|-----------|------|
| `reservation_expired_total` | D (좀비 TTL) |
| `rate_limit_rejected_total` | C, F |
| `reservation_attempted/succeeded`, 실패 사유별 | F 혼합 부하, Funnel 설명 |
| `integrity_mismatch_*` | 전 시나리오 정합 (0이 정상) |
| `payment_dropped`, `skipped_duplicate`, `settle_skipped_terminal` | mismatch 해석, 중복 Kafka 정산 |

시나리오 **F**는 KPI를 3개에서 **7개**(대기열~예약 실패·HTTP)로 늘려, 혼합 부하에서 **어느 단계가 먼저 포화되는지** 한 화면에서 보도록 했습니다.

### 5단계: 멀티 인스턴스·베이스라인 (옵션)

| 증상 | 대응 |
|------|------|
| 인스턴스마다 Ops 숫자가 들쭉날쭉 | `ticketing.metrics.cluster-counters` → Redis 합산 (옵션) |
| 결제 Δ가 과거 run 포함 | 테스트 시작 시 **베이스라인** 캡처 후 Δ 표시 |
| Grafana와 Ops 숫자 축이 다름 | 역할 분리: Ops=단일 run, Grafana=시계열·rate |

### 지표 개편 후에도 헷갈릴 때 체크리스트

1. **runId가 붙었는가?** (Ops 상단 8자리, Groovy 헤더)  
2. Ops가 **run-metrics**를 쓰는가? (runId 있을 때 전역 `business-metrics`와 혼동 금지)  
3. 시나리오 **C**인데 결제 KPI만 보고 있지 않은가?  
4. **Δ**가 0이면 부하·시간·스크립트 업로드부터, 그다음 Grafana `rate()`  
5. mismatch가 크면 Rabbit **queue depth** + `paymentWorkersSleeping`  

상세 API·필드: [ops-dashboard.md](./ops-dashboard.md) · [observability/metrics-contract.md](../observability/metrics-contract.md)

---

## Ops · 데이터 정합

| 증상 | 원인 | 조치 |
|------|------|------|
| F 매진 후에도 장시간 실행 | `/seats` 캐시, `baseUrl` 불일치 | `?refresh=true`, 단일 백엔드 URL |
| 히트맵 vs 스크립트 불일치 | DB vs 캐시 스냅샷 | `SeatViewCacheService` 5s TTL, `refresh=true` |
| 결제 Δ 정체 | Rabbit 적체, progress 타임아웃, 429 | `:15672` depth, `paymentWorkersSleeping` |
| `paymentRequested` 목표 미달 | 시간 상한, SOLD 좌석 고정(구 스크립트) | `05_all_in_one.groovy` 좌석 순환 버전 업로드 |

## Redis · 인프라

| 증상 | 원인 | 조치 |
|------|------|------|
| 클러스터 "비활성" | `redis-cluster-init` Exited | **정상** — `redis-node-1`~`6` Running |
| Grafana Redis up = 0 | 백엔드 PING 실패 | `redis-cluster` 프로파일, `REDIS_CLUSTER_NODES` |
| Micrometer 누적 급감 | JVM 재시작, LB 인스턴스 혼선 | 단일 인스턴스 검증; `sum by (instance)` |
| Prometheus 데이터 유실 | TSDB 볼륨 없음 | compose 재생성 시 초기화 |

## 재현 시 첨부 권장

- nGrinder 테스트 로그  
- `X-LoadTest-RunId` access 로그  
- `GET /api/dashboard/run-metrics?runId=...`  
- `business-metrics` 전후 JSON  

## 상세 세션 메모

- [changelog/review-notes.md](../changelog/review-notes.md) — progress 타임아웃, 좌석 순환, requestedCount=200 검증  
- [changelog/ops-metrics-notes.md](../changelog/ops-metrics-notes.md) — Ops UI·API 필드 변경  
