import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  api,
  type DashboardStatusDto,
  type NgrinderStatusResponse,
} from '../api'
type HeatSeat = { id: number; seatNumber: string; status: string; grade?: string }
type OpenEvent = { id: number; name: string }
type BizSnapshot = Awaited<ReturnType<typeof api.dashboardBusinessMetrics>>
/** Snapshot of global payment counters right after a load test starts (async pipeline; Δ is approximate). */
type PaymentGlobalBaseline = {
  paymentRequestedTotal: number
  paymentSucceededTotal: number
  paymentFailedTotal: number
}
type EventSnap = Awaited<ReturnType<typeof api.opsEventSummary>>
type RtSnapshot = Awaited<ReturnType<typeof api.dashboardRealtime>>

function fmtInt(n: number | undefined | null): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return String(Math.round(n))
}

function fmtNum1(n: number | undefined | null): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return String(Math.round(n * 10) / 10)
}

function fmtPct01(r: number | undefined | null): string {
  if (r == null || !Number.isFinite(r)) return '—'
  return `${Math.round(r * 100)}%`
}

/** Short hint shown below nGrinder controls for the selected scenario. */
function scenarioLoadTestHint(sc: 'A' | 'B' | 'C' | 'D' | 'E' | 'F'): string {
  switch (sc) {
    case 'A':
      return 'VUser당 1석만 예약합니다. 좌석을 vusers 근처로 두면 히트맵에서 경쟁이 잘 보이고, 훨씬 크게 두면 잔여(회색)가 많아져 한눈에 보기 어렵습니다.'
    case 'B':
      return '항상 좌석 1석만 만들며(핫키), 위 좌석 수 입력은 무시됩니다. Ops 시나리오 지표 3개는 락 실패·대기열·입장 순이며, 히트맵은 단일 칸 경쟁만 보입니다.'
    case 'C':
      return '/seats 폴링만 합니다. 이 runId에는 IP 10회/초·유저 5회/초 한도가 적용됩니다(Docker 전역 10000과 별개). vusers·testDurationSec을 올리면 Rate limit 거절(429)이 Ops·run-metrics에 쌓입니다.'
    case 'D':
      return 'Scenario D는 hold TTL 60초·결제 스킵(좀비)으로 실행됩니다. sleepMs 기본은 TTL+15초이며, 만료 후 좌석이 풀리면 VUser가 조기 종료하고 백엔드가 nGrinder 테스트를 중지합니다.'
    case 'E':
      return 'vusers/threads는 API에 보내지 않으며, 서버가 vusers≈좌석×배수로 정합니다. 좌석×배수가 커질수록 nGrinder VUser가 같이 커집니다. Ops에서 로드테스트 이벤트를 고르면 판매/대기열/점유율이 해당 이벤트 기준으로 보입니다.'
    case 'F':
      return 'A~E 동작을 랜덤 혼합합니다. 기본은 wall-clock testDurationSec(30 미만이면 서버 180초)까지 반복하며, 스크립트는 AVAILABLE 좌석이 없으면 그 전에 조기 종료합니다. 로컬에서는 vusers·좌석·시간을 줄여도 됩니다. 좌석은 vusers보다 작으면 서버가 vusers 이상으로 맞춥니다.'
  }
}

/** Extra KPI cards per scenario (mostly 3; F is mixed load so a wider funnel). */
function scenarioExtraKpis(
  sc: 'A' | 'B' | 'C' | 'D' | 'E' | 'F',
  bm: BizSnapshot | null,
  es: EventSnap | null,
  rt: RtSnapshot | null,
): { label: string; value: string; hint: string }[] {
  if (!bm) {
    const labels =
      sc === 'A'
        ? ['대기열 진입(누적)', '입장 토큰 발급(누적)', '좌석 락 실패(누적)']
        : sc === 'B'
          ? ['좌석 락 실패(누적)', '대기열 진입(누적)', '입장 토큰 발급(누적)']
          : sc === 'C'
            ? ['Rate limit 거절(누적)', 'HTTP 요청 처리(누적)', '지연 p99(근사)']
            : sc === 'D'
              ? ['예약 TTL 만료(누적)', '결제 요청(누적)', '결제 대기 예약(이벤트)']
              : sc === 'F'
                ? [
                    '대기열 진입(누적)',
                    '입장 토큰 발급(누적)',
                    '예약 시도(누적)',
                    '예약 성공(누적)',
                    '좌석 락 실패(누적)',
                    '예약 실패·좌석불가(누적)',
                    'HTTP 요청 처리(누적)',
                  ]
                : ['판매 좌석(이벤트)', '대기열 깊이(이벤트)', '좌석 점유율']
    return labels.map((label) => ({ label, value: '—', hint: '' }))
  }
  switch (sc) {
    case 'A':
      return [
        { label: '대기열 진입(누적)', value: fmtInt(bm.queueEnteredTotal), hint: 'joinQueue' },
        { label: '입장 토큰 발급(누적)', value: fmtInt(bm.admissionIssuedTotal), hint: '입장 스케줄러' },
        {
          label: '좌석 락 실패(누적)',
          value: fmtInt(bm.seatLockFailedTotal),
          hint: '첫 가용석 경쟁(썬더링 허드)',
        },
      ]
    case 'B':
      return [
        { label: '좌석 락 실패(누적)', value: fmtInt(bm.seatLockFailedTotal), hint: '핫키 단일 좌석 경쟁' },
        { label: '대기열 진입(누적)', value: fmtInt(bm.queueEnteredTotal), hint: 'joinQueue' },
        { label: '입장 토큰 발급(누적)', value: fmtInt(bm.admissionIssuedTotal), hint: '입장 스케줄러' },
      ]
    case 'C':
      return [
        { label: 'Rate limit 거절(누적)', value: fmtInt(bm.rateLimitRejectedTotal), hint: '429 시 서버 카운터' },
        {
          label: 'HTTP 요청 처리(누적)',
          value: fmtInt(bm.httpServerRequestTotal),
          hint: 'runId 요청만 카운트 (/seats 폴링 중심)',
        },
        {
          label: '지연 p99(근사)',
          value: rt != null && rt.p99Latency != null ? `${fmtNum1(rt.p99Latency)}ms` : '—',
          hint: 'realtime 샘플(2초 폴링)',
        },
      ]
    case 'D':
      return [
        { label: '예약 TTL 만료(누적)', value: fmtInt(bm.reservationExpiredTotal), hint: '만료 스케줄' },
        { label: '결제 요청(누적)', value: fmtInt(bm.paymentRequestedTotal), hint: 'TTL 이후 파이프라인' },
        es
          ? {
              label: '결제 대기 예약(이벤트)',
              value: fmtInt(es.pendingReservations),
              hint: '선택 이벤트 PENDING_PAYMENT',
            }
          : {
              label: '결제 드롭(누적)',
              value: fmtInt(bm.paymentDroppedMissingReservationTotal),
              hint: '예약 없이 요청된 건',
            },
      ]
    case 'F':
      return [
        { label: '대기열 진입(누적)', value: fmtInt(bm.queueEnteredTotal), hint: 'joinQueue' },
        { label: '입장 토큰 발급(누적)', value: fmtInt(bm.admissionIssuedTotal), hint: '입장 스케줄러' },
        { label: '예약 시도(누적)', value: fmtInt(bm.reservationAttemptedTotal), hint: 'reserve 호출' },
        { label: '예약 성공(누적)', value: fmtInt(bm.reservationSucceededTotal), hint: '생성 예약' },
        { label: '좌석 락 실패(누적)', value: fmtInt(bm.seatLockFailedTotal), hint: '락 경합' },
        {
          label: '예약 실패·좌석불가(누적)',
          value: fmtInt(bm.reservationFailedSeatNotAvailableTotal),
          hint: '점유/매진',
        },
        { label: 'HTTP 요청 처리(누적)', value: fmtInt(bm.httpServerRequestTotal), hint: 'runId HTTP' },
      ]
    case 'E':
    default:
      if (es) {
        return [
          { label: '판매 좌석(이벤트)', value: fmtInt(es.seatsSold), hint: '선택 이벤트' },
          { label: '대기열 깊이(이벤트)', value: fmtInt(es.queueDepth), hint: '선택 이벤트' },
          { label: '좌석 점유율', value: fmtPct01(es.seatsOccupiedRatio), hint: '(HELD+SOLD)/전체' },
        ]
      }
      return [
        {
          label: 'HTTP 요청 처리(누적)',
          value: fmtInt(bm.httpServerRequestTotal),
          hint: 'runId 요청만 카운트 (이벤트 미선택)',
        },
        { label: '대기열 진입(누적)', value: fmtInt(bm.queueEnteredTotal), hint: 'joinQueue' },
        { label: '입장 토큰 발급(누적)', value: fmtInt(bm.admissionIssuedTotal), hint: '입장 스케줄러' },
      ]
  }
}

function toneForStatus(status: string): { bg: string; label: string } {
  const s = (status ?? '').toUpperCase()
  if (s === 'AVAILABLE') return { bg: 'bg-white/80 dark:bg-white/10', label: 'Available' }
  if (s === 'HELD' || s === 'PENDING') return { bg: 'bg-amber-300/80 dark:bg-amber-500/25', label: 'Pending' }
  if (s === 'SOLD') return { bg: 'bg-red-400/80 dark:bg-red-500/25', label: 'Sold' }
  return { bg: 'bg-neutral-200/70 dark:bg-white/5', label: status }
}

function pill(label: string, ok: boolean | undefined, ms: number | undefined) {
  return (
    <div
      key={label}
      className="flex items-center gap-1.5 rounded-full border border-white/20 bg-white/10 px-2.5 py-1 text-[11px] backdrop-blur dark:border-white/10 dark:bg-black/25"
    >
      <span className="font-medium text-neutral-600 dark:text-neutral-300">{label}</span>
      <span className={ok ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}>
        {ok === undefined ? '—' : ok ? 'OK' : 'NG'}
      </span>
      {ms != null && <span className="text-neutral-500 dark:text-neutral-400">{Math.round(ms)}ms</span>}
    </div>
  )
}

function KpiCard({
  label,
  value,
  hint,
  accent = 'indigo',
}: {
  label: string
  value: string
  hint?: string
  accent?: 'indigo' | 'slate'
}) {
  const border =
    accent === 'indigo'
      ? 'border-l-indigo-500 dark:border-l-indigo-400'
      : 'border-l-slate-400/80 dark:border-l-slate-500'
  return (
    <div
      className={`flex h-full min-h-[7.75rem] flex-col rounded-2xl border border-white/20 bg-white/50 p-4 shadow-sm backdrop-blur dark:border-white/10 dark:bg-black/35 ${border} border-l-[4px]`}
    >
      <div className="min-h-[2.25rem] text-[11px] font-semibold uppercase leading-tight tracking-wide text-neutral-500 line-clamp-2 dark:text-neutral-400">
        {label}
      </div>
      <div className="mt-1.5 shrink-0 text-3xl font-bold tabular-nums tracking-tight text-neutral-900 dark:text-white">
        {value}
      </div>
      <div className="mt-auto min-h-[2.25rem] pt-2 text-[10px] leading-snug text-neutral-500 line-clamp-2 dark:text-neutral-400">
        {hint || '\u00a0'}
      </div>
    </div>
  )
}

function KpiCardGrid({ count, children }: { count: number; children: ReactNode }) {
  const cols =
    count > 3 ? 'grid-cols-2 sm:grid-cols-3 lg:grid-cols-4' : 'grid-cols-1 sm:grid-cols-3'
  return <div className={`grid gap-3 ${cols}`}>{children}</div>
}

function OpsStatusChip({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={`rounded-xl border border-white/25 bg-white/40 px-3 py-2 text-xs shadow-sm backdrop-blur dark:border-white/10 dark:bg-black/25 ${className}`}
    >
      {children}
    </div>
  )
}

// LiveMetricsPanel removed: the old event/cluster realtime cards were ambiguous and are deprecated in Ops UI.

function ngrinderStatusTone(statusName: string | undefined | null): { bg: string; fg: string; label: string } {
  const s = (statusName ?? '').toUpperCase()
  if (!s) return { bg: 'bg-neutral-500/15 dark:bg-white/10', fg: 'text-neutral-700 dark:text-neutral-200', label: '—' }
  if (s === 'TESTING') return { bg: 'bg-indigo-600/20 dark:bg-indigo-400/15', fg: 'text-indigo-800 dark:text-indigo-200', label: 'TESTING' }
  if (s === 'FINISHED') return { bg: 'bg-emerald-600/20 dark:bg-emerald-400/15', fg: 'text-emerald-800 dark:text-emerald-200', label: 'FINISHED' }
  if (s === 'READY') return { bg: 'bg-sky-600/20 dark:bg-sky-400/15', fg: 'text-sky-800 dark:text-sky-200', label: 'READY' }
  if (s === 'STOPPED' || s === 'CANCELED') return { bg: 'bg-amber-600/20 dark:bg-amber-400/15', fg: 'text-amber-800 dark:text-amber-200', label: s }
  if (s === 'ABNORMAL' || s === 'ERROR') return { bg: 'bg-red-600/20 dark:bg-red-400/15', fg: 'text-red-800 dark:text-red-200', label: s }
  return { bg: 'bg-neutral-600/15 dark:bg-white/10', fg: 'text-neutral-700 dark:text-neutral-200', label: s }
}

function clampInt(n: number, min: number, max: number): number {
  if (!Number.isFinite(n)) return min
  return Math.max(min, Math.min(max, Math.floor(n)))
}

function grafanaExploreRunIdUrl(runId: string): string {
  const q = `{compose_service="backend"} |= "runId=${runId}"`
  const left = {
    datasource: 'Loki',
    queries: [{ refId: 'A', expr: q }],
    range: { from: 'now-15m', to: 'now' },
  }
  return `/grafana/explore?orgId=1&left=${encodeURIComponent(JSON.stringify(left))}`
}

export function OpsDashboard() {
  const [eventSummary, setEventSummary] =
    useState<Awaited<ReturnType<typeof api.opsEventSummary>> | null>(null)
  const [events, setEvents] = useState<OpenEvent[]>([])
  const [eventId, setEventId] = useState<number | null>(null)
  const [seats, setSeats] = useState<HeatSeat[]>([])
  const [err, setErr] = useState<string | null>(null)
  const [deps, setDeps] = useState<DashboardStatusDto | null>(null)

  const [scenario, setScenario] = useState<'A' | 'B' | 'C' | 'D' | 'E' | 'F'>('A')
  const [baseUrl, setBaseUrl] = useState<string>('')
  const [vusers, setVusers] = useState<number>(50)
  const [threads, setThreads] = useState<number>(50)
  const [eventSeatCount, setEventSeatCount] = useState<number>(96)
  const [testDurationSec, setTestDurationSec] = useState<number>(20)
  const [sleepMs, setSleepMs] = useState<number>(75_000)
  const [crowdMultiplier, setCrowdMultiplier] = useState<number>(10)
  const [actionBusy, setActionBusy] = useState(false)

  useEffect(() => {
    if (scenario === 'E') return
    setThreads((t) => Math.max(t, vusers))
  }, [vusers, scenario])
  const [testId, setTestId] = useState<number | null>(null)
  const [status, setStatus] = useState<NgrinderStatusResponse | null>(null)
  const [bizSnapshot, setBizSnapshot] = useState<BizSnapshot | null>(null)
  const [rtSnapshot, setRtSnapshot] = useState<RtSnapshot | null>(null)
  const [testBaseline, setTestBaseline] = useState<BizSnapshot | null>(null)
  const [testScenario, setTestScenario] = useState<'A' | 'B' | 'C' | 'D' | 'E' | 'F' | null>(null)
  const [testExpectedVusers, setTestExpectedVusers] = useState<number | null>(null)
  const [testRunId, setTestRunId] = useState<string | null>(null)
  const [paymentGlobalBaseline, setPaymentGlobalBaseline] = useState<PaymentGlobalBaseline | null>(null)
  const [runMetrics, setRunMetrics] = useState<BizSnapshot | null>(null)
  const [logsCollected, setLogsCollected] = useState(false)
  const [logsCollecting, setLogsCollecting] = useState(false)
  const prevRequestedDeltaRef = useRef<number | null>(null)
  const flatRequestedDeltaTicksRef = useRef(0)

  const applyScenarioDefaults = (sc: 'A' | 'B' | 'C' | 'D' | 'E' | 'F') => {
    // Defaults are chosen for local/dev: readable heatmap + meaningful deltas without accidentally running 2000 VUsers.
    switch (sc) {
      case 'A':
        setVusers(50)
        setThreads(50)
        setEventSeatCount(96)
        setTestDurationSec(20)
        return
      case 'B':
        setVusers(50)
        setThreads(50)
        setEventSeatCount(1)
        return
      case 'C':
        setVusers(40)
        setThreads(40)
        setEventSeatCount(72)
        setTestDurationSec(20)
        return
      case 'D':
        setVusers(20)
        setThreads(20)
        setEventSeatCount(40)
        setSleepMs(70_000)
        return
      case 'E':
        // vusers/threads are computed server-side as seats×multiplier; keep numbers modest.
        setEventSeatCount(48)
        setCrowdMultiplier(10)
        return
      case 'F':
        // Mixed load: keep small by default; scale up intentionally.
        setVusers(200)
        setThreads(200)
        setEventSeatCount(200)
        setTestDurationSec(180)
        return
    }
  }

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const [es, st] = await Promise.all([api.opsOpenEvents(), api.dashboardStatus().catch(() => null)])
        if (cancelled) return
        setEvents(es)
        setEventId((prev) => prev ?? (es[0]?.id ?? null))
        setDeps(st)
      } catch (e) {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'ops summary failed')
      }
    }
    void load()
    const t = window.setInterval(load, 2000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [])

  useEffect(() => {
    // runId changes should reset previous run state immediately
    setRunMetrics(null)
    setTestBaseline(null)
    if (!testRunId) return
    let cancelled = false
    const tick = async () => {
      try {
        const rm = await api.dashboardRunMetrics(testRunId)
        if (!cancelled && rm && (rm as unknown as { found?: unknown }).found !== false) {
          // reuse BizSnapshot shape (subset of fields)
          setRunMetrics(rm as unknown as BizSnapshot)
          // capture baseline once (at first successful fetch for this run)
          setTestBaseline((prev) => prev ?? (rm as unknown as BizSnapshot))
        }
      } catch {
        // ignore (backend may restart)
      }
    }
    void tick()
    const t = window.setInterval(tick, 2000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [testRunId])

  useEffect(() => {
    if (!testRunId) setPaymentGlobalBaseline(null)
  }, [testRunId])

  useEffect(() => {
    let cancelled = false
    const tick = async () => {
      try {
        const [bm, rt] = await Promise.all([
          api.dashboardBusinessMetrics(),
          api.dashboardRealtime().catch(() => null),
        ])
        if (!cancelled) {
          setBizSnapshot(bm)
          setRtSnapshot(rt)
        }
      } catch {
        // ignore (dashboard may be down briefly)
      }
    }
    void tick()
    const t = window.setInterval(tick, 2000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [])

  useEffect(() => {
    if (!eventId) return
    let cancelled = false
    const load = async () => {
      try {
        const [xs, es] = await Promise.all([api.opsHeatmap(eventId), api.opsEventSummary(eventId)])
        if (!cancelled) {
          setSeats(xs)
          setEventSummary(es)
        }
      } catch (e) {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'heatmap failed')
      }
    }
    void load()
    const t = window.setInterval(load, 1000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [eventId])

  const byStatus = useMemo(() => {
    const m = { AVAILABLE: 0, HELD: 0, SOLD: 0, OTHER: 0 }
    for (const s of seats) {
      const st = (s.status ?? '').toUpperCase()
      if (st === 'AVAILABLE') m.AVAILABLE++
      else if (st === 'HELD' || st === 'PENDING') m.HELD++
      else if (st === 'SOLD') m.SOLD++
      else m.OTHER++
    }
    return m
  }, [seats])

  const sortedSeats = useMemo(() => {
    const toNum = (seatNumber: string) => {
      const m = seatNumber.match(/(\d+)/)
      return m ? Number(m[1]) : Number.MAX_SAFE_INTEGER
    }
    return [...seats].sort((a, b) => toNum(a.seatNumber) - toNum(b.seatNumber))
  }, [seats])

  const cols = 10
  const gridTemplate = `repeat(${cols}, minmax(0, 1fr))`

  const refreshNgrinder = async (id: number) => {
    try {
      const st = await api.ngrinderStatus(id)
      setStatus(st)
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'nGrinder status failed')
    }
  }

  useEffect(() => {
    if (!testId) return
    void refreshNgrinder(testId)
    // nGrinder logs "admin logined" on every Basic-auth request; 5s reduces noise vs 2s while staying responsive.
    const t = window.setInterval(() => void refreshNgrinder(testId), 5000)
    return () => window.clearInterval(t)
  }, [testId])

  const startNgrinder = async () => {
    setActionBusy(true)
    setErr(null)
    try {
      // capture baseline for clear "goal reached" progress on the homepage
      setTestBaseline(null)
      // avoid flashing previous test's run-scoped counters while new runId is being created/fetched
      setRunMetrics(null)
      setTestRunId(null)
      setTestScenario(scenario)
      setTestExpectedVusers(
        scenario === 'E'
          ? clampInt(eventSeatCount, 1, 5000) * clampInt(crowdMultiplier, 1, 5000)
          : clampInt(vusers, 1, 5000),
      )
      const created = await api.ngrinderStartScenario(scenario, {
        baseUrl: baseUrl.trim() || undefined,
        vusers: scenario === 'E' ? undefined : Math.max(1, Math.floor(vusers)),
        threads: scenario === 'E' ? undefined : Math.max(1, Math.floor(threads)),
        eventSeatCount: scenario === 'B' ? 1 : Math.max(1, Math.floor(eventSeatCount)),
        testDurationSec:
          scenario === 'A' || scenario === 'C' || scenario === 'F'
            ? Math.max(1, Math.floor(testDurationSec))
            : undefined,
        sleepMs: scenario === 'D' ? Math.max(0, Math.floor(sleepMs)) : undefined,
        crowdMultiplier: scenario === 'E' ? Math.max(1, Math.floor(crowdMultiplier)) : undefined,
      })
      const rawCreatedEventId = (created as unknown as { loadTestEventId?: unknown }).loadTestEventId
      const createdEventId =
        typeof rawCreatedEventId === 'number'
          ? rawCreatedEventId
          : typeof rawCreatedEventId === 'string'
            ? Number(rawCreatedEventId)
            : null
      const id = typeof created?.id === 'number' ? created.id : null
      const rid =
        typeof (created as unknown as { loadTestRunId?: unknown })?.loadTestRunId === 'string'
          ? ((created as unknown as { loadTestRunId?: string }).loadTestRunId ?? null)
          : null
      setTestRunId(rid)
      if (rid) {
        try {
          const bm = await api.dashboardBusinessMetrics()
          setPaymentGlobalBaseline({
            paymentRequestedTotal: Math.round(Number(bm.paymentRequestedTotal) || 0),
            paymentSucceededTotal: Math.round(Number(bm.paymentSucceededTotal) || 0),
            paymentFailedTotal: Math.round(Number(bm.paymentFailedTotal) || 0),
          })
        } catch {
          setPaymentGlobalBaseline(null)
        }
      } else {
        setPaymentGlobalBaseline(null)
      }
      // run-scoped baseline is captured from run-metrics after runId is known
      setTestId(id)
      setStatus(null)
      setLogsCollected(false)
      setLogsCollecting(false)
      if (createdEventId != null && Number.isFinite(createdEventId)) {
        // ensure the dropdown options include the new event immediately
        const es = await api.opsOpenEvents().catch(() => null)
        if (es) setEvents(es)
        setEventId(createdEventId)
      }
      if (id) await refreshNgrinder(id)
    } catch (e) {
      setErr(e instanceof Error ? e.message : '실행 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const stopNgrinder = async () => {
    if (!testId) return
    setActionBusy(true)
    setErr(null)
    try {
      await api.ngrinderStop(testId)
      await refreshNgrinder(testId)
    } catch (e) {
      setErr(e instanceof Error ? e.message : '중지 실패')
    } finally {
      setActionBusy(false)
    }
  }

  // If runId exists, never fall back to global counters (they include other traffic/tests).
  const effectiveBiz = testRunId ? runMetrics : bizSnapshot

  const finished = status?.status?.name === 'FINISHED'
  const statusTone = ngrinderStatusTone(status?.status?.name ?? null)
  const baseline = testBaseline
  const cur = effectiveBiz
  const expectedVusers = testExpectedVusers
  const admissionIssuedDelta =
    baseline &&
    cur &&
    Number.isFinite(baseline.admissionIssuedTotal ?? NaN) &&
    Number.isFinite(cur.admissionIssuedTotal ?? NaN)
      ? Math.max(0, Math.round((cur.admissionIssuedTotal ?? 0) - (baseline.admissionIssuedTotal ?? 0)))
      : null
  const queueEnteredDelta =
    baseline &&
    cur &&
    Number.isFinite(baseline.queueEnteredTotal ?? NaN) &&
    Number.isFinite(cur.queueEnteredTotal ?? NaN)
      ? Math.max(0, Math.round((cur.queueEnteredTotal ?? 0) - (baseline.queueEnteredTotal ?? 0)))
      : null
  const aGoal =
    testScenario === 'A' || testScenario === 'B' || testScenario === 'E'
      ? { label: '입장 토큰 발급(Δ) 목표', value: admissionIssuedDelta, target: expectedVusers }
      : null
  const aGoalReached =
    aGoal?.value != null && aGoal?.target != null ? aGoal.value >= aGoal.target : false
  const aGoalPct =
    aGoal?.value != null && aGoal?.target != null && aGoal.target > 0
      ? Math.max(0, Math.min(1, aGoal.value / aGoal.target))
      : null

  useEffect(() => {
    if (!testId) return
    if (!finished) return
    if (logsCollected || logsCollecting) return
    let cancelled = false
    setLogsCollecting(true)
    ;(async () => {
      try {
        await api.ngrinderLogs(testId)
        if (!cancelled) setLogsCollected(true)
      } catch {
        // keep trying via next refresh cycle
      } finally {
        if (!cancelled) setLogsCollecting(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [testId, finished, logsCollected, logsCollecting])

  const paymentDelta = useMemo(() => {
    if (!bizSnapshot || !testRunId || !paymentGlobalBaseline) return null
    const req = Math.max(
      0,
      Math.round((bizSnapshot.paymentRequestedTotal ?? 0) - paymentGlobalBaseline.paymentRequestedTotal),
    )
    const ok = Math.max(
      0,
      Math.round((bizSnapshot.paymentSucceededTotal ?? 0) - paymentGlobalBaseline.paymentSucceededTotal),
    )
    const fail = Math.max(
      0,
      Math.round((bizSnapshot.paymentFailedTotal ?? 0) - paymentGlobalBaseline.paymentFailedTotal),
    )
    return { req, ok, fail }
  }, [bizSnapshot, testRunId, paymentGlobalBaseline])

  const paymentRequestedDelta = useMemo(() => {
    if (paymentDelta != null) return paymentDelta.req
    if (effectiveBiz?.paymentRequestedTotal != null && Number.isFinite(effectiveBiz.paymentRequestedTotal)) {
      return Math.round(effectiveBiz.paymentRequestedTotal)
    }
    return null
  }, [paymentDelta, effectiveBiz?.paymentRequestedTotal])

  const loadTestExtraKpis = useMemo(
    () => scenarioExtraKpis(scenario, effectiveBiz, eventSummary, rtSnapshot),
    [scenario, effectiveBiz, eventSummary, rtSnapshot],
  )

  useEffect(() => {
    flatRequestedDeltaTicksRef.current = 0
    prevRequestedDeltaRef.current = null
  }, [testId])

  useEffect(() => {
    if (status?.status?.name !== 'TESTING' || paymentRequestedDelta == null) {
      flatRequestedDeltaTicksRef.current = 0
      prevRequestedDeltaRef.current = paymentRequestedDelta
      return
    }
    if (prevRequestedDeltaRef.current === paymentRequestedDelta) {
      flatRequestedDeltaTicksRef.current += 1
    } else {
      flatRequestedDeltaTicksRef.current = 0
    }
    prevRequestedDeltaRef.current = paymentRequestedDelta
  }, [testId, paymentRequestedDelta, status?.status?.name])

  const requestedDeltaLooksStuck =
    status?.status?.name === 'TESTING' &&
    paymentRequestedDelta != null &&
    flatRequestedDeltaTicksRef.current >= 4

  return (
    <div className="mx-auto w-full max-w-6xl">
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="text-lg font-semibold text-neutral-800 dark:text-neutral-100">운영 대시보드</div>
          <div className="text-xs text-neutral-500 dark:text-neutral-400">
            트래픽/비즈니스 신호등 + 실시간 좌석 히트맵
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <a
            className="rounded-2xl border border-white/25 bg-white/15 px-4 py-2 text-sm font-medium text-neutral-800 shadow active:scale-[0.99] dark:text-white"
            href="/grafana/d/ticketing-slo/ticketing-slo?orgId=1"
            target="_blank"
            rel="noreferrer"
          >
            Grafana SLO ↗
          </a>
          <a
            className="rounded-2xl border border-white/25 bg-white/15 px-4 py-2 text-sm font-medium text-neutral-800 shadow active:scale-[0.99] dark:text-white"
            href="/grafana/d/ticketing-bottlenecks/ticketing-bottlenecks?orgId=1"
            target="_blank"
            rel="noreferrer"
          >
            Bottleneck ↗
          </a>
          <a
            className="rounded-2xl border border-white/25 bg-white/15 px-4 py-2 text-sm font-medium text-neutral-800 shadow active:scale-[0.99] dark:text-white"
            href={
              testRunId
                ? `/grafana/d/ticketing-funnel/ticketing-funnel?orgId=1&var-runId=${encodeURIComponent(testRunId)}`
                : '/grafana/d/ticketing-funnel/ticketing-funnel?orgId=1'
            }
            target="_blank"
            rel="noreferrer"
            title={testRunId ? '현재 부하테스트 runId가 변수에 채워집니다' : 'Funnel에서 runId를 입력할 수 있습니다'}
          >
            Funnel ↗
          </a>
          <a
            className="rounded-2xl border border-white/25 bg-white/15 px-4 py-2 text-sm font-medium text-neutral-800 shadow active:scale-[0.99] dark:text-white"
            href="/grafana/d/ticketing-scenarios/ticketing-scenarios?orgId=1"
            target="_blank"
            rel="noreferrer"
          >
            Scenarios ↗
          </a>
        </div>
      </div>

      {err && (
        <div className="mb-3 rounded-2xl border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-700 dark:text-red-200">
          {err}
        </div>
      )}

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
          인프라
        </span>
        {deps?.deps ? pill('MySQL', deps.deps.mysql?.ok, deps.deps.mysql?.latencyMs) : null}
        {deps?.deps ? pill('Redis', deps.deps.redis?.ok, deps.deps.redis?.latencyMs) : null}
        {deps?.deps ? pill('Rabbit', deps.deps.rabbitmq?.ok, deps.deps.rabbitmq?.latencyMs) : null}
        {deps?.deps ? pill('Kafka', deps.deps.kafka?.ok, deps.deps.kafka?.latencyMs) : null}
        {!deps && <span className="text-xs text-neutral-500">상태를 불러오는 중…</span>}
      </div>

      {/* Removed the ambiguous \"event realtime\" and \"open events sum\" panels.
          They were hard to interpret for per-test debugging; Grafana funnel + per-run metrics are primary now. */}

      <div className="mt-6 rounded-3xl border border-white/25 bg-white/10 p-4 shadow-sm backdrop-blur dark:border-white/10 dark:bg-black/20">
        <div className="mb-2 flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="text-sm font-semibold text-neutral-800 dark:text-neutral-100">nGrinder 부하 테스트</div>
            <div className="text-xs text-neutral-500 dark:text-neutral-400">
              시나리오 A~F를 선택해 실행합니다. F는 A~E 패턴을 랜덤 혼합·대규모 VUser용입니다. vusers보다 threads가 작으면 서버에서 threads를 vusers 이상으로 자동 맞춥니다.
            </div>
          </div>
          <a className="text-xs font-medium text-[#007AFF] hover:underline" href="http://localhost:19080" target="_blank" rel="noreferrer">
            nGrinder Controller ↗
          </a>
        </div>
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2">
          <select
            className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
            value={scenario}
            onChange={(e) => {
              const v = e.target.value as 'A' | 'B' | 'C' | 'D' | 'E' | 'F'
              applyScenarioDefaults(v)
              setScenario(v)
            }}
            title="Scenario"
          >
            <option value="A">Scenario A · Open Run Spike</option>
            <option value="B">Scenario B · Hot Key / Lock</option>
            <option value="C">Scenario C · Retry Storm</option>
            <option value="D">Scenario D · Zombie TTL</option>
            <option value="E">Scenario E · Baseline (S×10 crowd)</option>
            <option value="F">Scenario F · Integrated random (A~E mix, 대규모)</option>
          </select>
          <input
            className="w-[16rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder="baseUrl (optional)"
            title="baseUrl (optional)"
          />
          {scenario !== 'E' ? (
            <>
              <input
                className="w-[7.5rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
                value={String(vusers)}
                onChange={(e) => setVusers(Number(e.target.value || 0))}
                placeholder="vusers"
                inputMode="numeric"
                title="vusers"
              />
              <input
                className="w-[7.5rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
                value={String(threads)}
                onChange={(e) => setThreads(Number(e.target.value || 0))}
                placeholder="threads"
                inputMode="numeric"
                title="threads"
              />
            </>
          ) : (
            <>
              <input
                className="w-[9.5rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
                value={String(crowdMultiplier)}
                onChange={(e) => setCrowdMultiplier(Number(e.target.value || 0))}
                placeholder="crowdMultiplier"
                inputMode="numeric"
                title="crowdMultiplier (vusers = seatCount × multiplier)"
              />
              <div className="text-xs text-neutral-500 dark:text-neutral-400">
                vusers≈{Math.max(1, Math.floor(eventSeatCount)) * Math.max(1, Math.floor(crowdMultiplier))}
              </div>
            </>
          )}
          {(scenario === 'A' || scenario === 'C' || scenario === 'D' || scenario === 'E' || scenario === 'F') && (
            <input
              className="w-[10rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
              value={String(eventSeatCount)}
              onChange={(e) => setEventSeatCount(Number(e.target.value || 0))}
              placeholder="eventSeatCount"
              inputMode="numeric"
              title="eventSeatCount"
            />
          )}
          {(scenario === 'A' || scenario === 'C' || scenario === 'F') && (
            <input
              className="w-[11rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
              value={String(testDurationSec)}
              onChange={(e) => setTestDurationSec(Number(e.target.value || 0))}
              placeholder="testDurationSec"
              inputMode="numeric"
              title="testDurationSec"
            />
          )}
          {scenario === 'D' && (
            <input
              className="w-[10rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
              value={String(sleepMs)}
              onChange={(e) => setSleepMs(Number(e.target.value || 0))}
              placeholder="sleepMs"
              inputMode="numeric"
              title="sleepMs"
            />
          )}
            </div>
            <div className="ml-auto flex shrink-0 items-center gap-2">
              <button
                type="button"
                className="rounded-xl bg-indigo-600/90 px-4 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
                onClick={() => void startNgrinder()}
                disabled={actionBusy || (scenario !== 'E' && (vusers < 1 || threads < 1))}
              >
                실행
              </button>
              <button
                type="button"
                className="rounded-xl bg-red-600/85 px-4 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
                onClick={() => void stopNgrinder()}
                disabled={actionBusy || !testId}
              >
                중지
              </button>
            </div>
          </div>

          <p className="rounded-xl border border-white/20 bg-white/5 px-3 py-2 text-[11px] leading-relaxed text-neutral-600 dark:border-white/10 dark:bg-black/10 dark:text-neutral-300">
            <span className="font-semibold text-neutral-700 dark:text-neutral-200">시나리오 {scenario}</span>
            <span className="text-neutral-400 dark:text-neutral-500"> · </span>
            {scenarioLoadTestHint(scenario)}
          </p>

          <div className="flex flex-wrap items-stretch gap-2">
            <OpsStatusChip>
              <span className={`rounded-full px-2 py-0.5 font-semibold ${statusTone.bg} ${statusTone.fg}`}>
                {statusTone.label}
              </span>
            </OpsStatusChip>
            <OpsStatusChip>
              <span className="text-neutral-500 dark:text-neutral-400">testId={testId ?? '—'}</span>
            </OpsStatusChip>
            {testRunId && (
              <OpsStatusChip>
                <span className="text-neutral-500 dark:text-neutral-400" title={`전체 runId: ${testRunId}`}>
                  runId=<span className="font-mono">{testRunId.slice(0, 8)}</span>
                </span>
              </OpsStatusChip>
            )}
            {testRunId && (
              <OpsStatusChip>
                <a
                  className="font-medium text-[#007AFF] hover:underline"
                  href={grafanaExploreRunIdUrl(testRunId)}
                  target="_blank"
                  rel="noreferrer"
                >
                  logs ↗
                </a>
              </OpsStatusChip>
            )}
            <OpsStatusChip>
              <span className="text-neutral-500 dark:text-neutral-400">
                paymentRequested(total)={paymentRequestedDelta ?? '—'}
              </span>
            </OpsStatusChip>
            {queueEnteredDelta != null && (
              <OpsStatusChip>
                <span className="text-neutral-500 dark:text-neutral-400">joinQueue(Δ)={queueEnteredDelta}</span>
              </OpsStatusChip>
            )}
            {aGoal && aGoal.value != null && aGoal.target != null && (
              <OpsStatusChip>
                <span className={aGoalReached ? 'text-emerald-700 dark:text-emerald-300' : 'text-neutral-500 dark:text-neutral-400'}>
                  {aGoal.label}: {aGoal.value}/{aGoal.target} {aGoalReached ? '(달성)' : ''}
                </span>
              </OpsStatusChip>
            )}
          </div>
          {aGoalPct != null && (
            <div className="w-full max-w-[36rem]">
              <div className="h-2 w-full overflow-hidden rounded-full bg-white/20 dark:bg-white/10">
                <div
                  className={aGoalReached ? 'h-2 bg-emerald-500/80' : 'h-2 bg-indigo-500/80'}
                  style={{ width: `${Math.round(aGoalPct * 100)}%` }}
                />
              </div>
            </div>
          )}
        </div>

        <div className="mt-3 space-y-3">
          <div>
            <div className="mb-2 flex flex-wrap items-center gap-2 text-[11px] font-bold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
              <span>공통 · 결제 파이프라인</span>
              {bizSnapshot?.clusterCountersEnabled ? (
                <span className="rounded-full bg-emerald-500/20 px-2 py-0.5 text-[10px] font-semibold normal-case text-emerald-800 dark:text-emerald-200">
                  Redis 클러스터 합산
                </span>
              ) : null}
              {testRunId ? (
                <span className="rounded-full bg-neutral-500/15 px-2 py-0.5 text-[10px] font-semibold normal-case text-neutral-700 dark:text-neutral-200">
                  {paymentDelta
                    ? '결제 Δ: 테스트 시작 직후 전역 스냅샷 기준(비동기·다른 트래픽 있으면 근사)'
                    : '결제 베이스라인 수집 중…'}
                </span>
              ) : null}
            </div>
            <KpiCardGrid count={3}>
              <KpiCard
                label={paymentDelta ? '결제 성공(이번 테스트 Δ)' : '결제 성공(누적)'}
                value={fmtInt(paymentDelta ? paymentDelta.ok : bizSnapshot?.paymentSucceededTotal)}
                hint={
                  paymentDelta
                    ? `전역 누적 ${fmtInt(bizSnapshot?.paymentSucceededTotal)}`
                    : 'settlement 성공(전역)'
                }
                accent="indigo"
              />
              <KpiCard
                label={paymentDelta ? '결제 실패(이번 테스트 Δ)' : '결제 실패(누적)'}
                value={fmtInt(paymentDelta ? paymentDelta.fail : bizSnapshot?.paymentFailedTotal)}
                hint={
                  paymentDelta
                    ? `전역 누적 ${fmtInt(bizSnapshot?.paymentFailedTotal)}`
                    : 'settlement 실패(전역)'
                }
                accent="indigo"
              />
              <KpiCard
                label="결제 처리 중"
                value={fmtInt(bizSnapshot?.paymentProcessing)}
                hint={paymentDelta ? 'DB PROCESSING(전역·스냅샷)' : 'DB PROCESSING 건수(전역)'}
                accent="indigo"
              />
            </KpiCardGrid>
          </div>
          <div>
            <div className="mb-2 text-[11px] font-bold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
              시나리오 {scenario} · 집중 지표
              {scenario === 'E' && !eventSummary ? (
                <span className="ml-2 font-normal normal-case text-amber-700 dark:text-amber-200">
                  (E: 히트맵 이벤트를 선택하면 이벤트 단위 지표가 표시됩니다)
                </span>
              ) : null}
              {scenario === 'C' ? (
                <span className="ml-2 font-normal normal-case text-neutral-500 dark:text-neutral-400">
                  (C: 인증된 GET /seats만 반복 — 공통 결제 카운터는 거의 안 움직일 수 있습니다)
                </span>
              ) : null}
            </div>
            {scenario === 'F' ? (
              <p className="mb-2 text-[11px] leading-relaxed text-neutral-500 dark:text-neutral-400">
                큐→입장→예약→HTTP 흐름 순입니다. 429·레이트리밋은 Grafana Scenarios 또는{' '}
                <span className="font-mono text-[10px]">rateLimitRejectedTotal</span>을 보세요.
              </p>
            ) : null}
            <KpiCardGrid count={loadTestExtraKpis.length}>
              {loadTestExtraKpis.map((k) => (
                <KpiCard key={k.label} label={k.label} value={k.value} hint={k.hint} accent="slate" />
              ))}
            </KpiCardGrid>
          </div>
        </div>

        {requestedDeltaLooksStuck && (
          <div className="mt-3 rounded-xl border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-900 dark:text-amber-100">
            paymentRequested(total)가 여러 번 갱신됐는데 값이 그대로입니다. 흔한 원인: 워커 슬립으로 큐 적체, progressMaxWaitSec 타임아웃, IP rate limit으로 HTTP 429.
          </div>
        )}
        <div className="mt-3 text-xs text-neutral-500 dark:text-neutral-400">
          {finished ? (logsCollected ? '테스트 종료(로그 확인 완료)' : '테스트 종료(로그 확인 대기)') : '테스트 진행/대기 중'}
        </div>
      </div>

      <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
        <div className="text-sm font-semibold text-neutral-800 dark:text-neutral-100">좌석 히트맵</div>
        <div className="flex items-center gap-2">
          <select
            className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
            value={eventId ?? ''}
            onChange={(e) => setEventId(e.target.value ? Number(e.target.value) : null)}
          >
            {events.length === 0 ? <option value="">OPEN 이벤트 없음</option> : null}
            {events.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name} (#{e.id})
              </option>
            ))}
          </select>
          <div className="text-xs text-neutral-500 dark:text-neutral-400">
            A:{byStatus.AVAILABLE} / P:{byStatus.HELD} / S:{byStatus.SOLD}
          </div>
        </div>
      </div>

      <div className="mt-3 rounded-3xl border border-white/25 bg-white/10 p-4 shadow-sm backdrop-blur dark:border-white/10 dark:bg-black/20">
        <div
          className="grid gap-1"
          style={{
            gridTemplateColumns: gridTemplate,
          }}
        >
          {sortedSeats.map((s) => {
            const t = toneForStatus(s.status)
            return (
              <div
                key={s.id}
                className={`flex h-10 items-center justify-center rounded-xl border border-white/20 text-[11px] font-semibold text-neutral-800 dark:text-neutral-100 ${t.bg}`}
                title={`${s.seatNumber} · ${t.label}`}
              >
                {s.seatNumber}
              </div>
            )
          })}
        </div>
        {sortedSeats.length === 0 && (
          <div className="py-10 text-center text-sm text-neutral-500 dark:text-neutral-400">
            좌석 데이터가 없습니다.
          </div>
        )}
        <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-neutral-500 dark:text-neutral-400">
          <span className="font-semibold uppercase tracking-wide">Legend</span>
          <span className="rounded-full border border-white/20 bg-white/40 px-2 py-0.5 dark:bg-white/10">Available</span>
          <span className="rounded-full border border-white/20 bg-amber-300/70 px-2 py-0.5 dark:bg-amber-500/20">Pending</span>
          <span className="rounded-full border border-white/20 bg-red-400/70 px-2 py-0.5 dark:bg-red-500/20">Sold</span>
        </div>
      </div>
    </div>
  )
}

