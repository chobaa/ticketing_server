# Grafana · Prometheus

## Prometheus 스크랩

설정: `docker/prometheus.yml`

| job | 타겟 | 수집 내용 |
|-----|------|-----------|
| `ticketing` | `backend:8080/actuator/prometheus` | HTTP SLI, 비즈니스 카운터, `ticketing_*_up`, 큐 깊이 |
| `redis_exporter` | `redis-exporter-1`~`6:9121` | 메모리·eviction |
| `mysqld_exporter` | `mysqld-exporter:9104` | MySQL connections |
| `kafka_exporter` | `kafka-exporter:9308` | consumer lag |
| `rabbitmq` | `rabbitmq:15692/metrics` | (선택) 네이티브 큐 메트릭 — 대시보드는 앱 `ticketing_payment_queue_depth` 사용 |

알람 규칙: `docker/alert.rules.yml` (`job="ticketing"` 전제)

## Grafana 대시보드 4종

프로비저닝: `docker/grafana/provisioning`, JSON: `docker/grafana/dashboards/`

| 대시보드 | UID 경로 | 패널 (유의미한 것만) |
|----------|----------|----------------------|
| **SLO** | `/grafana/d/ticketing-slo/ticketing-slo` | HTTP TPS, p95/p99, 5xx·429·409·404/s |
| **Bottleneck** | `/grafana/d/ticketing-bottlenecks/ticketing-bottlenecks` | Kafka/Redis up, 결제·대기열 깊이, Kafka lag, 결제 파이프라인 rate, MySQL, Redis memory, HikariCP |
| Scenarios | `/grafana/d/ticketing-scenarios/ticketing-scenarios` | A~F 비즈 카운터 **rate** |
| **Funnel** | `/grafana/d/ticketing-funnel/ticketing-funnel` | 퍼널 Stat(Δ), **runId** 변수 |

### 제거한 패널 (No data 원인)

| 제거 | 이유 |
|------|------|
| SLO · Tomcat threads | 사용자 SLI와 거리가 멀고, 환경에 따라 시계열이 비어 있음 |
| SLO · HikariCP | Bottleneck으로 이동 (인프라 병목용) |
| Bottleneck · `rabbitmq_queue_messages` | Rabbit prometheus 플러그인 미활성 시 항상 No data → 앱 `ticketing_payment_queue_depth`로 대체 |
| Bottleneck · Redis eviction rate | 대부분 0으로 정보량 낮음 (memory 패널로 충분) |

**참고:** SLO의 TPS·지연은 **HTTP 트래픽이 있을 때만** 곡선이 보입니다. 부하 테스트 전에는 No data가 정상입니다.

**Error rate 패널:** 예전에는 5xx·429만 조회해, F처럼 **409(락 경쟁)·404(입장 폴링)** 만 나는 테스트에서는 시계열이 없어 Grafana가 **No data**로 보였습니다. 5xx·429가 실제 0인 것(서버 정상)과 구분하려면 `or vector(0)` 및 409/404·`ticketing_ratelimit_rejected_total`을 함께 봅니다.

### Scenarios 패널 ↔ 시나리오

| 패널 | 시나리오 | 주요 PromQL |
|------|----------|-------------|
| 1 | A | `ticketing_queue_entered_total`, `admission_issued`, `seat_lock_failed` |
| 2 | B | 위 3종 rate + lock acquire p99 |
| 3 | C | `ratelimit_rejected`, `http_server_requests_seconds_*` |
| 4 | D | `reservation_expired`, `payment_requested`, inflight |
| 5 | 공통 | integrity mismatch 합 |
| 6 | E,F | `ticketing_payment_*` |
| 7 | E | integrity, queue entered |
| 8 | F | lock failed, HTTP, 429 |

### Funnel + runId

- Stat(Δ, 5m): JoinQueue → Admission → Reserve attempted → Reserve succeeded  
- Timeseries: 예약 실패 사유 rate, Payment outcomes rate  
- 상단 변수 **`runId`**: Ops에서 복사한 값 입력 → 해당 run 구간만 필터  

스크린샷 예시: [assets/screenshots/grafana-funnel-runid.png](../assets/screenshots/grafana-funnel-runid.png)

## Ops vs Grafana

| | Ops (`/ops`) | Grafana |
|---|--------------|---------|
| 단위 | **단일 run** | 기간·추세 |
| 강점 | 즉시 KPI, 히트맵, nGrinder 실행 | SLI, 병목, rate, runId 퍼널 |
| 데이터 | REST 폴링 + run-metrics | Prometheus TSDB |

## runId 드릴다운 (로그)

1. Ops/nGrinder 실행 → `loadTestRunId` 발급  
2. Groovy가 `X-LoadTest-RunId` 헤더 전송  
3. `RequestDebugContextFilter` → MDC `runId`, `reqId`  
4. 로그에서 `runId=...` 필터  

REST: `GET /api/dashboard/run-metrics?runId={uuid}`

## TSDB 보존

기본 compose는 Prometheus **볼륨 없음** → 컨테이너 재생성 시 시계열 초기화. 장기 보관 시 볼륨 마운트 검토.
