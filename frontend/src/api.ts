const API_BASE = import.meta.env.VITE_API_BASE ?? ''

export function getToken(): string | null {
  return localStorage.getItem('accessToken')
}

export function setToken(t: string | null) {
  if (t) localStorage.setItem('accessToken', t)
  else localStorage.removeItem('accessToken')
}

async function reqEmpty(
  path: string,
  opts: RequestInit & { skipAuth?: boolean } = {},
): Promise<void> {
  const headers: Record<string, string> = {
    ...(opts.headers as Record<string, string>),
  }
  const tok = getToken()
  if (tok && !opts.skipAuth) {
    headers.Authorization = `Bearer ${tok}`
  }
  const r = await fetch(`${API_BASE}${path}`, { ...opts, headers })
  if (!r.ok) {
    const err = await r.json().catch(() => ({ error: r.statusText }))
    throw new Error((err as { error?: string }).error ?? r.statusText)
  }
}

async function req<T>(
  path: string,
  opts: RequestInit & { skipAuth?: boolean } = {},
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(opts.headers as Record<string, string>),
  }
  const tok = getToken()
  if (tok && !opts.skipAuth) {
    headers.Authorization = `Bearer ${tok}`
  }
  const r = await fetch(`${API_BASE}${path}`, { ...opts, headers })
  if (!r.ok) {
    const err = await r.json().catch(() => ({ error: r.statusText }))
    throw new Error((err as { error?: string }).error ?? r.statusText)
  }
  if (r.status === 204) return undefined as T
  return r.json() as Promise<T>
}

export const api = {
  register: (email: string, password: string) =>
    req<{ accessToken: string }>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
      skipAuth: true,
    }),
  login: (email: string, password: string) =>
    req<{ accessToken: string }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
      skipAuth: true,
    }),
  events: (includeLoadTest = false) =>
    req<EventDto[]>(
      includeLoadTest ? '/api/events?includeLoadTest=true' : '/api/events',
      { skipAuth: true },
    ),
  createEvent: (body: CreateEventBody) =>
    req<EventDto>('/api/events', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  deleteEvent: (eventId: number) => reqEmpty(`/api/events/${eventId}`, { method: 'DELETE' }),
  seats: (eventId: number) => req<SeatDto[]>(`/api/events/${eventId}/seats`, { skipAuth: true }),
  joinQueue: (eventId: number) =>
    req<{
      position: number
      totalWaiting: number
    }>(`/api/events/${eventId}/queue`, { method: 'POST' }),
  queueMe: (eventId: number) =>
    req<{ inQueue: boolean; position: number; totalWaiting: number }>(
      `/api/events/${eventId}/queue/me`,
    ),
  admission: async (eventId: number): Promise<{ token: string } | null> => {
    const tok = getToken()
    const r = await fetch(`${API_BASE}/api/events/${eventId}/admission`, {
      headers: tok ? { Authorization: `Bearer ${tok}` } : {},
    })
    if (r.status === 404) return null
    if (!r.ok) {
      const err = await r.json().catch(() => ({ error: r.statusText }))
      throw new Error((err as { error?: string }).error ?? r.statusText)
    }
    return r.json() as Promise<{ token: string }>
  },
  reserve: (eventId: number, seatId: number, admissionToken: string) =>
    req<ReservationDto>(`/api/events/${eventId}/reservations`, {
      method: 'POST',
      body: JSON.stringify({ seatId, admissionToken }),
    }),
  reservationProgress: (eventId: number, reservationId: number) =>
    req<ReservationPaymentProgressDto>(`/api/events/${eventId}/reservations/${reservationId}/progress`),

  dashboardStatus: () => req<DashboardStatusDto>(`/api/dashboard/status`),
  dashboardPing: () => req<{ time: string }>(`/api/dashboard/ping`),
  dashboardRealtime: () =>
    req<{ time?: string; tps?: number; p99Latency?: number; meanLatencyMs?: number; queueDepth?: number }>(
      `/api/dashboard/realtime`,
    ),
  dashboardBusinessMetrics: () =>
    req<{
      time?: string
      paymentRequestedTotal?: number
      paymentSucceededTotal?: number
      paymentFailedTotal?: number
      paymentSettledTotal?: number
      paymentInflight?: number
      paymentQueueDepth?: number
      paymentWorkersSleeping?: number
      paymentWorkerSleepMsTotal?: number
      paymentProcessing?: number
      paymentDroppedMissingReservationTotal?: number
      paymentSkippedDuplicateTotal?: number
      paymentSettleSkippedAlreadyTerminalTotal?: number
      paymentRequestedExpectedTotal?: number
      paymentRequestedMismatch?: number
      paymentWipFromCounters?: number
      queueEnteredTotal?: number
      admissionIssuedTotal?: number
      seatLockFailedTotal?: number
      reservationExpiredTotal?: number
      rateLimitRejectedTotal?: number
      httpServerRequestTotal?: number
      reservationAttemptedTotal?: number
      reservationSucceededTotal?: number
      reservationFailedInvalidAdmissionTotal?: number
      reservationFailedSeatNotAvailableTotal?: number
      reservationFailedBadSeatTotal?: number
      clusterCountersEnabled?: boolean
    }>(`/api/dashboard/business-metrics`),

  dashboardRunMetrics: (runId: string) =>
    req<{
      runId: string
      found: boolean
      time: string
      queueEnteredTotal?: number
      admissionIssuedTotal?: number
      seatLockFailedTotal?: number
      reservationExpiredTotal?: number
      rateLimitRejectedTotal?: number
      httpServerRequestTotal?: number
      reservationAttemptedTotal?: number
      reservationSucceededTotal?: number
      reservationFailedInvalidAdmissionTotal?: number
      reservationFailedSeatNotAvailableTotal?: number
      reservationFailedBadSeatTotal?: number
      paymentRequestedTotal?: number
      paymentSucceededTotal?: number
      paymentFailedTotal?: number
    }>(`/api/dashboard/run-metrics?runId=${encodeURIComponent(runId)}`),

  ngrinderStatus: (id: number) => req<NgrinderStatusResponse>(`/api/dashboard/ngrinder/tests/${id}/status`),
  ngrinderPerf: (id: number, dataType = 'TPS,Errors,Mean_Test_Time', imgWidth = 800) =>
    req<NgrinderPerfResponse>(
      `/api/dashboard/ngrinder/tests/${id}/perf?dataType=${encodeURIComponent(dataType)}&imgWidth=${imgWidth}`,
    ),
  ngrinderLogs: (id: number) => req<NgrinderLogsResponse>(`/api/dashboard/ngrinder/tests/${id}/logs`),
  ngrinderStop: (id: number) => reqEmpty(`/api/dashboard/ngrinder/tests/${id}/stop`, { method: 'POST' }),
  /** Start a test that runs until payment-requested delta reaches requestedCount, then stops. */
  ngrinderStartPaymentRequestedCount: (requestedCount: number) =>
    req<NgrinderPerfTestEntity>(
      `/api/dashboard/ngrinder/payment-requests/start?requestedCount=${encodeURIComponent(String(requestedCount))}`,
      { method: 'POST' },
    ),

  ngrinderStartScenario: (
    scenario: 'A' | 'B' | 'C' | 'D' | 'E' | 'F',
    opts: {
      baseUrl?: string
      vusers?: number
      threads?: number
      eventSeatCount?: number
      testDurationSec?: number
      sleepMs?: number
      crowdMultiplier?: number
    } = {},
  ) => {
    const qs = new URLSearchParams()
    qs.set('scenario', scenario)
    if (opts.baseUrl) qs.set('baseUrl', opts.baseUrl)
    if (typeof opts.vusers === 'number') qs.set('vusers', String(opts.vusers))
    if (typeof opts.threads === 'number') qs.set('threads', String(opts.threads))
    if (typeof opts.eventSeatCount === 'number') qs.set('eventSeatCount', String(opts.eventSeatCount))
    if (typeof opts.testDurationSec === 'number') qs.set('testDurationSec', String(opts.testDurationSec))
    if (typeof opts.sleepMs === 'number') qs.set('sleepMs', String(opts.sleepMs))
    if (typeof opts.crowdMultiplier === 'number') qs.set('crowdMultiplier', String(opts.crowdMultiplier))
    return req<NgrinderPerfTestEntity>(`/api/dashboard/ngrinder/scenarios/start?${qs.toString()}`, { method: 'POST' })
  },

  opsSummary: () =>
    req<{
      time: string
      queueDepth: number
      pendingReservations: number
      processingPayments: number
      activeUsersEstimate: number
      seatsTotal: number
      seatsAvailable: number
      seatsHeld: number
      seatsSold: number
      seatsRemainingRatio: number
    }>(`/api/ops/summary`),

  opsOpenEvents: () => req<Array<{ id: number; name: string }>>(`/api/ops/events/open`),

  opsHeatmap: (eventId: number) =>
    req<Array<{ id: number; seatNumber: string; status: string; grade?: string }>>(
      `/api/ops/events/${eventId}/heatmap`,
    ),

  opsEventSummary: (eventId: number) =>
    req<{
      time: string
      eventId: number
      queueDepth: number
      pendingReservations: number
      processingPayments: number
      activeUsersEstimate: number
      seatsTotal: number
      seatsAvailable: number
      seatsHeld: number
      seatsSold: number
      seatsRemainingRatio: number
      seatsOccupiedRatio: number
    }>(`/api/ops/events/${eventId}/summary`),
}

export type DepStatus = { ok: boolean; latencyMs: number; error?: string }
export interface DashboardStatusDto {
  time: string
  deps: {
    mysql: DepStatus
    redis: DepStatus
    rabbitmq: DepStatus
    kafka: DepStatus
  }
}

export interface CreateEventBody {
  name: string
  venue: string
  startDate: string
  seatCount: number
  seatPrice: number
  grade?: string
}

export interface EventDto {
  id: number
  name: string
  venue: string
  startDate: string
  status: string
  /** PUBLIC = 일반 공연, LOAD_TEST = 부하테스트용(기본 목록에서 숨김) */
  listingScope?: string
}

export interface SeatDto {
  id: number
  eventId: number
  seatNumber: string
  grade: string
  price: number
  status: string
}

export interface ReservationDto {
  id: number
  userId: number
  eventId: number
  seatId: number
  status: string
}

export interface ReservationPaymentProgressDto {
  reservationId: number
  reservationStatus: string
  reservedAt: string
  paymentStatus: string | null
  paymentStartedAt: string | null
  paymentFinishedAt: string | null
  failureCode: string | null
  failureMessage: string | null
}

export type NgrinderTestStatusName =
  | 'SAVED'
  | 'READY'
  | 'TESTING'
  | 'FINISHED'
  | 'STOP_BY_ERROR'
  | 'CANCELED'
  | 'UNKNOWN'

/** nGrinder log list endpoint returns an array of file names (strings). */
export type NgrinderLogsResponse = string[]

/** Partial perf test returned after READY / clone (fields vary by version). */
export interface NgrinderPerfTestEntity {
  id?: number
  testName?: string
  status?: { name?: NgrinderTestStatusName }
  loadTestRunId?: string
}

export interface NgrinderStatusResponse {
  id: number
  message: string
  status: { name: NgrinderTestStatusName }
}

export interface NgrinderPerfResponse {
  TPS?: { TPS?: Array<number | null> }
  Throughput?: { Throughput?: Array<number | null> }
  Errors?: { Errors?: Array<number | null> }
  Mean_Test_Time?: { ['Mean_Test_Time_(ms)']?: Array<number | null> }
  chartInterval?: number
}
