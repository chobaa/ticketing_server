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
  events: () => req<EventDto[]>('/api/events', { skipAuth: true }),
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
    }>(`/api/dashboard/business-metrics`),

  /** Page index is 0-based (nGrinder / Spring Data). */
  ngrinderTests: (page = 0, size = 20) =>
    req<NgrinderTestsListResponse>(`/api/dashboard/ngrinder/tests?page=${page}&size=${size}`),
  ngrinderStatus: (id: number) => req<NgrinderStatusResponse>(`/api/dashboard/ngrinder/tests/${id}/status`),
  ngrinderPerf: (id: number, dataType = 'TPS,Errors,Mean_Test_Time', imgWidth = 800) =>
    req<NgrinderPerfResponse>(
      `/api/dashboard/ngrinder/tests/${id}/perf?dataType=${encodeURIComponent(dataType)}&imgWidth=${imgWidth}`,
    ),
  ngrinderLogs: (id: number) => req<NgrinderLogsResponse>(`/api/dashboard/ngrinder/tests/${id}/logs`),
  ngrinderReady: (id: number) =>
    req<NgrinderPerfTestEntity>(`/api/dashboard/ngrinder/tests/${id}/ready`, { method: 'POST' }),
  ngrinderStop: (id: number) => reqEmpty(`/api/dashboard/ngrinder/tests/${id}/stop`, { method: 'POST' }),
  ngrinderCloneStart: (id: number) =>
    req<NgrinderPerfTestEntity>(`/api/dashboard/ngrinder/tests/${id}/clone-and-start`, { method: 'POST' }),
  ngrinderDeleteAll: () => reqEmpty(`/api/dashboard/ngrinder/tests/delete-all`, { method: 'POST' }),
  /** Start a single test that runs until paymentCount payments are settled, then auto-stops. */
  ngrinderStartPayments: (paymentCount: number) =>
    req<NgrinderPerfTestEntity>(`/api/dashboard/ngrinder/payments/start?paymentCount=${encodeURIComponent(String(paymentCount))}`, {
      method: 'POST',
    }),
  /** Start a deterministic test that issues exactly requestCount reserve requests then finishes. */
  ngrinderStartRequestCount: (requestCount: number) =>
    req<NgrinderPerfTestEntity>(
      `/api/dashboard/ngrinder/requests/start?requestCount=${encodeURIComponent(String(requestCount))}`,
      { method: 'POST' },
    ),
  /** Start a test that runs until payment-requested delta reaches requestedCount, then stops. */
  ngrinderStartPaymentRequestedCount: (requestedCount: number) =>
    req<NgrinderPerfTestEntity>(
      `/api/dashboard/ngrinder/payment-requests/start?requestedCount=${encodeURIComponent(String(requestedCount))}`,
      { method: 'POST' },
    ),
  ngrinderStartPreset: (
    key: string,
    opts?: {
      baseUrl?: string
      vusers?: number
      threads?: number
      testDurationSec?: number
      eventSeatCount?: number
      seatPoolSize?: number
    },
  ) =>
    req<NgrinderPerfTestEntity>(
      (() => {
        const p = new URLSearchParams()
        if (opts?.baseUrl) p.set('baseUrl', opts.baseUrl)
        if (opts?.vusers != null) p.set('vusers', String(opts.vusers))
        if (opts?.threads != null) p.set('threads', String(opts.threads))
        if (opts?.testDurationSec != null) p.set('testDurationSec', String(opts.testDurationSec))
        if (opts?.eventSeatCount != null) p.set('eventSeatCount', String(opts.eventSeatCount))
        if (opts?.seatPoolSize != null) p.set('seatPoolSize', String(opts.seatPoolSize))
        const qs = p.toString()
        return `/api/dashboard/ngrinder/presets/${encodeURIComponent(key)}/start${qs ? `?${qs}` : ''}`
      })(),
      { method: 'POST' },
    ),
  ngrinderRunAllPresets: (baseUrl?: string) =>
    req<Record<string, NgrinderPerfTestEntity | { error?: string }>>(
      `/api/dashboard/ngrinder/presets/run-all${baseUrl ? `?baseUrl=${encodeURIComponent(baseUrl)}` : ''}`,
      { method: 'POST' },
    ),
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

export interface NgrinderTestItem {
  id: number
  testName?: string
  name?: string
  description?: string
  createdAt?: number
  lastModifiedAt?: number
  status?: { name?: NgrinderTestStatusName } | string
}

export interface NgrinderTestsListResponse {
  tests?: NgrinderTestItem[]
  totalElements?: number
  number?: number
  size?: number
  /** Present when backend wrapped a raw JSON array from older nGrinder list APIs. */
  hasNext?: boolean
}

/** nGrinder log list endpoint returns an array of file names (strings). */
export type NgrinderLogsResponse = string[]

/** Partial perf test returned after READY / clone (fields vary by version). */
export interface NgrinderPerfTestEntity {
  id?: number
  testName?: string
  status?: { name?: NgrinderTestStatusName }
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
