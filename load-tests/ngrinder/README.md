# nGrinder 부하 테스트

## 개요

[nGrinder](https://github.com/naver/ngrinder) 컨트롤러는 **TPS·응답 시간 그래프**, **VUser**, **실행 로그·에러 로그**를 웹 UI에서 제공합니다.

## Docker로 컨트롤러 실행 (선택)

공식 이미지 대신 로컬에 WAR를 배포하거나, 커뮤니티 이미지를 사용할 수 있습니다. 예시:

```bash
docker run -d -p 8080:80 -p 16001:16001 -p 12000-12009:12000-12009 ngrinder/ngrinder-controller:latest
```

에이전트는 별도 호스트 또는 컨테이너로 띄운 뒤 컨트롤러에 등록합니다.

## 스크립트 업로드 (필수: "script should exist" 방지)

nGrinder REST API로 테스트를 생성할 때 `script should exist`가 뜨면, 컨트롤러에 스크립트가 아직 업로드되지 않은 상태입니다.

PowerShell에서 아래 스크립트를 1회 실행해 업로드하세요:

```powershell
cd C:\Users\Chobaa\Desktop\ticketing_server
.\load-tests\ngrinder\upload-scripts.ps1 -ControllerBaseUrl http://localhost:19080 -Username admin -Password admin
```

## 스크립트 타깃

- 로컬 백엔드: `http://localhost:8080`
- Docker 프론트(nginx) 경유: `http://localhost` → `/api` 프록시

Groovy 스크립트에서 다음 순서를 권장합니다.

1. `POST /api/auth/login` → Bearer 토큰 추출  
2. `POST /api/events/{id}/queue` (Authorization)  
3. `GET /api/events/{id}/queue/me` 폴링  
4. (입장 후) `POST /api/events/{id}/reservations` with `seatId`, `admissionToken`

## 결과 확인

컨트롤러 UI에서 **TPS**, **Mean Test time**, **에러율** 차트와 **로그 탭**의 스택 트레이스를 확인합니다. 같은 시점에 Prometheus(`http://localhost:9090`)의 `http_server_requests`와 앱 대시보드(WebSocket)를 비교하면 서버·클라이언트 관점을 나눌 수 있습니다.

## 포함된 nGrinder 스크립트
아래 4개 스크립트는 각각 별도 테스트로 실행되며, nGrinder 대시보드에서 시나리오별로 구분해서 볼 수 있습니다.

- `load-tests/ngrinder/scripts/01_comprehensive.groovy`: 종합 흐름(큐 입장 → admission 토큰 폴링 → 예약) 검증
- `load-tests/ngrinder/scripts/02_concurrency.groovy`: 동시성(모두 동일 좌석 예약 시도, 성공 1건 기대)
- `load-tests/ngrinder/scripts/03_load.groovy`: 부하(토큰 1회 획득 후 동일 스레드에서 반복 예약 요청)
- `load-tests/ngrinder/scripts/04_data_integrity.groovy`: 데이터 정합성(예약 성공 좌석 수 vs 좌석 `HELD` 상태 수 비교)

## 스크립트 파라미터(Controller에 설정)
필요 시 nGrinder 컨트롤러의 “Script Parameters”에 아래 값을 넣어 실행합니다.

- `baseUrl`: 대상 서버 URL (기본값: `http://localhost:8080`)
- `adminEmail`, `adminPassword`: 관리자 계정 (기본값: admin_자동생성 + `password123456`)
- `userPassword`: 테스트용 사용자 비밀번호 (기본값: `password123456`)
- `eventSeatCount`: 이벤트 좌석 수 (기본값: comprehensive=20, concurrency=1, load=200, dataIntegrity=5)
- `seatPrice`: 좌석 가격 (기본값: `100.00`)
- `seatGrade`: 좌석 grade (기본값: `R`)
- `admissionPollIntervalMs`: admission 폴링 간격 (기본값: `200`)
- `admissionMaxWaitSec`: admission 폴링 최대 대기 (기본값: 15~30)
- `testDurationSec`: load 시나리오 실행 시간 (기본값: `30`)
