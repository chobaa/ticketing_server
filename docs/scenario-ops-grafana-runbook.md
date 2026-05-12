# 시나리오 A~F · Ops / Grafana / 부하 실행 런북

이 문서는 nGrinder 시나리오 **A~F**에 대해 **Ops 화면 지표**, **Grafana `ticketing-scenarios` 대시보드**, **좌석·파라미터**가 어떻게 대응하는지와, 한 번의 로컬 실행에서 측정한 **비즈니스 메트릭 증분**을 정리합니다.

## 1. 빠른 참조 — 시나리오별 Ops 3지표

| 시나리오 | Ops에서 보는 3개 (시나리오 선택 시 `scenarioExtraKpis`) |
|----------|----------------------------------------------------------|
| **A** | 대기열 진입(누적) · 입장 토큰 발급(누적) · 좌석 락 실패(누적) |
| **B** | 좌석 락 실패 · 대기열 진입 · 입장 토큰 발급 |
| **C** | Rate limit 거절 · HTTP 요청 처리 · 지연 p99(근사, realtime) |
| **D** | 예약 TTL 만료 · 결제 요청 · 결제 대기 예약(이벤트 선택 시) / 없으면 결제 드롭 |
| **E** | 판매 좌석 · 대기열 깊이 · 좌석 점유율 (이벤트 미선택 시 HTTP·대기열·입장으로 대체) |
| **F** | 좌석 락 실패 · HTTP 요청 처리 · Rate limit 거절 |

구현: `frontend/src/components/OpsDashboard.tsx` 의 `scenarioExtraKpis`.

## 2. Grafana `ticketing-scenarios` (uid: `ticketing-scenarios`)

| 패널 id | 제목 | 시나리오 정합 | 주요 PromQL / 지표 |
|--------|------|----------------|---------------------|
| **1** | Scenario A: Queue / Admission / Seat lock failed | **A** | `ticketing_queue_entered_total`, `ticketing_queue_admission_issued_total`, `ticketing_reservation_seat_lock_failed_total` |
| **2** | Scenario B: Lock fail / queue / admission + lock acquire p99 | **B** | 위 3종 rate + `ticketing_reservation_seat_lock_acquire_seconds_bucket` (p99 ms) |
| **3** | Scenario C: 429 by scope + total + HTTP + HTTP p99 | **C** | `ticketing_ratelimit_rejected_total`, `http_server_requests_seconds_*` |
| **4** | Scenario D: Expired & payment req + pending/processing/inflight | **D** | `ticketing_reservation_expired_total`, `ticketing_payment_requested_total`, integrity gauges, `ticketing_payment_inflight` |
| **5** | Integrity mismatch (sum) | 공통 | 정합성 가우지 합 |
| **6** | Payment pipeline rates | 공통·E·F | `ticketing_payment_*_total` |
| **7** | Scenario E: SOLD/HELD + PENDING + queue entered/s | **E** | `ticketing_integrity_*`, `ticketing_queue_entered_total` |
| **8** | Scenario F: Seat lock fail + HTTP + 429 | **F** | `ticketing_reservation_seat_lock_failed_total`, `http_server_requests_seconds_count`, `ticketing_ratelimit_rejected_total` |

파일: `docker/grafana/dashboards/ticketing-scenarios.json`  
프로비저닝은 Grafana의 dashboards 볼륨/설정에 따라 자동 로드됩니다.

## 2.1 Grafana `ticketing-funnel` (uid: `ticketing-funnel`)

`ops/sec` 보다 “100개의 joinQueue가 단계별로 어디로 갔는지”를 보기 위한 **Funnel 대시보드**입니다.

- **Stat(Δ, 5m)**: JoinQueue → Admission issued → Reserve attempted → Reserve succeeded
- **Timeseries**: Reserve 실패 사유별(rate) + Payment outcomes(rate)

파일: `docker/grafana/dashboards/ticketing-funnel.json`

## 2.2 runId로 건별 드릴다운(실무식)

메트릭만으로는 건별 추적이 불가능하므로, 로드테스트 실행마다 `runId`를 발급하고 모든 API 호출에
`X-LoadTest-RunId` 헤더를 포함해 **로그에서 바로 필터링**할 수 있게 합니다.

- **백엔드**: 요청 필터가 MDC에 `runId`, `reqId`를 넣고 콘솔 로그 패턴에 출력
- **nGrinder**: 시나리오 스크립트가 `param`의 `runId`를 읽어 `X-LoadTest-RunId` 헤더로 전송
- **Ops**: nGrinder 실행 결과에 `loadTestRunId`를 받아 화면에 표시(앞 8자리)

주의: `runId`는 고유값이라 Prometheus label로 쓰지 않습니다(고카디널리티).

## 3. Prometheus / job 이름

`docker/prometheus.yml` 에 `job_name: ticketing` → 타겟 `backend:8080` 이 정의되어 있습니다. 알람·일부 쿼리는 `job="ticketing"` 필터를 전제로 할 수 있습니다 (`docker/alert.rules.yml` 참고).

## 4. 좌석·파라미터 (백엔드 요약)

| 시나리오 | 좌석 수 | 비고 |
|----------|---------|------|
| **A** | UI 기본·API 미지정 시 `max(32, min(200, vusers+24))` | 히트맵 가독성 |
| **B** | 항상 **1** (핫키) | UI `eventSeatCount` 무시 |
| **C** | 미지정 시 `max(24, min(128, vusers*4))` | `/seats` 폴링 위주 |
| **D** | `< vusers` 이면 **vusers**로 상향; 미지정 시 `max(vu, min(300, vu*2))` | 스레드별 다른 좌석 |
| **E** | 미지정 시 **48**; `vusers`는 **좌석×crowdMultiplier** 로만 결정 | Ops에서 `eventSeatCount` 입력 노출 |
| **F** | `max(800, wantVu)` 등 대규모 기본; `< vusers` 이면 vusers 이상 | `testDurationSec` &lt; 30 이면 서버에서 **180** |

구현: `backend/.../NgrinderDashboardController.java` 의 `startScenario`.

## 5. 재현 스크립트

연속 실행 + `/api/dashboard/business-metrics` 증분 수집:

```powershell
powershell -NoProfile -File scripts/run-scenarios-metrics.ps1
```

## 6. 로컬 실행 결과 (한 세션, 2026-05-10)

아래는 **같은 JVM 누적 카운터**에서 시나리오를 **순서대로** 돌린 뒤, 각 구간 직전·직후 스냅샷의 **차이(Δ)** 입니다. 단위는 모두 **건수(누적 증가)** 이며, `httpServerRequestTotal` 은 Micrometer `http.server.requests` 타이머의 **count**입니다.

**파라미터 (요약)**

| 시나리오 | 대기(초) | 요청 파라미터 요약 |
|----------|----------|---------------------|
| A | 22 | `vusers=18&threads=18&eventSeatCount=56&testDurationSec=14` |
| B | 38 | `vusers=15&threads=15` |
| C | 22 | `vusers=14&threads=14&eventSeatCount=56&testDurationSec=14` |
| D | 16 | `vusers=8&threads=8&eventSeatCount=40&sleepMs=6000` |
| E | 50 | `eventSeatCount=20&crowdMultiplier=4` (vusers 미전송) |
| F | 42 | `vusers=12&threads=12&eventSeatCount=48&testDurationSec=35` |

**Δ 비즈니스 메트릭 (`/api/dashboard/business-metrics`)**

| 시나리오 | Δ 대기열 진입 | Δ 입장 발급 | Δ 락 실패 | Δ 429 | Δ HTTP(count) | Δ 예약 만료 | Δ 결제 요청 | Ops 3지표와의 일치 | Grafana 패널 |
|----------|---------------|-------------|------------|-------|----------------|-------------|--------------|-------------------|----------------|
| **A** | 36 | 18 | 34 | 0 | 0 | 0 | 2 | 대기열·입장·락 **↑** (의도와 일치) | 패널 **1** |
| **B** | 15 | 15 | 14 | 0 | 0 | 0 | 1 | 락·대기열·입장 **↑** (의도와 일치) | 패널 **2** |
| **C** | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 해당 구간에서 신호 약함※ | 패널 **3** |
| **D** | 8 | 8 | 0 | 0 | 0 | 0 | 8 | 대기열·입장·결제 파이프 **↑**; TTL 만료는 `sleepMs` &lt; hold TTL 이면 0이 정상 | 패널 **4**, **6** |
| **E** | 80 | 80 | 10 | 0 | 180 | 0 | 20 | 베이스라인 부하로 대기열·입장·HTTP·결제 **↑**; Ops 이벤트 KPI는 이벤트 선택 시 패널 **7**과 대응 | 패널 **6**, **7** |
| **F** | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 해당 구간에서 신호 없음※ | 패널 **8** |

※ **C, F 가 0인 이유 (가능성)**  
- **C**: 짧은 구간·낮은 동시성에서는 IP/유저 rate limit 한도에 걸리지 않아 `rateLimitRejectedTotal` 이 0일 수 있음. `/seats` 위주라 `queueEntered` 도 거의 안 올라감. Grafana 패널 **3** 은 429·HTTP·p99 를 함께 두었으므로, 부하를 키우면 같은 지표에서 확인 가능.  
- **F**: nGrinder 컨트롤러에 스크립트가 없거나 테스트가 즉시 실패하면(이전 관측에서 `scriptRevision: -1`) 백엔드로 트래픽이 안 들어올 수 있음. `scripts` 업로드 후 재실행 권장.

※ **Δ HTTP 가 0으로 보이는 경우 (A, B)**  
저부하·짧은 창에서는 `http.server.requests` 카운터 증분이 스냅샷 두 번 사이에 잡히지 않을 수 있음. Grafana에서는 `rate(http_server_requests_seconds_count[...])` 로 구간 평균을 보는 것이 안전.

## 7. Grafana / 대시보드 반영 체크리스트

| 항목 | 상태 |
|------|------|
| Ops 시나리오별 3 KPI (`scenarioExtraKpis`) | 코드 기준 A~F 정의됨 |
| Grafana 시나리오별 전용 패널 (1,2,3,4,7,8) + 공통(5,6) | `ticketing-scenarios.json` 에 존재 |
| Prom 메트릭 이름이 `BusinessMetrics` / Spring HTTP 타이머와 일치 | `actuator/prometheus` 로 검증 가능 |
| Prometheus `job=ticketing` | `docker/prometheus.yml` 에 정의 |
| nGrinder 시나리오 시작 API | `POST /api/dashboard/ngrinder/scenarios/start` |

## 8. 권장 확인 순서 (운영자)

1. Ops에서 시나리오 선택 → **해당 3지표** 확인.  
2. Grafana **티켓팅 시나리오 (A~F) 분석** 대시보드에서 **같은 시나리오 행**의 rate·gauge 확인.  
3. 이 문서 **섹션 6 표**처럼 `business-metrics` 를 전후 2회 호출해 증분을 기록(또는 `scripts/run-scenarios-metrics.ps1` 실행).  
4. C·F 가 약하면 **vusers·testDurationSec** 조정 및 nGrinder 스크립트 업로드 여부 확인.
