# Ticketing Frontend

React + TypeScript + Vite + Tailwind 기반의 프론트엔드입니다.  
전체 실행/구성은 루트 문서 `../README.md`를 참고하세요.

## 로컬 개발

```bash
npm install
npm run dev
```

- 기본적으로 Vite 개발 서버는 `/api`, `/ws`를 `http://localhost:8080`으로 프록시합니다.

## 환경 변수

Vite는 `VITE_` prefix 환경 변수를 사용합니다.

- `VITE_API_BASE`: API base URL (예: `http://localhost:8080`)
- `VITE_WS_URL`: WebSocket URL (예: `ws://localhost/ws/metrics`)

Docker(nginx)로 구동 시에는 `frontend/Dockerfile`이 빌드 타임에 값을 주입할 수 있도록 되어 있으며,
기본값은 빈 문자열(동일 origin 기준)입니다.
