"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { formatMoney } from "@/lib/money";
import type { ClosingReport } from "@/lib/types";
import { Button, ErrorBanner, formatDateTime } from "@/components/ui";

const textarea =
  "w-full rounded-md border border-line-strong bg-surface px-2.5 py-1.5 text-sm outline-none focus:border-accent focus:ring-2 focus:ring-accent/15";

/**
 * 마감 확정·취소 (ADMIN). 체크리스트가 남아 있어도 확정할 수 있지만,
 * 남은 항목은 확정 스냅샷과 Audit Log에 그대로 남는다.
 */
export function ClosePanel({
  base,
  report: r,
  isAdmin,
  canClose,
  memberName,
  onChanged,
}: {
  base: string;
  report: ClosingReport;
  isAdmin: boolean;
  /** 지난 달까지만 마감할 수 있다 */
  canClose: boolean;
  memberName: (id: number | null | undefined) => string;
  onChanged: () => void;
}) {
  const [note, setNote] = useState("");
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);
  const closed = r.closing.status === "CLOSED";
  const remaining = r.checklist.filter((c) => !c.done);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setPending(true);
    setError(undefined);
    try {
      await api(`${base}/closing/${r.month}/${closed ? "reopen" : "close"}`, { method: "POST", body: { note: note.trim() } });
      setNote("");
      onChanged();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="space-y-3 px-4 py-3 text-sm">
      {closed ? (
        <div className="space-y-1">
          <p>
            <span className="font-medium">{memberName(r.closing.closedBy)}</span>
            <span className="text-ink-soft"> 확정 · {formatDateTime(r.closing.closedAt)}</span>
          </p>
          <p className="whitespace-pre-line rounded-md bg-paper px-3 py-2">{r.closing.note}</p>
          {r.closing.changedSinceClose && (
            <p role="alert" className="rounded-md border border-warn/30 bg-warn-soft px-3 py-2 text-warn">
              확정 이후 매출·비용 합계가 바뀌었습니다. 확정 시점: 매출 {formatMoney(r.closing.snapshot?.totals?.REVENUE)}원, 비용{" "}
              {formatMoney(r.closing.snapshot?.totals?.EXPENSE)}원. 필요하면 마감을 취소하고 다시 확정하세요.
            </p>
          )}
        </div>
      ) : (
        <p className="text-ink-soft">
          {canClose ? "아직 마감하지 않았습니다." : "이번 달은 아직 마감할 수 없습니다. 지난 달부터 마감할 수 있습니다."}
        </p>
      )}

      {isAdmin && (closed || canClose) && (
        <form onSubmit={submit} className="space-y-2 border-t border-line pt-3">
          <ErrorBanner error={error} />
          {!closed && remaining.length > 0 && (
            <p className="text-xs text-warn">
              남은 체크리스트 {remaining.length}개({remaining.map((c) => c.title).join(", ")})가 확정 기록에 함께 남습니다.
            </p>
          )}
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={2}
            maxLength={1000}
            required
            className={textarea}
            aria-label={closed ? "마감 취소 사유" : "마감 의견"}
            placeholder={closed ? "마감 취소 사유 (필수)" : "마감 의견 (필수) — 예: 은행 대사 2건은 다음 달 처리"}
          />
          <div className="flex justify-end">
            <Button type="submit" variant={closed ? "danger" : "primary"} disabled={pending || note.trim() === ""}>
              {closed ? "마감 취소" : "마감 확정"}
            </Button>
          </div>
        </form>
      )}
      {!isAdmin && (closed || canClose) && <p className="text-xs text-ink-soft">마감 확정·취소는 관리자만 할 수 있습니다.</p>}
    </div>
  );
}
