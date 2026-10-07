"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { checkJournalLines, toLineRequests, type LineDraft } from "@/lib/journal";
import { todayKst } from "@/lib/dates";
import { ACCOUNT_TYPE_LABEL, type Account, type JournalEntry } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Button, ErrorBanner, Field, Input, Money, Select } from "@/components/ui";

const blank = (): LineDraft => ({ accountId: "", debit: "", credit: "" });

function strip(v: string) {
  return v === "0.00" ? "" : v.replace(/\.00$/, "");
}

export function JournalForm({
  initial,
  transactionId,
  onSaved,
}: {
  initial?: JournalEntry;
  transactionId?: number | null;
  onSaved: (entry: JournalEntry) => void;
}) {
  const { base } = useCompany();
  const accounts = useApi<Account[]>(`${base}/accounts`);
  const [lines, setLines] = useState<LineDraft[]>(
    initial ? initial.lines.map((l) => ({ accountId: String(l.accountId), debit: strip(l.debit), credit: strip(l.credit) })) : [blank(), blank()],
  );
  const [error, setError] = useState<ApiError | string>();
  const [pending, setPending] = useState(false);
  const check = checkJournalLines(lines);

  // 수정 시에는 비활성 계정이 이미 쓰인 라인도 보여야 하므로 비활성 계정도 목록에 남긴다(선택지에는 표시).
  const usedIds = new Set(lines.map((l) => l.accountId));
  const options = (accounts.data ?? []).filter((a) => a.active || usedIds.has(String(a.id)));

  function update(i: number, patch: Partial<LineDraft>) {
    setLines((prev) => prev.map((l, idx) => (idx === i ? { ...l, ...patch } : l)));
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (check.errors.length > 0) {
      setError(check.errors[0]);
      return;
    }
    const f = new FormData(e.currentTarget);
    const txId = String(f.get("transactionId") ?? "").trim();
    const body = {
      entryDate: f.get("entryDate"),
      description: String(f.get("description") ?? "").trim() || null,
      transactionId: txId ? Number(txId) : null,
      lines: toLineRequests(lines),
    };
    setPending(true);
    setError(undefined);
    try {
      const saved = initial
        ? await api<JournalEntry>(`${base}/journal-entries/${initial.id}`, { method: "PUT", body })
        : await api<JournalEntry>(`${base}/journal-entries`, { method: "POST", body });
      onSaved(saved);
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-[180px_1fr_160px]">
        <Field label="전표일자">
          <Input name="entryDate" type="date" required defaultValue={initial?.entryDate ?? todayKst()} />
        </Field>
        <Field label="적요">
          <Input name="description" maxLength={500} defaultValue={initial?.description ?? ""} />
        </Field>
        <Field label="연결 거래 ID (선택)">
          <Input name="transactionId" inputMode="numeric" pattern="\d*" defaultValue={initial?.transactionId ?? transactionId ?? ""} />
        </Field>
      </div>

      <div className="overflow-x-auto rounded-md border border-line">
        <table className="w-full min-w-[600px] text-sm">
          <thead className="bg-paper/60 text-xs text-ink-soft">
            <tr>
              <th className="px-2 py-2 text-left font-medium">계정과목</th>
              <th className="w-44 px-2 py-2 text-right font-medium">차변</th>
              <th className="w-44 px-2 py-2 text-right font-medium">대변</th>
              <th className="w-10" />
            </tr>
          </thead>
          <tbody>
            {lines.map((line, i) => (
              <tr key={i} className="border-t border-line">
                <td className="px-2 py-1.5">
                  <Select value={line.accountId} onChange={(e) => update(i, { accountId: e.target.value })} aria-label={`${i + 1}행 계정과목`}>
                    <option value="">계정과목 선택</option>
                    {options.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.code} {a.name} ({ACCOUNT_TYPE_LABEL[a.type]}){a.active ? "" : " · 비활성"}
                      </option>
                    ))}
                  </Select>
                </td>
                <td className="px-2 py-1.5">
                  <Input className="num" inputMode="decimal" value={line.debit} onChange={(e) => update(i, { debit: e.target.value })} aria-label={`${i + 1}행 차변`} />
                </td>
                <td className="px-2 py-1.5">
                  <Input className="num" inputMode="decimal" value={line.credit} onChange={(e) => update(i, { credit: e.target.value })} aria-label={`${i + 1}행 대변`} />
                </td>
                <td className="px-1 text-center">
                  <button
                    type="button"
                    disabled={lines.length <= 2}
                    onClick={() => setLines((prev) => prev.filter((_, idx) => idx !== i))}
                    className="text-ink-soft hover:text-danger disabled:opacity-30"
                    aria-label={`${i + 1}행 삭제`}
                  >
                    ✕
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t-2 border-line-strong bg-paper/40">
            <tr>
              <td className="px-2 py-2">
                <Button type="button" variant="ghost" onClick={() => setLines((prev) => [...prev, blank()])}>
                  + 라인 추가
                </Button>
              </td>
              <td className="px-4 py-2 text-right font-medium">
                <Money value={check.totalDebit} />
              </td>
              <td className="px-4 py-2 text-right font-medium">
                <Money value={check.totalCredit} />
              </td>
              <td />
            </tr>
          </tfoot>
        </table>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className={`text-sm ${check.balanced ? "text-accent" : "text-warn"}`}>
          {check.balanced ? (
            "✓ 차변과 대변이 일치합니다."
          ) : (
            <>
              차액 <Money value={check.difference} /> 원 — 차변 합계와 대변 합계가 같아야 저장할 수 있습니다.
            </>
          )}
        </p>
        <Button type="submit" variant="primary" disabled={pending || !check.balanced}>
          {initial ? "전표 수정" : "전표 저장"}
        </Button>
      </div>
      <ErrorBanner error={error} />
    </form>
  );
}
