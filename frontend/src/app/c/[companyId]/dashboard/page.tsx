"use client";

import Link from "next/link";
import { useApi } from "@/lib/api";
import { defaultClosingMonth, monthLabel } from "@/lib/closing";
import { monthRange, todayKst } from "@/lib/dates";
import { DIRECTION_LABEL, type AnomalySummary, type ClosingReport, type AuditLog, type JournalEntrySummary, type PageResponse, type DocumentSummary, type ReconSummary, type Transaction } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Badge, Card, Empty, Money, StatusBadge, formatDateTime, td, th } from "@/components/ui";
import { actionLabel } from "../audit-logs/labels";

type StatTone = "neutral" | "accent" | "warn" | "danger" | "blue";

const STAT_TONE: Record<StatTone, string> = {
  neutral: "border-line bg-surface",
  accent: "border-accent/20 bg-gradient-to-br from-surface to-accent-soft/70",
  warn: "border-warn/20 bg-gradient-to-br from-surface to-warn-soft/65",
  danger: "border-danger/20 bg-gradient-to-br from-surface to-danger-soft/55",
  blue: "border-blue/20 bg-gradient-to-br from-surface to-blue-soft/65",
};

function Stat({ label, value, sub, href, pending, tone = "neutral" }: { label: string; value?: number | string; sub?: string; href?: string; pending?: string; tone?: StatTone }) {
  const body = (
    <div className={`group h-full rounded-xl border p-4 shadow-sm shadow-black/[0.02] transition-all hover:-translate-y-0.5 hover:shadow-md hover:shadow-black/[0.05] ${STAT_TONE[tone]}`}>
      <div className="flex items-start justify-between gap-3">
        <p className="text-xs font-semibold text-ink-soft">{label}</p>
        {href && <span className="text-xs text-ink-soft/40 transition group-hover:text-accent">↗</span>}
      </div>
      {pending ? (
        <p className="mt-2 text-sm text-ink-soft/70">{pending}</p>
      ) : (
        <p className="num mt-2 text-left text-2xl font-semibold tracking-[-0.04em]">{value ?? "–"}</p>
      )}
      {sub && <p className="mt-2 min-h-4 text-xs leading-5 text-ink-soft">{sub}</p>}
    </div>
  );
  return href ? <Link href={href} className="rounded-xl">{body}</Link> : body;
}

export default function DashboardPage() {
  const { base, company } = useCompany();
  const today = todayKst();
  const month = monthRange(today);
  const link = (p: string) => `/c/${company.id}/${p}`;

  const monthTx = useApi<PageResponse<Transaction>>(`${base}/transactions`, { ...month, status: "PENDING", size: 1 });
  const pendingJe = useApi<PageResponse<JournalEntrySummary>>(`${base}/journal-entries`, { status: "PENDING", size: 1 });
  const monthJe = useApi<PageResponse<JournalEntrySummary>>(`${base}/journal-entries`, { ...month, status: "PENDING", size: 1 });
  const recentTx = useApi<PageResponse<Transaction>>(`${base}/transactions`, { size: 6 });
  // VIEWER는 Audit Log 조회 권한이 없다.
  const audit = useApi<PageResponse<AuditLog>>(company.role === "VIEWER" ? null : `${base}/audit-logs`, { size: 6 });
  const recon = useApi<ReconSummary>(`${base}/reconciliation/summary`);
  const docs = useApi<DocumentSummary>(`${base}/documents/summary`);
  const anomalies = useApi<AnomalySummary>(`${base}/anomalies/summary`);
  // 마감 대상은 지난 달
  const closingMonth = defaultClosingMonth();
  const closing = useApi<ClosingReport>(`${base}/closing/${closingMonth}`);
  const rate = (v: number | null | undefined) => (v === null || v === undefined ? undefined : `${v.toFixed(1)}%`);

  return (
    <>
      <section className="relative mb-7 overflow-hidden rounded-2xl bg-sidebar px-6 py-6 text-white shadow-lg shadow-sidebar/10 sm:px-7 sm:py-7">
        <div className="absolute -right-16 -top-20 size-64 rounded-full bg-accent/25 blur-3xl" />
        <div className="relative flex flex-wrap items-end justify-between gap-5">
          <div>
            <div className="mb-3 flex items-center gap-2">
              <Badge tone="accent">LIVE</Badge>
              <span className="text-xs font-medium text-white/50">{month.from.slice(0, 7)} 기준</span>
            </div>
            <h1 className="text-2xl font-bold tracking-[-0.035em] sm:text-3xl">{company.name} 회계 대시보드</h1>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-white/60">거래부터 증빙, 대사, 이상거래, 월마감까지 현재 업무 상태를 한눈에 확인하세요.</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Link href={link("copilot")} className="rounded-lg border border-white/15 bg-white/10 px-3.5 py-2 text-sm font-semibold transition hover:bg-white/15">
              AI에게 질문하기
            </Link>
            <Link href={link("transactions")} className="rounded-lg bg-white px-3.5 py-2 text-sm font-semibold text-sidebar transition hover:bg-white/90">
              거래 확인하기
            </Link>
          </div>
        </div>
      </section>

      <div className="mb-3 flex items-center justify-between">
        <div>
          <h2 className="text-base font-bold">이번 달 핵심 지표</h2>
          <p className="mt-0.5 text-xs text-ink-soft">등록된 회계 데이터의 실시간 요약입니다.</p>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="이번 달 거래" value={monthTx.data?.totalElements} sub="취소 거래 제외" href={link("transactions")} tone="accent" />
        <Stat label="이번 달 전표" value={monthJe.data?.totalElements} sub="취소 전표 제외" href={link("journal-entries")} tone="blue" />
        <Stat label="자동대사 성공률" value={recon.data ? (rate(recon.data.autoSuccessRate) ?? "–") : undefined} sub={recon.data ? `승인 ${recon.data.autoConfirmed} · 거절 ${recon.data.autoRejected}` : undefined} href={link("reconciliation")} tone="accent" />
        <Stat
          label={`월마감 진행률 · ${monthLabel(closingMonth)}`}
          value={closing.data ? `${closing.data.progress.percent}%` : undefined}
          sub={closing.data ? (closing.data.closing.status === "CLOSED" ? "마감 확정 완료" : `체크리스트 ${closing.data.progress.done}/${closing.data.progress.total}`) : undefined}
          href={link(`closing?month=${closingMonth}`)}
          tone="blue"
        />
      </div>

      <div className="mb-3 mt-7">
        <h2 className="text-base font-bold">확인이 필요한 업무</h2>
        <p className="mt-0.5 text-xs text-ink-soft">처리 지연이나 위험 신호가 있는 항목을 우선 확인하세요.</p>
      </div>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="미승인 전표" value={pendingJe.data?.totalElements} sub="검토 후 승인 필요" href={link("journal-entries")} tone={pendingJe.data?.totalElements ? "warn" : "neutral"} />
        <Stat
          label="처리되지 않은 증빙"
          value={docs.data?.unprocessed}
          sub={docs.data ? `확인 필요 ${docs.data.byStatus.REVIEW_REQUIRED} · 실패 ${docs.data.byStatus.FAILED} · 처리 중 ${docs.data.byStatus.PENDING}` : undefined}
          href={link("documents")}
          tone={docs.data?.unprocessed ? "warn" : "neutral"}
        />
        <Stat label="미매칭 은행 거래" value={recon.data?.unmatched} sub={recon.data ? `검토 대기 ${recon.data.suggested} · 대사율 ${rate(recon.data.matchRate) ?? "–"}` : undefined} href={link("reconciliation")} tone={recon.data?.unmatched ? "warn" : "neutral"} />
        <Stat
          label="이상거래 / 고위험"
          value={anomalies.data ? `${anomalies.data.open} / ${anomalies.data.highRisk}` : undefined}
          sub={
            anomalies.data
              ? anomalies.data.lastScan
                ? `미검토 기준 · 심각 ${anomalies.data.openByLevel.CRITICAL} · 분석 ${anomalies.data.lastScan.periodTo}까지`
                : "아직 분석하지 않음"
              : undefined
          }
          href={link("anomalies")}
          tone={anomalies.data?.highRisk ? "danger" : "neutral"}
        />
      </div>

      <div className="mt-7 grid gap-6 lg:grid-cols-2">
        <Card title="최근 거래" actions={<Link href={link("transactions")} className="text-xs text-accent hover:underline">전체 보기</Link>}>
          {recentTx.data?.content.length === 0 ? (
            <Empty>등록된 거래가 없습니다.</Empty>
          ) : (
            <table className="w-full">
              <thead>
                <tr>
                  <th className={th}>거래일</th>
                  <th className={th}>거래처</th>
                  <th className={`${th} text-right`}>금액</th>
                  <th className={th}>상태</th>
                </tr>
              </thead>
              <tbody>
                {recentTx.data?.content.map((t) => (
                  <tr key={t.id}>
                    <td className={`${td} num text-left`}>{t.txDate}</td>
                    <td className={`${td} max-w-40 truncate`}>{t.counterparty ?? "-"}</td>
                    <td className={`${td} text-right`}>
                      <span className={t.direction === "IN" ? "text-accent" : ""}>
                        {DIRECTION_LABEL[t.direction]} <Money value={t.amount} />
                      </span>
                    </td>
                    <td className={td}>
                      <StatusBadge status={t.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <Card title="최근 변경 이력" actions={audit.data && <Link href={link("audit-logs")} className="text-xs text-accent hover:underline">전체 보기</Link>}>
          {company.role === "VIEWER" ? (
            <Empty>조회자 권한으로는 변경 이력을 볼 수 없습니다.</Empty>
          ) : audit.data?.content.length === 0 ? (
            <Empty>이력이 없습니다.</Empty>
          ) : (
            <ul>
              {audit.data?.content.map((a) => (
                <li key={a.id} className="flex items-center justify-between border-b border-line px-4 py-2 text-sm last:border-b-0">
                  <span>
                    {actionLabel(a.action)}
                    {a.targetId != null && <span className="text-ink-soft"> #{a.targetId}</span>}
                  </span>
                  <span className="text-xs text-ink-soft">{formatDateTime(a.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </>
  );
}
