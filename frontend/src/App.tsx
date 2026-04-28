import { type FormEvent, useEffect, useRef, useState } from 'react'
import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom'
import {
  api,
  getToken,
  setToken,
  type CreateEventBody,
  type EventDto,
  type ReservationPaymentProgressDto,
  type SeatDto,
} from './api'
import { LiquidGlassPanel } from './components/LiquidGlassPanel'
import { DeveloperDashboard, UserDashboard } from './components/TrafficAnalyticsDashboard'

function AuthCard({ onAuthed }: { onAuthed: () => void }) {
  const [email, setEmail] = useState('demo@ticketing.local')
  const [password, setPassword] = useState('password123')
  const [err, setErr] = useState<string | null>(null)
  const nav = useNavigate()

  async function register() {
    setErr(null)
    try {
      const t = await api.register(email, password)
      setToken(t.accessToken)
      onAuthed()
      nav('/events')
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Error')
    }
  }

  async function login() {
    setErr(null)
    try {
      const t = await api.login(email, password)
      setToken(t.accessToken)
      onAuthed()
      nav('/events')
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Error')
    }
  }

  return (
    <LiquidGlassPanel className="mx-auto w-full max-w-md">
      <h1 className="mb-6 text-2xl font-semibold tracking-tight">Ticketing</h1>
      <div className="mb-4 flex flex-col gap-2 text-left">
        <label className="text-sm text-neutral-600 dark:text-neutral-300">Email</label>
        <input
          className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <label className="text-sm text-neutral-600 dark:text-neutral-300">Password</label>
        <input
          type="password"
          className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-neutral-900 outline-none backdrop-blur dark:bg-black/20 dark:text-white"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </div>
      {err && <p className="mb-2 text-sm text-red-500">{err}</p>}
      <div className="flex gap-2">
        <button
          type="button"
          className="flex-1 rounded-2xl bg-[#007AFF] px-4 py-3 font-medium text-white shadow-lg active:scale-[0.98]"
          onClick={login}
        >
          Log in
        </button>
        <button
          type="button"
          className="flex-1 rounded-2xl border border-white/30 bg-white/20 px-4 py-3 font-medium backdrop-blur active:scale-[0.98]"
          onClick={register}
        >
          Register
        </button>
      </div>
    </LiquidGlassPanel>
  )
}

function EventList() {
  const [events, setEvents] = useState<EventDto[]>([])
  const [err, setErr] = useState<string | null>(null)
  const [showAdd, setShowAdd] = useState(false)
  const [addErr, setAddErr] = useState<string | null>(null)
  const [form, setForm] = useState<CreateEventBody>({
    name: '',
    venue: '',
    startDate: '',
    seatCount: 50,
    seatPrice: 99000,
    grade: 'R',
  })

  function load() {
    api
      .events()
      .then(setEvents)
      .catch((e) => setErr(String(e)))
  }

  useEffect(() => {
    load()
  }, [])

  useEffect(() => {
    if (!showAdd) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowAdd(false)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [showAdd])

  async function submitAdd(e: FormEvent) {
    e.preventDefault()
    setAddErr(null)
    try {
      let start = form.startDate
      if (start && start.length === 16) start = `${start}:00`
      await api.createEvent({
        ...form,
        startDate: start,
      })
      setShowAdd(false)
      setForm({
        name: '',
        venue: '',
        startDate: '',
        seatCount: 50,
        seatPrice: 99000,
        grade: 'R',
      })
      load()
    } catch (x) {
      setAddErr(x instanceof Error ? x.message : '추가 실패')
    }
  }

  return (
    <div className="mx-auto w-full max-w-lg px-4 py-8">
      <div className="mb-6 flex items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">공연</h2>
        <button
          type="button"
          className="rounded-2xl bg-[#007AFF] px-4 py-2 text-sm font-medium text-white shadow active:scale-[0.98]"
          onClick={() => setShowAdd(true)}
        >
          공연 추가
        </button>
      </div>
      {showAdd && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-white/30 p-4 backdrop-blur-sm dark:bg-black/25">
          <LiquidGlassPanel className="max-h-[90vh] w-full max-w-md overflow-y-auto">
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-lg font-semibold">새 공연</h3>
              <button
                type="button"
                className="text-sm text-neutral-500"
                onClick={() => setShowAdd(false)}
              >
                나가기
              </button>
            </div>
            <form className="flex flex-col gap-3 text-left" onSubmit={submitAdd}>
              <label className="text-sm text-neutral-600 dark:text-neutral-300">공연명</label>
              <input
                required
                className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-neutral-900 dark:bg-black/20 dark:text-white"
                value={form.name}
                onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
              />
              <label className="text-sm text-neutral-600 dark:text-neutral-300">장소</label>
              <input
                className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-neutral-900 dark:bg-black/20 dark:text-white"
                value={form.venue}
                onChange={(e) => setForm((f) => ({ ...f, venue: e.target.value }))}
              />
              <label className="text-sm text-neutral-600 dark:text-neutral-300">공연일시</label>
              <input
                required
                type="datetime-local"
                className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-neutral-900 dark:bg-black/20 dark:text-white"
                value={form.startDate}
                onChange={(e) => setForm((f) => ({ ...f, startDate: e.target.value }))}
              />
              <label className="text-sm text-neutral-600 dark:text-neutral-300">좌석 수</label>
              <input
                required
                type="number"
                min={1}
                max={5000}
                className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-neutral-900 dark:bg-black/20 dark:text-white"
                value={form.seatCount}
                onChange={(e) =>
                  setForm((f) => ({ ...f, seatCount: Number(e.target.value) || 1 }))
                }
              />
              <label className="text-sm text-neutral-600 dark:text-neutral-300">좌석 가격 (원)</label>
              <input
                required
                type="number"
                min={0}
                className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-neutral-900 dark:bg-black/20 dark:text-white"
                value={form.seatPrice}
                onChange={(e) =>
                  setForm((f) => ({ ...f, seatPrice: Number(e.target.value) || 0 }))
                }
              />
              <label className="text-sm text-neutral-600 dark:text-neutral-300">등급 라벨</label>
              <input
                className="rounded-xl border border-white/30 bg-white/40 px-3 py-2 text-neutral-900 dark:bg-black/20 dark:text-white"
                value={form.grade}
                onChange={(e) => setForm((f) => ({ ...f, grade: e.target.value }))}
              />
              {addErr && <p className="text-sm text-red-500">{addErr}</p>}
              <div className="mt-2 flex gap-2">
                <button
                  type="button"
                  className="flex-1 rounded-2xl border border-white/30 bg-white/20 py-3 font-medium text-neutral-700 backdrop-blur dark:text-white"
                  onClick={() => setShowAdd(false)}
                >
                  나가기
                </button>
                <button
                  type="submit"
                  className="flex-1 rounded-2xl bg-[#007AFF] py-3 font-medium text-white"
                >
                  등록
                </button>
              </div>
            </form>
          </LiquidGlassPanel>
        </div>
      )}
      {err && <p className="text-center text-red-500">{err}</p>}
      <ul className="flex flex-col gap-3">
        {events.map((e) => (
          <li key={e.id}>
            <div className="relative">
              <Link to={`/events/${e.id}`}>
                <LiquidGlassPanel className="block cursor-pointer hover:opacity-95 active:scale-[0.99]">
                  <div className="pr-10 text-left">
                    <div className="text-lg font-medium">{e.name}</div>
                    <div className="text-sm text-neutral-600 dark:text-neutral-400">{e.venue}</div>
                    <div className="mt-1 text-xs uppercase text-violet-500">{e.status}</div>
                  </div>
                </LiquidGlassPanel>
              </Link>
              <button
                type="button"
                aria-label="Delete event"
                title="삭제"
                className="absolute right-3 top-3 rounded-xl border border-red-500/30 bg-white/20 px-2.5 py-1.5 text-xs font-semibold text-red-600 backdrop-blur hover:bg-red-500/10 active:scale-[0.98] dark:text-red-300"
                onClick={async (ev) => {
                  ev.preventDefault()
                  ev.stopPropagation()
                  const ok = window.confirm(`이 공연을 삭제할까요?\n\n${e.name}`)
                  if (!ok) return
                  try {
                    await api.deleteEvent(e.id)
                    load()
                  } catch (x) {
                    setErr(x instanceof Error ? x.message : '삭제 실패')
                  }
                }}
              >
                삭제
              </button>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}

function EventDetail() {
  const { id } = useParams<{ id: string }>()
  const nav = useNavigate()
  const eventId = Number(id)
  const [seats, setSeats] = useState<SeatDto[]>([])
  const [queueText, setQueueText] = useState('대기열에 없음 — 「대기열 진입」을 눌러 주세요.')
  const [admissionToken, setAdmissionToken] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [paymentStep, setPaymentStep] = useState(false)
  const [reservationId, setReservationId] = useState<number | null>(null)
  const [progress, setProgress] = useState<ReservationPaymentProgressDto | null>(null)
  const [progressNow, setProgressNow] = useState(() => new Date().getTime())
  const [finalizedAtMs, setFinalizedAtMs] = useState<number | null>(null)
  const detailContentRef = useRef<HTMLDivElement | null>(null)

  function progressStageRank(p: ReservationPaymentProgressDto | null): number {
    if (!p) return 0
    if (p.reservationStatus === 'CONFIRMED' || p.reservationStatus === 'CANCELED') return 4
    if (p.paymentFinishedAt) return 3
    if (p.paymentStartedAt) return 2
    return 1
  }

  useEffect(() => {
    if (admissionToken) return
    const handle = window.setInterval(async () => {
      try {
        const st = await api.queueMe(eventId)
        setQueueText(
          st.inQueue
            ? `대기 중 · 순번 약 ${st.position} / ${st.totalWaiting}명`
            : '대기열에 없음 — 「대기열 진입」을 눌러 주세요.',
        )
        const adm = await api.admission(eventId)
        if (adm?.token) setAdmissionToken(adm.token)
      } catch {
        setQueueText('대기열 상태를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')
      }
    }, 2000)
    return () => clearInterval(handle)
  }, [eventId, admissionToken])

  useEffect(() => {
    if (!admissionToken || paymentStep) return
    const load = () => {
      api
        .seats(eventId)
        .then(setSeats)
        .catch((e) => setMsg(String(e)))
    }
    load()
    const handle = window.setInterval(load, 1000)
    return () => clearInterval(handle)
  }, [eventId, admissionToken, paymentStep])

  useEffect(() => {
    if (!admissionToken || paymentStep) return
    const handle = window.setInterval(async () => {
      try {
        const adm = await api.admission(eventId)
        if (!adm?.token) {
          setAdmissionToken(null)
          setSeats([])
          setQueueText('대기열에 없음 — 「대기열 진입」을 눌러 주세요.')
          setMsg('입장 시간이 만료되었습니다. 다시 대기열에 진입해 주세요.')
        }
      } catch {
        setAdmissionToken(null)
        setSeats([])
        setQueueText('대기열에 없음 — 「대기열 진입」을 눌러 주세요.')
      }
    }, 15000)
    return () => clearInterval(handle)
  }, [eventId, admissionToken, paymentStep])

  async function join() {
    setMsg(null)
    setPaymentStep(false)
    setReservationId(null)
    setProgress(null)
    setFinalizedAtMs(null)
    try {
      const r = await api.joinQueue(eventId)
      setQueueText(`대기 중 · 순번 약 ${r.position} / ${r.totalWaiting}명`)
    } catch (e) {
      setMsg(e instanceof Error ? e.message : 'queue error')
    }
  }

  async function reserve(seatId: number) {
    setMsg(null)
    if (!admissionToken) {
      setMsg('먼저 대기열에 진입해 입장 토큰을 받아 주세요.')
      return
    }
    try {
      const r = await api.reserve(eventId, seatId, admissionToken)
      setMsg(`예약 생성됨 · reservation #${r.id} (${r.status})`)
      setReservationId(r.id)
      setProgressNow(new Date().getTime())
      setProgress(null)
      setFinalizedAtMs(null)
      setPaymentStep(true)
    } catch (e) {
      setMsg(e instanceof Error ? e.message : 'reserve error')
    }
  }

  useEffect(() => {
    if (!paymentStep || !reservationId) return
    const handle = window.setInterval(() => setProgressNow(new Date().getTime()), 1000)
    return () => clearInterval(handle)
  }, [paymentStep, reservationId])

  useEffect(() => {
    if (!paymentStep || !reservationId) return
    let cancelled = false
    const load = async () => {
      try {
        const p = await api.reservationProgress(eventId, reservationId)
        if (cancelled) return
        setProgress((prev) => (progressStageRank(p) >= progressStageRank(prev) ? p : prev))
        if (p.reservationStatus === 'CONFIRMED') {
          setMsg(`결제 완료 · reservation #${reservationId} (CONFIRMED)`)
          setFinalizedAtMs((prev) => prev ?? new Date().getTime())
        } else if (p.reservationStatus === 'CANCELED') {
          const reason = p.failureMessage ? ` (${p.failureMessage})` : ''
          setMsg(`결제 실패 · reservation #${reservationId} (CANCELED)${reason}`)
          setFinalizedAtMs((prev) => prev ?? new Date().getTime())
        }
      } catch {
        // ignore polling error
      }
    }
    load()
    const handle = window.setInterval(load, 1000)
    return () => {
      cancelled = true
      clearInterval(handle)
    }
  }, [eventId, paymentStep, reservationId])

  const reservedMs = progress?.reservedAt ? Date.parse(progress.reservedAt) : null
  const paymentStartedMs = progress?.paymentStartedAt ? Date.parse(progress.paymentStartedAt) : null
  const paymentFinishedMs = progress?.paymentFinishedAt ? Date.parse(progress.paymentFinishedAt) : null
  const paymentSucceeded = progress?.reservationStatus === 'CONFIRMED'
  const paymentFailed = progress?.reservationStatus === 'CANCELED'
  const paymentOutcome = paymentSucceeded ? 'SUCCESS' : paymentFailed ? 'FAILED' : 'PENDING'
  const canReturnHomeByBackgroundClick = paymentOutcome !== 'PENDING'
  const queueTextView = admissionToken
    ? '입장 완료 · 좌석을 선택해 주세요 (입장 토큰 유효 10분)'
    : queueText

  useEffect(() => {
    if (!canReturnHomeByBackgroundClick) return
    const onWindowMouseDown = (ev: MouseEvent) => {
      if (ev.button !== 0) return
      const target = ev.target as HTMLElement | null
      if (!target) return
      if (target.closest('a,button,input,select,textarea,[role="button"]')) return
      if (detailContentRef.current?.contains(target)) return
      nav('/')
    }
    window.addEventListener('mousedown', onWindowMouseDown)
    return () => window.removeEventListener('mousedown', onWindowMouseDown)
  }, [canReturnHomeByBackgroundClick, nav])

  function goHomeOnLeftBackgroundClick(e: React.MouseEvent<HTMLDivElement>) {
    if (!canReturnHomeByBackgroundClick) return
    if (e.target !== e.currentTarget) return
    nav('/')
  }

  const stopAtMs = paymentOutcome === 'PENDING' ? progressNow : finalizedAtMs ?? progressNow
  const totalElapsedSec =
    reservedMs == null ? 0 : Math.max(0, Math.floor((stopAtMs - reservedMs) / 1000))
  const queueElapsedSec =
    reservedMs == null ? 0 : Math.max(0, Math.floor(((paymentStartedMs ?? stopAtMs) - reservedMs) / 1000))
  const processingElapsedSec =
    paymentStartedMs == null
      ? 0
      : Math.max(0, Math.floor(((paymentFinishedMs ?? stopAtMs) - paymentStartedMs) / 1000))
  const settlementElapsedSec =
    paymentFinishedMs == null ? 0 : Math.max(0, Math.floor((stopAtMs - paymentFinishedMs) / 1000))

  return (
    <div className="w-full min-h-screen px-4 py-8" onClick={goHomeOnLeftBackgroundClick}>
      <div ref={detailContentRef} className="mx-auto w-full max-w-2xl">
        <Link to="/events" className="mb-4 inline-block text-sm text-[#007AFF]">
          ← 목록
        </Link>
        <LiquidGlassPanel className="mb-6">
          <div className="flex items-center justify-between gap-3">
            <div className="text-lg font-semibold">예매</div>
            <button
              type="button"
              className="rounded-xl border border-red-500/30 bg-white/20 px-3 py-1.5 text-xs font-semibold text-red-600 backdrop-blur hover:bg-red-500/10 active:scale-[0.98] dark:text-red-300"
              onClick={async () => {
                const ok = window.confirm(`이 공연(eventId=${eventId})을 삭제할까요?\n(연관된 좌석/예약/결제도 정리됩니다)`)
                if (!ok) return
                try {
                  await api.deleteEvent(eventId)
                  nav('/events')
                } catch (x) {
                  setMsg(x instanceof Error ? x.message : '삭제 실패')
                }
              }}
            >
              삭제
            </button>
          </div>
          <p className="mt-2 text-sm text-neutral-600 dark:text-neutral-300">{queueTextView}</p>
          <p className="mt-1 text-xs text-neutral-500">
            입장 토큰: {admissionToken ? `${admissionToken.slice(0, 8)}…` : '없음'}
          </p>
          <button
            type="button"
            className="mt-4 rounded-2xl bg-[#007AFF] px-5 py-2.5 text-sm font-medium text-white"
            onClick={join}
          >
            대기열 진입
          </button>
        </LiquidGlassPanel>
        {msg && <p className="mb-4 text-center text-sm text-neutral-700 dark:text-neutral-200">{msg}</p>}
        {paymentStep && (
          <LiquidGlassPanel className="mb-6 border-emerald-400/30">
            <div className="text-base font-semibold text-emerald-800 dark:text-emerald-200">결제 단계</div>
            <p className="mt-2 text-sm text-neutral-600 dark:text-neutral-300">
              결제 파이프라인 진행 상태를 추적 중입니다.
            </p>
            {paymentOutcome !== 'PENDING' && (
              <div
                className={`mt-3 rounded-xl border px-3 py-2 text-sm font-medium ${
                  paymentOutcome === 'SUCCESS'
                    ? 'border-emerald-400/40 bg-emerald-100/60 text-emerald-900 dark:bg-emerald-900/30 dark:text-emerald-100'
                    : 'border-red-400/40 bg-red-100/60 text-red-900 dark:bg-red-900/30 dark:text-red-100'
                }`}
              >
                {paymentOutcome === 'SUCCESS'
                  ? `예매가 성공적으로 완료되었습니다. (reservation #${reservationId})`
                  : `예매 결제에 실패했습니다. (reservation #${reservationId})`}
                {paymentOutcome === 'FAILED' && progress?.failureMessage
                  ? ` 사유: ${progress.failureMessage}`
                  : ''}
                <div className="mt-1 text-xs opacity-80">
                  안내: 좌측/우측 빈 화면을 좌클릭하면 홈 화면으로 돌아갑니다.
                </div>
              </div>
            )}
            <div className="mt-3 rounded-xl border border-white/30 bg-white/25 p-3 text-sm dark:bg-black/20">
              <div className="mb-2 font-medium text-neutral-800 dark:text-neutral-100">
                reservation #{reservationId ?? '-'}
                {paymentOutcome === 'PENDING'
                  ? ` · 현재 단계 진행 ${paymentStartedMs == null ? queueElapsedSec : paymentFinishedMs == null ? processingElapsedSec : settlementElapsedSec}초`
                  : ` · 총 ${totalElapsedSec}초`}
              </div>
              <div className="space-y-1.5 text-neutral-700 dark:text-neutral-200">
                <div>
                  1) 예약 확정(HELD): {reservedMs ? '완료' : '대기'} {reservedMs ? '(0초)' : ''}
                </div>
                <div>
                  2) 결제 워커 큐 대기:{' '}
                  {paymentStartedMs == null
                    ? `진행중 (${queueElapsedSec}초)`
                    : `완료 (${queueElapsedSec}초)`}
                </div>
                <div>
                  3) 결제 시뮬레이션 처리:{' '}
                  {paymentStartedMs == null
                    ? '대기 (0초)'
                    : paymentFinishedMs == null
                      ? `진행중 (${processingElapsedSec}초)`
                      : `완료 (${processingElapsedSec}초)`}
                </div>
                <div>
                  4) 결과 반영:{' '}
                  {paymentFinishedMs == null
                    ? '대기 (0초)'
                    : paymentOutcome === 'PENDING'
                      ? `진행중 (${settlementElapsedSec}초)`
                      : paymentSucceeded
                        ? `성공 반영 완료 (${settlementElapsedSec}초)`
                        : `실패 롤백 완료 (${settlementElapsedSec}초)`}
                </div>
              </div>
              {progress?.paymentStatus && (
                <div className="mt-3 text-xs text-neutral-600 dark:text-neutral-300">
                  paymentStatus={progress.paymentStatus}
                  {progress.failureCode ? ` · failureCode=${progress.failureCode}` : ''}
                </div>
              )}
            </div>
          </LiquidGlassPanel>
        )}
        {admissionToken && !paymentStep && (
          <p className="mb-3 text-center text-xs text-neutral-500">
            다른 사용자와 좌석이 겹치지 않도록 좌석 상태를 1초마다 갱신합니다.
          </p>
        )}
        {admissionToken ? (
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {seats.map((s) => (
              <button
                key={s.id}
                type="button"
                disabled={s.status !== 'AVAILABLE' || paymentStep}
                onClick={() => reserve(s.id)}
                className="rounded-2xl border border-white/20 bg-white/10 px-2 py-3 text-sm backdrop-blur disabled:opacity-40"
              >
                {s.seatNumber}
                <div className="text-xs text-neutral-500">{s.status}</div>
              </button>
            ))}
          </div>
        ) : (
          <p className="text-center text-sm text-neutral-500">입장 토큰 발급 후 좌석표가 표시됩니다.</p>
        )}
      </div>
    </div>
  )
}

function Layout({
  children,
  authed,
  onLogout,
}: {
  children: React.ReactNode
  authed: boolean
  onLogout: () => void
}) {
  const nav = useNavigate()
  return (
    <div className="min-h-screen bg-gradient-to-b from-slate-200 via-violet-100 to-fuchsia-100 pb-12 dark:from-slate-950 dark:via-slate-900 dark:to-slate-950">
      <div className="mx-auto max-w-5xl px-4 pt-6">
        <nav className="mb-8 flex flex-wrap items-center justify-center gap-4 text-sm">
          <Link className="text-[#007AFF]" to="/">
            Home
          </Link>
          <Link className="text-[#007AFF]" to="/events">
            Events
          </Link>
          {authed && (
            <Link className="text-[#007AFF]" to="/dashboard">
              Dashboard
            </Link>
          )}
          {authed && (
            <button
              type="button"
              className="rounded-full border border-white/30 bg-white/20 px-4 py-1.5 text-neutral-800 backdrop-blur dark:text-white"
              onClick={() => {
                setToken(null)
                onLogout()
                nav('/')
              }}
            >
              로그아웃
            </button>
          )}
        </nav>
        {children}
      </div>
    </div>
  )
}

export default function App() {
  const [authed, setAuthed] = useState(() => !!getToken())

  return (
    <BrowserRouter>
      <Layout authed={authed} onLogout={() => setAuthed(false)}>
        <Routes>
          <Route
            path="/"
            element={
              authed ? (
                <Navigate to="/events" replace />
              ) : (
                <div className="flex flex-col items-center gap-10">
                  <AuthCard onAuthed={() => setAuthed(true)} />
                </div>
              )
            }
          />
          <Route
            path="/events"
            element={authed ? <EventList /> : <Navigate to="/" replace />}
          />
          <Route
            path="/events/:id"
            element={authed ? <EventDetail /> : <Navigate to="/" replace />}
          />
          <Route
            path="/dashboard"
            element={authed ? <UserDashboard /> : <Navigate to="/" replace />}
          />
          <Route
            path="/dashboard/dev"
            element={authed ? <DeveloperDashboard /> : <Navigate to="/" replace />}
          />
        </Routes>
      </Layout>
    </BrowserRouter>
  )
}
