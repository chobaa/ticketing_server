# 고성능 티켓팅 플랫폼 (Phase 1)

Spring Boot 3 · Java 21(가상 스레드) · Redis(Redisson, 대기열 ZSET) · MySQL · Kafka · RabbitMQ · React(Vite, Tailwind, Liquid Glass, Recharts) · Prometheus · Docker Compose

## 빠른 시작 (Docker)

```bash
docker compose up --build
```

- 프론트: [http://localhost](http://localhost) (nginx → API·WebSocket 프록시)
- API 직접: [http://localhost:8080](http://localhost:8080)
- Prometheus: [http://localhost:9090](http://localhost:9090)
- RabbitMQ 관리: [http://localhost:15672](http://localhost:15672) (guest/guest)

첫 실행 시 시드 데이터로 공연 1건·좌석 100석이 생성됩니다. 회원가입 후 로그인하거나, 데모 계정으로 등록해 사용할 수 있습니다.

## 로컬 개발 (IDE)

1. MySQL·Redis·Kafka·RabbitMQ를 띄우거나 `docker compose up mysql redis zookeeper kafka rabbitmq` 만 실행  
2. 백엔드: `cd backend && mvn spring-boot:run` (JDK 21, Maven 필요)  
3. 프론트: `cd frontend && npm install && npm run dev` — Vite 프록시가 `/api`, `/ws`를 `localhost:8080`으로 넘깁니다.

## 아키텍처 요약

- **대기열**: Redis `ZSET`, 스케줄러가 배치 입장 후 입장 토큰(Redis TTL) 발급  
- **예매**: Redisson 좌석 락 + JPA 비관적 락으로 좌석 상태 갱신 → `ticket-reserved` Kafka 발행  
- **비동기**: Kafka Consumer가 `payment.queue` / `notification.queue`(RabbitMQ)로 전달 → 스텁 리스너  
- **관측**: Micrometer + Prometheus, WebSocket으로 초당 스냅샷(TPS·p99·대기열 깊이) 푸시  

자세한 기획은 [기획서.md](./기획서.md)를 참고하세요.

## nGrinder

부하 테스트·그래프·로그 시각화 절차는 [load-tests/ngrinder/README.md](./load-tests/ngrinder/README.md)를 참고하세요.
