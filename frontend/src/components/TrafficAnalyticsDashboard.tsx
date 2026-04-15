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
  type NgrinderTestItem,
  type NgrinderTestsListResponse,
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
  errors: number | null
  meanMs: number | null
}

function toPerfSeries(p: NgrinderPerfResponse): PerfPoint[] {
  const tps = p.TPS?.TPS ?? []
  const errors = p.Errors?.Errors ?? []
  const mean = p.Mean_Test_Time?.['Mean_Test_Time_(ms)'] ?? []
  const maxLen = Math.max(tps.length, errors.length, mean.length)
  const interval = p.chartInterval ?? 2
  const out: PerfPoint[] = []
  for (let i = 0; i < maxLen; i++) {
    out.push({
      x: `${i * interval}s`,
      tps: tps[i] ?? null,
      errors: errors[i] ?? null,
      meanMs: mean[i] ?? null,
    })
  }
  return out
}

function rowStatusName(t: NgrinderTestItem): string {
  const s = t.status
  if (s == null) return '—'
  if (typeof s === 'string') return s
  return s.name ?? '—'
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
    if (x.tps != null || x.meanMs != null || x.errors != null) return x
  }
  return undefined
}

function computeHasNextPage(
  meta: NgrinderTestsListResponse | null,
  testsLen: number,
  pageSize: number,
): boolean {
  if (!meta) return false
  if (typeof meta.hasNext === 'boolean') return meta.hasNext
  if (meta.totalElements != null && meta.number != null) {
    const s = meta.size ?? pageSize
    if (s > 0) return (meta.number + 1) * s < meta.totalElements
  }
  return testsLen >= pageSize
}

function NgrinderLoadTestPanel() {
  const [listPage, setListPage] = useState(0)
  const pageSize = 15
  const [listMeta, setListMeta] = useState<NgrinderTestsListResponse | null>(null)
  const [tests, setTests] = useState<NgrinderTestItem[]>([])
  const [listLoading, setListLoading] = useState(false)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [status, setStatus] = useState<NgrinderStatusResponse | null>(null)
  const [perf, setPerf] = useState<NgrinderPerfResponse | null>(null)
  const [logFiles, setLogFiles] = useState<string[]>([])
  const [err, setErr] = useState<string | null>(null)
  const [actionBusy, setActionBusy] = useState(false)
  const [baseUrl, setBaseUrl] = useState<string>('http://host.docker.internal:8080')
  const [allVusers, setAllVusers] = useState<number>(400)
  const [allDurationSec, setAllDurationSec] = useState<number>(30)
  const [allSeatPoolSize, setAllSeatPoolSize] = useState<number>(10)

  const refreshList = async () => {
    setListLoading(true)
    setErr(null)
    try {
      const res = await api.ngrinderTests(listPage, pageSize)
      setListMeta(res)
      setTests(Array.isArray(res.tests) ? res.tests : [])
    } catch (e) {
      setErr(e instanceof Error ? e.message : '목록을 불러오지 못했습니다.')
      setTests([])
      setListMeta(null)
    } finally {
      setListLoading(false)
    }
  }

  useEffect(() => {
    void refreshList()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listPage])

  const refreshDetail = async (id: number) => {
    try {
      const [st, pf, logs] = await Promise.all([
        api.ngrinderStatus(id),
        api.ngrinderPerf(id),
        api.ngrinderLogs(id).catch(() => [] as string[]),
      ])
      setStatus(st)
      setPerf(pf)
      setLogFiles(Array.isArray(logs) ? logs : [])
    } catch (e) {
      setErr(e instanceof Error ? e.message : '상세 조회 실패')
      setStatus(null)
      setPerf(null)
      setLogFiles([])
    }
  }

  useEffect(() => {
    if (selectedId == null) {
      setStatus(null)
      setPerf(null)
      setLogFiles([])
      return
    }
    void refreshDetail(selectedId)
    const t = window.setInterval(() => {
      void refreshDetail(selectedId)
    }, 2500)
    return () => window.clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  const series = useMemo(() => (perf ? toPerfSeries(perf) : []), [perf])
  const last = lastPerfPoint(series)
  const totalElements = listMeta?.totalElements
  const hasNextPage = computeHasNextPage(listMeta, tests.length, pageSize)

  const selectRow = (id: number) => {
    setSelectedId(id)
    setErr(null)
  }

  const runReady = async (id: number) => {
    setActionBusy(true)
    setErr(null)
    try {
      await api.ngrinderReady(id)
      await refreshList()
      if (selectedId === id) await refreshDetail(id)
    } catch (e) {
      setErr(e instanceof Error ? e.message : '시작(READY) 요청 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const runClone = async (id: number) => {
    setActionBusy(true)
    setErr(null)
    try {
      const created = await api.ngrinderCloneStart(id)
      const newId = created.id
      await refreshList()
      if (typeof newId === 'number' && newId > 0) {
        setSelectedId(newId)
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : '복제 실행 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const runStop = async (id: number) => {
    setActionBusy(true)
    setErr(null)
    try {
      await api.ngrinderStop(id)
      await refreshList()
      if (selectedId === id) await refreshDetail(id)
    } catch (e) {
      setErr(e instanceof Error ? e.message : '중지 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const deleteAll = async () => {
    setActionBusy(true)
    setErr(null)
    try {
      await api.ngrinderDeleteAll()
      setSelectedId(null)
      setListPage(0)
      await refreshList()
    } catch (e) {
      setErr(e instanceof Error ? e.message : '전체 삭제 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const startPreset = async (key: string) => {
    setActionBusy(true)
    setErr(null)
    try {
      const created = await api.ngrinderStartPreset(key, { baseUrl })
      await refreshList()
      if (typeof created.id === 'number' && created.id > 0) setSelectedId(created.id)
    } catch (e) {
      setErr(e instanceof Error ? e.message : '프리셋 실행 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const startAllInOne = async () => {
    setActionBusy(true)
    setErr(null)
    try {
      const created = await api.ngrinderStartPreset('all', {
        baseUrl,
        vusers: allVusers,
        threads: allVusers,
        testDurationSec: allDurationSec,
        eventSeatCount: Math.max(allVusers, 50),
        seatPoolSize: allSeatPoolSize,
      })
      await refreshList()
      if (typeof created.id === 'number' && created.id > 0) setSelectedId(created.id)
    } catch (e) {
      setErr(e instanceof Error ? e.message : '올인원 실행 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const runAllPresets = async () => {
    setActionBusy(true)
    setErr(null)
    try {
      await api.ngrinderRunAllPresets(baseUrl)
      setListPage(0)
      await refreshList()
    } catch (e) {
      setErr(e instanceof Error ? e.message : '전체 실행 실패')
    } finally {
      setActionBusy(false)
    }
  }

  const logText = status?.message ? stripStatusMessage(status.message) : ''

  return (
    <div className="flex w-full flex-col gap-4">
      <LiquidGlassPanel className="flex w-full flex-col p-5">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3 className="font-inter text-xl font-bold tracking-tight text-neutral-800 dark:text-white">
              nGrinder 부하 테스트
            </h3>
            <p className="mt-1 max-w-xl text-xs text-neutral-600 dark:text-neutral-300">
              테스트 목록에서 항목을 선택하면 상태·로그·TPS 그래프를 주기적으로 갱신합니다. SAVED 테스트는 READY로 올린 뒤
              에이전트가 실행하고, 종료된 테스트는 복제 실행으로 다시 돌릴 수 있습니다.
            </p>
            <div className="mt-3 max-w-3xl rounded-2xl border border-white/20 bg-white/10 p-3 text-[12px] leading-relaxed text-neutral-700 backdrop-blur dark:bg-black/15 dark:text-neutral-200">
              <div className="font-semibold text-neutral-800 dark:text-neutral-100">숫자/지표 설명</div>
              <ul className="mt-2 grid gap-1.5 sm:grid-cols-2">
                <li>
                  <span className="font-semibold">VUser</span>: 동시에 요청을 보내는 가상 사용자 수(대략 동시 사용자). 값이 커질수록 부하가
                  증가합니다.
                </li>
                <li>
                  <span className="font-semibold">Duration(s)</span>: 테스트 실행 시간(초). 테스트가 끝나면 TPS/지연이 안정화되는 구간을
                  확인하세요.
                </li>
                <li>
                  <span className="font-semibold">SeatPool</span>: 테스트 스크립트가 고르는 좌석 후보의 크기. 작을수록 충돌이 늘어
                  동시성/정합성 이슈를 더 강하게 자극합니다.
                </li>
                <li>
                  <span className="font-semibold">TPS</span>: 초당 처리 건수(Throughput). 높을수록 시스템이 더 많은 요청을 처리 중입니다.
                </li>
                <li>
                  <span className="font-semibold">Mean (ms)</span>: 평균 응답 시간(밀리초). TPS와 함께 보고, 급격한 상승은 병목/포화
                  신호입니다.
                </li>
                <li>
                  <span className="font-semibold">Errors</span>: 에러 발생 건수(구간별). 급증하면 서버 오류/타임아웃/레이트리밋 등을
                  의심하세요.
                </li>
              </ul>
              <div className="mt-2 text-[11px] text-neutral-500 dark:text-neutral-400">
                팁: 목록에서 테스트를 선택하면 우측 상단의 TPS/Errors/Mean 값은 “가장 최근 구간”을 보여줍니다.
              </div>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <input
              className="w-[18rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-xs text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="대상 baseUrl (예: http://host.docker.internal:8080)"
              title="nGrinder 에이전트가 요청을 보낼 대상 서버의 base URL"
            />
            <input
              className="w-[7.5rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-xs text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
              value={String(allVusers)}
              onChange={(e) => setAllVusers(Number(e.target.value || 0))}
              placeholder="VUser"
              inputMode="numeric"
              title="동시에 요청을 보내는 가상 사용자 수(부하 강도)"
            />
            <input
              className="w-[7.5rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-xs text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
              value={String(allDurationSec)}
              onChange={(e) => setAllDurationSec(Number(e.target.value || 0))}
              placeholder="Duration(s)"
              inputMode="numeric"
              title="테스트 실행 시간(초)"
            />
            <input
              className="w-[7.5rem] rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-xs text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
              value={String(allSeatPoolSize)}
              onChange={(e) => setAllSeatPoolSize(Number(e.target.value || 0))}
              placeholder="SeatPool"
              inputMode="numeric"
              title="좌석 후보 풀 크기(작을수록 좌석 충돌↑, 동시성 이슈 자극↑)"
            />
            <button
              type="button"
              className="rounded-xl bg-neutral-900/85 px-3 py-2 text-sm font-medium text-white shadow disabled:opacity-50 dark:bg-white/15"
              onClick={() => void runAllPresets()}
              disabled={actionBusy}
            >
              전체 실행
            </button>
            <button
              type="button"
              className="rounded-xl bg-indigo-600/90 px-3 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
              onClick={() => void startAllInOne()}
              disabled={actionBusy}
              title="부하(VUser)와 함께 예약/취소/동시성/정합성을 한 번에 검증"
            >
              올인원
            </button>
            <button
              type="button"
              className="rounded-xl bg-emerald-600/90 px-3 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
              onClick={() => void startPreset('comp')}
              disabled={actionBusy}
            >
              종합
            </button>
            <button
              type="button"
              className="rounded-xl bg-amber-600/90 px-3 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
              onClick={() => void startPreset('conc')}
              disabled={actionBusy}
            >
              동시성
            </button>
            <button
              type="button"
              className="rounded-xl bg-teal-600/90 px-3 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
              onClick={() => void startPreset('integrity')}
              disabled={actionBusy}
            >
              정합성
            </button>
            <button
              type="button"
              className="rounded-xl bg-[#007AFF]/90 px-3 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
              onClick={() => void startPreset('load-lite')}
              disabled={actionBusy}
            >
              부하(약)
            </button>
            <button
              type="button"
              className="rounded-xl bg-violet-600/90 px-3 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
              onClick={() => void startPreset('load-heavy')}
              disabled={actionBusy}
            >
              부하(강)
            </button>
            <button
              type="button"
              className="rounded-xl border border-white/30 bg-white/30 px-3 py-2 text-sm text-neutral-800 backdrop-blur dark:bg-black/20 dark:text-white"
              onClick={() => void refreshList()}
              disabled={listLoading}
            >
              {listLoading ? '목록 로딩…' : '목록 새로고침'}
            </button>
            <button
              type="button"
              className="rounded-xl bg-red-600/85 px-3 py-2 text-sm font-medium text-white shadow disabled:opacity-50"
              onClick={() => void deleteAll()}
              disabled={actionBusy}
            >
              전체 삭제
            </button>
            <button
              type="button"
              className="rounded-xl border border-white/20 px-3 py-2 text-sm text-neutral-700 disabled:opacity-50 dark:text-neutral-200"
              onClick={() => setListPage((p) => Math.max(0, p - 1))}
              disabled={listPage <= 0 || listLoading}
            >
              이전
            </button>
            <button
              type="button"
              className="rounded-xl border border-white/20 px-3 py-2 text-sm text-neutral-700 disabled:opacity-50 dark:text-neutral-200"
              onClick={() => setListPage((p) => p + 1)}
              disabled={listLoading || !hasNextPage}
            >
              다음
            </button>
            <span className="text-xs text-neutral-500 dark:text-neutral-400">
              페이지 {listPage + 1}
              {totalElements != null ? ` · 총 ${totalElements}건` : ''}
            </span>
          </div>
        </div>

        {err && <p className="mb-2 text-sm text-red-500">{err}</p>}

        <div className="grid min-h-[22rem] gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)]">
          <div className="min-h-0 overflow-hidden rounded-xl border border-white/20 bg-white/10 dark:bg-black/15">
            <div className="max-h-[22rem] overflow-auto">
              <table className="w-full min-w-[280px] border-collapse text-left text-sm">
                <thead className="sticky top-0 z-10 bg-white/70 text-xs font-semibold uppercase text-neutral-600 backdrop-blur dark:bg-black/50 dark:text-neutral-300">
                  <tr>
                    <th className="px-3 py-2">ID</th>
                    <th className="px-3 py-2">이름</th>
                    <th className="px-3 py-2">상태</th>
                    <th className="px-3 py-2">동작</th>
                  </tr>
                </thead>
                <tbody>
                  {tests.length === 0 && !listLoading ? (
                    <tr>
                      <td colSpan={4} className="px-3 py-6 text-center text-neutral-500">
                        테스트가 없습니다. nGrinder에서 시나리오를 만든 뒤 다시 새로고침하세요.
                      </td>
                    </tr>
                  ) : (
                    tests.map((t) => {
                      const id = t.id
                      const active = selectedId === id
                      const name = t.testName ?? t.name ?? '—'
                      return (
                        <tr
                          key={id}
                          className={`cursor-pointer border-t border-white/10 ${active ? 'bg-[#007AFF]/15' : 'hover:bg-white/10'}`}
                          onClick={() => selectRow(id)}
                        >
                          <td className="px-3 py-2 font-mono text-xs text-neutral-800 dark:text-white">{id}</td>
                          <td className="max-w-[10rem] truncate px-3 py-2 text-neutral-800 dark:text-neutral-100" title={name}>
                            {name}
                          </td>
                          <td className="px-3 py-2 text-xs text-emerald-700 dark:text-emerald-300">{rowStatusName(t)}</td>
                          <td className="px-2 py-1.5" onClick={(ev) => ev.stopPropagation()}>
                            <div className="flex flex-wrap gap-1">
                              <button
                                type="button"
                                className="rounded-lg bg-[#007AFF]/90 px-2 py-1 text-[11px] font-medium text-white disabled:opacity-50"
                                disabled={actionBusy}
                                onClick={() => void runReady(id)}
                              >
                                READY
                              </button>
                              <button
                                type="button"
                                className="rounded-lg bg-violet-600/90 px-2 py-1 text-[11px] font-medium text-white disabled:opacity-50"
                                disabled={actionBusy}
                                onClick={() => void runClone(id)}
                              >
                                복제
                              </button>
                              <button
                                type="button"
                                className="rounded-lg bg-red-600/85 px-2 py-1 text-[11px] font-medium text-white disabled:opacity-50"
                                disabled={actionBusy}
                                onClick={() => void runStop(id)}
                              >
                                중지
                              </button>
                            </div>
                          </td>
                        </tr>
                      )
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>

          <div className="flex min-h-[22rem] flex-col gap-3">
            {selectedId == null ? (
              <p className="rounded-xl border border-dashed border-white/25 bg-white/5 p-6 text-center text-sm text-neutral-500 dark:text-neutral-400">
                왼쪽에서 테스트를 선택하면 모니터링 패널이 표시됩니다.
              </p>
            ) : (
              <>
                <div className="flex flex-wrap items-center gap-3 text-sm font-medium">
                  <span className="text-neutral-600 dark:text-neutral-300">선택 #{selectedId}</span>
                  <span className="text-emerald-600 dark:text-emerald-300">상태: {status?.status?.name ?? '—'}</span>
                  <span className="text-[#007AFF]">TPS: {last?.tps ?? '—'}</span>
                  <span className="text-[#FF3B30]">에러: {last?.errors ?? '—'}</span>
                  <span className="text-violet-600 dark:text-violet-300">
                    평균: {last?.meanMs != null ? `${last.meanMs} ms` : '—'}
                  </span>
                </div>
                <div className="grid min-h-0 flex-1 gap-3 lg:grid-rows-[minmax(12rem,1fr)_minmax(10rem,auto)]">
                  <div className="min-h-[12rem] w-full rounded-xl border border-white/15 bg-white/5 p-2 dark:bg-black/20">
                    <ResponsiveContainer width="100%" height="100%">
                      <LineChart data={series} margin={{ top: 5, right: 8, left: -16, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.2)" vertical={false} />
                        <XAxis
                          dataKey="x"
                          stroke="rgba(163,163,163,0.8)"
                          tick={{ fontSize: 11, fontFamily: 'Inter' }}
                        />
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
                        <Line
                          type="monotone"
                          dataKey="meanMs"
                          name="Mean (ms)"
                          stroke="#8B5CF6"
                          strokeWidth={2}
                          dot={false}
                        />
                        <Line
                          type="monotone"
                          dataKey="errors"
                          name="Errors"
                          stroke="#FF3B30"
                          strokeWidth={2}
                          dot={false}
                        />
                      </LineChart>
                    </ResponsiveContainer>
                  </div>
                  <div className="flex min-h-[10rem] flex-col gap-2 rounded-xl border border-white/15 bg-white/5 p-3 dark:bg-black/20">
                    <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
                      진행 로그 (status message)
                    </div>
                    <pre className="max-h-28 overflow-auto whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed text-neutral-800 dark:text-neutral-200">
                      {logText || '—'}
                    </pre>
                    {logFiles.length > 0 && (
                      <>
                        <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
                          로그 파일
                        </div>
                        <ul className="max-h-20 list-inside list-disc overflow-auto text-xs text-neutral-700 dark:text-neutral-300">
                          {logFiles.map((f) => (
                            <li key={f}>{f}</li>
                          ))}
                        </ul>
                      </>
                    )}
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      </LiquidGlassPanel>
    </div>
  )
}
