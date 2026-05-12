# Metrics Contract (Ticketing Observability)

이 문서는 **Prometheus/Grafana를 유지**하면서도, 부하 테스트 시나리오(A~D)와 운영 웹 대시보드(신호등/히트맵)에 필요한 지표를 “이름/의미/단위/활용처(패널/알람)”로 고정합니다.

## 공통 원칙
- **카디널리티 제한**: `eventId`, `userId`, `seatId`를 라벨로 쓰지 않는다.
- **운영 대시보드용 지표**는 “정확한 회계”가 아니라, **즉시 판단 가능한 신호**(트렌드/이상 징후)에 초점을 둔다.
- **시나리오 C(조회 폭격)**의 429는 “실패”가 아니라 **정상 방어 신호**로 별도 집계한다.

## 1) Application (Spring Boot / Micrometer)

### HTTP / 사용자 체감
- **`http_server_requests_seconds_*`** (Micrometer 기본)
  - **용도**: TPS, p95/p99 latency, 5xx/4xx 분해
  - **패널**: TPS, p95/p99, 에러율(5xx/429)
  - **알람**: p99 상승, 5xx 비율 상승

### DB / Hikari
- **`hikaricp_connections_active`**, **`hikaricp_connections_pending`**
  - **용도**: 커넥션 고갈, DB 병목 탐지
  - **알람**: pending > 0 지속

### Tomcat Thread
- **`tomcat_threads_current`**, **`tomcat_threads_config_max`**
  - **용도**: 스레드 고갈/큐잉
  - **알람**: current ≈ max 지속

### JVM
- **GC pause / heap** (Micrometer/JVM 기본)
  - **용도**: stop-the-world, 메모리 압박
  - **알람**: GC pause 급증

### Queue / Admission (대기열)
- **`ticketing_queue_entered_total`**
  - **의미**: `joinQueue` 호출 누적
  - **시나리오 A**: 오픈런 스파이크 입력량 확인
- **`ticketing_queue_admission_issued_total`**
  - **의미**: 스케줄러가 admission token 발급한 누적
  - **운영 대시보드**: “대기열 통과 속도”의 근거(초당/분당 rate)

### Reservation / Lock (Hot Key)
- **`ticketing_reservation_seat_lock_failed_total`**
  - **의미**: 좌석 lock 획득 실패(경합) 누적
  - **시나리오 B**: 핫키 경합 강도, fail-fast 여부 확인
- **`ticketing_reservation_seat_lock_acquire_seconds_*`** (Timer)
  - **의미**: Redisson tryLock 획득 시간 분포
- **`ticketing_reservation_reserve_seconds_*`** (Timer)
  - **의미**: reserve(좌석 HELD + 예약 생성 + 이벤트 발행) 처리 시간 분포

### Payment Pipeline (Kafka → Rabbit → Worker → DB)
- **`ticketing_payment_requested_total`**
- **`ticketing_payment_succeeded_total`**
- **`ticketing_payment_failed_total`**
- **`ticketing_payment_inflight`** (Gauge)
- **`ticketing_payment_worker_sleeping`** (Gauge)

### Rate Limiting (Retry Storm)
- **`ticketing_ratelimit_rejected_total{scope=\"ip|user\"}`**
  - **의미**: 429 차단 누적(스코프별)
  - **시나리오 C**: 방어 동작 확인 (알람은 “급증”만, 실패로 취급 X)

### Data Integrity (정합성)
- **`ticketing_integrity_mismatch_*`**
  - **의미**: 좌석/예약/결제 상태 불일치 감지(0이 정상인 항목)
  - **알람**: mismatch 증가 또는 0이 아닌 상태 지속

## 2) Middleware / Exporters

### Redis (redis_exporter)
- CPU/메모리/evicted_keys/connected_clients/commandstats
  - **시나리오 C**: CPU 포화 + eviction
  - **시나리오 A/B**: ops 증가 대비 latency/eviction 여부

### MySQL (mysqld_exporter)
- InnoDB row lock/slow/connection/thread
  - **시나리오 A/B/D**: lock wait, thread/connection 포화

### Kafka (kafka_exporter)
- consumer lag
  - **시나리오 A/D**: lag 폭증 후 회복 여부(유실 없는 큐잉)

## 3) 운영 웹 대시보드(신호등/히트맵) 데이터 계약(요약)
- **Traffic**: activeUsers, queueDepth, admissionRatePerMin
- **Business**: paymentSuccessTps, paymentFailureRate, seatsRemainingRatio
- **Heatmap**: seatId → status(AVAILABLE|PENDING|SOLD)

