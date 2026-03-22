import { type FormEvent, useEffect, useState } from 'react'
import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom'
import { api, getToken, setToken, type CreateEventBody, type EventDto, type SeatDto } from './api'
import { LiquidGlassPanel } from './components/LiquidGlassPanel'
import { TrafficAnalyticsDashboard } from './components/TrafficAnalyticsDashboard'

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
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm">
          <LiquidGlassPanel className="max-h-[90vh] w-full max-w-md overflow-y-auto">
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-lg font-semibold">새 공연</h3>
              <button
                type="button"
                className="text-sm text-neutral-500"
                onClick={() => setShowAdd(false)}
              >
                닫기
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
              <button
                type="submit"
                className="mt-2 rounded-2xl bg-[#007AFF] py-3 font-medium text-white"
              >
                등록
              </button>
            </form>
          </LiquidGlassPanel>
        </div>
      )}
      {err && <p className="text-center text-red-500">{err}</p>}
      <ul className="flex flex-col gap-3">
        {events.map((e) => (
          <li key={e.id}>
            <Link to={`/events/${e.id}`}>
              <LiquidGlassPanel className="block cursor-pointer hover:opacity-95 active:scale-[0.99]">
                <div className="text-left">
                  <div className="text-lg font-medium">{e.name}</div>
                  <div className="text-sm text-neutral-600 dark:text-neutral-400">{e.venue}</div>
                  <div className="mt-1 text-xs uppercase text-violet-500">{e.status}</div>
                </div>
              </LiquidGlassPanel>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}

function EventDetail() {
  const { id } = useParams<{ id: string }>()
  const eventId = Number(id)
  const [seats, setSeats] = useState<SeatDto[]>([])
  const [queueText, setQueueText] = useState('대기열에 없음 — 「대기열 진입」을 눌러 주세요.')
  const [admissionToken, setAdmissionToken] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [paymentStep, setPaymentStep] = useState(false)

  useEffect(() => {
    if (admissionToken) {
      setQueueText('입장 완료 · 좌석을 선택해 주세요 (입장 토큰 유효 10분)')
    }
  }, [admissionToken])

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
        /* ignore */
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
    try {
      const r = await api.joinQueue(eventId)
      setQueueText(`대기 중 · 순번 약 ${r.position} / ${r.totalWaiting}명`)
      setAdmissionToken(r.admissionToken)
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
      setPaymentStep(true)
    } catch (e) {
      setMsg(e instanceof Error ? e.message : 'reserve error')
    }
  }

  return (
    <div className="mx-auto w-full max-w-2xl px-4 py-8">
      <Link to="/events" className="mb-4 inline-block text-sm text-[#007AFF]">
        ← 목록
      </Link>
      <LiquidGlassPanel className="mb-6">
        <div className="text-lg font-semibold">예매</div>
        <p className="mt-2 text-sm text-neutral-600 dark:text-neutral-300">{queueText}</p>
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
            예약이 확보되었습니다. 아래에서 결제를 진행해 주세요. (좌석 자동 새로고침은 결제 화면 진입 전까지만
            동작합니다.)
          </p>
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
            element={authed ? <TrafficAnalyticsDashboard /> : <Navigate to="/" replace />}
          />
        </Routes>
      </Layout>
    </BrowserRouter>
  )
}
