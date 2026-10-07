"use client";

import Link from "next/link";
import { useState } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import type { Account, DocumentDetail, JournalEntry } from "@/lib/types";
import { Badge, Button, ErrorBanner, Field, Input, Select, formatDateTime } from "@/components/ui";

/** 계정과목 추천 확인 → 승인/거절, 승인 후 전표 생성 */
export function ReviewPanel({ base, companyId, doc, canWrite, onChanged }: { base: string; companyId: number; doc: DocumentDetail; canWrite: boolean; onChanged: () => void }) {
  const accounts = useApi<Account[]>(`${base}/accounts`, { active: true });
  const [accountId, setAccountId] = useState<string>(String(doc.accountId ?? doc.recommendedAccountId ?? ""));
  const [note, setNote] = useState("");
  const [rejecting, setRejecting] = useState(false);
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);
  const hasErrors = doc.validation.some((i) => i.severity === "ERROR");

  async function act(fn: () => Promise<unknown>) {
    setPending(true);
    setError(undefined);
    try {
      await fn();
      onChanged();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  const accountLabel = (id: number | null) => {
    const a = accounts.data?.find((x) => x.id === id);
    return a ? `${a.code} ${a.name}` : id ? `#${id}` : "-";
  };

  if (doc.status === "APPROVED") {
    return (
      <div className="space-y-4">
        <div className="rounded-md bg-accent-soft px-3 py-2 text-sm text-accent">
          {accountLabel(doc.accountId)}(으)로 승인됨 · {formatDateTime(doc.reviewedAt)}
          {doc.reviewNote && <span className="text-ink-soft"> · “{doc.reviewNote}”</span>}
          {doc.recommendedAccountId && doc.recommendedAccountId !== doc.accountId && (
            <span className="block text-xs text-ink-soft">추천({accountLabel(doc.recommendedAccountId)})과 다른 계정과목을 선택함</span>
          )}
        </div>
        <JournalSection base={base} companyId={companyId} doc={doc} canWrite={canWrite} accounts={accounts.data ?? []} onChanged={onChanged} />
      </div>
    );
  }

  if (doc.status === "REJECTED") {
    return <p className="rounded-md bg-danger-soft px-3 py-2 text-sm text-danger">거절됨: {doc.reviewNote}</p>;
  }

  return (
    <div className="space-y-4">
      <div>
        <h3 className="mb-2 text-sm font-medium">계정과목 추천</h3>
        {doc.recommendation.length === 0 ? (
          <p className="text-xs text-ink-soft">추천할 근거가 없습니다. 직접 선택하세요.</p>
        ) : (
          <ul className="space-y-1.5">
            {doc.recommendation.map((c) => (
              <li key={c.accountId}>
                <button
                  type="button"
                  onClick={() => setAccountId(String(c.accountId))}
                  className={`w-full rounded-md border px-3 py-2 text-left text-sm transition-colors ${accountId === String(c.accountId) ? "border-accent bg-accent-soft" : "border-line hover:bg-paper"}`}
                >
                  <span className="flex items-center justify-between gap-2">
                    <span className="font-medium">
                      {c.code} {c.name}
                    </span>
                    <span className="flex items-center gap-1.5">
                      <Badge tone={c.source === "HISTORY" ? "accent" : "neutral"}>{c.source === "HISTORY" ? "과거 이력" : "키워드 규칙"}</Badge>
                      <span className="num text-xs">{Math.round(c.confidence * 100)}%</span>
                    </span>
                  </span>
                  <span className="mt-0.5 block text-[11px] text-ink-soft">{c.reasons.join(" · ")}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {canWrite && (
        <>
          <Field label="승인할 계정과목">
            <Select value={accountId} onChange={(e) => setAccountId(e.target.value)}>
              <option value="">선택하세요</option>
              {accounts.data?.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.code} {a.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={rejecting ? "거절 사유 (필수)" : "검토 의견 (선택)"}>
            <Input value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} />
          </Field>
          <ErrorBanner error={error} />
          {hasErrors && <p className="text-xs text-danger">검증 오류가 있어 승인할 수 없습니다. 필드를 확인·수정하세요.</p>}
          <div className="flex justify-end gap-2">
            {rejecting ? (
              <>
                <Button variant="ghost" onClick={() => setRejecting(false)}>
                  취소
                </Button>
                <Button
                  variant="danger"
                  disabled={pending || !note.trim()}
                  onClick={() => act(() => api(`${base}/documents/${doc.id}/reject`, { method: "POST", body: { reason: note } }))}
                >
                  거절 확정
                </Button>
              </>
            ) : (
              <>
                <Button variant="danger" onClick={() => setRejecting(true)}>
                  거절
                </Button>
                <Button
                  variant="primary"
                  disabled={pending || hasErrors || !accountId || doc.status === "FAILED"}
                  onClick={() =>
                    act(() => api(`${base}/documents/${doc.id}/approve`, { method: "POST", body: { accountId: Number(accountId), note: note || null } }))
                  }
                >
                  승인
                </Button>
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}

const DEFAULT_CREDIT: Record<string, string> = { CARD: "2020", CASH: "1010", TRANSFER: "1020" };

function JournalSection({ base, companyId, doc, canWrite, accounts, onChanged }: { base: string; companyId: number; doc: DocumentDetail; canWrite: boolean; accounts: Account[]; onChanged: () => void }) {
  const defaultCode = DEFAULT_CREDIT[doc.paymentMethod ?? ""] ?? "2020";
  const [creditId, setCreditId] = useState<string>("");
  const effectiveCredit = creditId || String(accounts.find((a) => a.code === defaultCode)?.id ?? "");
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);
  const entry = useApi<JournalEntry>(doc.journalEntryId ? `${base}/journal-entries/${doc.journalEntryId}` : null);
  const linkedActive = entry.data && entry.data.status !== "VOID";

  if (linkedActive) {
    return (
      <p className="text-sm">
        연결된 전표:{" "}
        <Link href={`/c/${companyId}/journal-entries/${doc.journalEntryId}`} className="font-medium text-accent hover:underline">
          전표 #{doc.journalEntryId}
        </Link>
      </p>
    );
  }
  if (!canWrite) return null;

  return (
    <div className="space-y-2 rounded-md border border-line p-3">
      <h3 className="text-sm font-medium">전표 생성</h3>
      <p className="text-xs text-ink-soft">
        차변: 승인된 계정과목(공급가액) + 부가세대급금(부가세) / 대변: 아래 지급 계정(합계)
        {entry.data?.status === "VOID" && " · 이전 전표가 취소되어 다시 만들 수 있습니다."}
      </p>
      <Field label="대변 (지급 수단)">
        <Select value={effectiveCredit} onChange={(e) => setCreditId(e.target.value)}>
          {accounts
            .filter((a) => a.type === "ASSET" || a.type === "LIABILITY")
            .map((a) => (
              <option key={a.id} value={a.id}>
                {a.code} {a.name}
              </option>
            ))}
        </Select>
      </Field>
      <ErrorBanner error={error} />
      <div className="flex justify-end">
        <Button
          variant="primary"
          disabled={pending || !effectiveCredit}
          onClick={async () => {
            setPending(true);
            setError(undefined);
            try {
              await api(`${base}/documents/${doc.id}/journal-entry`, { method: "POST", body: { creditAccountId: Number(effectiveCredit) } });
              onChanged();
            } catch (err) {
              setError(err as ApiError);
            } finally {
              setPending(false);
            }
          }}
        >
          전표 생성
        </Button>
      </div>
    </div>
  );
}
