# 메트릭 계약 (Metrics Contract)

Prometheus/Grafana와 Ops 대시보드가 **동일한 이름·의미**를 공유하도록 고정한 계약서입니다.

## 공통 원칙

| 원칙 | 설명 |
|------|------|
| 카디널리티 | `eventId`, `userId`, `seatId`를 **라벨로 쓰지 않음** |
| Ops 지표 | 정확한 회계보다 **즉시 판단 가능한 신호**(트렌드·이상) |
| 시나리오 C | `429`는 실패가 아니라 **정상 방어 신호** |
| runId | 로그/REST 드릴다운용. **Prometheus 라벨 금지** |

## 1. 애플리케이션 (Micrometer)

### HTTP / 사용자 체감

| 메트릭 | 용도 | 알람 예 |
|--------|------|---------|
| `http_server_requests_seconds_*` | TPS, p95/p99, 4xx/5xx | p99 상승, 5xx 비율 |

### DB / Tomcat / JVM

| 메트릭 | 용도 |
|--------|------|
| `hikaricp_connections_active`, `hikaricp_connections_pending` | 커넥션 고갈 |
| `tomcat_threads_current`, `tomcat_threads_config_max` | 스레드 고갈 |
| GC pause, heap | 메모리·STW |

### 대기열

| 메트릭 | 의미 | 시나리오 |
|--------|------|----------|
| `ticketing_queue_entered_total` | `joinQueue` 누적 | A, B, E, F |
| `ticketing_queue_admission_issued_total` | 입장 토큰 발급 누적 | A, B, E, F |

### 예약 / 락

| 메트릭 | 의미 | 시나리오 |
|--------|------|----------|
| `ticketing_reservation_seat_lock_failed_total` | Redisson 락 실패 | A, B, F |
| `ticketing_reservation_seat_lock_acquire_seconds_*` | 락 획득 시간 | B |
| `ticketing_reservation_reserve_seconds_*` | reserve 전체 시간 | A, E |

### 결제 파이프라인

| 메트릭 | 의미 |
|--------|------|
| `ticketing_payment_requested_total` | Kafka→Rabbit 적재 시점 |
| `ticketing_payment_succeeded_total` | 정산 성공 |
| `ticketing_payment_failed_total` | 정산 실패 |
| `ticketing_payment_inflight` | 처리 중 (Gauge) |
| `ticketing_payment_worker_sleeping` | 워커 슬립 (Gauge) |

### Rate limit

| 메트릭 | 의미 | 시나리오 |
|--------|------|----------|
| `ticketing_ratelimit_rejected_total{scope="ip\|user"}` | 429 누적 | C, F |

### 정합성

| 메트릭 | 의미 |
|--------|------|
| `ticketing_integrity_mismatch_*` | 좌석·예약·결제 불일치 (**0이 정상**) |

## 2. 앱 의존성·큐 (Grafana Bottleneck)

| 메트릭 | 의미 |
|--------|------|
| `ticketing_kafka_up` | Kafka AdminClient ping (1/0) |
| `ticketing_redis_up` | Redis PING (1/0) |
| `ticketing_payment_queue_depth` | Rabbit `payment.queue` 메시지 수 |
| `ticketing_waiting_queue_size` | OPEN 이벤트 Redis 대기열 합 |

## 3. 미들웨어 / Exporter

| 소스 | 관측 포인트 | Grafana |
|------|-------------|---------|
| Redis exporter | `redis_memory_used_bytes` | Bottleneck |
| MySQL exporter | `mysql_global_status_threads_connected` | Bottleneck |
| Kafka exporter | `kafka_consumergroup_lag` | Bottleneck |

## 4. Ops REST / WebSocket 계약 (요약)

| 축 | 필드 예 |
|----|---------|
| Traffic | `activeUsers`, `queueDepth`, `admissionRatePerMin` |
| Business | `paymentSuccessTps`, `paymentFailureRate`, `seatsRemainingRatio` |
| Heatmap | `seatId` → `AVAILABLE` \| `PENDING` \| `SOLD` |

상세 API: [operations/ops-dashboard.md](../operations/ops-dashboard.md)

## 관련 문서

- [grafana-and-prometheus.md](./grafana-and-prometheus.md)
- [operations/load-test-runbook.md](../operations/load-test-runbook.md)
