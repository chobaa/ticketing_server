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

type Point = { time: string; tps: number; p99Latency: number; queueDepth: number }
type MiniPoint = {
  time: string
  pingMs: number | null
  mysqlMs: number | null
  redisMs: number | null
  kafkaMs: number | null
  rabbitMs: number | null
}

export function UserDashboard() {
  const nav = useNavigate()
  const [data, setData] = useState<Point[]>([])
  const [mini, setMini] = useState<MiniPoint[]>([])
  const [status, setStatus] = useState<DashboardStatusDto | null>(null)
  const [pingMs, setPingMs] = useState<number | null>(null)
  const pingMsRef = useRef<number | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [rtMode, setRtMode] = useState<'ws' | 'http'>('ws')

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
          p99Latency?: number
          queueDepth?: number
        }
        const pt: Point = {
          time: m.time ? new Date(m.time).toLocaleTimeString() : '',
          tps: m.tps ?? 0,
          p99Latency: m.p99Latency ?? 0,
          queueDepth: m.queueDepth ?? 0,
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
          p99Latency: m.p99Latency ?? 0,
          queueDepth: m.queueDepth ?? 0,
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
    const refresh = async () => {
      try {
        setErr(null)
        const st = await api.dashboardStatus()
        if (!cancelled) setStatus(st)
        if (!cancelled) {
          const t = new Date().toLocaleTimeString()
          setMini((prev) =>
            [
              ...prev,
              {
                time: t,
                pingMs: pingMsRef.current,
                mysqlMs: st.deps.mysql.latencyMs ?? null,
                redisMs: st.deps.redis.latencyMs ?? null,
                kafkaMs: st.deps.kafka.latencyMs ?? null,
                rabbitMs: st.deps.rabbitmq.latencyMs ?? null,
              },
            ].slice(-120),
          )
        }
      } catch (e) {
        if (!cancelled) setErr(e instanceof Error ? e.message : '상태 조회 실패')
      }
    }
    void refresh()
    const t = window.setInterval(refresh, 5000)
    return () => {
      cancelled = true
      window.clearInterval(t)
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
          pingMsRef.current = ms
          setPingMs(ms)
        }
      } catch {
        if (!cancelled) {
          pingMsRef.current = null
          setPingMs(null)
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

  return (
    <div className="mx-auto w-full max-w-6xl">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-lg font-semibold text-neutral-800 dark:text-neutral-100">대시보드</div>
          <div className="text-xs text-neutral-500 dark:text-neutral-400">
            서버 상태/핑/반응속도 및 실시간 처리 지표를 확인합니다.
          </div>
        </div>
        <button
          type="button"
          className="rounded-2xl bg-neutral-900/85 px-4 py-2 text-sm font-medium text-white shadow active:scale-[0.99] dark:bg-white/15"
          onClick={() => nav('/dashboard/dev')}
        >
          Grafana (개발자)
        </button>
      </div>

      {err && <p className="mb-3 text-sm text-red-500">{err}</p>}

      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatusCard title="서버 핑" value={pingMs != null ? `${pingMs} ms` : '—'} tone="blue" />
        <StatusCard
          title="MySQL"
          value={status?.deps.mysql.ok ? 'UP' : 'DOWN'}
          sub={status ? `${status.deps.mysql.latencyMs}ms` : '—'}
          tone={status?.deps.mysql.ok ? 'green' : 'red'}
        />
        <StatusCard
          title="Redis"
          value={status?.deps.redis.ok ? 'UP' : 'DOWN'}
          sub={status ? `${status.deps.redis.latencyMs}ms` : '—'}
          tone={status?.deps.redis.ok ? 'green' : 'red'}
        />
        <StatusCard
          title="Kafka / RabbitMQ"
          value={
            status
              ? `${status.deps.kafka.ok ? 'UP' : 'DOWN'} / ${status.deps.rabbitmq.ok ? 'UP' : 'DOWN'}`
              : '—'
          }
          sub={status ? `K:${status.deps.kafka.latencyMs}ms · R:${status.deps.rabbitmq.latencyMs}ms` : '—'}
          tone={status && status.deps.kafka.ok && status.deps.rabbitmq.ok ? 'green' : 'amber'}
        />
      </div>

      <LiquidGlassPanel className="h-96 w-full" contentClassName="flex h-full flex-col p-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
          <h3 className="font-inter text-xl font-bold tracking-tight text-neutral-800 dark:text-white">
            실시간 상태
          </h3>
          <div className="flex flex-wrap gap-4 text-sm font-medium">
            <span className="text-[#007AFF]">TPS: {last?.tps ?? 0}</span>
            <span className="text-[#FF3B30]">p99: {last ? `${last.p99Latency}ms` : '—'}</span>
            <span className="text-violet-500">Queue: {last?.queueDepth ?? 0}</span>
            <span className="text-xs text-neutral-500 dark:text-neutral-400">
              {rtMode === 'ws' ? 'WS' : 'HTTP 폴백'}
            </span>
          </div>
        </div>
        <div className="grid min-h-0 w-full flex-1 gap-4 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
          <div className="min-h-0 w-full rounded-2xl border border-white/15 bg-white/5 p-2 dark:bg-black/20">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={data} margin={{ top: 6, right: 8, left: -16, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.2)" vertical={false} />
                <XAxis
                  dataKey="time"
                  stroke="rgba(163,163,163,0.8)"
                  tick={{ fontSize: 11, fontFamily: 'Inter' }}
                />
                <YAxis
                  yAxisId="tps"
                  stroke="rgba(163,163,163,0.8)"
                  tick={{ fontSize: 11, fontFamily: 'Inter' }}
                  width={46}
                  allowDecimals={false}
                />
                <YAxis
                  yAxisId="ms"
                  orientation="right"
                  stroke="rgba(163,163,163,0.8)"
                  tick={{ fontSize: 11, fontFamily: 'Inter' }}
                  width={58}
                />
                <YAxis yAxisId="queue" orientation="right" hide />
                <Tooltip
                  contentStyle={{
                    backgroundColor: 'rgba(255, 255, 255, 0.85)',
                    backdropFilter: 'blur(8px)',
                    borderRadius: '12px',
                    border: '1px solid rgba(255,255,255,0.4)',
                    boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
                  }}
                />
                <Line yAxisId="tps" type="monotone" dataKey="tps" name="TPS" stroke="#007AFF" strokeWidth={3} dot={false} />
                <Line
                  yAxisId="ms"
                  type="monotone"
                  dataKey="p99Latency"
                  name="p99 ms"
                  stroke="#FF3B30"
                  strokeWidth={2}
                  dot={false}
                />
                <Line
                  yAxisId="queue"
                  type="monotone"
                  dataKey="queueDepth"
                  name="Queue"
                  stroke="#8B5CF6"
                  strokeWidth={2}
                  dot={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="grid min-h-0 gap-3">
            <MiniPanel title="Ping (ms)">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={mini} margin={{ top: 6, right: 8, left: -22, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.18)" vertical={false} />
                  <XAxis dataKey="time" hide />
                  <YAxis hide />
                  <Tooltip />
                  <Line type="monotone" dataKey="pingMs" stroke="#007AFF" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </MiniPanel>

            <MiniPanel title="Dependencies latency (ms)">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={mini} margin={{ top: 6, right: 8, left: -22, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.18)" vertical={false} />
                  <XAxis dataKey="time" hide />
                  <YAxis hide />
                  <Tooltip />
                  <Line type="monotone" dataKey="mysqlMs" name="MySQL" stroke="#10B981" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="redisMs" name="Redis" stroke="#F59E0B" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="kafkaMs" name="Kafka" stroke="#8B5CF6" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="rabbitMs" name="Rabbit" stroke="#EF4444" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </MiniPanel>

            <MiniPanel title="Queue depth">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={data} margin={{ top: 6, right: 8, left: -22, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.18)" vertical={false} />
                  <XAxis dataKey="time" hide />
                  <YAxis hide />
                  <Tooltip />
                  <Line type="monotone" dataKey="queueDepth" stroke="#8B5CF6" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </MiniPanel>
          </div>
        </div>
      </LiquidGlassPanel>
    </div>
  )
}

function GrafanaPanel() {
  // Default to provisioned dashboard; user can open in new tab.
  const src = '/grafana/d/ticketing-overview/ticketing-overview?orgId=1&kiosk=tv'
  return (
    <div className="relative left-1/2 right-1/2 -ml-[50vw] -mr-[50vw] w-screen">
      <LiquidGlassPanel
        className="h-[92vh] w-full overflow-hidden"
        contentClassName="flex h-full flex-col p-0"
      >
        <div className="flex items-center justify-between gap-2 border-b border-white/15 bg-white/20 px-4 py-3 text-sm backdrop-blur dark:bg-black/20">
          <div className="font-semibold text-neutral-800 dark:text-neutral-100">Grafana</div>
          <a
            className="text-xs font-medium text-[#007AFF] hover:underline"
            href={src}
            target="_blank"
            rel="noreferrer"
          >
            새 탭으로 열기
          </a>
        </div>
        <iframe title="Grafana" src={src} className="min-h-0 w-full flex-1" />
      </LiquidGlassPanel>
    </div>
  )
}

export function DeveloperDashboard() {
  const [tab, setTab] = useState<'loadtest' | 'grafana'>('grafana')
  return (
    <div className="mx-auto w-full max-w-none">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm text-neutral-600 dark:text-neutral-300">
          개발자 화면: 트래픽 생성(nGrinder) 및 Grafana 관측
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className={`rounded-full border px-4 py-1.5 text-sm backdrop-blur ${
              tab === 'grafana'
                ? 'border-white/40 bg-white/40 text-neutral-900 dark:bg-black/25 dark:text-white'
                : 'border-white/20 bg-white/10 text-neutral-700 dark:text-neutral-200'
            }`}
            onClick={() => setTab('grafana')}
          >
            Grafana
          </button>
          <button
            type="button"
            className={`rounded-full border px-4 py-1.5 text-sm backdrop-blur ${
              tab === 'loadtest'
                ? 'border-white/40 bg-white/40 text-neutral-900 dark:bg-black/25 dark:text-white'
                : 'border-white/20 bg-white/10 text-neutral-700 dark:text-neutral-200'
            }`}
            onClick={() => setTab('loadtest')}
          >
            인위적 트래픽 생성 (nGrinder)
          </button>
        </div>
      </div>

      {tab === 'grafana' ? <GrafanaPanel /> : <NgrinderLoadTestPanel />}
    </div>
  )
}

function MiniPanel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex min-h-[5.5rem] flex-col overflow-hidden rounded-2xl border border-white/15 bg-white/5 p-3 dark:bg-black/20">
      <div className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
        {title}
      </div>
      <div className="min-h-0 flex-1">{children}</div>
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
  throughput: number | null
}

function toPerfSeries(p: NgrinderPerfResponse): PerfPoint[] {
  const tps = p.TPS?.TPS ?? []
  const throughput = p.Throughput?.Throughput ?? []
  const maxLen = Math.max(tps.length, throughput.length)
  const interval = p.chartInterval ?? 2
  const out: PerfPoint[] = []
  for (let i = 0; i < maxLen; i++) {
    out.push({
      x: `${i * interval}s`,
      tps: tps[i] ?? null,
      throughput: throughput[i] ?? null,
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

function lastPerfPoint(series: PerfPoint[]): PerfPoint | undefined {
  for (let i = series.length - 1; i >= 0; i--) {
    const x = series[i]
    if (x.tps != null || x.throughput != null) return x
  }
  return undefined
}

function NgrinderLoadTestPanel() {
  const [requestCount, setRequestCount] = useState<number>(1000)
  const [actionBusy, setActionBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const [testId, setTestId] = useState<number | null>(null)
  const [status, setStatus] = useState<NgrinderStatusResponse | null>(null)
  const [perf, setPerf] = useState<NgrinderPerfResponse | null>(null)
  const [logFiles, setLogFiles] = useState<string[]>([])

  const refresh = async (id: number) => {
    try {
      const [st, pf, logs] = await Promise.all([
        api.ngrinderStatus(id),
        api.ngrinderPerf(id, 'TPS,Throughput', 900),
        api.ngrinderLogs(id).catch(() => [] as string[]),
      ])
      setStatus(st)
      setPerf(pf)
      setLogFiles(Array.isArray(logs) ? logs : [])
    } catch (e) {
      setErr(e instanceof Error ? e.message : '상태 조회 실패')
    }
  }

  useEffect(() => {
    if (!testId) return
    void refresh(testId)
    const t = window.setInterval(() => void refresh(testId), 2500)
    return () => window.clearInterval(t)
  }, [testId])

  const series = useMemo(() => (perf ? toPerfSeries(perf) : []), [perf])
  const last = lastPerfPoint(series)

  const start = async () => {
    setActionBusy(true)
    setErr(null)
    try {
      const created = await api.ngrinderStartRequestCount(Math.max(1, Math.floor(requestCount)))
      const id = typeof created?.id === 'number' ? created.id : null
      setTestId(id)
      setStatus(null)
      setPerf(null)
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

  return (
    <div className="flex w-full flex-col gap-4">
      <LiquidGlassPanel className="flex w-full flex-col p-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h3 className="font-inter text-xl font-bold tracking-tight text-neutral-800 dark:text-white">
              nGrinder 부하 테스트 (요청 개수 지정)
            </h3>
            <p className="mt-1 max-w-xl text-xs text-neutral-600 dark:text-neutral-300">
              reserve 요청을 정확히 입력한 개수만큼 발생시키면 스크립트가 즉시 종료(FINISHED)합니다.
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
              value={String(requestCount)}
              onChange={(e) => setRequestCount(Number(e.target.value || 0))}
              placeholder="요청 수"
              inputMode="numeric"
              title="발생시킬 reserve 요청 수"
            />
            <button
              type="button"
              className="rounded-xl bg-indigo-600/90 px-4 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
              onClick={() => void start()}
              disabled={actionBusy || requestCount < 1}
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

        {err && <p className="mt-3 text-sm text-red-500">{err}</p>}

        <div className="mt-4 grid gap-3 lg:grid-cols-4">
          <StatusCard title="Test ID" value={testId ? `#${testId}` : '—'} sub="생성된 nGrinder 테스트" tone="blue" />
          <StatusCard
            title="Status"
            value={status?.status?.name ?? '—'}
            sub="자동 종료까지 실행 중"
            tone={status?.status?.name === 'TESTING' ? 'green' : 'amber'}
          />
          <StatusCard title="TPS" value={last?.tps != null ? String(last.tps) : '—'} sub="최근 구간" tone="blue" />
          <StatusCard
            title="Throughput"
            value={last?.throughput != null ? String(last.throughput) : '—'}
            sub="최근 구간"
            tone="amber"
          />
        </div>

        <div className="mt-3 min-h-[12rem] w-full rounded-xl border border-white/15 bg-white/5 p-2 dark:bg-black/20">
          <ResponsiveContainer width="100%" height={240}>
            <LineChart data={series} margin={{ top: 5, right: 8, left: -16, bottom: 0 }}>
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
              <Line type="monotone" dataKey="tps" name="TPS" stroke="#007AFF" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="throughput" name="Throughput" stroke="#8B5CF6" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        <div className="mt-3 grid gap-3 lg:grid-cols-2">
          <MiniPanel title="Status message (nGrinder)">
            <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed text-neutral-800 dark:text-neutral-200">
              {statusText || '—'}
            </pre>
          </MiniPanel>
          <MiniPanel title="Log files">
            {logFiles.length === 0 ? (
              <div className="text-xs text-neutral-500 dark:text-neutral-400">—</div>
            ) : (
              <ul className="max-h-48 list-inside list-disc overflow-auto text-xs text-neutral-700 dark:text-neutral-300">
                {logFiles.map((f) => (
                  <li key={f}>{f}</li>
                ))}
              </ul>
            )}
          </MiniPanel>
        </div>
      </LiquidGlassPanel>
    </div>
  )
}
