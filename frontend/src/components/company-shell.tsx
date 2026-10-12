"use client";

import Link from "next/link";
import { useParams, usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { useApi } from "@/lib/api";
import { ROLE_LABEL, type Company } from "@/lib/types";
import { CompanyProvider } from "@/components/company-context";
import { ErrorBanner, Loading } from "@/components/ui";
import { LogoutButton } from "@/components/logout-button";

const NAV = [
  {
    label: "Overview",
    items: [{ href: "dashboard", label: "대시보드" }],
  },
  {
    label: "회계 업무",
    items: [
      { href: "transactions", label: "거래" },
      { href: "journal-entries", label: "전표" },
      { href: "accounts", label: "계정과목" },
      { href: "documents", label: "증빙 문서" },
      { href: "reconciliation", label: "은행 대사" },
      { href: "closing", label: "월마감" },
    ],
  },
  {
    label: "AI 분석",
    items: [
      { href: "anomalies", label: "이상거래" },
      { href: "copilot", label: "AI Copilot", badge: "AI" },
      { href: "rules", label: "회계 규정" },
    ],
  },
  {
    label: "관리",
    items: [
      { href: "audit-logs", label: "감사 로그" },
      { href: "settings", label: "설정" },
    ],
  },
];

const ALL_NAV = NAV.flatMap((group) => group.items);

export function CompanyShell({ children }: { children: ReactNode }) {
  const { companyId } = useParams<{ companyId: string }>();
  const pathname = usePathname();
  const { data: company, error, reload } = useApi<Company>(/^\d+$/.test(companyId) ? `/companies/${companyId}` : null);

  return (
    <div className="flex min-h-screen bg-paper">
      <aside className="sticky top-0 hidden h-screen w-64 shrink-0 flex-col border-r border-white/5 bg-sidebar text-sidebar-ink md:flex">
        <div className="px-5 pb-4 pt-5">
          <Link href="/companies" className="flex items-center gap-3 text-white" aria-label="LedgerGuard AI 회사 선택">
            <span className="grid size-9 place-items-center rounded-xl bg-accent text-sm font-bold shadow-lg shadow-black/20">LG</span>
            <span>
              <span className="block text-sm font-bold tracking-tight">LedgerGuard</span>
              <span className="block text-[10px] font-semibold uppercase tracking-[0.2em] text-white/45">Accounting AI</span>
            </span>
          </Link>
          <Link
            href="/companies"
            className="mt-5 block rounded-xl border border-white/10 bg-white/[0.045] px-3.5 py-3 transition hover:border-white/20 hover:bg-white/[0.07]"
            title="회사 전환"
          >
            <span className="block text-[10px] font-semibold uppercase tracking-wider text-white/40">현재 회사</span>
            <span className="mt-1 flex items-center justify-between gap-2">
              <span className="truncate text-sm font-semibold text-white">{company?.name ?? "불러오는 중"}</span>
              <span className="text-white/35">↗</span>
            </span>
            {company && <span className="mt-1 block text-xs text-sidebar-ink">{ROLE_LABEL[company.role]}</span>}
          </Link>
        </div>
        <nav className="flex-1 overflow-y-auto px-3 pb-4" aria-label="주요 메뉴">
          {NAV.map((group) => (
            <div key={group.label} className="mb-4">
              <p className="px-3 pb-1.5 text-[10px] font-semibold uppercase tracking-[0.16em] text-white/30">{group.label}</p>
              <div className="space-y-0.5">
                {group.items.map((item) => {
                  const href = `/c/${companyId}/${item.href}`;
                  const active = pathname === href || pathname.startsWith(`${href}/`);
                  return (
                    <Link
                      key={item.href}
                      href={href}
                      aria-current={active ? "page" : undefined}
                      className={`group flex items-center justify-between rounded-lg px-3 py-2 text-sm transition ${
                        active ? "bg-white/10 font-semibold text-white shadow-sm" : "hover:bg-white/[0.055] hover:text-white"
                      }`}
                    >
                      <span className="flex items-center gap-2.5">
                        <span className={`size-1.5 rounded-full transition ${active ? "bg-[#67d5a7]" : "bg-white/20 group-hover:bg-white/40"}`} />
                        {item.label}
                      </span>
                      {item.badge && <span className="rounded bg-accent/30 px-1.5 py-0.5 text-[9px] font-bold text-[#8ce4bd]">{item.badge}</span>}
                    </Link>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>
        <div className="border-t border-white/10 px-5 py-4">
          <div className="mb-3 flex items-center gap-2 text-[11px] text-white/45">
            <span className="size-1.5 rounded-full bg-[#67d5a7] shadow-[0_0_0_3px_rgb(103_213_167/0.12)]" />
            모든 시스템 정상
          </div>
          <LogoutButton className="text-xs font-medium hover:text-white" />
        </div>
      </aside>

      <div className="min-w-0 flex-1">
        <div className="sticky top-0 z-20 border-b border-line bg-surface/95 backdrop-blur md:hidden">
          <div className="flex items-center justify-between px-4 py-3">
            <Link href="/companies" className="flex min-w-0 items-center gap-2">
              <span className="grid size-7 shrink-0 place-items-center rounded-lg bg-sidebar text-[10px] font-bold text-white">LG</span>
              <span className="truncate text-sm font-bold">{company?.name ?? "LedgerGuard"}</span>
            </Link>
            {company && <span className="rounded-full bg-accent-soft px-2 py-1 text-[10px] font-semibold text-accent">{ROLE_LABEL[company.role]}</span>}
          </div>
          <nav className="flex gap-1 overflow-x-auto px-3 pb-2" aria-label="모바일 메뉴">
            {ALL_NAV.map((item) => {
              const href = `/c/${companyId}/${item.href}`;
              const active = pathname === href || pathname.startsWith(`${href}/`);
              return (
                <Link
                  key={item.href}
                  href={href}
                  aria-current={active ? "page" : undefined}
                  className={`shrink-0 rounded-lg px-3 py-1.5 text-xs font-medium ${active ? "bg-sidebar text-white" : "text-ink-soft"}`}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>
        </div>
        <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6 md:px-8 md:py-8 xl:px-10">
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
