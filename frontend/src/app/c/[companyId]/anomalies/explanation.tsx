"use client";

import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { EXPLANATION_STATUS_LABEL, type Explanation, type ExplanationStatus } from "@/lib/types";
import { Badge, Button, ErrorBanner, formatDateTime } from "@/components/ui";

const textarea =
  "w-full rounded-md border border-line-strong bg-surface px-2.5 py-1.5 text-sm outline-none focus:border-accent focus:ring-2 focus:ring-accent/15";

const TONE: Record<ExplanationStatus, "accent" | "warn" | "danger" | "neutral"> = {
  GENERATED: "accent",
  EDITED: "accent",
  PENDING: "warn",
  FALLBACK: "neutral",
  FAILED: "danger",
};

/** 생성 중이면 결과가 올 때까지 주기적으로 새로고침한다 */
export function usePollWhilePending(status: ExplanationStatus | undefined, reload: () => void) {
  useEffect(() => {
    if (status !== "PENDING") return;
    const timer = setInterval(reload, 3000);
    return () => clearInterval(timer);
  }, [status, reload]);
}

/**
 * LLM 설명. LLM은 규칙·ML 근거를 문장으로 옮길 뿐이며 판정하지 않는다.
 * 근거에 없는 숫자 등으로 검증에 실패하면 규칙 근거로 만든 요약(FALLBACK)이 대신 표시된다.
 */
export function ExplanationPanel({
  explanation: e,
  canWrite,
  regeneratePath,
  editPath,
  memberName,
  onChanged,
  emptyText,
}: {
  explanation: Explanation | null;
  canWrite: boolean;
  regeneratePath: string;
  /** 없으면 수정 기능을 숨긴다 */
  editPath?: string;
  memberName?: (id: number | null | undefined) => string;
  onChanged: () => void;
  emptyText: string;
}) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState("");
  const [checkpoints, setCheckpoints] = useState("");
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);

  async function run(fn: () => Promise<unknown>) {
    setPending(true);
    setError(undefined);
    try {
      await fn();
      setEditing(false);
      onChanged();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  function startEdit() {
    setText(e?.text ?? "");
    setCheckpoints((e?.checkpoints ?? []).join("\n"));
    setEditing(true);
  }

  function onSave(ev: FormEvent) {
    ev.preventDefault();
    if (!editPath) return;
    const list = checkpoints.split("\n").map((c) => c.trim()).filter(Boolean).slice(0, 5);
    run(() => api(editPath, { method: "PUT", body: { text: text.trim(), checkpoints: list } }));
  }

  const regenerate = () => run(() => api(regeneratePath, { method: "POST" }));
  const meta = e?.meta ?? {};
  const issues = meta.validation ?? [];

  if (editing) {
    return (
      <form onSubmit={onSave} className="space-y-2 px-4 py-3">
        <ErrorBanner error={error} />
        <textarea value={text} onChange={(x) => setText(x.target.value)} rows={5} maxLength={4000} required className={textarea} aria-label="설명" />
        <textarea
          value={checkpoints}
          onChange={(x) => setCheckpoints(x.target.value)}
          rows={3}
          className={textarea}
          aria-label="확인 사항 (한 줄에 하나)"
          placeholder="확인 사항 (한 줄에 하나, 최대 5개)"
        />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={() => setEditing(false)}>
            취소
          </Button>
          <Button type="submit" variant="primary" disabled={pending || text.trim() === ""}>
            저장
          </Button>
        </div>
      </form>
    );
  }

  return (
    <div className="space-y-3 px-4 py-3 text-sm">
      <ErrorBanner error={error} />
      {!e ? (
        <p className="text-ink-soft">{emptyText}</p>
      ) : e.status === "PENDING" ? (
        <p className="flex items-center gap-2 text-ink-soft">
          <span className="h-2 w-2 animate-pulse rounded-full bg-warn" />
          로컬 LLM이 설명을 만드는 중입니다… (시도 {e.attempt})
        </p>
      ) : e.status === "FAILED" ? (
        <p className="text-danger">{meta.error ?? "설명을 만들지 못했습니다."}</p>
      ) : (
        <>
          <div className="flex items-start gap-2">
            <Badge tone={TONE[e.status]}>{EXPLANATION_STATUS_LABEL[e.status]}</Badge>
            {meta.stale && <Badge tone="warn">근거가 바뀜</Badge>}
          </div>
          <p className="whitespace-pre-line leading-relaxed">{e.text}</p>
          {e.checkpoints.length > 0 && (
            <ul className="space-y-1">
              {e.checkpoints.map((c) => (
                <li key={c} className="flex gap-2 text-ink-soft">
                  <span aria-hidden>☐</span>
                  {c}
                </li>
              ))}
            </ul>
          )}
          {e.status === "FALLBACK" && (
            <p className="rounded-md bg-paper px-3 py-2 text-xs text-ink-soft">
              {meta.llmError
                ? "로컬 LLM에 연결하지 못해 규칙 근거로 만든 요약을 보여줍니다."
                : `LLM 설명이 검증을 통과하지 못해 규칙 근거로 만든 요약을 보여줍니다: ${issues.map((i) => i.message).join(" / ")}`}
            </p>
          )}
          {meta.stale && <p className="text-xs text-warn">담당자가 수정한 뒤 탐지 근거가 바뀌었습니다. 필요하면 다시 생성하세요.</p>}
        </>
      )}

      {e && (
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line pt-2">
          <p className="text-[11px] text-ink-soft">
            {e.status === "EDITED"
              ? `${memberName ? memberName(e.editedBy) : `#${e.editedBy}`} 수정 · ${formatDateTime(e.editedAt)}`
              : [meta.model, meta.promptVersion, meta.durationMs !== undefined ? `${(meta.durationMs / 1000).toFixed(1)}초` : null, e.completedAt ? formatDateTime(e.completedAt) : null]
                  .filter(Boolean)
                  .join(" · ")}
          </p>
          {canWrite && e.status !== "PENDING" && (
            <div className="flex gap-1">
              {editPath && (
                <Button variant="ghost" onClick={startEdit} disabled={pending}>
                  수정
                </Button>
              )}
              <Button variant="ghost" onClick={regenerate} disabled={pending}>
                다시 생성
              </Button>
            </div>
          )}
        </div>
      )}
      {e && (
        <details className="text-[11px] text-ink-soft">
          <summary className="cursor-pointer">LLM에 전달한 근거 데이터</summary>
          <pre className="mt-1 max-h-72 overflow-auto rounded bg-paper p-2 font-mono">{JSON.stringify(e.facts, null, 2)}</pre>
          {meta.rejectedText && (
            <>
              <p className="mt-2">검증에서 거부된 LLM 출력</p>
              <pre className="mt-1 whitespace-pre-wrap rounded bg-paper p-2 font-mono">{meta.rejectedText}</pre>
            </>
          )}
        </details>
      )}
      {!e && canWrite && (
        <Button variant="ghost" onClick={regenerate} disabled={pending}>
          설명 생성
        </Button>
      )}
    </div>
  );
}
