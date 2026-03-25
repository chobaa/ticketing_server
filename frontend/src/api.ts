const API_BASE = import.meta.env.VITE_API_BASE ?? ''

export function getToken(): string | null {
  return localStorage.getItem('accessToken')
}

export function setToken(t: string | null) {
  if (t) localStorage.setItem('accessToken', t)
  else localStorage.removeItem('accessToken')
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
