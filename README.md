# 고성능 티켓팅 플랫폼

대기열·예매·비동기 결제 파이프라인을 포함한 티켓팅 백엔드와 대시보드, 부하·관측 스택을 한 저장소에서 돌릴 수 있는 프로젝트입니다.

## 기술 스택

| 영역 | 구성 |
|------|------|
| API | Spring Boot 3.3, Java 21, 가상 스레드 |
| 데이터 | MySQL 8.4, Flyway, JPA |
| 캐시·대기열 | Redis 7 Cluster(6노드), Redisson, ZSET 기반 입장 |
| 메시징 | Apache Kafka (Zookeeper), RabbitMQ 3 (관리 UI + Prometheus 플러그인 `15692`) |
| 프론트 | React, Vite, Tailwind, nginx(정적 + API/WebSocket/Grafana 역프록시) |
| 관측 | Micrometer, Spring Actuator `/actuator/prometheus`, Prometheus, Grafana(프로비저닝 대시보드) |
| 부하 | nGrinder Controller + Agent (Docker), Groovy 스크립트는 `load-tests/ngrinder/scripts` |

## 사전 요구 사항

- Docker Engine + Docker Compose v2  
- (로컬 백엔드만 실행 시) JDK 21, Maven 3.9+, Node.js 22+ 권장(Docker 프론트 빌드와 동일 계열)

## 빠른 시작 (전체 스택)

```bash
docker compose up -d --build
```

백엔드가 MySQL 헬스체크 이후 기동합니다. Redis 클러스터는 `redis-cluster-init`이 한 번 실행되어 토폴로지를 만듭니다.

### 엔드포인트 (호스트 기준)

| 설명 | URL | 비고 |
|------|-----|------|
| 웹 앱 | [http://localhost](http://localhost) | nginx → `/api`, `/ws`, `/actuator` 프록시 |
| API·Actuator 직접 | [http://localhost:8080](http://localhost:8080) | 컨테이너 백엔드 포트 노출 |
| Prometheus | [http://localhost:9090](http://localhost:9090) | TSDB는 compose에 볼륨 없음 → 컨테이너 재생성 시 시계열 초기화 |
| Grafana | [http://localhost/grafana](http://localhost/grafana) | `admin` / `admin`, 서브패스 `/grafana` |
| Grafana 직접 | [http://localhost:3000](http://localhost:3000) | 동일 인스턴스, 루트 URL로 접속 |
| RabbitMQ 관리 | [http://localhost:15672](http://localhost:15672) | `guest` / `guest` |
| RabbitMQ Prometheus | (내부) `rabbitmq:15692` | 호스트는 `15692` 포트 매핑, Prometheus가 스크랩 |
| nGrinder UI | [http://localhost:19080](http://localhost:19080) | `admin` / `admin`, Windows 포트 충돌 회피용 리매핑 |
| MySQL | `localhost:3306` | DB `ticketing`, 사용자 `ticketing` / `ticketing` |
| Kafka | `localhost:9092` | 단일 브로커(ZK 기반) |

### 초기 데이터

- DB가 비어 있으면 `SeedDataRunner`가 **공연 1건(`Summer Live 2025`) + 좌석 100석(A1~A100)** 을 생성합니다.  
- 사용자 계정은 시드하지 않습니다. UI에서 회원가입하거나, 부하 스크립트·관리 API에서 사용하는 계정을 따로 준비합니다.

### 데이터·관측 초기화

이름 붙은 볼륨을 지우면 DB·Grafana·nGrinder 컨트롤러 저장소가 함께 초기화됩니다.

```bash
docker compose down -v
docker compose up -d --build
```

- **nGrinder**: 컨트롤러 볼륨이 비면 스크립트 저장소가 비어 있습니다. 아래를 다시 실행하세요.  
  `.\load-tests\ngrinder\upload-scripts.ps1` (기본 컨트롤러 URL: `http://localhost:19080`)  
  자세한 절차: [load-tests/ngrinder/README.md](./load-tests/ngrinder/README.md)

## 로컬 개발 (IDE에서 백엔드·프론트만)

1. 인프라만 Compose로 띄우기 (예시):  
   `docker compose up -d mysql redis-node-1 redis-node-2 redis-node-3 redis-node-4 redis-node-5 redis-node-6 redis-cluster-init zookeeper kafka rabbitmq`  
   백엔드를 호스트에서 돌릴 때는 Redis 클러스터 노드 주소를 환경 변수로 맞추거나, 단일 Redis를 쓰는 프로파일로 전환합니다.
2. 백엔드: `cd backend && mvn spring-boot:run`  
3. 프론트: `cd frontend && npm install && npm run dev` — Vite가 `/api`, `/ws`를 `localhost:8080`으로 프록시합니다.

### Redis 프로파일

| 실행 환경 | 설정 |
|-----------|------|
| 위 Docker Compose 전체 | `SPRING_PROFILES_ACTIVE=redis-cluster`, `REDIS_CLUSTER_NODES=...` (compose에 이미 설정) |
| 단일 Redis(로컬 6379) | `redis-cluster` 프로파일을 쓰지 않고 기본 `application.yml`의 단일 노드 설정 사용 |

## 아키텍처 요약

- **대기열**: Redis `ZSET`, 스케줄러가 배치 입장 후 입장 토큰(TTL) 발급  
- **예매**: Redisson 좌석 락 + JPA 비관적 락으로 좌석 상태 갱신 → Kafka `ticket-reserved` 등 이벤트 발행  
- **비동기 결제·알림**: Kafka Consumer가 RabbitMQ 큐(`payment.queue` 등)로 전달 → 워커 소비(동시성은 `PAYMENT_WORKER_CONCURRENCY` 등으로 조절)  
- **실시간 대시보드**: WebSocket으로 TPS·지연·큐 깊이 등 스냅샷 푸시; 일부 비즈니스 지표는 REST 집계

상세 기획·흐름: [기획서.md](./기획서.md), 보조 다이어그램: [flowchart_comparison.md](./flowchart_comparison.md)

## 관측 (Prometheus / Grafana)

- **스크랩 대상** (`docker/prometheus.yml`):  
  - `ticketing` → `backend:8080/actuator/prometheus`  
  - `rabbitmq` → `rabbitmq:15692/metrics` (큐 ready/unacked 등; `docker/rabbitmq/rabbitmq.conf`에서 per-object 메트릭 활성화)
- **Grafana**: `docker/grafana/provisioning`으로 데이터소스·대시보드 자동 로드. 예: `Ticketing overview` (`docker/grafana/dashboards/ticketing-overview.json`).
- **프론트에서 Grafana**: nginx가 `/grafana/`를 Grafana로 넘기므로, 브라우저에서는 `http://localhost/grafana/` 로 접근하는 구성과 맞습니다.

## nGrinder 부하 테스트

- UI: `http://localhost:19080`  
- 컨테이너 안에서 백엔드를 칠 때는 `TICKETING_NGRINDER_TARGET_BASE_URL`(기본 `http://host.docker.internal:8080`) 등으로 **에이전트가 도달 가능한 URL**을 지정합니다.  
- 앱 대시보드에서 프리셋 실행 시, 컨트롤러에 스크립트가 없으면 API가 업로드 안내를 반환합니다.  
- 스크립트 업로드·시나리오 설명: [load-tests/ngrinder/README.md](./load-tests/ngrinder/README.md)

## 저장소 구조 (요약)

```
backend/          Spring Boot API, 도메인, Flyway 마이그레이션
docs/             검토용 변경 메모 (아래 링크)
frontend/         Vite React SPA, nginx 설정(Docker)
load-tests/ngrinder/   Groovy 스크립트, upload-scripts.ps1
docker/           prometheus.yml, grafana provisioning, rabbitmq.conf
docker-compose.yml
```

## 변경·검토 노트

최근 세션에서 반영된 세부 사항(대시보드 nGrinder 모니터링, `payment-requests/start` **최대 15분** 창, Groovy 좌석 순환, 메트릭 API 수정 등)은 **[docs/change-notes-review.md](./docs/change-notes-review.md)** 에 모아 두었습니다. PR/위키로 옮길 때 참고용입니다.
