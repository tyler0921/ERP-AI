"use client";

import type { MatchLevel, ScoreDetail } from "@/lib/types";
import { Badge } from "@/components/ui";

export const LEVEL_LABEL: Record<MatchLevel, string> = {
  HIGH: "높음",
  REVIEW: "검토 필요",
  LOW: "낮음",
};

export function ScoreBadge({ score, level }: { score: number; level: MatchLevel }) {
  const tone = level === "HIGH" ? "accent" : level === "REVIEW" ? "warn" : "neutral";
  return (
    <Badge tone={tone}>
      <span className="num">{score}</span>점 · {LEVEL_LABEL[level]}
    </Badge>
  );
}

const ITEMS: { key: keyof ScoreDetail; label: string; max: number }[] = [
  { key: "amount", label: "금액", max: 40 },
  { key: "date", label: "날짜", max: 20 },
  { key: "counterparty", label: "거래처", max: 20 },
  { key: "memo", label: "메모", max: 10 },
  { key: "pattern", label: "과거 패턴", max: 10 },
];

/** Match Score 항목별 막대 + 규칙 엔진이 남긴 사유 */
export function ScoreBreakdown({ detail }: { detail: ScoreDetail }) {
  return (
    <div className="space-y-2">
      <div className="space-y-1.5">
        {ITEMS.map(({ key, label, max }) => {
          const value = Number(detail[key] ?? 0);
          return (
            <div key={key} className="grid grid-cols-[64px_1fr_52px] items-center gap-2 text-xs">
              <span className="text-ink-soft">{label}</span>
              <div className="h-1.5 overflow-hidden rounded-full bg-line" role="presentation">
                <div className={`h-full rounded-full ${value === max ? "bg-accent" : value > 0 ? "bg-warn" : ""}`} style={{ width: `${(value / max) * 100}%` }} />
              </div>
              <span className="num">
                {value} / {max}
              </span>
            </div>
          );
        })}
      </div>
      {detail.reasons?.length > 0 && (
        <ul className="flex flex-wrap gap-1">
          {detail.reasons.map((r) => (
            <li key={r} className="rounded bg-paper px-1.5 py-0.5 text-[11px] text-ink-soft">
              {r}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
