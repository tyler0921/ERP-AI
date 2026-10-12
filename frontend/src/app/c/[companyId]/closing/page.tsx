"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { useApi } from "@/lib/api";
import { currentMonthKst, defaultClosingMonth, isSelectableMonth, monthLabel, shiftMonth } from "@/lib/closing";
import { formatMoney, toCents } from "@/lib/money";
import { VARIANCE_BASIS_LABEL, VARIANCE_CODE_LABEL, type ClosingChange, type ClosingLine, type ClosingReport, type ClosingTrendPoint, type Member } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Badge, Button, Card, Empty, ErrorBanner, Input, Loading, Money, PageHeader, td, th } from "@/components/ui";
import { ExplanationPanel, usePollWhilePending } from "../anomalies/explanation";
import { ClosePanel } from "./close-panel";
import { TrendChart } from "./trend-chart";

const KIND_LABEL: Record<ClosingLine["kind"], string> = { TOTAL: "합계", ACCOUNT: "계정과목", VENDOR: "거래처" };

function MonthPicker({ month, current, onChange }: { month: string; current: string; onChange: (m: string) => void }) {
  return (
    <div className="flex items-center gap-1">
      <Button variant="ghost" aria-label="이전 달" onClick={() => onChange(shiftMonth(month, -1))}>
        ◀
      </Button>
      <Input
        type="month"
        value={month}
        max={current}
        onChange={(e) => isSelectableMonth(e.target.value, current) && onChange(e.target.value)}
        className="w-36"
        aria-label="기준월"
      />
      <Button variant="ghost" aria-label="다음 달" disabled={month >= current} onClick={() => onChange(shiftMonth(month, 1))}>
        ▶
      </Button>
    </div>
  );
}

function TotalCard({ line }: { line: ClosingLine }) {
  return (
    <div className="rounded-lg border border-line bg-surface p-4">
      <p className="text-xs text-ink-soft">{line.label}</p>
      <p className="mt-1 text-2xl font-medium">
        <Money value={line.current} />
        <span className="ml-0.5 text-sm text-ink-soft">원</span>
      </p>
      <dl className="mt-2 space-y-0.5 text-xs">
        <div className="flex justify-between gap-2">
          <dt className="text-ink-soft">전월</dt>
          <dd>{line.vsPrevMonth.text}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-ink-soft">전년 동월</dt>
          <dd>{line.vsPrevYear.text}</dd>
        </div>
      </dl>
    </div>
  );
}

/** "+2,700,000" 아래 "+15.3%" (비교값이 0이면 "신규"). 문장 전체는 title로 */
function ChangeCell({ change }: { change: ClosingChange }) {
  if (toCents(change.diff) === BigInt(0)) return <span className="text-ink-soft">-</span>;
  const up = !change.diff.startsWith("-");
  return (
    <>
      <span className="num">
        {up ? "+" : ""}
        {formatMoney(change.diff)}
      </span>
      <span className="block text-ink-soft">{change.pct === null ? "신규" : `${up ? "+" : ""}${change.pct}%`}</span>
    </>
  );
}

function LineTable({ lines, nameHeader, empty }: { lines: ClosingLine[]; nameHeader: string; empty: string }) {
  if (lines.length === 0) return <Empty>{empty}</Empty>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full">
        <thead>
          <tr>
            <th className={th}>{nameHeader}</th>
            <th className={`${th} text-right`}>당월</th>
            <th className={`${th} text-right`}>전월</th>
            <th className={`${th} text-right`}>전년 동월</th>
            <th className={`${th} text-right`}>전월 대비</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l) => (
            <tr key={l.key}>
              <td className={`${td} max-w-48 truncate`} title={l.label}>
                {l.label}
              </td>
              <td className={`${td} text-right`}>
                <Money value={l.current} />
              </td>
              <td className={`${td} text-right text-ink-soft`}>
                <Money value={l.prevMonth} />
              </td>
              <td className={`${td} text-right text-ink-soft`}>
                <Money value={l.prevYear} />
              </td>
              <td className={`${td} whitespace-nowrap text-right text-xs`} title={l.vsPrevMonth.text}>
                <ChangeCell change={l.vsPrevMonth} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ClosingView() {
  const router = useRouter();
  const params = useSearchParams();
  const { base, company, canWrite, isAdmin } = useCompany();
  const current = currentMonthKst();
  const requested = params.get("month");
  const month = isSelectableMonth(requested, current) ? requested : defaultClosingMonth();
  const go = (m: string) => router.replace(`/c/${company.id}/closing?month=${m}`);

  const report = useApi<ClosingReport>(`${base}/closing/${month}`);
  const trend = useApi<ClosingTrendPoint[]>(`${base}/closing/trend`, { to: month, months: 12 });
  const members = useApi<Member[]>(`${base}/members`);
  const reloadAll = () => {
    report.reload();
    trend.reload();
  };
  // 월을 바꾸는 동안 이전 달 보고서를 보여주지 않는다
  const r = report.data?.month === month ? report.data : undefined;
  usePollWhilePending(r?.explanation?.status, report.reload);

  const memberName = (userId: number | null | undefined) =>
    userId == null ? "-" : (members.data?.find((m) => m.userId === userId)?.name ?? `#${userId}`);
  const closed = r?.closing.status === "CLOSED";

  return (
    <>
      <PageHeader
        title="월마감"
        description="집계·비교·주요 변동·체크리스트는 전표와 규칙으로 계산합니다. AI는 계산된 결과를 설명만 하고, 마감은 관리자가 확정합니다."
        actions={<MonthPicker month={month} current={current} onChange={go} />}
      />

      <ErrorBanner error={report.error} />
      {!r ? (
        report.error ? null : <Loading />
      ) : (
        <div className="space-y-6">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className="font-semibold">{monthLabel(r.month)}</span>
            {closed ? <Badge tone="accent">마감 확정</Badge> : <Badge tone="warn">마감 전</Badge>}
            {r.closing.changedSinceClose && <Badge tone="danger">확정 이후 변경됨</Badge>}
            <span className="text-ink-soft">
              · 비교: 전월 {r.prevMonth}, 전년 동월 {r.prevYearMonth} · 취소(VOID) 전표 제외
            </span>
          </div>

          <div className="grid gap-3 md:grid-cols-3">
            {r.totals.map((l) => (
              <TotalCard key={l.key} line={l} />
            ))}
          </div>

          <div className="grid gap-6 lg:grid-cols-3">
            <Card title="월별 추이 (12개월)" className="lg:col-span-2">
              <ErrorBanner error={trend.error} />
              {trend.data ? <TrendChart points={trend.data} selected={month} onSelect={go} /> : <Loading />}
            </Card>

            <Card title={`마감 체크리스트 · ${r.progress.done}/${r.progress.total}`}>
              <div className="px-4 pt-3">
                <div className="h-1.5 overflow-hidden rounded-full bg-line" role="progressbar" aria-valuenow={r.progress.percent} aria-valuemin={0} aria-valuemax={100} aria-label="마감 진행률">
                  <div className="h-full rounded-full bg-accent" style={{ width: `${r.progress.percent}%` }} />
                </div>
                <p className="mt-1 text-xs text-ink-soft">{r.progress.percent}% 완료</p>
              </div>
              <ul className="px-2 py-2">
                {r.checklist.map((c) => (
                  <li key={c.code}>
                    <Link href={`/c/${company.id}/${c.href}`} className="flex items-start gap-2 rounded px-2 py-1.5 text-sm hover:bg-paper">
                      <span aria-hidden className={`w-4 shrink-0 text-center ${c.done ? "text-accent" : "text-ink-soft"}`}>
                        {c.done ? "✓" : "○"}
                      </span>
                      <span className="flex-1">
                        <span className={c.done ? "text-ink-soft" : "font-medium"}>{c.title}</span>
                        <span className="block text-xs text-ink-soft">{c.detail}</span>
                      </span>
                      {!c.done && c.remaining > 0 && <Badge tone="warn">{c.remaining}건</Badge>}
                    </Link>
                  </li>
                ))}
              </ul>
              <p className="border-t border-line px-4 py-2 text-xs text-ink-soft">
                이상거래: 검토 대기 {r.anomalies.open}건(고위험 {r.anomalies.highRisk}) · 이상 확정 {r.anomalies.confirmed} · 정상 {r.anomalies.dismissed}
              </p>
            </Card>
          </div>

          <div className="grid gap-6 lg:grid-cols-2">
            <Card title="AI 월마감 설명">
              <ExplanationPanel
                explanation={r.explanation}
                canWrite={canWrite}
                regeneratePath={`${base}/closing/${month}/explanation?force=true`}
                editPath={`${base}/closing/${month}/explanation`}
                memberName={memberName}
                onChanged={report.reload}
                emptyText="아직 설명이 없습니다. 집계 결과(근거 데이터)만 로컬 LLM에 보내 설명을 만듭니다. 원본 전표는 보내지 않습니다."
              />
            </Card>
            <Card title="마감 확정">
              <ClosePanel base={base} report={r} isAdmin={isAdmin} canClose={month < current} memberName={memberName} onChanged={reloadAll} />
            </Card>
          </div>

          <Card title="주요 변동" actions={<span className="text-xs text-ink-soft">증감 1,000,000원 이상 그리고 30% 이상, 또는 신규·소멸 · 상위 10개</span>}>
            {r.variances.length === 0 ? (
              <Empty>기준을 넘는 변동이 없습니다.</Empty>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr>
                      <th className={th}>구분</th>
                      <th className={th}>비교</th>
                      <th className={th}>변동</th>
                      <th className={th}>내용</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.variances.map((v) => (
                      <tr key={`${v.kind}:${v.key}:${v.basis}`}>
                        <td className={`${td} whitespace-nowrap text-ink-soft`}>{KIND_LABEL[v.kind]}</td>
                        <td className={`${td} whitespace-nowrap`}>{VARIANCE_BASIS_LABEL[v.basis]}</td>
                        <td className={td}>
                          <Badge>{VARIANCE_CODE_LABEL[v.code]}</Badge>
                        </td>
                        <td className={td}>{v.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <div className="grid gap-6 lg:grid-cols-2">
            <Card title="계정과목별 손익">
              <LineTable lines={r.accounts} nameHeader="계정과목" empty="수익·비용 전표가 없습니다." />
            </Card>
            <Card title="거래처별 비용" actions={<span className="text-xs text-ink-soft">거래 → 증빙 → 대사된 은행 거래 순으로 거래처를 찾음</span>}>
              <LineTable lines={r.vendors} nameHeader="거래처" empty="비용 전표가 없습니다." />
            </Card>
          </div>
        </div>
      )}
    </>
  );
}

export default function ClosingPage() {
  return (
    <Suspense>
      <ClosingView />
    </Suspense>
  );
}
