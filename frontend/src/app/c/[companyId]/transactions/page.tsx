"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { normalizeMoneyInput } from "@/lib/money";
import { todayKst } from "@/lib/dates";
import { DIRECTION_LABEL, type Direction, type PageResponse, type Transaction } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import {
  Button,
  Card,
  Empty,
  ErrorBanner,
  Field,
  Input,
  Loading,
  Modal,
  Money,
  PageHeader,
  Pagination,
  Select,
  StatusBadge,
  formatDateTime,
  td,
  th,
} from "@/components/ui";

interface Filters {
  from: string;
  to: string;
  direction: string;
  status: string;
  q: string;
}

const EMPTY_FILTERS: Filters = { from: "", to: "", direction: "", status: "", q: "" };

export default function TransactionsPage() {
  const { base, canWrite, company } = useCompany();
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const list = useApi<PageResponse<Transaction>>(`${base}/transactions`, { ...filters, page, size: 20 });
  const [editing, setEditing] = useState<Transaction | "new" | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  function onFilter(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setFilters({
      from: String(f.get("from") ?? ""),
      to: String(f.get("to") ?? ""),
      direction: String(f.get("direction") ?? ""),
      status: String(f.get("status") ?? ""),
      q: String(f.get("q") ?? "").trim(),
    });
    setPage(0);
  }

  return (
    <>
      <PageHeader
        title="거래"
        description="입출금 거래를 등록하고 전표와 연결합니다. 거래는 삭제하지 않고 사유와 함께 취소합니다."
        actions={canWrite && <Button variant="primary" onClick={() => setEditing("new")}>거래 등록</Button>}
      />

      <Card>
        <form onSubmit={onFilter} className="grid grid-cols-2 gap-2 border-b border-line p-3 lg:grid-cols-[repeat(4,minmax(0,1fr))_2fr_auto]">
          <Input name="from" type="date" aria-label="시작일" />
          <Input name="to" type="date" aria-label="종료일" />
          <Select name="direction" aria-label="입출금">
            <option value="">입출금 전체</option>
            <option value="IN">입금</option>
            <option value="OUT">출금</option>
          </Select>
          <Select name="status" aria-label="상태">
            <option value="">상태 전체</option>
            <option value="PENDING">미승인</option>
            <option value="VOID">취소</option>
          </Select>
          <Input name="q" placeholder="거래처·메모 검색" />
          <Button type="submit">조회</Button>
        </form>

        {list.error && (
          <div className="p-3">
            <ErrorBanner error={list.error} />
          </div>
        )}
        {list.loading && !list.data ? (
          <Loading />
        ) : list.data?.content.length === 0 ? (
          <Empty>조건에 맞는 거래가 없습니다.</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px]">
              <thead>
                <tr>
                  <th className={th}>#</th>
                  <th className={th}>거래일</th>
                  <th className={th}>거래처</th>
                  <th className={th}>구분</th>
                  <th className={`${th} text-right`}>금액</th>
                  <th className={th}>메모</th>
                  <th className={th}>상태</th>
                </tr>
              </thead>
              <tbody>
                {list.data?.content.map((t) => (
                  <tr key={t.id} onClick={() => setSelectedId(t.id)} className={`cursor-pointer hover:bg-paper ${t.status === "VOID" ? "text-ink-soft line-through decoration-ink-soft/40" : ""}`}>
                    <td className={`${td} num text-left text-ink-soft`}>{t.id}</td>
                    <td className={`${td} num text-left`}>{t.txDate}</td>
                    <td className={`${td} max-w-48 truncate`}>{t.counterparty ?? "-"}</td>
                    <td className={td}>{DIRECTION_LABEL[t.direction]}</td>
                    <td className={`${td} text-right ${t.direction === "IN" ? "text-accent" : ""}`}>
                      <Money value={t.amount} />
                    </td>
                    <td className={`${td} max-w-64 truncate text-ink-soft`}>{t.memo ?? ""}</td>
                    <td className={td}>
                      <StatusBadge status={t.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {list.data && <Pagination page={page} size={20} total={list.data.totalElements} onChange={setPage} />}
      </Card>

      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "거래 등록" : "거래 수정"}>
        {editing !== null && (
          <TransactionForm
            base={base}
            initial={editing === "new" ? null : editing}
            onDone={() => {
              setEditing(null);
              list.reload();
            }}
          />
        )}
      </Modal>

      <Modal open={selectedId !== null} onClose={() => setSelectedId(null)} title={`거래 #${selectedId ?? ""}`}>
        {selectedId !== null && (
          <TransactionDetail
            key={selectedId}
            base={base}
            companyId={company.id}
            id={selectedId}
            canWrite={canWrite}
            onEdit={(t) => {
              setSelectedId(null);
              setEditing(t);
            }}
            onChanged={list.reload}
          />
        )}
      </Modal>
    </>
  );
}

function TransactionForm({ base, initial, onDone }: { base: string; initial: Transaction | null; onDone: () => void }) {
  const [error, setError] = useState<ApiError | string>();
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const amount = normalizeMoneyInput(String(f.get("amount")));
    if (amount === null || amount === "0.00") {
      setError("금액은 0보다 큰 숫자로, 소수점 둘째 자리까지 입력하세요.");
      return;
    }
    const body = {
      txDate: f.get("txDate"),
      counterparty: String(f.get("counterparty") ?? "").trim() || null,
      amount,
      direction: f.get("direction") as Direction,
      memo: String(f.get("memo") ?? "").trim() || null,
    };
    setPending(true);
    setError(undefined);
    try {
      if (initial) await api(`${base}/transactions/${initial.id}`, { method: "PATCH", body });
      else await api(`${base}/transactions`, { method: "POST", body });
      onDone();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <ErrorBanner error={error} />
      <div className="grid grid-cols-2 gap-3">
        <Field label="거래일">
          <Input name="txDate" type="date" required defaultValue={initial?.txDate ?? todayKst()} />
        </Field>
        <Field label="구분">
          <Select name="direction" defaultValue={initial?.direction ?? "OUT"}>
            <option value="OUT">출금</option>
            <option value="IN">입금</option>
          </Select>
        </Field>
      </div>
      <Field label="금액 (원)">
        <Input name="amount" inputMode="decimal" required className="num" placeholder="1,250,000" defaultValue={initial?.amount.replace(/\.00$/, "") ?? ""} />
      </Field>
      <Field label="거래처">
        <Input name="counterparty" maxLength={200} defaultValue={initial?.counterparty ?? ""} />
      </Field>
      <Field label="메모">
        <Input name="memo" maxLength={2000} defaultValue={initial?.memo ?? ""} />
      </Field>
      <div className="flex justify-end gap-2 pt-2">
        <Button type="submit" variant="primary" disabled={pending}>
          {initial ? "수정" : "등록"}
        </Button>
      </div>
    </form>
  );
}

function TransactionDetail({
  base,
  companyId,
  id,
  canWrite,
  onEdit,
  onChanged,
}: {
  base: string;
  companyId: number;
  id: number;
  canWrite: boolean;
  onEdit: (t: Transaction) => void;
  onChanged: () => void;
}) {
  const { data: t, error, reload } = useApi<Transaction>(`${base}/transactions/${id}`);
  const [voidError, setVoidError] = useState<ApiError>();
  const [voiding, setVoiding] = useState(false);

  if (error) return <ErrorBanner error={error} />;
  if (!t) return <Loading />;

  const linked = (t.journalEntryIds?.length ?? 0) > 0;
  const editable = canWrite && t.status !== "VOID" && !linked;

  async function onVoid(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const reason = String(new FormData(e.currentTarget).get("reason")).trim();
    try {
      await api(`${base}/transactions/${id}/void`, { method: "POST", body: { reason } });
      setVoiding(false);
      reload();
      onChanged();
    } catch (err) {
      setVoidError(err as ApiError);
    }
  }

  return (
    <div className="space-y-4 text-sm">
      <dl className="grid grid-cols-[100px_1fr] gap-y-2">
        <dt className="text-ink-soft">상태</dt>
        <dd>
          <StatusBadge status={t.status} />
          {t.voidReason && <span className="ml-2 text-ink-soft">사유: {t.voidReason}</span>}
        </dd>
        <dt className="text-ink-soft">거래일</dt>
        <dd className="num text-left">{t.txDate}</dd>
        <dt className="text-ink-soft">구분 / 금액</dt>
        <dd>
          {DIRECTION_LABEL[t.direction]} <Money value={t.amount} /> 원
        </dd>
        <dt className="text-ink-soft">거래처</dt>
        <dd>{t.counterparty ?? "-"}</dd>
        <dt className="text-ink-soft">메모</dt>
        <dd className="whitespace-pre-wrap">{t.memo ?? "-"}</dd>
        <dt className="text-ink-soft">연결 전표</dt>
        <dd className="flex flex-wrap gap-2">
          {linked
            ? t.journalEntryIds!.map((jid) => (
                <Link key={jid} href={`/c/${companyId}/journal-entries/${jid}`} className="text-accent hover:underline">
                  전표 #{jid}
                </Link>
              ))
            : "없음"}
        </dd>
        <dt className="text-ink-soft">등록 / 수정</dt>
        <dd className="text-ink-soft">
          {formatDateTime(t.createdAt)} / {formatDateTime(t.updatedAt)}
        </dd>
      </dl>

      {canWrite && t.status !== "VOID" && (
        <div className="flex flex-wrap gap-2 border-t border-line pt-4">
          {!linked && (
            <Link href={`/c/${companyId}/journal-entries/new?transactionId=${t.id}`}>
              <Button variant="primary">전표 작성</Button>
            </Link>
          )}
          <Button disabled={!editable} onClick={() => onEdit(t)}>
            수정
          </Button>
          <Button variant="danger" disabled={linked} onClick={() => setVoiding(true)}>
            취소(VOID)
          </Button>
          {linked && <p className="w-full text-xs text-ink-soft">전표가 연결된 거래는 수정·취소할 수 없습니다. 연결된 전표를 먼저 취소하세요.</p>}
        </div>
      )}

      {voiding && (
        <form onSubmit={onVoid} className="space-y-2 rounded-md border border-danger/30 bg-danger-soft/50 p-3">
          <ErrorBanner error={voidError} />
          <Field label="취소 사유 (Audit Log에 기록됩니다)">
            <Input name="reason" required maxLength={500} autoFocus />
          </Field>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setVoiding(false)}>
              닫기
            </Button>
            <Button type="submit" variant="danger">
              거래 취소
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}
