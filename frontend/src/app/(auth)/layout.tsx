export default function AuthLayout({ children }: LayoutProps<"/">) {
  return (
    <main className="grid min-h-screen lg:grid-cols-[1fr_480px]">
      <aside className="hidden flex-col justify-between bg-sidebar p-12 text-sidebar-ink lg:flex">
        <p className="text-sm font-semibold tracking-widest text-white">LEDGERGUARD AI</p>
        <div>
          <p className="text-3xl leading-snug font-semibold text-white">
            알고리즘과 규칙이 판단하고,
            <br />
            AI가 설명하며,
            <br />
            사람이 최종 승인합니다.
          </p>
          <p className="mt-4 max-w-md text-sm leading-relaxed">
            증빙 인식, 은행 거래 대사, 이상거래 탐지, 월마감 분석을 하나의 회계 Workflow로 연결하는 업무 보조 플랫폼.
          </p>
        </div>
        <p className="text-xs">개발용 가상 데이터만 사용합니다.</p>
      </aside>
      <div className="flex items-center justify-center bg-paper px-6 py-12">
        <div className="w-full max-w-sm">{children}</div>
      </div>
    </main>
  );
}
