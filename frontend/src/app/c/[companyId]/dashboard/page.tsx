"use client";

import Link from "next/link";
import { useApi } from "@/lib/api";
import { monthRange, todayKst } from "@/lib/dates";
import { DIRECTION_LABEL, type AuditLog, type JournalEntrySummary, type PageResponse, type Transaction } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Card, Empty, Money, PageHeader, StatusBadge, formatDateTime, td, th } from "@/components/ui";
import { actionLabel } from "../audit-logs/labels";

function Stat({ label, value, sub, href, pending }: { label: string; value?: number | string; sub?: string; href?: string; pending?: string }) {
  const body = (
    <div className="h-full rounded-lg border border-line bg-surface p-4 transition-colors hover:border-line-strong">
      <p className="text-xs text-ink-soft">{label}</p>
      {pending ? (
        <p className="mt-2 text-sm text-ink-soft/70">{pending}</p>
      ) : (
        <p className="num mt-1 text-left text-2xl font-medium">{value ?? "–"}</p>
      )}
      {sub && <p className="mt-1 text-xs text-ink-soft">{sub}</p>}
    </div>
  );
  return href ? <Link href={href}>{body}</Link> : body;
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

  return (
    <>
      <PageHeader title="Dashboard" description={`${month.from.slice(0, 7)} 회계 현황`} />

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="이번 달 거래" value={monthTx.data?.totalElements} sub="취소 제외" href={link("transactions")} />
        <Stat label="이번 달 전표" value={monthJe.data?.totalElements} sub="취소 제외" href={link("journal-entries")} />
        <Stat label="미승인 전표" value={pendingJe.data?.totalElements} sub="승인 Workflow는 이후 Phase" href={link("journal-entries")} />
        <Stat label="처리되지 않은 증빙" pending="Phase 3 · Document AI" />
        <Stat label="자동대사 성공률" pending="Phase 2 · Bank Reconciliation" />
        <Stat label="미매칭 거래" pending="Phase 2 · Bank Reconciliation" />
        <Stat label="이상거래 / 고위험" pending="Phase 4 · Anomaly Detection" />
        <Stat label="월마감 진행률" pending="Phase 6 · Closing Copilot" />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
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
