"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { RISK_LEVELS } from "@/lib/anomaly";
import { ANOMALY_STATUS_LABEL, RISK_LEVEL_LABEL, type AnomalyDetail, type RiskLevel } from "@/lib/types";
import { Button, ErrorBanner, Field, Select, formatDateTime } from "@/components/ui";

type Decision = "CONFIRMED" | "DISMISSED";

const textarea =
  "w-full rounded-md border border-line-strong bg-surface px-2.5 py-1.5 text-sm outline-none focus:border-accent focus:ring-2 focus:ring-accent/15";

/** 이상 확정 / 정상 처리 (+ Risk Level 조정, 의견 필수), 판정 취소(다시 열기) */
export function ReviewPanel({
  base,
  result: r,
  canWrite,
  memberName,
  onChanged,
}: {
  base: string;
  result: AnomalyDetail;
  canWrite: boolean;
  memberName: (id: number | null | undefined) => string;
  onChanged: () => void;
}) {
  const [decision, setDecision] = useState<Decision>("CONFIRMED");
  const [adjusted, setAdjusted] = useState<"" | RiskLevel>("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);

  async function submit(path: string, body: unknown) {
    setPending(true);
    setError(undefined);
    try {
      await api(`${base}/anomalies/${r.id}/${path}`, { method: "POST", body });
      setNote("");
      setAdjusted("");
      onChanged();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  function onReview(e: FormEvent) {
    e.preventDefault();
    submit("review", { decision, adjustedLevel: adjusted || null, note: note.trim() });
  }

  function onReopen(e: FormEvent) {
    e.preventDefault();
    submit("reopen", { note: note.trim() });
  }

  const previous = r.context.previousReview;
  const reviewed = r.status === "CONFIRMED" || r.status === "DISMISSED";

  return (
    <div className="space-y-3 px-4 py-3 text-sm">
      {r.status === "CLEARED" && (
        <p className="rounded-md bg-paper px-3 py-2 text-ink-soft">
          해제됨 — {r.context.clearedReason ?? "재분석에서 기준을 넘지 않았습니다."} 다시 기준을 넘으면 검토 대기로 돌아옵니다.
        </p>
      )}

      {reviewed && (
        <div className={`rounded-md px-3 py-2 ${r.status === "CONFIRMED" ? "bg-danger-soft text-danger" : "bg-accent-soft text-accent"}`}>
          <p className="font-medium">
            {ANOMALY_STATUS_LABEL[r.status]}
            {r.adjustedLevel && ` · 위험 수준 ${RISK_LEVEL_LABEL[r.adjustedLevel]}(으)로 조정`}
          </p>
          <p className="mt-0.5 text-ink">“{r.reviewNote}”</p>
          <p className="mt-0.5 text-xs text-ink-soft">
            {memberName(r.reviewedBy)} · {formatDateTime(r.reviewedAt)}
          </p>
        </div>
      )}

      {previous && !reviewed && (
        <div className="rounded-md border border-line px-3 py-2 text-xs text-ink-soft">
          이전 판정: {ANOMALY_STATUS_LABEL[previous.status]}
          {previous.adjustedLevel && ` (${RISK_LEVEL_LABEL[previous.adjustedLevel]}로 조정)`} · “{previous.note}” · {memberName(previous.reviewedBy)}{" "}
          {formatDateTime(previous.reviewedAt)}
          {r.status === "OPEN" && <span className="block">판정 이후 다시 열렸습니다 (판정 취소 또는 새 근거).</span>}
        </div>
      )}

      <ErrorBanner error={error} />

      {canWrite && r.status === "OPEN" && (
        <form onSubmit={onReview} className="space-y-3">
          <fieldset className="grid grid-cols-2 gap-2">
            <legend className="sr-only">판정</legend>
            {(["CONFIRMED", "DISMISSED"] as const).map((d) => (
              <label
                key={d}
                className={`cursor-pointer rounded-md border px-3 py-2 text-center text-sm font-medium ${
                  decision === d ? (d === "CONFIRMED" ? "border-danger bg-danger-soft text-danger" : "border-accent bg-accent-soft text-accent") : "border-line-strong text-ink-soft hover:bg-paper"
                }`}
              >
                <input type="radio" name="decision" value={d} checked={decision === d} onChange={() => setDecision(d)} className="sr-only" />
                {d === "CONFIRMED" ? "이상거래 확정" : "정상 거래로 처리"}
              </label>
            ))}
          </fieldset>
          <Field label="위험 수준 조정 (선택)" hint={`산정값: ${RISK_LEVEL_LABEL[r.riskLevel]} (${r.riskScore}점)`}>
            <Select value={adjusted} onChange={(e) => setAdjusted(e.target.value as "" | RiskLevel)}>
              <option value="">조정하지 않음</option>
              {RISK_LEVELS.filter((l) => l !== r.riskLevel).map((l) => (
                <option key={l} value={l}>
                  {RISK_LEVEL_LABEL[l]}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="검토 의견 (필수)">
            <textarea value={note} onChange={(e) => setNote(e.target.value)} required maxLength={1000} rows={3} className={textarea} placeholder="확인한 내용과 판단 근거" />
          </Field>
          <Button type="submit" variant={decision === "CONFIRMED" ? "danger" : "primary"} disabled={pending || note.trim() === ""} className="w-full">
            {pending ? "저장 중…" : decision === "CONFIRMED" ? "이상거래로 확정" : "정상 거래로 처리"}
          </Button>
        </form>
      )}

      {canWrite && reviewed && (
        <form onSubmit={onReopen} className="space-y-2 border-t border-line pt-3">
          <Field label="판정 취소 사유">
            <textarea value={note} onChange={(e) => setNote(e.target.value)} required maxLength={1000} rows={2} className={textarea} />
          </Field>
          <Button type="submit" disabled={pending || note.trim() === ""}>
            다시 검토 대기로
          </Button>
        </form>
      )}

      {!canWrite && r.status === "OPEN" && <p className="text-ink-soft">조회자 권한으로는 판정할 수 없습니다.</p>}
    </div>
  );
}
