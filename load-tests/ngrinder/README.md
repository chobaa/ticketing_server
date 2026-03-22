# nGrinder 부하 테스트

## 개요

[nGrinder](https://github.com/naver/ngrinder) 컨트롤러는 **TPS·응답 시간 그래프**, **VUser**, **실행 로그·에러 로그**를 웹 UI에서 제공합니다.

## Docker로 컨트롤러 실행 (선택)

공식 이미지 대신 로컬에 WAR를 배포하거나, 커뮤니티 이미지를 사용할 수 있습니다. 예시:

```bash
docker run -d -p 8080:80 -p 16001:16001 -p 12000-12009:12000-12009 ngrinder/ngrinder-controller:latest
```

에이전트는 별도 호스트 또는 컨테이너로 띄운 뒤 컨트롤러에 등록합니다.

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
