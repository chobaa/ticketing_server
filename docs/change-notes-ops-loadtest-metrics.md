# Ops / 부하테스트 / 비즈니스 메트릭 변경 요약

이 문서는 운영 대시보드(`/ops`), nGrinder 시나리오(A~E), `/api/dashboard/business-metrics` 및 관련 백엔드 동작에 대한 **주요 변경 사항**과 **운영 시 유의점**을 정리한다.

---

## 1. `/ops` 운영 대시보드

### 1.1 이벤트 vs 클러스터 지표

- **선택 이벤트**: 추정 활성 사용자, 대기열, 결제 대기·처리중, 좌석 점유율 + 점유 진행 바(HELD/SOLD 등).
- **전체 클러스터**: 동일 축을 OPEN 이벤트 합산 기준으로 표시.
- 카드 UI: 큰 숫자(`tabular-nums`), 섹션 구분, 강조 테두리 등 시각적 위계 개선.

### 1.2 nGrinder 패널

- 시나리오 **A~E** 선택 실행, `vusers`/`threads`/기간 등 파라미터 유지.
- **스레드 정렬**: nGrinder에서 `threads < vusers`이면 실제 실행 볼륨이 크게 줄어들 수 있어, 백엔드에서 `threads = max(threads, vusers)`로 보정하고 응답에 `ngrinderVusers`, `ngrinderThreads`를 포함한다.
- 프론트에서는 A~D에서 `vusers` 변경 시 `threads`를 `max(threads, vusers)`로 맞춘다.

### 1.3 부하 테스트 “공통 + 시나리오” 지표

- **공통 3개 (결제 파이프라인)**  
  - 결제 성공(누적), 결제 실패(누적), 결제 처리 중(DB `PROCESSING` 건수).
- **시나리오별 3개**  
  - **A**: 대기열 진입, 입장 토큰 발급, 좌석 락 실패(썬더링 허드·첫 가용석 경쟁).  
  - **B**: 좌석 락 실패, 대기열 진입, 입장 토큰(핫키).  
  - **C**: Rate limit 거절, HTTP 요청 처리 누적(`httpServerRequestTotal`), p99 근사(`/api/dashboard/realtime`).  
    - C는 **GET `/seats` 폴링** 중심이라 공통 결제 카운터가 거의 안 움직일 수 있음(안내 문구).  
  - **D**: 예약 TTL 만료, 결제 요청(누적), 이벤트 선택 시 결제 대기 예약 / 미선택 시 결제 드롭.  
  - **E**: 이벤트 선택 시 판매 좌석·대기열·점유율; 미선택 시 HTTP 누적·대기열·입장.

### 1.4 결제 요청 vs 성공/실패 (UI 설명)

- `/ops`에 **의미 차이**와 `paymentRequestedMismatch`, WIP, 드롭/중복을 요약한 안내 블록을 추가했다.
- 백엔드 정의:  
  `requested ≈ (succeeded + failed) + processing + queueDepth + dropped + duplicate`  
  (`settleAlreadyTerminal`는 동일 논리 결제의 중복 Kafka 정산 등이라 위 식에 넣지 않음.)

---

## 2. `/api/dashboard/business-metrics`

### 2.1 추가 필드

| 필드 | 설명 |
|------|------|
| `queueEnteredTotal` | `ticketing.queue.entered.total` |
| `admissionIssuedTotal` | `ticketing.queue.admission.issued.total` |
| `seatLockFailedTotal` | `ticketing.reservation.seat_lock.failed.total` |
| `reservationExpiredTotal` | `ticketing.reservation.expired.total` |
| `rateLimitRejectedTotal` | `ticketing.ratelimit.rejected.total` (scope 태그별 **합산**) |
| `httpServerRequestTotal` | `http.server.requests` Timer **누적 count** (시나리오 C 등 읽기 부하) |

기존 결제·큐·mismatch·WIP 필드는 그대로 두며, 프론트는 2초 간격으로 `business-metrics`와 `realtime`을 함께 폴링한다.

### 2.2 Counter 읽기 방식 (성공 수가 “줄어들어” 보이던 완화)

- `MeterRegistry.find(name).counter()`는 **동일 이름의 여러 Counter 시리즈 중 하나만** 반환할 수 있다.
- `DashboardRealtimeController` 및 `NgrinderPaymentCountRunner`의 카운터 조회는 **`find(name).counters()` 전부 합산**으로 통일했다.
- 그럼에도 **누적이 줄어들어 보이는** 흔한 원인:
  - **프로세스 재시작/재배포** → Micrometer 카운터 0부터 재시작.
  - **로드밸런서 뒤 여러 인스턴스** → 요청마다 다른 JVM의 더 작은 누적을 볼 수 있음.
  - **Grafana `rate()` / `increase()`** → 카운터 리셋 구간에서 음수 스파이크.

### 2.3 Redis 클러스터 합산 카운터 (옵션)

`ticketing.metrics.cluster-counters.enabled` (`TICKETING_METRICS_CLUSTER_COUNTERS_ENABLED`) 가 **true**이면:

- `BusinessMetrics`의 증가 지점마다 동일 이벤트를 **Redis** `INCRBY` 로 미러링한다 (키 접두사 `ticketing:metrics:cluster:v1:`).
- `/api/dashboard/business-metrics`는 위에 대응하는 지표를 **Redis 합계**로 읽고, 응답에 `clusterCountersEnabled: true` 를 넣는다.
- `NgrinderPaymentCountRunner`의 요청/정산 기반 중지 로직도 동일 소스를 사용한다.

**미러링 대상:** 결제 요청·성공·실패·드롭·중복 스킵·terminal 스킵·워커 슬립 ms, 대기열 진입·입장 발급·좌석 락 실패·예약 만료·rate limit 거절(전 scope 합산용 단일 Redis 키).

**아직 로컬/인스턴스 한정:** `httpServerRequestTotal`, Rabbit `paymentQueueDepth` 게이지 등은 Redis 합산에 포함하지 않는다.

**주의:**

- 기능을 **처음 켠 직후** Redis 키는 0부터이므로, Micrometer에만 쌓여 있던 과거 누적과 숫자가 어긋날 수 있다(운영에서 켤 타이밍을 정하거나 키 네임스페이스를 비운 뒤 맞춘다).
- `false`(기본)면 기존처럼 **로컬 Micrometer 합산**만 사용한다.

---

## 3. 멀티 인스턴스 관측 — Prometheus와 병행

Redis 합산은 **Ops / 단일 HTTP API** 관점에서 빠르게 전역 합을 보기 위한 것이다. 장기·알람·SLI는 여전히 **Prometheus에서 `sum by (instance, …)`** 로 집계하는 것을 권장한다.

---

## 4. 관련 코드 위치 (참고)

| 영역 | 경로 |
|------|------|
| 비즈니스 메트릭 API | `backend/.../DashboardRealtimeController.java` |
| Redis 미러/조회 | `backend/.../metrics/ClusterBusinessMetricsBridge.java` |
| 증가 훅 | `backend/.../metrics/BusinessMetrics.java` |
| nGrinder 카운트 기반 중지 | `backend/.../ngrinder/NgrinderPaymentCountRunner.java` |
| nGrinder 시나리오 시작 | `backend/.../NgrinderDashboardController.java` |
| 설정 | `backend/src/main/resources/application.yml` (`ticketing.metrics.cluster-counters`) |
| Ops UI | `frontend/src/components/OpsDashboard.tsx` |
| API 타입 | `frontend/src/api.ts` |
| 부하 스크립트 | `load-tests/ngrinder/scripts/10_*.groovy` ~ `15_*.groovy` |

### 시나리오 F (통합 랜덤)

- 스크립트: `15_scenario_f_integrated_random_mixed.groovy` — 벽시계 동안 **0~4 랜덤**으로 A·B·C·D·E 스타일 행동을 반복한다.
- 스레드마다 고정 사용자(`mix_…`) + 확률적으로 **일회성 사용자**(`ephem_…`)가 등록 후 `/seats`만 읽어 가입/조회 부하를 섞는다.
- 백엔드 `scenario=F`: 기본 **2000 VUser**·좌석 수 맞춤·`threshold=D`·`testDurationSec` 최소 30·최대 3600초.
- nGrinder에 스크립트 업로드 필요: `load-tests/ngrinder/upload-scripts.ps1`
| 메트릭 계약(개요) | `docs/metrics-contract.md` |

---

## 5. 배포 시

코드·프론트 변경 후 일반적으로:

```bash
docker compose up -d --build backend frontend
```

nGrinder 스크립트를 수정한 경우에는 기존 절차대로 컨트롤러에 스크립트 업로드가 필요하다.
