# 부하 테스트 런북 (시나리오 A~F)

nGrinder 시나리오 **A~F**와 Ops·Grafana 지표의 대응, 실행·검증 절차입니다.

## 실행 경로

| 방법 | 설명 |
|------|------|
| Ops UI | `/ops` → 시나리오 선택 → 실행 |
| API | `POST /api/dashboard/ngrinder/scenarios/start?scenario=A&...` |
| 스크립트 | `load-tests/ngrinder/scripts/10_` ~ `15_*.groovy` |

스크립트 업로드: `.\load-tests\ngrinder\upload-scripts.ps1`  
상세: [load-tests/ngrinder/README.md](../../load-tests/ngrinder/README.md)

## runId 격리

- 백엔드가 run마다 UUID 발급 → Groovy `param` → `X-LoadTest-RunId`  
- Ops 화면에 앞 8자리 표시  
- Grafana Funnel `runId` 변수와 동일 값 사용  

## 시나리오 요약

| ID | 스크립트 | 부하 패턴 | 검증 목표 |
|:--:|----------|-----------|-----------|
| A | `10_scenario_a_thundering_herd` | 동시 `joinQueue` → 첫 좌석 예약 | 오픈런 스파이크·락 경합 |
| B | `11_scenario_b_hot_key_lock` | **1석** 동시 `reserve` | 정확히 1건 성공 |
| C | `12_scenario_c_retry_storm` | `GET /seats` 반복 | 429 방어 (`runId`당 IP 10/초·유저 5/초, Docker 전역 한도와 별개) |
| D | `13_scenario_d_zombie_ttl` | 예약 후 hold **60s**·결제 스킵·`sleepMs` 대기 | TTL 만료·재가용·runId 만료 건수 도달 시 nGrinder 자동 중지 |
| E | `14_scenario_e_baseline_ticketing` | 정상 퍼널 × crowd | 기준선 |
| F | `15_scenario_f_integrated_random_mixed` | A~E 랜덤 혼합 | 종합 내구도 |

## Ops KPI (`scenarioExtraKpis`)

구현: `frontend/src/components/OpsDashboard.tsx`

| 시나리오 | Ops KPI |
|:--:|---------|
| A | 대기열 진입 · 입장 토큰 · 좌석 락 실패 |
| B | 락 실패 · 대기열 · 입장 |
| C | Rate limit 거절 · HTTP( runId ) · p99 |
| D | TTL 만료 · 결제 요청 · 대기 예약 또는 드롭 |
| E | 판매 좌석 · 대기열 깊이 · 점유율 (이벤트 선택 시) |
| F | 대기열 · 입장 · 예약 시도/성공 · 락 실패 · 예약 실패 · HTTP |

공통: 결제 성공/실패/처리중.

## 좌석·파라미터 (백엔드)

`NgrinderDashboardController.startScenario` 기준:

| 시나리오 | 좌석 수 | 비고 |
|----------|---------|------|
| A | `max(32, min(200, vusers+24))` | 미지정 시 |
| B | **1** (핫키) | UI `eventSeatCount` 무시 |
| C | `max(24, min(128, vusers*4))` | |
| D | `max(vusers, …)` | 스레드별 다른 좌석 |
| E | 기본 48, vusers = 좌석×`crowdMultiplier` | |
| F | 대규모 기본, `testDurationSec` min 30 | |

## 증분(Δ) 측정

```powershell
powershell -NoProfile -File scripts/run-scenarios-metrics.ps1
```

또는 전후 `GET /api/dashboard/business-metrics` 스냅샷 차이.  
짧은 구간·저부하는 HTTP Δ가 0일 수 있음 → Grafana `rate()` 권장.

## 권장 확인 순서

1. Ops에서 시나리오 실행 → **해당 KPI** 확인  
2. Grafana **Scenarios**에서 동일 시나리오 패널 rate 확인  
3. 필요 시 Funnel에 **runId** 입력  
4. C/F 신호가 약하면 `vusers`·`testDurationSec` 상향 및 스크립트 업로드 확인  

## 참고: 로컬 Δ 샘플 (2026-05-10)

동일 JVM에서 A→F 순차 실행 후 스냅샷 Δ. C/F는 부하·스크립트 상태에 따라 0일 수 있음.  
전체 표는 [changelog/review-notes.md](../changelog/review-notes.md) 및 이전 runbook 아카이브를 참고.

## 관련 문서

- [ops-dashboard.md](./ops-dashboard.md)
- [observability/metrics-contract.md](../observability/metrics-contract.md)
- [troubleshooting.md](./troubleshooting.md)
