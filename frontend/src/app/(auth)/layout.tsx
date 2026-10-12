export default function AuthLayout({ children }: LayoutProps<"/">) {
  return (
    <main className="grid min-h-screen lg:grid-cols-[minmax(0,1fr)_520px]">
      <aside className="relative hidden overflow-hidden bg-sidebar p-12 text-sidebar-ink lg:flex lg:flex-col lg:justify-between xl:p-16">
        <div className="absolute -left-32 bottom-20 size-96 rounded-full bg-accent/20 blur-3xl" />
        <div className="absolute -right-28 -top-24 size-80 rounded-full border border-white/10" />
        <div className="absolute -right-12 top-4 size-56 rounded-full border border-white/[0.07]" />
        <div className="relative flex items-center gap-3 text-white">
          <span className="grid size-10 place-items-center rounded-xl bg-accent text-sm font-bold shadow-xl shadow-black/20">LG</span>
          <span>
            <span className="block text-base font-bold tracking-tight">LedgerGuard</span>
            <span className="block text-[10px] font-semibold uppercase tracking-[0.22em] text-white/45">Accounting AI</span>
          </span>
        </div>
        <div className="relative max-w-2xl">
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-[#79d8b1]">Reliable accounting workflow</p>
          <p className="mt-5 text-4xl font-bold leading-[1.25] tracking-[-0.04em] text-white xl:text-5xl">
            복잡한 회계 업무를
            <br />더 선명하게.
          </p>
          <p className="mt-6 max-w-xl text-base leading-7 text-white/60">
            증빙 인식부터 은행 대사, 이상거래 탐지, 월마감까지. 근거가 남는 AI와 함께 더 빠르고 안전하게 처리하세요.
          </p>
          <div className="mt-9 grid max-w-xl grid-cols-3 gap-3">
            {["증빙 자동 인식", "이상거래 탐지", "AI 회계 분석"].map((feature) => (
              <div key={feature} className="rounded-xl border border-white/10 bg-white/[0.045] px-3 py-3 text-xs font-medium text-white/75">
                <span className="mb-2 block size-1.5 rounded-full bg-[#67d5a7]" />
                {feature}
              </div>
            ))}
          </div>
        </div>
        <p className="relative text-xs text-white/35">보안과 감사 추적을 고려한 회계 업무 보조 플랫폼</p>
      </aside>
      <div className="relative flex items-center justify-center bg-paper px-6 py-12 sm:px-10">
        <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-line-strong to-transparent" />
        <div className="w-full max-w-sm rounded-2xl border border-line bg-surface p-7 shadow-xl shadow-black/[0.055] sm:p-8">{children}</div>
      </div>
    </main>
  );
}
