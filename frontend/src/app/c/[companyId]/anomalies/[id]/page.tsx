"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useApi } from "@/lib/api";
import { FEATURE_LABEL, formatFeature } from "@/lib/anomaly";
import { ANOMALY_SOURCE_LABEL, RISK_LEVEL_LABEL, type AnomalyDetail, type Member } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Button, Card, ErrorBanner, Loading, Money, PageHeader, formatDateTime, td, th } from "@/components/ui";
import { AnomalyStatusBadge, MlStatusBadge, RiskBadge, RiskBreakdown } from "../risk";
import { ReviewPanel } from "./review-panel";

export default function AnomalyDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { base, canWrite, company } = useCompany();
  const { data: r, error, reload } = useApi<AnomalyDetail>(/^\d+$/.test(id) ? `${base}/anomalies/${id}` : null);
  const members = useApi<Member[]>(`${base}/members`);

  if (error) return <ErrorBanner error={error} />;
  if (!r) return <Loading />;

  const memberName = (userId: number | null | undefined) =>
    userId == null ? "-" : (members.data?.find((m) => m.userId === userId)?.name ?? `#${userId}`);
  const link = (p: string) => `/c/${company.id}/${p}`;
  const ml = r.mlDetail;

  return (
    <>
      <PageHeader
        title={`이상거래 #${r.id}`}
        description={`${ANOMALY_SOURCE_LABEL[r.sourceType]} #${r.sourceId} · ${r.counterparty ?? "거래처 없음"}`}
        actions={
          <>
            <AnomalyStatusBadge status={r.status} />
            <Link href={link("anomalies")}>
              <Button variant="ghost">목록</Button>
            </Link>
          </>
        }
      />

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
        <div className="space-y-4">
          <Card title="위험도">
            <div className="space-y-3 px-4 py-4">
              <div className="flex flex-wrap items-baseline gap-3">
                <span className="num text-4xl font-semibold">{r.riskScore}</span>
                <span className="text-sm text-ink-soft">/ 100</span>
                <RiskBadge level={r.effectiveLevel} />
                {r.adjustedLevel && (
                  <span className="text-xs text-ink-soft">
                    담당자 조정 (산정 {RISK_LEVEL_LABEL[r.riskLevel]} → {RISK_LEVEL_LABEL[r.adjustedLevel]})
                  </span>
                )}
              </div>
              <RiskBreakdown ruleScore={r.ruleScore} mlPoints={ml.points} score={r.riskScore} />
              <p className="text-xs text-ink-soft">
                Risk Score = 규칙 점수(최대 100) + ML 점수 × {ml.maxPoints}점, 최대 100. 40점 이상만 이상거래로 저장합니다.
              </p>
            </div>
          </Card>

          <Card title="규칙 근거" actions={<span className="text-xs text-ink-soft">규칙 점수 {r.ruleScore}</span>}>
            {r.ruleHits.length === 0 ? (
              <p className="px-4 py-4 text-sm text-ink-soft">적중한 규칙이 없습니다.</p>
            ) : (
              <ul>
                {r.ruleHits.map((h) => (
                  <li key={h.code} className="border-b border-line px-4 py-3 last:border-b-0">
                    <div className="flex items-center justify-between gap-3">
                      <span className="text-sm font-medium">{h.title}</span>
                      <span className="num text-xs font-semibold text-danger">+{h.weight}</span>
                    </div>
                    <p className="mt-1 text-sm text-ink-soft">{h.message}</p>
                    <details className="mt-1">
                      <summary className="cursor-pointer text-[11px] text-ink-soft">판단에 쓴 값 · {h.code}</summary>
                      <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 font-mono text-[11px]">
                        {Object.entries(h.evidence).map(([k, v]) => (
                          <div key={k} className="contents">
                            <dt className="text-ink-soft">{k}</dt>
                            <dd className="break-all">{Array.isArray(v) ? v.join(", ") : String(v)}</dd>
                          </div>
                        ))}
                      </dl>
                    </details>
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card title="ML 근거 (IsolationForest)" actions={<MlStatusBadge status={ml.status} />}>
            <div className="space-y-3 px-4 py-3 text-sm">
              {ml.score !== undefined ? (
                <>
                  <p>
                    이상 점수 <b className="num">{Math.round(ml.score * 100)}%</b>
                    <span className="text-ink-soft"> → +{ml.points}점</span>
                    {ml.rawScore !== undefined && <span className="ml-2 text-xs text-ink-soft">(원점수 {ml.rawScore.toFixed(3)})</span>}
                  </p>
                  {ml.topFeatures && ml.topFeatures.length > 0 ? (
                    <table className="w-full">
                      <thead>
                        <tr>
                          <th className={th}>모집단에서 크게 벗어난 특성</th>
                          <th className={`${th} text-right`}>이 거래</th>
                          <th className={`${th} text-right`}>중앙값</th>
                          <th className={`${th} text-right`}>z</th>
                        </tr>
                      </thead>
                      <tbody>
                        {ml.topFeatures.map((f) => (
                          <tr key={f.feature}>
                            <td className={td}>{FEATURE_LABEL[f.feature] ?? f.feature}</td>
                            <td className={`${td} num`}>{f.value === null ? "이력 없음" : formatFeature(f.feature, f.value)}</td>
                            <td className={`${td} num text-ink-soft`}>{formatFeature(f.feature, f.median)}</td>
                            <td className={`${td} num`}>{f.zScore.toFixed(1)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  ) : (
                    <p className="text-xs text-ink-soft">모집단에서 크게(|z| ≥ 2) 벗어난 특성은 없습니다.</p>
                  )}
                </>
              ) : (
                <p className="text-ink-soft">{ml.message ?? "ML 점수가 없습니다."} 규칙 점수만으로 계산했습니다.</p>
              )}
              {ml.model && <p className="text-[11px] text-ink-soft">모델: {ml.model}</p>}
            </div>
          </Card>
        </div>

        <div className="space-y-4">
          <Card title="검토">
            <ReviewPanel base={base} result={r} canWrite={canWrite} memberName={memberName} onChanged={reload} />
          </Card>

          <Card title="원천 거래">
            <dl className="grid grid-cols-[96px_1fr] gap-x-3 gap-y-2 px-4 py-3 text-sm">
              <dt className="text-ink-soft">원천</dt>
              <dd>
                {ANOMALY_SOURCE_LABEL[r.sourceType]} #{r.sourceId}
                {r.sourceType === "BANK_TRANSACTION" && (
                  <Link href={link("reconciliation")} className="ml-2 text-xs text-accent hover:underline">
                    은행 대사에서 보기
                  </Link>
                )}
              </dd>
              <dt className="text-ink-soft">거래일시</dt>
              <dd className="num text-left">
                {r.txDate} {r.txTime ?? ""}
              </dd>
              <dt className="text-ink-soft">거래처</dt>
              <dd>{r.counterparty ?? "-"}</dd>
              <dt className="text-ink-soft">출금액</dt>
              <dd>
                <Money value={r.amount} />원
              </dd>
              <dt className="text-ink-soft">적요</dt>
              <dd>{r.context.memo ?? "-"}</dd>
              <dt className="text-ink-soft">계정과목</dt>
              <dd>{r.context.accountCode ? `${r.context.accountCode} ${r.context.accountName}` : "-"}</dd>
              <dt className="text-ink-soft">연결 전표</dt>
              <dd className="flex flex-wrap gap-2">
                {r.context.journalEntryIds.length === 0
                  ? "-"
                  : r.context.journalEntryIds.map((e) => (
                      <Link key={e} href={link(`journal-entries/${e}`)} className="text-accent hover:underline">
                        #{e}
                      </Link>
                    ))}
              </dd>
              <dt className="text-ink-soft">증빙</dt>
              <dd className="flex flex-wrap gap-2">
                {r.context.documentIds.length === 0
                  ? "없음"
                  : r.context.documentIds.map((d) => (
                      <Link key={d} href={link(`documents/${d}`)} className="text-accent hover:underline">
                        #{d}
                      </Link>
                    ))}
              </dd>
            </dl>
          </Card>

          <Card title="특성 (규칙·ML 공통 입력)">
            <table className="w-full">
              <tbody>
                {orderedFeatures(r.features).map(([k, v]) => (
                  <tr key={k}>
                    <td className={`${td} text-ink-soft`}>{FEATURE_LABEL[k] ?? k}</td>
                    <td className={`${td} num`}>{formatFeature(k, v)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>

          <p className="px-1 text-[11px] text-ink-soft">
            처음 탐지 {formatDateTime(r.firstDetectedAt)} · 마지막 분석 #{r.scanId} · 갱신 {formatDateTime(r.updatedAt)}
          </p>
        </div>
      </div>
    </>
  );
}

/** 특성은 JSONB 키 순서가 아니라 화면에 정한 순서로 보여준다 */
function orderedFeatures(features: AnomalyDetail["features"]): [string, string | number | null][] {
  const known = Object.keys(FEATURE_LABEL).filter((k) => k in features);
  const rest = Object.keys(features).filter((k) => !(k in FEATURE_LABEL));
  return [...known, ...rest].map((k) => [k, features[k]]);
}
