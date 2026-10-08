"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { RISK_LEVELS, RULE_SHORT_LABEL, defaultScanPeriod, scanPeriodError } from "@/lib/anomaly";
import { todayKst } from "@/lib/dates";
import {
  ANOMALY_SOURCE_LABEL,
  ANOMALY_STATUS_LABEL,
  RISK_LEVEL_LABEL,
  type AnomalyListItem,
  type AnomalyScan,
  type AnomalySource,
  type AnomalyStatus,
  type AnomalySummary,
  type PageResponse,
  type RiskLevel,
} from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Button, Card, Empty, ErrorBanner, Field, Input, Loading, Money, PageHeader, Pagination, Select, formatDateTime, td, th } from "@/components/ui";
import { AnomalyStatusBadge, MlStatusBadge, RiskBadge, RiskScore } from "./risk";

const STATUS_TABS: ("" | AnomalyStatus)[] = ["OPEN", "CONFIRMED", "DISMISSED", "CLEARED", ""];

export default function AnomaliesPage() {
  const router = useRouter();
  const { base, canWrite, company } = useCompany();
  const [status, setStatus] = useState<"" | AnomalyStatus>("OPEN");
  const [level, setLevel] = useState<"" | RiskLevel>("");
  const [sourceType, setSourceType] = useState<"" | AnomalySource>("");
  const [page, setPage] = useState(0);
  const list = useApi<PageResponse<AnomalyListItem>>(`${base}/anomalies`, { status, level, sourceType, page, size: 20 });
  const summary = useApi<AnomalySummary>(`${base}/anomalies/summary`);

  function filterLevel(next: "" | RiskLevel) {
    setLevel(next);
    setStatus("OPEN");
    setPage(0);
  }

  return (
    <>
      <PageHeader
        title="이상거래"
        description="규칙 엔진과 IsolationForest(ML)가 매긴 Risk Score 순으로 보여줍니다. 탐지는 후보일 뿐이며, 이상 여부는 담당자가 판정합니다."
      />

      {canWrite && (
        <ScanPanel
          base={base}
          companyId={company.id}
          onDone={() => {
            list.reload();
            summary.reload();
          }}
        />
      )}

      {summary.data?.lastScan && <LastScan scan={summary.data.lastScan} />}

      {/* 미검토 결과의 위험 수준별 건수. 누르면 해당 수준만 본다 */}
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        {RISK_LEVELS.map((l) => {
          const active = level === l && status === "OPEN";
          return (
            <button
              key={l}
              type="button"
              onClick={() => filterLevel(active ? "" : l)}
              aria-pressed={active}
              className={`rounded-lg border bg-surface p-3 text-left transition-colors ${active ? "border-ink" : "border-line hover:border-line-strong"}`}
            >
              <div className="flex items-center justify-between">
                <RiskBadge level={l} />
                <span className="text-[11px] text-ink-soft">검토 대기</span>
              </div>
              <p className="num mt-2 text-left text-2xl font-medium">{summary.data ? summary.data.openByLevel[l] : "–"}</p>
            </button>
          );
        })}
      </div>

      <Card
        title={
          <div className="flex flex-wrap gap-1">
            {STATUS_TABS.map((t) => (
              <button
                key={t || "ALL"}
                type="button"
                onClick={() => {
                  setStatus(t);
                  setPage(0);
                }}
                className={`rounded px-2 py-1 text-xs ${status === t ? "bg-ink text-white" : "text-ink-soft hover:bg-paper"}`}
              >
                {t ? ANOMALY_STATUS_LABEL[t] : "전체"}
                {t === "OPEN" && summary.data ? <span className="num ml-1 opacity-70">{summary.data.open}</span> : null}
                {t === "CONFIRMED" && summary.data ? <span className="num ml-1 opacity-70">{summary.data.confirmed}</span> : null}
                {t === "DISMISSED" && summary.data ? <span className="num ml-1 opacity-70">{summary.data.dismissed}</span> : null}
              </button>
            ))}
          </div>
        }
        actions={
          <>
            <Select value={level} onChange={(e) => { setLevel(e.target.value as "" | RiskLevel); setPage(0); }} className="w-28" aria-label="위험 수준">
              <option value="">모든 수준</option>
              {RISK_LEVELS.map((l) => (
                <option key={l} value={l}>
                  {RISK_LEVEL_LABEL[l]}
                </option>
              ))}
            </Select>
            <Select value={sourceType} onChange={(e) => { setSourceType(e.target.value as "" | AnomalySource); setPage(0); }} className="w-28" aria-label="원천">
              <option value="">모든 원천</option>
              <option value="BANK_TRANSACTION">은행 거래</option>
              <option value="TRANSACTION">거래</option>
            </Select>
          </>
        }
      >
        {list.error && (
          <div className="p-3">
            <ErrorBanner error={list.error} />
          </div>
        )}
        {list.loading && !list.data ? (
          <Loading />
        ) : list.data?.content.length === 0 ? (
          <Empty>
            {summary.data?.lastScan ? "조건에 맞는 이상거래가 없습니다." : "아직 분석하지 않았습니다. 기간을 정해 분석을 실행하세요."}
          </Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px]">
              <thead>
                <tr>
                  <th className={th}>위험도</th>
                  <th className={th}>거래일시</th>
                  <th className={th}>원천</th>
                  <th className={th}>거래처</th>
                  <th className={`${th} text-right`}>출금액</th>
                  <th className={th}>탐지 근거</th>
                  <th className={th}>상태</th>
                </tr>
              </thead>
              <tbody>
                {list.data?.content.map((r) => (
                  <tr key={r.id} onClick={() => router.push(`/c/${company.id}/anomalies/${r.id}`)} className="cursor-pointer hover:bg-paper">
                    <td className={td}>
                      <div className="flex items-center gap-2">
                        <RiskScore score={r.riskScore} level={r.riskLevel} />
                        <RiskBadge level={r.effectiveLevel} />
                      </div>
                      {r.adjustedLevel && <p className="mt-0.5 text-[11px] text-ink-soft">원래 {RISK_LEVEL_LABEL[r.riskLevel]} → 조정</p>}
                    </td>
                    <td className={`${td} num whitespace-nowrap text-left`}>
                      {r.txDate}
                      {r.txTime && <span className="ml-1 text-ink-soft">{r.txTime.slice(0, 5)}</span>}
                    </td>
                    <td className={`${td} whitespace-nowrap text-xs text-ink-soft`}>{ANOMALY_SOURCE_LABEL[r.sourceType]}</td>
                    <td className={`${td} max-w-44 truncate`}>{r.counterparty ?? "-"}</td>
                    <td className={`${td} text-right`}>
                      <Money value={r.amount} />
                    </td>
                    <td className={td}>
                      <ul className="flex max-w-72 flex-wrap gap-1">
                        {r.ruleCodes.map((c) => (
                          <li key={c} className="rounded bg-paper px-1.5 py-0.5 text-[11px] text-ink-soft">
                            {RULE_SHORT_LABEL[c] ?? c}
                          </li>
                        ))}
                        {r.mlScore !== null && Number(r.mlScore) >= 0.25 && (
                          <li className="rounded bg-warn-soft px-1.5 py-0.5 text-[11px] text-warn">ML {Math.round(Number(r.mlScore) * 100)}%</li>
                        )}
                      </ul>
                    </td>
                    <td className={`${td} whitespace-nowrap`}>
                      <AnomalyStatusBadge status={r.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {list.data && <Pagination page={page} size={20} total={list.data.totalElements} onChange={setPage} />}
      </Card>
    </>
  );
}

function ScanPanel({ base, companyId, onDone }: { base: string; companyId: number; onDone: () => void }) {
  const initial = defaultScanPeriod(todayKst());
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<ApiError | string>();
  const periodError = scanPeriodError(from, to);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (periodError) return setError(periodError);
    setRunning(true);
    setError(undefined);
    try {
      await api<AnomalyScan>(`${base}/anomalies/scan`, { method: "POST", body: { from, to } });
      onDone();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setRunning(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="mb-4 rounded-lg border border-line bg-surface px-4 py-3">
      <div className="flex flex-wrap items-end gap-3">
        <Field label="분석 시작일">
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="w-40" required />
        </Field>
        <Field label="분석 종료일">
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="w-40" required />
        </Field>
        <Button type="submit" variant="primary" disabled={running || periodError !== null}>
          {running ? "분석 중…" : "이상거래 분석 실행"}
        </Button>
        <p className="min-w-60 flex-1 text-xs text-ink-soft">
          기간 안의 출금(은행 거래·거래)을 과거 180일과 비교합니다. 검토한 결과는 다시 분석해도 바뀌지 않습니다.{" "}
          <a href="/samples/bank-statement-anomaly-sample.csv" download className="text-accent hover:underline">
            데모용 은행 CSV
          </a>
          를{" "}
          <Link href={`/c/${companyId}/reconciliation`} className="text-accent hover:underline">
            은행 대사
          </Link>
          에서 올려 볼 수 있습니다.
        </p>
      </div>
      {(error || periodError) && (
        <div className="mt-3">
          <ErrorBanner error={error ?? periodError} />
        </div>
      )}
    </form>
  );
}

function LastScan({ scan }: { scan: AnomalyScan }) {
  // 분석한 거래가 있는 원천의 ML 사유만 보여준다 (거래가 0건인 원천의 "표본 부족"은 잡음)
  const ml = Object.entries(scan.mlInfo).filter(([source]) => (scan.summary.analyzedBySource?.[source as AnomalySource] ?? 0) > 0);
  return (
    <div className="mb-4 rounded-lg border border-line bg-surface px-4 py-3 text-sm">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <span className="font-medium">마지막 분석</span>
        <span className="num text-left">
          {scan.periodFrom} ~ {scan.periodTo}
        </span>
        <span className="text-ink-soft">
          출금 {scan.analyzedCount.toLocaleString()}건 중 <b className="text-ink">{scan.flaggedCount}</b>건 탐지
        </span>
        <span className="text-xs text-ink-soft">
          신규 {scan.summary.created} · 갱신 {scan.summary.updated} · 해제 {scan.summary.cleared}
          {scan.summary.reopened > 0 && ` · 다시 열림 ${scan.summary.reopened}`}
          {scan.summary.keptReviewed > 0 && ` · 검토 유지 ${scan.summary.keptReviewed}`}
        </span>
        <MlStatusBadge status={scan.mlStatus} />
        <span className="ml-auto text-xs text-ink-soft">
          {formatDateTime(scan.createdAt)} · {scan.durationMs}ms
        </span>
      </div>
      {ml.some(([, info]) => info?.message) && (
        <ul className="mt-1.5 space-y-0.5 text-xs text-ink-soft">
          {ml.map(([source, info]) =>
            info?.message ? (
              <li key={source}>
                {ANOMALY_SOURCE_LABEL[source as AnomalySource]}: {info.message}
              </li>
            ) : null,
          )}
        </ul>
      )}
    </div>
  );
}
