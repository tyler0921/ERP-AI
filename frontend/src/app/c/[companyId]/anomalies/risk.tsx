"use client";

import { ANOMALY_STATUS_LABEL, ML_STATUS_LABEL, RISK_LEVEL_LABEL, type AnomalyStatus, type MlStatus, type RiskLevel } from "@/lib/types";
import { Badge } from "@/components/ui";

const LEVEL_CLASS: Record<RiskLevel, string> = {
  CRITICAL: "bg-danger text-white",
  HIGH: "bg-danger-soft text-danger",
  MEDIUM: "bg-warn-soft text-warn",
  LOW: "bg-line/60 text-ink-soft",
};

const BAR_CLASS: Record<RiskLevel, string> = {
  CRITICAL: "bg-danger",
  HIGH: "bg-danger/70",
  MEDIUM: "bg-warn",
  LOW: "bg-line-strong",
};

export function RiskBadge({ level }: { level: RiskLevel }) {
  return <span className={`inline-block rounded px-1.5 py-0.5 text-xs font-semibold ${LEVEL_CLASS[level]}`}>{RISK_LEVEL_LABEL[level]}</span>;
}

/** 목록용: 점수 + 얇은 막대 */
export function RiskScore({ score, level }: { score: number; level: RiskLevel }) {
  return (
    <div className="flex min-w-24 items-center gap-2">
      <span className="num w-7 text-sm font-semibold">{score}</span>
      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-line" role="presentation">
        <div className={`h-full rounded-full ${BAR_CLASS[level]}`} style={{ width: `${score}%` }} />
      </div>
    </div>
  );
}

/** 상세용: 규칙 점수와 ML 점수를 쌓은 막대 (위험도 구간 눈금 포함) */
export function RiskBreakdown({ ruleScore, mlPoints, score }: { ruleScore: number; mlPoints: number; score: number }) {
  // 합계는 100에서 잘리므로 ML 막대도 남은 폭까지만 그린다
  const ml = Math.max(0, Math.min(mlPoints, 100 - Math.min(ruleScore, 100)));
  return (
    <div>
      <div className="relative h-3 overflow-hidden rounded-full bg-line" role="img" aria-label={`규칙 ${ruleScore}점, ML ${mlPoints}점, 합계 ${score}점`}>
        <div className="absolute inset-y-0 left-0 bg-ink" style={{ width: `${Math.min(ruleScore, 100)}%` }} />
        <div
          className="absolute inset-y-0 bg-warn"
          style={{ left: `${Math.min(ruleScore, 100)}%`, width: `${ml}%`, backgroundImage: "repeating-linear-gradient(45deg, transparent 0 3px, rgb(255 255 255 / 0.35) 3px 6px)" }}
        />
        {[40, 70, 85].map((t) => (
          <div key={t} className="absolute inset-y-0 w-px bg-surface" style={{ left: `${t}%` }} />
        ))}
      </div>
      <div className="mt-1 flex justify-between text-[10px] text-ink-soft">
        <span>0</span>
        <span className="flex gap-3">
          <span>
            <i className="mr-1 inline-block h-2 w-2 rounded-sm bg-ink align-middle" />규칙 {ruleScore}
          </span>
          <span>
            <i className="mr-1 inline-block h-2 w-2 rounded-sm bg-warn align-middle" />ML +{mlPoints}
          </span>
        </span>
        <span>100</span>
      </div>
    </div>
  );
}

export function AnomalyStatusBadge({ status }: { status: AnomalyStatus }) {
  const tone = status === "OPEN" ? "warn" : status === "CONFIRMED" ? "danger" : status === "DISMISSED" ? "accent" : "neutral";
  return <Badge tone={tone}>{ANOMALY_STATUS_LABEL[status]}</Badge>;
}

export function MlStatusBadge({ status }: { status: MlStatus }) {
  const tone = status === "APPLIED" ? "accent" : status === "UNAVAILABLE" ? "danger" : status === "PARTIAL" ? "warn" : "neutral";
  return <Badge tone={tone}>{ML_STATUS_LABEL[status]}</Badge>;
}
