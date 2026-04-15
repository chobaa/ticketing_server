import type { ReactNode } from 'react'

type Props = { children: ReactNode; className?: string; contentClassName?: string }

export function LiquidGlassPanel({ children, className = '', contentClassName }: Props) {
  return (
    <div
      className={`relative overflow-hidden rounded-[2rem] border border-white/20 shadow-xl transition-all duration-300 ease-in-out ${className}`}
    >
      <div
        className="absolute inset-0 z-0 bg-white/15 backdrop-blur-lg"
        style={{ backdropFilter: 'blur(16px) saturate(180%)' }}
      />
      <div className="pointer-events-none absolute inset-0 z-10 rounded-[2rem] bg-gradient-to-br from-white/30 to-transparent opacity-50 shadow-[inset_0_4px_20px_rgba(255,255,255,0.3)]" />
      <div
        className={`relative z-20 text-neutral-800 dark:text-neutral-100 ${contentClassName ?? 'p-6'}`}
      >
        {children}
      </div>
    </div>
  )
}
