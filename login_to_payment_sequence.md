# Login to Payment Sequence

아래는 현재 코드 기준 `로그인 -> 대기열 -> 좌석 예약 -> 비동기 결제 -> 최종 정산`을 텍스트로 정리한 흐름입니다.

## 1) 로그인

1. 사용자가 `POST /api/auth/login`으로 이메일/비밀번호를 보냅니다.
2. 서버(AuthService)는 사용자 조회 후 비밀번호를 검증합니다.
3. 성공하면 JWT(accessToken)를 발급해 응답하고, 프론트는 토큰을 저장합니다.

실패 분기:
- 이메일이 없거나 비밀번호 불일치: `400 Invalid credentials`
- 토큰 없이 보호 API 호출: 인증 실패(접근 불가)

## 2) 대기열 진입 및 입장 토큰 발급

1. 사용자가 `POST /api/events/{eventId}/queue`를 호출하면 Redis ZSET 대기열에 등록됩니다.
2. 사용자는 `GET /queue/me`로 순번을 확인합니다.
3. 스케줄러가 주기적으로 앞 순번 사용자에게 입장 토큰(TTL)을 발급합니다.
4. 사용자는 `GET /admission`을 폴링하다 토큰을 받으면 좌석 선택 단계로 넘어갑니다.

실패 분기:
- 아직 입장 차례가 아니면 `GET /admission`은 `404`를 반환합니다.
- 토큰이 만료된 상태로 예약하면 `409 Invalid or missing admission token`이 발생하며 대기열 재진입이 필요합니다.

## 3) 좌석 예약

1. 사용자가 `POST /api/events/{eventId}/reservations`로 `seatId + admissionToken`을 전송합니다.
2. 서버는 입장 토큰을 검증하고, Redis 좌석 락을 획득 시도합니다.
3. 락 획득 후 DB에서 좌석 행을 잠그고 상태를 확인합니다.
4. 좌석이 유효하고 `AVAILABLE`이면 좌석을 `HELD`로 변경합니다.
5. 예약을 `PENDING_PAYMENT`로 생성합니다.
6. 트랜잭션 커밋 이후 `ticket-reserved` 이벤트를 Kafka에 발행합니다.

실패 분기:
- 좌석 락 획득 실패: `409 Could not acquire seat lock`
- 좌석 없음/이벤트 불일치: `400`
- 좌석이 이미 점유됨: `409 Seat not available`

## 4) 비동기 결제 파이프라인

1. `ticket-reserved` 이벤트를 소비한 결제 컨슈머가 `payment-requested`를 발행합니다.
2. 브리지 컨슈머가 `payment-requested`를 RabbitMQ `payment.queue`에 적재합니다.
3. Payment Worker가 큐를 소비해 payment를 `PROCESSING`으로 만들고 지연/성공률 기반 시뮬레이션을 수행합니다.
4. 결과에 따라 `payment-succeeded` 또는 `payment-failed` 이벤트를 Kafka에 발행합니다.

## 5) 최종 정산

성공 경로:
1. `payment-succeeded` 수신
2. 정산 서비스가 예약/좌석 상태를 검증
3. 예약 `PENDING_PAYMENT -> CONFIRMED`
4. 좌석 `HELD -> SOLD`
5. 프론트 progress 폴링에서 최종 성공 표시

실패 경로:
1. `payment-failed` 수신
2. 정산 서비스가 예약/좌석 상태를 검증
3. 예약 `PENDING_PAYMENT -> CANCELED`
4. 좌석 `HELD -> AVAILABLE` (롤백)
5. 프론트 progress 폴링에서 실패 사유와 함께 최종 실패 표시

## 6) 추가 실패/예외 연결

- 사용자가 결제 중 `cancel` 호출 시: 실패 정산 경로로 처리되어 `CANCELED + AVAILABLE`로 복구됩니다.
- 결제 결과 이벤트가 늦게/중복 도착한 경우: 이미 terminal 상태면 정산을 건너뛰어 상태 오염을 막습니다.
- 워커 처리 시점에 예약이 이미 terminal 상태면: payment를 실패로 마감하고 추가 정산 이벤트를 내지 않습니다.

## Notes

- 성공 최종 상태: `reservation=CONFIRMED`, `seat=SOLD`
- 실패 최종 상태: `reservation=CANCELED`, `seat=AVAILABLE`
- 프론트는 `GET /api/events/{eventId}/reservations/{reservationId}/progress` 폴링으로 결제 결과를 확정 표시합니다.
