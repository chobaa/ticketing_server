# 기술 문서 (Ticketing Server)

이 디렉터리는 **관측·부하 테스트·운영 UI**를 중심으로 한 프로젝트 문서입니다.  
제품 기획·아키텍처 비교는 루트 [기획서.md](../기획서.md), [flowchart_comparison.md](../flowchart_comparison.md)를 참고하세요.

## 문서 맵

```text
docs/
├── README.md                          ← 이 파일 (색인)
├── assets/                            ← README 데모용 영상·스크린샷
├── observability/                     ← 메트릭·Grafana·Prometheus
├── operations/                        ← Ops·부하 시나리오·트러블슈팅
└── changelog/                         ← 세션별 변경·검증 메모
```

## 빠른 링크

| 읽고 싶은 것 | 문서 |
|--------------|------|
| Micrometer 이름·의미·알람 원칙 | [observability/metrics-contract.md](./observability/metrics-contract.md) |
| Grafana 4종·Prometheus·runId | [observability/grafana-and-prometheus.md](./observability/grafana-and-prometheus.md) |
| 시나리오 A~F·Ops KPI·Δ 측정 | [operations/load-test-runbook.md](./operations/load-test-runbook.md) |
| `/ops` UI·`business-metrics` API | [operations/ops-dashboard.md](./operations/ops-dashboard.md) |
| 증상별 원인·조치 | [operations/troubleshooting.md](./operations/troubleshooting.md) |
| nGrinder 스크립트 업로드 | [../load-tests/ngrinder/README.md](../load-tests/ngrinder/README.md) |

## 권장 읽기 순서

1. **온보딩** — 루트 [README.md](../README.md) §7 Quick Start  
2. **부하 검증** — [operations/load-test-runbook.md](./operations/load-test-runbook.md)  
3. **지표 해석** — [observability/metrics-contract.md](./observability/metrics-contract.md)  
4. **장애·이상 징후** — [operations/troubleshooting.md](./operations/troubleshooting.md)  

## 변경 이력 (아카이브)

| 문서 | 내용 |
|------|------|
| [changelog/review-notes.md](./changelog/review-notes.md) | 대시보드·nGrinder·Redis 관련 세션 메모 |
| [changelog/ops-metrics-notes.md](./changelog/ops-metrics-notes.md) | Ops·`business-metrics`·클러스터 카운터 변경 |

## 레거시 경로

이전 파일명은 아래로 **이전**되었습니다. 북마크는 새 경로로 갱신하세요.

| 이전 | 현재 |
|------|------|
| `docs/metrics-contract.md` | `docs/observability/metrics-contract.md` |
| `docs/scenario-ops-grafana-runbook.md` | `docs/operations/load-test-runbook.md` |
| `docs/change-notes-review.md` | `docs/changelog/review-notes.md` |
| `docs/change-notes-ops-loadtest-metrics.md` | `docs/changelog/ops-metrics-notes.md` |
