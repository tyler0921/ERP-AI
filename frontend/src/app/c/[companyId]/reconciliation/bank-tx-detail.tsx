"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { DIRECTION_LABEL, type BankTransaction, type Candidate, type ReconMatch } from "@/lib/types";
import { Badge, Button, ErrorBanner, Field, Input, Loading, Money, formatDateTime } from "@/components/ui";
import { ScoreBadge, ScoreBreakdown } from "./score";

const MATCH_STATUS_LABEL = { SUGGESTED: "검토 대기", CONFIRMED: "대사 완료", REJECTED: "거절", CANCELLED: "취소" };

interface Props {
  base: string;
  companyId: number;
  tx: BankTransaction;
  canWrite: boolean;
  onChanged: () => void;
}

export function BankTxDetail({ base, companyId, tx, canWrite, onChanged }: Props) {
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const match = useApi<ReconMatch>(tx.match ? `${base}/reconciliation/matches/${tx.match.matchId}` : null);
  const candidates = useApi<Candidate[]>(tx.status !== "MATCHED" ? `${base}/bank-transactions/${tx.id}/candidates` : null);

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

  const matchUrl = (action: string) => `${base}/reconciliation/matches/${tx.match!.matchId}/${action}`;

  function onCancel(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const reason = String(new FormData(e.currentTarget).get("reason")).trim();
    act(() => api(matchUrl("cancel"), { method: "POST", body: { reason } }));
  }

  return (
    <div className="space-y-5 text-sm">
      <dl className="grid grid-cols-[90px_1fr] gap-y-1.5">
        <dt className="text-ink-soft">거래일시</dt>
        <dd className="num text-left">
          {tx.txDate} {tx.txTime ?? ""}
        </dd>
        <dt className="text-ink-soft">구분 / 금액</dt>
        <dd>
          {DIRECTION_LABEL[tx.direction]} <Money value={tx.amount} /> 원
        </dd>
        <dt className="text-ink-soft">거래처</dt>
        <dd>{tx.counterparty ?? "-"}</dd>
        <dt className="text-ink-soft">적요·메모</dt>
        <dd>{tx.memo ?? "-"}</dd>
      </dl>

      <ErrorBanner error={error} />

      {/* 현재 매칭 */}
      {tx.match && (
        <section className="rounded-md border border-line p-3">
          <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <h3 className="font-medium">{tx.match.status === "SUGGESTED" ? "자동 대사 제안" : "확정된 매칭"}</h3>
              <Badge tone={tx.match.status === "CONFIRMED" ? "accent" : "warn"}>{MATCH_STATUS_LABEL[tx.match.status]}</Badge>
              <Badge>{tx.match.source === "AUTO" ? "자동" : "수동"}</Badge>
            </div>
            <ScoreBadge score={tx.match.score} level={tx.match.level} />
          </header>
          <p className="mb-3">
            {tx.match.journalEntryIds.map((id) => (
              <Link key={id} href={`/c/${companyId}/journal-entries/${id}`} className="font-medium text-accent hover:underline">
                전표 #{id}
              </Link>
            ))}
          </p>
          {match.data ? <ScoreBreakdown detail={match.data.scoreDetail} /> : <Loading />}
          {match.data?.reviewedAt && (
            <p className="mt-3 text-xs text-ink-soft">
              검토 {formatDateTime(match.data.reviewedAt)}
              {match.data.reviewNote && ` · "${match.data.reviewNote}"`}
            </p>
          )}

          {canWrite && tx.match.status === "SUGGESTED" && (
            <div className="mt-3 flex justify-end gap-2 border-t border-line pt-3">
              <Button variant="danger" disabled={pending} onClick={() => act(() => api(matchUrl("reject"), { method: "POST", body: {} }))}>
                거절
              </Button>
              <Button variant="primary" disabled={pending} onClick={() => act(() => api(matchUrl("confirm"), { method: "POST", body: {} }))}>
                승인
              </Button>
            </div>
          )}
          {canWrite && tx.match.status === "CONFIRMED" && !cancelling && (
            <div className="mt-3 flex justify-end border-t border-line pt-3">
              <Button variant="danger" onClick={() => setCancelling(true)}>
                매칭 취소
              </Button>
            </div>
          )}
          {cancelling && (
            <form onSubmit={onCancel} className="mt-3 space-y-2 border-t border-line pt-3">
              <Field label="취소 사유 (Audit Log에 기록됩니다)">
                <Input name="reason" required maxLength={1000} autoFocus />
              </Field>
              <div className="flex justify-end gap-2">
                <Button type="button" variant="ghost" onClick={() => setCancelling(false)}>
                  닫기
                </Button>
                <Button type="submit" variant="danger" disabled={pending}>
                  매칭 취소
                </Button>
              </div>
            </form>
          )}
        </section>
      )}

      {/* 후보 전표 (수동 매칭) */}
      {tx.status !== "MATCHED" && (
        <section>
          <h3 className="mb-2 font-medium">후보 전표</h3>
          <p className="mb-2 text-xs text-ink-soft">
            ±30일 안에서 이 계좌의 계정과목이 같은 방향으로 움직인 전표입니다. 금액이 일치해야 매칭할 수 있습니다.
          </p>
          {candidates.loading && !candidates.data ? (
            <Loading />
          ) : candidates.data?.length === 0 ? (
            <p className="rounded-md bg-paper px-3 py-4 text-center text-xs text-ink-soft">후보 전표가 없습니다. 전표를 먼저 작성하세요.</p>
          ) : (
            <ul className="space-y-2">
              {candidates.data?.map((c) => {
                const amountOk = c.scoreDetail.amount > 0;
                const isCurrent = tx.match?.journalEntryIds.includes(c.journalEntryId);
                return (
                  <li key={c.journalEntryId} className="rounded-md border border-line p-3">
                    <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
                      <div>
                        <Link href={`/c/${companyId}/journal-entries/${c.journalEntryId}`} className="font-medium hover:underline">
                          전표 #{c.journalEntryId}
                        </Link>
                        <span className="num ml-2 text-xs text-ink-soft">{c.entryDate}</span>
                        <p className="text-xs text-ink-soft">{c.description ?? c.counterparty ?? "-"}</p>
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        <ScoreBadge score={c.score} level={c.level} />
                        <span className="text-xs">
                          <Money value={c.bankAmount} /> 원
                        </span>
                      </div>
                    </div>
                    <ScoreBreakdown detail={c.scoreDetail} />
                    <div className="mt-2 flex flex-wrap items-center justify-end gap-2">
                      {c.rejected && <Badge tone="danger">이전에 거절됨</Badge>}
                      {c.suggestedElsewhere && <Badge tone="warn">다른 거래에 제안됨</Badge>}
                      {canWrite && !isCurrent && (
                        <Button
                          disabled={pending || !amountOk}
                          title={amountOk ? undefined : "금액이 다른 전표는 매칭할 수 없습니다."}
                          onClick={() =>
                            act(() =>
                              api(`${base}/reconciliation/matches`, {
                                method: "POST",
                                body: { bankTransactionId: tx.id, journalEntryId: c.journalEntryId },
                              }),
                            )
                          }
                        >
                          이 전표로 매칭
                        </Button>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}
