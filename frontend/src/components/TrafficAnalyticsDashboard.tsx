import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { LiquidGlassPanel } from './LiquidGlassPanel'
import {
  api,
  type DashboardStatusDto,
  type NgrinderPerfResponse,
  type NgrinderStatusResponse,
} from '../api'

type Point = { time: string; tps: number; meanLatencyMs: number }
type SparkPoint = { time: string; value: number | null }

export function UserDashboard() {
  const nav = useNavigate()
  const [data, setData] = useState<Point[]>([])
  const [pingMs, setPingMs] = useState<number | null>(null)
  const [pingSeries, setPingSeries] = useState<SparkPoint[]>([])
  const [settledTotal, setSettledTotal] = useState<number | null>(null)
  const [settledSeries, setSettledSeries] = useState<SparkPoint[]>([])
  const [rtMode, setRtMode] = useState<'ws' | 'http'>('ws')
  const [deps, setDeps] = useState<DashboardStatusDto | null>(null)
  const [paymentMismatch, setPaymentMismatch] = useState<number | null>(null)

  useEffect(() => {
    const path = '/ws/metrics'
    const explicit = import.meta.env.VITE_WS_URL as string | undefined
    const url =
      explicit ||
      `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${path}`
    const ws = new WebSocket(url)
    let lastWsAt = Date.now()
    const maybeMarkAlive = () => {
      lastWsAt = Date.now()
      setRtMode('ws')
    }
    ws.onopen = () => maybeMarkAlive()
    ws.onmessage = (ev) => {
      try {
        const m = JSON.parse(ev.data) as {
          time?: string
          tps?: number
          meanLatencyMs?: number
        }
        const pt: Point = {
          time: m.time ? new Date(m.time).toLocaleTimeString() : '',
          tps: m.tps ?? 0,
          meanLatencyMs: m.meanLatencyMs ?? 0,
        }
        maybeMarkAlive()
        setData((prev) => {
          const next = [...prev, pt]
          return next.slice(-60)
        })
      } catch {
        /* ignore */
      }
    }
    const watchdog = window.setInterval(() => {
      // If no WS messages for a while, switch UI to HTTP fallback.
      if (Date.now() - lastWsAt > 2500) setRtMode('http')
    }, 1000)
    ws.onclose = () => setRtMode('http')
    ws.onerror = () => setRtMode('http')
    return () => {
      window.clearInterval(watchdog)
      ws.close()
    }
  }, [])

  useEffect(() => {
    if (rtMode !== 'http') return
    let cancelled = false
    const pull = async () => {
      try {
        const m = await api.dashboardRealtime()
        if (cancelled) return
        const pt: Point = {
          time: m.time ? new Date(m.time).toLocaleTimeString() : '',
          tps: m.tps ?? 0,
          meanLatencyMs: m.meanLatencyMs ?? 0,
        }
        setData((prev) => [...prev, pt].slice(-60))
      } catch {
        // ignore (keeps last chart)
      }
    }
    void pull()
    const t = window.setInterval(pull, 1000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [rtMode])

  useEffect(() => {
    let cancelled = false
    const pullDeps = async () => {
      try {
        const st = await api.dashboardStatus()
        if (!cancelled) setDeps(st)
      } catch {
        if (!cancelled) setDeps(null)
      }
    }
    void pullDeps()
    const t = window.setInterval(pullDeps, 15_000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    const pullSettled = async () => {
      try {
        const bm = await api.dashboardBusinessMetrics()
        if (cancelled) return
        const mm = bm.paymentRequestedMismatch
        if (typeof mm === 'number' && Number.isFinite(mm)) {
          setPaymentMismatch(Math.round(mm * 1000) / 1000)
        } else {
          setPaymentMismatch(null)
        }
        const s =
          bm.paymentSettledTotal ??
          (typeof bm.paymentSucceededTotal === 'number' || typeof bm.paymentFailedTotal === 'number'
            ? (bm.paymentSucceededTotal ?? 0) + (bm.paymentFailedTotal ?? 0)
            : null)
        if (typeof s === 'number' && Number.isFinite(s)) {
          setSettledTotal(Math.round(s))
          const t = new Date().toLocaleTimeString()
          setSettledSeries((prev) => [...prev, { time: t, value: s }].slice(-60))
        }
      } catch {
        if (!cancelled) {
          setPaymentMismatch(null)
          setSettledTotal(null)
          const t = new Date().toLocaleTimeString()
          setSettledSeries((prev) => [...prev, { time: t, value: null }].slice(-60))
        }
      }
    }
    void pullSettled()
    const settledTimer = window.setInterval(pullSettled, 2000)
    return () => {
      cancelled = true
      window.clearInterval(settledTimer)
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    const ping = async () => {
      const st = performance.now()
      try {
        await api.dashboardPing()
        const ms = Math.max(0, Math.round((performance.now() - st) * 10) / 10)
        if (!cancelled) {
          setPingMs(ms)
          const t = new Date().toLocaleTimeString()
          setPingSeries((prev) => [...prev, { time: t, value: ms }].slice(-60))
        }
      } catch {
        if (!cancelled) {
          setPingMs(null)
          const t = new Date().toLocaleTimeString()
          setPingSeries((prev) => [...prev, { time: t, value: null }].slice(-60))
        }
      }
    }
    void ping()
    const t = window.setInterval(ping, 3000)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [])

  const last = data[data.length - 1]
  const sparkMean = useMemo<SparkPoint[]>(
    () => data.map((p) => ({ time: p.time, value: p.meanLatencyMs })),
    [data],
  )
  const sparkTps = useMemo<SparkPoint[]>(
    () => data.map((p) => ({ time: p.time, value: p.tps })),
    [data],
  )

  return (
    <div className="mx-auto w-full max-w-6xl">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-lg font-semibold text-neutral-800 dark:text-neutral-100">대시보드</div>
          <div className="text-xs text-neutral-500 dark:text-neutral-400">
            실시간 TPS·지연·결제 정산·인프라 상태를 한 화면에서 봅니다.
          </div>
        </div>
        <div className="flex items-center gap-2">
          <a
            className="rounded-2xl bg-neutral-900/85 px-4 py-2 text-sm font-medium text-white shadow active:scale-[0.99] dark:bg-white/15"
            href="/grafana/d/ticketing-overview/ticketing-overview?orgId=1"
            target="_blank"
            rel="noreferrer"
          >
            Grafana 열기 ↗
          </a>
        </div>
      </div>

      <InfraDepsRow deps={deps} mismatch={paymentMismatch} />

      <div className="mt-4 grid gap-3 lg:grid-cols-2 xl:grid-cols-4">
        <MetricCard
          title="Ping"
          value={pingMs != null ? `${pingMs} ms` : '—'}
          sub={rtMode === 'ws' ? '실시간 WS' : 'HTTP 폴링'}
          data={pingSeries}
          stroke="#007AFF"
          unit="ms"
        />
        <MetricCard
          title="정산(settled)"
          value={settledTotal != null ? `${settledTotal} 건` : '—'}
          sub="성공+실패 누적"
          data={settledSeries}
          stroke="#22C55E"
          unit="건"
        />
        <MetricCard
          title="평균 응답 시간"
          value={last ? `${Math.max(0, Math.round(last.meanLatencyMs * 10) / 10)} ms` : '—'}
          sub="http.server.requests mean"
          data={sparkMean}
          stroke="#FF3B30"
          unit="ms"
        />
        <MetricCard
          title="TPS"
          value={last ? `${Math.round((last.tps ?? 0) * 10) / 10}` : '—'}
          sub="WebSocket 스냅샷"
          data={sparkTps}
          stroke="#8B5CF6"
          unit="req/s"
        />
      </div>

      <div className="mt-6">
        <div className="mb-2 flex items-end justify-between gap-3">
          <div>
            <div className="text-sm font-semibold text-neutral-800 dark:text-neutral-100">테스트</div>
            <div className="text-xs text-neutral-500 dark:text-neutral-400">
              대시보드 아래에서 바로 트래픽을 생성하고 결과를 확인합니다.
            </div>
          </div>
          <button
            type="button"
            className="rounded-2xl border border-white/25 bg-white/15 px-3 py-2 text-xs font-semibold text-neutral-700 backdrop-blur hover:bg-white/25 active:scale-[0.99] dark:text-neutral-100"
            onClick={() => nav('/dashboard/dev')}
          >
            테스트 화면 크게 보기
          </button>
        </div>
        <NgrinderLoadTestPanel />
      </div>
    </div>
  )
}

function InfraDepsRow({
  deps,
  mismatch,
}: {
  deps: DashboardStatusDto | null
  mismatch: number | null
}) {
  const pill = (label: string, ok: boolean | undefined, ms: number | undefined) => (
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
  return (
    <div className="flex flex-col gap-2 rounded-2xl border border-white/20 bg-white/5 p-3 dark:border-white/10 dark:bg-black/20">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
          인프라
        </span>
        {deps?.deps
          ? pill('MySQL', deps.deps.mysql?.ok, deps.deps.mysql?.latencyMs)
          : null}
        {deps?.deps ? pill('Redis', deps.deps.redis?.ok, deps.deps.redis?.latencyMs) : null}
        {deps?.deps ? pill('Rabbit', deps.deps.rabbitmq?.ok, deps.deps.rabbitmq?.latencyMs) : null}
        {deps?.deps ? pill('Kafka', deps.deps.kafka?.ok, deps.deps.kafka?.latencyMs) : null}
        {!deps && <span className="text-xs text-neutral-500">상태를 불러오는 중…</span>}
      </div>
      <div className="flex flex-wrap items-center gap-2 text-[11px] text-neutral-600 dark:text-neutral-300">
        <span className="font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
          결제 카운터 정합
        </span>
        <span>
          requested − 기대값 차이:{' '}
          <span
            className={
              mismatch == null
                ? 'font-mono text-neutral-500'
                : Math.abs(mismatch) < 0.01
                  ? 'font-mono text-emerald-600 dark:text-emerald-400'
                  : 'font-mono text-amber-700 dark:text-amber-300'
            }
          >
            {mismatch != null ? String(mismatch) : '—'}
          </span>
        </span>
        <span className="text-neutral-500">(백엔드 /api/dashboard/business-metrics)</span>
      </div>
    </div>
  )
}

function MetricCard({
  title,
  value,
  sub,
  data,
  stroke,
  unit,
}: {
  title: string
  value: string
  sub: string
  data: SparkPoint[]
  stroke: string
  unit: string
}) {
  const chartData = useMemo(
    () => data.filter((p) => p.value != null && typeof p.value === 'number' && Number.isFinite(p.value)),
    [data],
  )
  return (
    <div className="rounded-3xl border border-white/25 bg-white/10 p-5 shadow-sm backdrop-blur dark:border-white/10 dark:bg-black/20">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div>
          <div className="text-xs font-semibold tracking-wide text-neutral-500 dark:text-neutral-400">
            {title}
          </div>
          <div className="mt-1 text-2xl font-semibold tracking-tight text-neutral-900 dark:text-white">
            {value}
          </div>
        </div>
        <div className="text-xs text-neutral-500 dark:text-neutral-400">{sub}</div>
      </div>
      <div className="relative h-28 w-full rounded-2xl border border-white/20 bg-white/5 p-2 dark:border-white/10 dark:bg-black/20">
        <div className="pointer-events-none absolute right-3 top-2 text-[10px] font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
          {unit}
        </div>
        {chartData.length === 0 ? (
          <div className="flex h-full items-center justify-center text-xs text-neutral-500 dark:text-neutral-400">
            스파크라인 데이터 없음
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chartData} margin={{ top: 6, right: 10, left: 6, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.12)" vertical={false} />
              <XAxis
                dataKey="time"
                interval="preserveStartEnd"
                tick={{ fontSize: 10, fill: 'rgba(115,115,115,0.9)' }}
                axisLine={false}
                tickLine={false}
                minTickGap={24}
              />
              <YAxis
                domain={['auto', 'auto']}
                tick={{ fontSize: 10, fill: 'rgba(115,115,115,0.9)' }}
                axisLine={false}
                tickLine={false}
                width={42}
                tickFormatter={(v) => {
                  if (typeof v !== 'number' || !Number.isFinite(v)) return ''
                  const x = Math.round(v * 10) / 10
                  return String(x)
                }}
              />
              <Tooltip
                formatter={(v) =>
                  typeof v === 'number' ? [`${Math.round(v * 10) / 10} ${unit}`, title] : ['—', title]
                }
                contentStyle={{
                  background: 'rgba(17, 17, 17, 0.75)',
                  border: '1px solid rgba(255,255,255,0.12)',
                  borderRadius: 16,
                  boxShadow: '0 12px 40px rgba(0,0,0,0.35)',
                }}
                labelStyle={{ color: 'rgba(255,255,255,0.85)' }}
              />
              <Line type="monotone" dataKey="value" stroke={stroke} strokeWidth={2.2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  )
}

export function DeveloperDashboard() {
  return (
    <div className="mx-auto w-full max-w-none">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm text-neutral-600 dark:text-neutral-300">
          테스트 화면: 트래픽 생성(nGrinder)
        </div>
        <a
          className="text-xs font-medium text-[#007AFF] hover:underline"
          href="/grafana/d/ticketing-overview/ticketing-overview?orgId=1"
          target="_blank"
          rel="noreferrer"
        >
          Grafana 열기 ↗
        </a>
      </div>
      <NgrinderLoadTestPanel />
    </div>
  )
}

function StatusCard({
  title,
  value,
  sub,
  tone,
}: {
  title: string
  value: string
  sub?: string
  tone: 'blue' | 'green' | 'red' | 'amber'
}) {
  const toneCls =
    tone === 'blue'
      ? 'text-[#007AFF]'
      : tone === 'green'
        ? 'text-emerald-600 dark:text-emerald-300'
        : tone === 'red'
          ? 'text-red-600 dark:text-red-300'
          : 'text-amber-700 dark:text-amber-300'
  return (
    <LiquidGlassPanel className="p-4">
      <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
        {title}
      </div>
      <div className={`mt-2 text-lg font-semibold ${toneCls}`}>{value}</div>
      <div className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">{sub ?? ' '}</div>
    </LiquidGlassPanel>
  )
}

type PerfPoint = {
  x: string
  tps: number | null
}

type BizPoint = {
  x: string
  requested: number | null
  settled: number | null
  inflight: number | null
  queue: number | null
  sleeping: number | null
  sleepMsTotal: number | null
}

function toPerfSeries(p: NgrinderPerfResponse): PerfPoint[] {
  const tps = p.TPS?.TPS ?? []
  const maxLen = tps.length
  const interval = p.chartInterval ?? 2
  const out: PerfPoint[] = []
  for (let i = 0; i < maxLen; i++) {
    out.push({
      x: `${i * interval}s`,
      tps: tps[i] ?? null,
    })
  }
  return out
}

function stripStatusMessage(html: string): string {
  return html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .trim()
}

function NgrinderLoadTestPanel() {
  const [requestedTarget, setRequestedTarget] = useState<number>(200)
  const [actionBusy, setActionBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [biz, setBiz] = useState<{
    paymentRequestedTotal?: number
    paymentSucceededTotal?: number
    paymentFailedTotal?: number
    paymentSettledTotal?: number
    paymentInflight?: number
    paymentQueueDepth?: number
    paymentProcessing?: number
    paymentWorkersSleeping?: number
    paymentWorkerSleepMsTotal?: number
    paymentRequestedMismatch?: number
  } | null>(null)

  const [testId, setTestId] = useState<number | null>(null)
  const [status, setStatus] = useState<NgrinderStatusResponse | null>(null)
  const [perf, setPerf] = useState<NgrinderPerfResponse | null>(null)
  const [bizSeries, setBizSeries] = useState<BizPoint[]>([])
  const [logsCollected, setLogsCollected] = useState(false)
  const [logsCollecting, setLogsCollecting] = useState(false)
  const [baselineRequested, setBaselineRequested] = useState<number | null>(null)
  const [baselineSucceeded, setBaselineSucceeded] = useState<number | null>(null)
  const [baselineFailed, setBaselineFailed] = useState<number | null>(null)

  const prevRequestedDeltaRef = useRef<number | null>(null)
  const flatRequestedDeltaTicksRef = useRef(0)

  const refresh = async (id: number) => {
    try {
      const [st, pf, bm] = await Promise.all([
        api.ngrinderStatus(id),
        api.ngrinderPerf(id, 'TPS', 900),
        api.dashboardBusinessMetrics().catch(() => null),
      ])
      setStatus(st)
      setPerf(pf)
      if (bm) {
        setBiz({
          paymentRequestedTotal: bm.paymentRequestedTotal,
          paymentSucceededTotal: bm.paymentSucceededTotal,
          paymentFailedTotal: bm.paymentFailedTotal,
          paymentSettledTotal: bm.paymentSettledTotal,
          paymentInflight: bm.paymentInflight,
          paymentQueueDepth: bm.paymentQueueDepth,
          paymentProcessing: bm.paymentProcessing,
          paymentWorkersSleeping: bm.paymentWorkersSleeping,
          paymentWorkerSleepMsTotal: bm.paymentWorkerSleepMsTotal,
          paymentRequestedMismatch: bm.paymentRequestedMismatch,
        })
      }
      if (bm) {
        const now = new Date().toLocaleTimeString()
        setBizSeries((prev) => {
          const p: BizPoint = {
            x: now,
            requested: bm.paymentRequestedTotal ?? null,
            settled: bm.paymentSettledTotal ?? null,
            inflight: bm.paymentInflight ?? null,
            queue: bm.paymentQueueDepth ?? null,
            sleeping: bm.paymentWorkersSleeping ?? null,
            sleepMsTotal: bm.paymentWorkerSleepMsTotal ?? null,
          }
          const last = prev[prev.length - 1]
          if (
            last &&
            last.requested === p.requested &&
            last.settled === p.settled &&
            last.inflight === p.inflight &&
            last.queue === p.queue &&
            last.sleeping === p.sleeping &&
            last.sleepMsTotal === p.sleepMsTotal
          ) {
            return prev
          }
          return [...prev, p].slice(-180)
        })
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : '상태 조회 실패')
    }
  }

  useEffect(() => {
    if (!testId) return
    void refresh(testId)
    const t = window.setInterval(() => void refresh(testId), 2000)
    return () => window.clearInterval(t)
  }, [testId])

  const series = useMemo(() => (perf ? toPerfSeries(perf) : []), [perf])

  const start = async () => {
    setActionBusy(true)
    setErr(null)
    try {
      // capture current settled total as baseline, so we can show delta during the test
      const bm = await api.dashboardBusinessMetrics().catch(() => null)
      const baseReq = bm?.paymentRequestedTotal ?? null
      const baseSucc = bm?.paymentSucceededTotal ?? null
      const baseFail = bm?.paymentFailedTotal ?? null
      const base =
        bm?.paymentSettledTotal ??
        (bm?.paymentSucceededTotal != null || bm?.paymentFailedTotal != null
          ? (bm?.paymentSucceededTotal ?? 0) + (bm?.paymentFailedTotal ?? 0)
          : null)
      void base
      setBaselineRequested(typeof baseReq === 'number' && Number.isFinite(baseReq) ? baseReq : null)
      setBaselineSucceeded(typeof baseSucc === 'number' && Number.isFinite(baseSucc) ? baseSucc : null)
      setBaselineFailed(typeof baseFail === 'number' && Number.isFinite(baseFail) ? baseFail : null)

      const created = await api.ngrinderStartPaymentRequestedCount(Math.max(1, Math.floor(requestedTarget)))
      const id = typeof created?.id === 'number' ? created.id : null
      setTestId(id)
      setStatus(null)
      setPerf(null)
      setBizSeries([])
      setLogsCollected(false)
      setLogsCollecting(false)
      if (id) await refresh(id)
    } catch (e) {
      setErr(e instanceof Error ? e.message : '실행 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const stop = async () => {
    if (!testId) return
    setActionBusy(true)
    setErr(null)
    try {
      await api.ngrinderStop(testId)
      await refresh(testId)
    } catch (e) {
      setErr(e instanceof Error ? e.message : '중지 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const statusText = status?.message ? stripStatusMessage(status.message) : ''
  const statusCounts = useMemo(() => parseStatusCounts(statusText), [statusText])
  const finished = status?.status?.name === 'FINISHED'

  // Define "test end" as: FINISHED status + logs endpoint successfully fetched at least once.
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

  const issued = Math.max(1, Math.floor(requestedTarget))
  const success = statusCounts.success
  const fail = statusCounts.fail
  const settled = success != null || fail != null ? (success ?? 0) + (fail ?? 0) : null
  void issued

  const paymentRequested = biz?.paymentRequestedTotal ?? null
  const paymentSucceeded = biz?.paymentSucceededTotal ?? null
  const paymentFailed = biz?.paymentFailedTotal ?? null
  const paymentSettled =
    biz?.paymentSettledTotal ??
    (paymentSucceeded != null || paymentFailed != null ? (paymentSucceeded ?? 0) + (paymentFailed ?? 0) : null)
  const paymentIntegrityOk =
    paymentRequested != null && paymentSettled != null ? Math.round(paymentRequested) === Math.round(paymentSettled) : null

  const paymentRequestedDelta =
    paymentRequested != null && baselineRequested != null
      ? Math.max(0, Math.round(paymentRequested - baselineRequested))
      : null

  const paymentSucceededDelta =
    paymentSucceeded != null && baselineSucceeded != null
      ? Math.max(0, Math.round(paymentSucceeded - baselineSucceeded))
      : null

  const paymentFailedDelta =
    paymentFailed != null && baselineFailed != null
      ? Math.max(0, Math.round(paymentFailed - baselineFailed))
      : null

  const paymentSettledDelta =
    paymentSucceededDelta != null || paymentFailedDelta != null
      ? (paymentSucceededDelta ?? 0) + (paymentFailedDelta ?? 0)
      : null

  const paymentInflightEstimateDelta =
    paymentRequestedDelta != null && paymentSettledDelta != null
      ? Math.max(0, paymentRequestedDelta - paymentSettledDelta)
      : null

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

  // payment success/fail delta are derived from separate baselines captured at test start.

  const avgTps = useMemo(() => {
    const xs = series.map((p) => p.tps).filter((x): x is number => typeof x === 'number' && Number.isFinite(x))
    if (xs.length === 0) return null
    return xs.reduce((a, b) => a + b, 0) / xs.length
  }, [series])
  const avgThroughputPerMin = avgTps != null ? avgTps * 60 : null

  return (
    <div className="flex w-full flex-col gap-4">
      <LiquidGlassPanel className="flex w-full flex-col p-5">
        <div className="rounded-2xl border border-white/20 bg-white/5 p-4 dark:border-white/10 dark:bg-black/20">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <h3 className="font-inter text-xl font-bold tracking-tight text-neutral-800 dark:text-white">
                nGrinder 부하 테스트 (발행(requested) 건수 지정)
              </h3>
              <p className="mt-1 max-w-xl text-xs text-neutral-600 dark:text-neutral-300">
                서버의 결제요청 발행(requested) 증가분이 목표치에 도달하면 종료됩니다. (정확히 N은 보장 어렵지만 N 근처에서 멈춤)
              </p>
              <a
                className="mt-2 inline-block text-xs font-medium text-[#007AFF]"
                href="http://localhost:19080"
                target="_blank"
                rel="noreferrer"
              >
                nGrinder Controller 열기 ↗
              </a>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <input
                className="w-[10.5rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-sm text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
                value={String(requestedTarget)}
                onChange={(e) => setRequestedTarget(Number(e.target.value || 0))}
                placeholder="발행(requested) 수"
                inputMode="numeric"
                title="목표 발행(requested) 수"
              />
              <button
                type="button"
                className="rounded-xl bg-indigo-600/90 px-4 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
                onClick={() => void start()}
                disabled={actionBusy || requestedTarget < 1}
              >
                실행
              </button>
              <button
                type="button"
                className="rounded-xl bg-red-600/85 px-4 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
                onClick={() => void stop()}
                disabled={actionBusy || !testId}
              >
                중지
              </button>
            </div>
          </div>
        </div>

        {err && <p className="mt-3 text-sm text-red-500">{err}</p>}

        {requestedDeltaLooksStuck && (
          <div className="mt-3 rounded-xl border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-900 dark:text-amber-100">
            Requested(Δ)가 여러 번 갱신됐는데도 값이 그대로입니다. 아래 파이프라인 지표를 확인하세요. 흔한 원인: 결제 워커 시뮬레이션 슬립으로 큐 적체, nGrinder progress 대기 타임아웃(새 테스트는
            progressMaxWaitSec=120), 로컬 단독 실행 시 IP rate limit(기본 10/s)로 예약 HTTP 429.
          </div>
        )}

        <div className="mt-4 grid gap-3 lg:grid-cols-4">
          <StatusCard
            title="Status"
            value={status?.status?.name ?? '—'}
            sub="자동 종료까지 실행 중"
            tone={status?.status?.name === 'TESTING' ? 'green' : 'amber'}
          />
          <StatusCard
            title="Requested (Δ)"
            value={paymentRequestedDelta != null ? String(paymentRequestedDelta) : '—'}
            sub="서버 메트릭 기준(이번 테스트 증가분)"
            tone="blue"
          />
          <StatusCard
            title="성공"
            value={paymentSucceededDelta != null ? String(paymentSucceededDelta) : '—'}
            sub="서버 메트릭 기준(이번 테스트 증가분)"
            tone="green"
          />
          <StatusCard
            title="실패"
            value={paymentFailedDelta != null ? String(paymentFailedDelta) : '—'}
            sub="서버 메트릭 기준(이번 테스트 증가분)"
            tone="red"
          />
        </div>

        <div className="mt-3 text-[11px] font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
          진행 중 파이프라인 (서버)
        </div>
        <div className="mt-1 grid gap-3 lg:grid-cols-4">
          <StatusCard
            title="Inflight (관측)"
            value={biz?.paymentInflight != null ? String(Math.round(biz.paymentInflight)) : '—'}
            sub="processing(DB) + Rabbit 대기"
            tone="blue"
          />
          <StatusCard
            title="Queue depth"
            value={biz?.paymentQueueDepth != null ? String(Math.round(biz.paymentQueueDepth)) : '—'}
            sub="아직 워커에 전달 안 된 건"
            tone="amber"
          />
          <StatusCard
            title="Processing (DB)"
            value={biz?.paymentProcessing != null ? String(Math.round(biz.paymentProcessing)) : '—'}
            sub="PROCESSING 결제 행"
            tone="amber"
          />
          <StatusCard
            title="Workers sleeping"
            value={biz?.paymentWorkersSleeping != null ? String(Math.round(biz.paymentWorkersSleeping)) : '—'}
            sub="시뮬레이션 슬립 중인 워커"
            tone="amber"
          />
        </div>
        <div className="mt-2 grid gap-3 lg:grid-cols-2">
          <StatusCard
            title="Requested mismatch"
            value={
              biz?.paymentRequestedMismatch != null ? String(Math.round(biz.paymentRequestedMismatch * 10) / 10) : '—'
            }
            sub="0에 가까우면 카운터·큐·DB가 맞음"
            tone={biz?.paymentRequestedMismatch != null && Math.abs(biz.paymentRequestedMismatch) > 2 ? 'red' : 'green'}
          />
          <StatusCard
            title="TPS (nGrinder)"
            value={avgTps != null ? String(Math.round(avgTps * 10) / 10) : '—'}
            sub="위 차트와 동일(샘플 평균)"
            tone="blue"
          />
        </div>

        <div className="mt-3 min-h-[12rem] w-full rounded-xl border border-white/15 bg-white/5 p-2 dark:bg-black/20">
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={bizSeries} margin={{ top: 8, right: 10, left: 6, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.2)" vertical={false} />
              <XAxis dataKey="x" stroke="rgba(163,163,163,0.8)" tick={{ fontSize: 11, fontFamily: 'Inter' }} />
              <YAxis stroke="rgba(163,163,163,0.8)" tick={{ fontSize: 11, fontFamily: 'Inter' }} />
              <Tooltip
                contentStyle={{
                  backgroundColor: 'rgba(255, 255, 255, 0.85)',
                  backdropFilter: 'blur(8px)',
                  borderRadius: '12px',
                  border: '1px solid rgba(255,255,255,0.4)',
                  boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
                }}
              />
              <Line type="monotone" dataKey="requested" name="requested(total)" stroke="#007AFF" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="settled" name="settled(total)" stroke="#22C55E" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="inflight" name="inflight" stroke="#A855F7" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="queue" name="queueDepth" stroke="#F59E0B" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="sleeping" name="sleepingWorkers" stroke="#FF3B30" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        <MiniPanel title="요약 (테스트 종료 후)">
          {!finished ? (
            <div className="text-xs text-neutral-500 dark:text-neutral-400">테스트가 종료되면 요약을 표시합니다.</div>
          ) : !logsCollected ? (
            <div className="text-xs text-neutral-500 dark:text-neutral-400">
              로그 수집 중… {logsCollecting ? '(확인 중)' : '(재시도 대기)'}
            </div>
          ) : (
            <div className="grid gap-2 text-sm text-neutral-800 dark:text-neutral-200">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
                  HTTP 요청 결과 (nGrinder)
                </div>
                <div className="text-neutral-500 dark:text-neutral-400">결제건수와 1:1 비교 불가</div>
              </div>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
                  HTTP Success / HTTP Fail / HTTP Total
                </div>
                <div className="font-mono text-xs">
                  {success ?? '—'} / {fail ?? '—'} / {settled ?? '—'}
                </div>
              </div>

              <div className="mt-2 flex flex-wrap items-center justify-between gap-3">
                <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
                  정합성 여부 (서버 결제정산)
                </div>
                <div className={paymentIntegrityOk === true ? 'text-emerald-600 dark:text-emerald-300' : paymentIntegrityOk === false ? 'text-red-600 dark:text-red-300' : 'text-neutral-500 dark:text-neutral-400'}>
                  {paymentIntegrityOk === true ? 'OK' : paymentIntegrityOk === false ? 'Mismatch' : '—'}
                </div>
              </div>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
                  paymentRequestedΔ / paymentSettledΔ / inflight(Δ, 추정)
                </div>
                <div className="font-mono text-xs">
                  {paymentRequestedDelta ?? '—'} / {paymentSettledDelta ?? '—'} / {paymentInflightEstimateDelta ?? '—'}
                </div>
              </div>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
                  Throughput (avg, /min)
                </div>
                <div className="font-mono text-xs">{avgThroughputPerMin != null ? Math.round(avgThroughputPerMin * 10) / 10 : '—'}</div>
              </div>
            </div>
          )}
        </MiniPanel>
      </LiquidGlassPanel>
    </div>
  )
}

function MiniPanel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mt-3 flex min-h-[5.5rem] flex-col overflow-hidden rounded-2xl border border-white/15 bg-white/5 p-3 dark:bg-black/20">
      <div className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
        {title}
      </div>
      <div className="min-h-0 flex-1">{children}</div>
    </div>
  )
}

function parseStatusCounts(text: string): { success: number | null; fail: number | null } {
  const n = (s: string | undefined): number | null => {
    if (!s) return null
    const x = Number(s.replace(/,/g, ''))
    return Number.isFinite(x) ? x : null
  }

  // nGrinder status message varies by version; try several common labels.
  const success =
    n(text.match(/\bSuccess(?:es)?\b[^0-9]*([0-9][0-9,]*)/i)?.[1]) ??
    n(text.match(/\bSuccessful\b[^0-9]*([0-9][0-9,]*)/i)?.[1]) ??
    n(text.match(/\bOK\b[^0-9]*([0-9][0-9,]*)/i)?.[1])

  const fail =
    n(text.match(/\bFail(?:ed|ures?)?\b[^0-9]*([0-9][0-9,]*)/i)?.[1]) ??
    n(text.match(/\bError(?:s)?\b[^0-9]*([0-9][0-9,]*)/i)?.[1])

  return { success, fail }
}
