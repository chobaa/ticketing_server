import { useEffect, useState } from 'react'
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

type Point = { time: string; tps: number; p99Latency: number; queueDepth: number }

export function TrafficAnalyticsDashboard() {
  const [data, setData] = useState<Point[]>([])

  useEffect(() => {
    const path = '/ws/metrics'
    const explicit = import.meta.env.VITE_WS_URL as string | undefined
    const url =
      explicit ||
      `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${path}`
    const ws = new WebSocket(url)
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
        setData((prev) => {
          const next = [...prev, pt]
          return next.slice(-60)
        })
      } catch {
        /* ignore */
      }
    }
    return () => ws.close()
  }, [])

  const last = data[data.length - 1]

  return (
    <LiquidGlassPanel className="flex h-96 w-full max-w-4xl flex-col p-6">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-inter text-xl font-bold tracking-tight text-neutral-800 dark:text-white">
          Real-time System Analytics
        </h3>
        <div className="flex flex-wrap gap-4 text-sm font-medium">
          <span className="text-[#007AFF]">TPS: {last?.tps ?? 0}</span>
          <span className="text-[#FF3B30]">p99: {last ? `${last.p99Latency}ms` : '—'}</span>
          <span className="text-violet-500">Queue: {last?.queueDepth ?? 0}</span>
        </div>
      </div>
      <div className="min-h-0 flex-1 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 5, right: 8, left: -16, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.2)" vertical={false} />
            <XAxis dataKey="time" stroke="rgba(163,163,163,0.8)" tick={{ fontSize: 11, fontFamily: 'Inter' }} />
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
            <Line type="monotone" dataKey="tps" name="TPS" stroke="#007AFF" strokeWidth={3} dot={false} />
            <Line
              type="monotone"
              dataKey="p99Latency"
              name="p99 ms"
              stroke="#FF3B30"
              strokeWidth={2}
              dot={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </LiquidGlassPanel>
  )
}
