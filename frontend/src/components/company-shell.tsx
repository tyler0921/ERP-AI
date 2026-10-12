"use client";

import Link from "next/link";
import { useParams, usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { useApi } from "@/lib/api";
import { ROLE_LABEL, type Company } from "@/lib/types";
import { CompanyProvider } from "@/components/company-context";
import { ErrorBanner, Loading } from "@/components/ui";
import { LogoutButton } from "@/components/logout-button";

// 사이드바는 spec의 전체 화면 구성을 보여주되, 아직 구현되지 않은 Phase의 메뉴는 비활성으로 표시한다.
const NAV: { href: string; label: string; phase?: number }[] = [
  { href: "dashboard", label: "Dashboard" },
  { href: "transactions", label: "거래" },
  { href: "journal-entries", label: "전표" },
  { href: "accounts", label: "계정과목" },
  { href: "documents", label: "증빙 문서" },
  { href: "reconciliation", label: "은행 대사" },
  { href: "anomalies", label: "이상거래" },
  { href: "closing", label: "월마감" },
  { href: "copilot", label: "AI Copilot", phase: 7 },
  { href: "rules", label: "회계 규정", phase: 8 },
  { href: "audit-logs", label: "Audit Log" },
  { href: "settings", label: "설정" },
];

export function CompanyShell({ children }: { children: ReactNode }) {
  const { companyId } = useParams<{ companyId: string }>();
  const pathname = usePathname();
  const { data: company, error, reload } = useApi<Company>(/^\d+$/.test(companyId) ? `/companies/${companyId}` : null);

  return (
    <div className="flex min-h-screen">
      <aside className="sticky top-0 hidden h-screen w-56 shrink-0 flex-col bg-sidebar text-sidebar-ink md:flex">
        <div className="border-b border-white/10 px-4 py-4">
          <p className="text-[11px] font-semibold tracking-widest text-white/60">LEDGERGUARD AI</p>
          <Link href="/companies" className="mt-1 block truncate text-sm font-semibold text-white hover:underline" title="회사 전환">
            {company?.name ?? "…"}
          </Link>
          {company && <p className="mt-0.5 text-xs">{ROLE_LABEL[company.role]}</p>}
        </div>
        <nav className="flex-1 overflow-y-auto px-2 py-3">
          {NAV.map((item) => {
            const href = `/c/${companyId}/${item.href}`;
            const active = pathname === href || pathname.startsWith(`${href}/`);
            if (item.phase) {
              return (
                <span key={item.href} className="flex cursor-default items-center justify-between rounded px-2.5 py-1.5 text-sm text-white/30" title={`Phase ${item.phase}에서 제공 예정`}>
                  {item.label}
                  <span className="text-[10px]">P{item.phase}</span>
                </span>
              );
            }
            return (
              <Link
                key={item.href}
                href={href}
                className={`block rounded px-2.5 py-1.5 text-sm ${active ? "bg-white/10 font-medium text-white" : "hover:bg-white/5 hover:text-white"}`}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>
        <div className="border-t border-white/10 px-4 py-3">
          <LogoutButton className="text-xs hover:text-white" />
        </div>
      </aside>

      <div className="min-w-0 flex-1">
        {/* 모바일용 간단 네비게이션 */}
        <nav className="flex gap-1 overflow-x-auto border-b border-line bg-surface px-3 py-2 md:hidden">
          <Link href="/companies" className="shrink-0 rounded px-2 py-1 text-sm font-semibold">
            {company?.name ?? "…"}
          </Link>
          {NAV.filter((n) => !n.phase).map((item) => (
            <Link key={item.href} href={`/c/${companyId}/${item.href}`} className="shrink-0 rounded px-2 py-1 text-sm text-ink-soft">
              {item.label}
            </Link>
          ))}
        </nav>
        <main className="mx-auto max-w-6xl px-4 py-6 md:px-8">
          {error ? (
            <div className="space-y-3">
              <ErrorBanner error={error} />
              <Link href="/companies" className="text-sm text-accent hover:underline">
                회사 목록으로
              </Link>
            </div>
          ) : !company ? (
            <Loading />
          ) : (
            <CompanyProvider company={company} reloadCompany={reload}>
              {children}
            </CompanyProvider>
          )}
        </main>
      </div>
    </div>
  );
}
