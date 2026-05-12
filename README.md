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
| **운영 대시보드 (Ops)** | [http://localhost/ops](http://localhost/ops) | 로그인 후 · 부하 실행·히트맵·runId·Grafana 바로가기 |
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
- **운영 대시보드 (Ops)**: 시나리오 A~F 실행·상태·좌석 히트맵, `X-LoadTest-RunId` 기반 **run-metrics** 드릴다운, 결제는 전역 카운터 + 테스트 시작 시점 **베이스라인 Δ**(비동기 파이프라인 한계는 UI에 안내)

상세 기획·흐름: [기획서.md](./기획서.md), 보조 다이어그램: [flowchart_comparison.md](./flowchart_comparison.md)

시나리오·Grafana·runId 운영 요약: [docs/scenario-ops-grafana-runbook.md](./docs/scenario-ops-grafana-runbook.md) · 메트릭 계약: [docs/metrics-contract.md](./docs/metrics-contract.md)

## 관측 (Prometheus / Grafana)

- **스크랩 대상** (`docker/prometheus.yml`):  
  - `ticketing` → `backend:8080/actuator/prometheus`  
  - `rabbitmq` → `rabbitmq:15692/metrics` (큐 ready/unacked 등; `docker/rabbitmq/rabbitmq.conf`에서 per-object 메트릭 활성화)
- **Grafana**: `docker/grafana/provisioning`으로 데이터소스·대시보드 자동 로드. **프로비저닝 대시보드는 아래 4종**(파일은 `docker/grafana/dashboards/*.json`).
- **프론트에서 Grafana**: nginx가 `/grafana/`를 Grafana로 넘기므로, 브라우저에서는 `http://localhost/grafana/` 로 접근하는 구성과 맞습니다.

### Grafana 대시보드 4종 (역할이 서로 다름)

| 대시보드 | UID (경로) | 용도 |
|----------|------------|------|
| 티켓팅 SLO (사용자 체감) | `/grafana/d/ticketing-slo/ticketing-slo` | TPS, p95/p99, 5xx/429, Hikari, Tomcat **시계열** |
| 티켓팅 병목 추적 | `/grafana/d/ticketing-bottlenecks/ticketing-bottlenecks` | Kafka/Redis up, Rabbit depth, consumer lag, MySQL 연결, Redis eviction 등 **원인 분해** |
| 티켓팅 시나리오 (A~F) | `/grafana/d/ticketing-scenarios/ticketing-scenarios` | 비즈 카운터 **rate**로 시나리오별 패턴 비교 |
| 티켓팅 Funnel | `/grafana/d/ticketing-funnel/ticketing-funnel` | Join→Admission→Reserve→Pay 퍼널, **runId** 변수·Loki 링크 |

Ops 상단에서 동일 4종으로 바로 열 수 있습니다. Ops는 **한 번의 부하 테스트**를 돌리며 run-metrics·히트맵·nGrinder를 묶어 보여 주고, Grafana는 **기간·추세·인프라 깊이**에 유리합니다(숫자 축이 겹쳐 보일 수 있으나 데이터 소스·목적이 다릅니다).

## nGrinder 부하 테스트

- UI: `http://localhost:19080`  
- 컨테이너 안에서 백엔드를 칠 때는 `TICKETING_NGRINDER_TARGET_BASE_URL`(기본 `http://host.docker.internal:8080`) 등으로 **에이전트가 도달 가능한 URL**을 지정합니다.  
- **Ops**(`/ops`)에서 시나리오 선택 실행 시, 백엔드가 `runId`를 발급하고 Groovy가 `X-LoadTest-RunId` 헤더로 전달합니다. run-metrics는 `GET /api/dashboard/run-metrics?runId=...` 로 조회합니다.  
- 컨트롤러에 스크립트가 없으면 API가 업로드 안내를 반환합니다. 볼륨 초기화 후에는 `upload-scripts.ps1`을 다시 실행하세요.  
- 스크립트 업로드·시나리오 설명: [load-tests/ngrinder/README.md](./load-tests/ngrinder/README.md)

## 저장소 구조 (요약)

```
backend/          Spring Boot API, 도메인, Flyway 마이그레이션
docs/             시나리오·runbook, 메트릭 계약, 변경 메모
frontend/         Vite React SPA, nginx 설정(Docker)
load-tests/ngrinder/   Groovy 스크립트, upload-scripts.ps1
docker/           prometheus.yml, grafana provisioning, rabbitmq.conf
docker-compose.yml
```

## 변경·검토 노트

- [docs/change-notes-review.md](./docs/change-notes-review.md) — 검토용 누적 메모  
- [docs/change-notes-ops-loadtest-metrics.md](./docs/change-notes-ops-loadtest-metrics.md) — Ops·부하·메트릭 관련 변경 요약

## 마무리 체크리스트 (README·녹화·노션)

README는 위 내용으로 **현재 구성과 URL**을 반영한 상태입니다. 남은 작업은 팀/발표용 자료 정리입니다.

| 작업 | 제안 |
|------|------|
| **녹화 영상** | (1) `docker compose up` 후 로그인 → `/ops` (2) 시나리오 1~2개 짧게 실행 (3) `runId` 툴팁·run-metrics KPI·히트맵 (4) Grafana 4종 중 Funnel에 runId 전달·Scenarios에서 rate 확인 (5) nGrinder Controller에서 종료 상태 |
| **노션** | 한 페이지에 **URL 표**(웹, Ops, Grafana×4, Prometheus, nGrinder) + **역할 한 줄**(Ops=단일 테스트 조작, Grafana=시계열·병목) + **runId 흐름**(헤더 → MDC → run-metrics) + 스크립트 재업로드 주의 + 위 `docs/` 링크 |

외부 위키로 옮길 때는 이 README의 표·절만 복사해도 됩니다.
