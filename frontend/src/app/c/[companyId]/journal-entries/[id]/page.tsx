"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import type { JournalEntry } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Button, Card, ErrorBanner, Field, Input, Loading, Money, PageHeader, StatusBadge, formatDateTime, td, th } from "@/components/ui";
import { JournalForm } from "../journal-form";

export default function JournalEntryDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { base, canWrite, company } = useCompany();
  const { data: entry, error, reload } = useApi<JournalEntry>(/^\d+$/.test(id) ? `${base}/journal-entries/${id}` : null);
  const [mode, setMode] = useState<"view" | "edit" | "void">("view");
  const [voidError, setVoidError] = useState<ApiError>();

  if (error) return <ErrorBanner error={error} />;
  if (!entry) return <Loading />;

  const isVoid = entry.status === "VOID";

  async function onVoid(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const reason = String(new FormData(e.currentTarget).get("reason")).trim();
    try {
      await api(`${base}/journal-entries/${entry!.id}/void`, { method: "POST", body: { reason } });
      setMode("view");
      reload();
    } catch (err) {
      setVoidError(err as ApiError);
    }
  }

  return (
    <>
      <PageHeader
        title={`전표 #${entry.id}`}
        description={entry.description ?? undefined}
        actions={
          <>
            <Link href={`/c/${company.id}/journal-entries`}>
              <Button variant="ghost">목록</Button>
            </Link>
            {canWrite && !isVoid && mode === "view" && (
              <>
                <Button onClick={() => setMode("edit")}>수정</Button>
                <Button variant="danger" onClick={() => setMode("void")}>
                  취소(VOID)
                </Button>
              </>
            )}
          </>
        }
      />

      {mode === "edit" ? (
        <Card className="p-4">
          <JournalForm
            initial={entry}
            onSaved={() => {
              setMode("view");
              reload();
            }}
          />
          <div className="mt-3 flex justify-end">
            <Button variant="ghost" onClick={() => setMode("view")}>
              편집 취소
            </Button>
          </div>
        </Card>
      ) : (
        <div className="space-y-4">
          {mode === "void" && (
            <form onSubmit={onVoid} className="space-y-2 rounded-lg border border-danger/30 bg-danger-soft/50 p-4">
              <ErrorBanner error={voidError} />
              <Field label="취소 사유 (Audit Log에 기록되며, 전표는 삭제되지 않고 VOID 상태로 남습니다)">
                <Input name="reason" required maxLength={500} autoFocus />
              </Field>
              <div className="flex justify-end gap-2">
                <Button type="button" variant="ghost" onClick={() => setMode("view")}>
                  닫기
                </Button>
                <Button type="submit" variant="danger">
                  전표 취소
                </Button>
              </div>
            </form>
          )}

          <Card>
            <dl className="grid grid-cols-2 gap-4 border-b border-line p-4 text-sm md:grid-cols-4">
              <div>
                <dt className="text-xs text-ink-soft">상태</dt>
                <dd className="mt-1">
                  <StatusBadge status={entry.status} />
                </dd>
              </div>
              <div>
                <dt className="text-xs text-ink-soft">전표일자</dt>
                <dd className="num mt-1 text-left">{entry.entryDate}</dd>
              </div>
              <div>
                <dt className="text-xs text-ink-soft">연결 거래</dt>
                <dd className="mt-1">{entry.transactionId ? `거래 #${entry.transactionId}` : "-"}</dd>
              </div>
              <div>
                <dt className="text-xs text-ink-soft">최종 수정</dt>
                <dd className="mt-1">{formatDateTime(entry.updatedAt)}</dd>
              </div>
              {entry.voidReason && (
                <div className="col-span-full">
                  <dt className="text-xs text-ink-soft">취소 사유</dt>
                  <dd className="mt-1 text-danger">{entry.voidReason}</dd>
                </div>
              )}
            </dl>
            <table className="w-full">
              <thead>
                <tr>
                  <th className={th}>계정과목</th>
                  <th className={`${th} text-right`}>차변</th>
                  <th className={`${th} text-right`}>대변</th>
                </tr>
              </thead>
              <tbody>
                {entry.lines.map((l) => (
                  <tr key={l.id}>
                    <td className={td}>
                      <span className="num mr-2 text-ink-soft">{l.accountCode}</span>
                      {l.accountName}
                    </td>
                    <td className={`${td} text-right`}>{l.debit !== "0.00" && <Money value={l.debit} />}</td>
                    <td className={`${td} text-right`}>{l.credit !== "0.00" && <Money value={l.credit} />}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="bg-paper/60 font-medium">
                  <td className="px-3 py-2 text-sm">합계</td>
                  <td className="px-3 py-2 text-right text-sm">
                    <Money value={entry.totalDebit} />
                  </td>
                  <td className="px-3 py-2 text-right text-sm">
                    <Money value={entry.totalCredit} />
                  </td>
                </tr>
              </tfoot>
            </table>
          </Card>
        </div>
      )}
    </>
  );
}
