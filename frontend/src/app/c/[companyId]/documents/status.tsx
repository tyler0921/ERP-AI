"use client";

import { DOC_STATUS_LABEL, type DocStatus } from "@/lib/types";
import { Badge } from "@/components/ui";

export function DocStatusBadge({ status }: { status: DocStatus }) {
  const tone =
    status === "APPROVED" || status === "AI_REVIEWED"
      ? "accent"
      : status === "REVIEW_REQUIRED" || status === "PENDING"
        ? "warn"
        : status === "FAILED" || status === "REJECTED"
          ? "danger"
          : "neutral";
  return (
    <Badge tone={tone}>
      {status === "PENDING" && <span className="mr-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-warn align-middle" />}
      {DOC_STATUS_LABEL[status]}
    </Badge>
  );
}

/** 0~1 신뢰도를 색으로 구분해 보여준다 */
export function Confidence({ value }: { value: number | null | undefined }) {
  if (value === null || value === undefined) return <span className="text-xs text-ink-soft">–</span>;
  const pct = Math.round(value * 100);
  const color = value >= 0.8 ? "text-accent" : value >= 0.6 ? "text-warn" : "text-danger";
  return <span className={`num text-xs font-medium ${color}`}>{pct}%</span>;
}
