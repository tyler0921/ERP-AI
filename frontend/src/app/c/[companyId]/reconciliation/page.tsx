"use client";

import { useRef, useState, type ChangeEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import type { BankAccount, BankImport, BankTransaction, BankTxStatus, PageResponse, ReconSummary, RunResult } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Badge, Button, Card, Empty, ErrorBanner, Loading, Modal, Money, PageHeader, Pagination, Select, td, th } from "@/components/ui";
import { BankAccountForm } from "./bank-account-form";
import { BankTxDetail } from "./bank-tx-detail";
import { ScoreBadge } from "./score";

const STATUS_TABS: { value: "" | BankTxStatus; label: string }[] = [
  { value: "", label: "전체" },
  { value: "SUGGESTED", label: "검토 대기" },
  { value: "UNMATCHED", label: "미매칭" },
  { value: "MATCHED", label: "대사 완료" },
];

function StatusBadge({ status }: { status: BankTxStatus }) {
  if (status === "MATCHED") return <Badge tone="accent">대사 완료</Badge>;
  if (status === "SUGGESTED") return <Badge tone="warn">검토 대기</Badge>;
  return <Badge>미매칭</Badge>;
}

function percent(v: number | null) {
  return v === null ? "–" : `${v.toFixed(1)}%`;
}

export default function ReconciliationPage() {
  const { base, canWrite, company } = useCompany();
  const accounts = useApi<BankAccount[]>(`${base}/bank-accounts`);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const account = accounts.data?.find((a) => a.id === selectedId) ?? accounts.data?.find((a) => a.active) ?? accounts.data?.[0];

  const [status, setStatus] = useState<"" | BankTxStatus>("");
  const [page, setPage] = useState(0);
  const list = useApi<PageResponse<BankTransaction>>(account ? `${base}/bank-transactions` : null, {
    bankAccountId: account?.id,
    status,
    page,
    size: 30,
  });
  const summary = useApi<ReconSummary>(account ? `${base}/reconciliation/summary` : null, { bankAccountId: account?.id });

  const [creating, setCreating] = useState(false);
  const [detail, setDetail] = useState<BankTransaction | null>(null);
  const [notice, setNotice] = useState<string>();
  const [error, setError] = useState<ApiError>();
  const [busy, setBusy] = useState<"upload" | "run" | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  function refresh() {
    list.reload();
    summary.reload();
  }

  async function onUpload(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file || !account) return;
    const form = new FormData();
    form.append("file", file);
    setBusy("upload");
    setError(undefined);
    setNotice(undefined);
    try {
      const result = await api<BankImport>(`${base}/bank-accounts/${account.id}/imports`, { method: "POST", body: form });
      setNotice(
        `${result.fileName}: ${result.totalRows}행 중 ${result.importedRows}건 등록` +
          (result.duplicateRows > 0 ? `, ${result.duplicateRows}건은 이미 등록되어 건너뜀` : "") +
          ` (인코딩 ${result.encoding})`,
      );
      refresh();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(null);
    }
  }

  async function onRun() {
    if (!account) return;
    setBusy("run");
    setError(undefined);
    setNotice(undefined);
    try {
      const r = await api<RunResult>(`${base}/reconciliation/run`, { method: "POST", body: { bankAccountId: account.id } });
      setNotice(
        `미매칭 ${r.processed}건 분석 → 높음 ${r.suggestedHigh}건, 검토 필요 ${r.suggestedReview}건 제안, ${r.unmatched}건 미매칭. 제안은 승인해야 확정됩니다.`,
      );
      setStatus("");
      setPage(0);
      refresh();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(null);
    }
  }

  if (accounts.loading && !accounts.data) return <Loading />;

  return (
    <>
      <PageHeader
        title="은행 대사"
        description="은행 거래내역과 전표를 규칙 기반 Match Score로 매칭합니다. 알고리즘이 제안하고, 담당자가 승인해야 확정됩니다."
        actions={
          account && (
            <>
              <Select value={account.id} onChange={(e) => setSelectedId(Number(e.target.value))} aria-label="은행 계좌" className="w-56">
                {accounts.data?.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name} {a.accountNoMasked ?? ""} {a.active ? "" : "(비활성)"}
                  </option>
                ))}
              </Select>
              {canWrite && (
                <Button variant="ghost" onClick={() => setCreating(true)}>
                  + 계좌
                </Button>
              )}
            </>
          )
        }
      />

      {!account ? (
        <Card>
          <div className="px-6 py-10 text-center">
            <p className="font-medium">등록된 은행 계좌가 없습니다.</p>
            <p className="mt-1 text-sm text-ink-soft">계좌를 등록하고 은행에서 내려받은 거래내역 CSV를 업로드하세요.</p>
            {canWrite && (
              <Button variant="primary" className="mt-4" onClick={() => setCreating(true)}>
                은행 계좌 등록
              </Button>
            )}
          </div>
        </Card>
      ) : (
        <>
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-line bg-surface px-4 py-3">
            <p className="text-sm text-ink-soft">
              연결 계정과목{" "}
              <span className="font-medium text-ink">
                {account.glAccountCode} {account.glAccountName}
              </span>
              {account.bankName && ` · ${account.bankName}`}
            </p>
            {canWrite && account.active && (
              <div className="flex flex-wrap items-center gap-2">
                <a href="/samples/bank-statement-sample.csv" download className="text-xs text-ink-soft hover:text-ink hover:underline">
                  샘플 CSV
                </a>
                <input ref={fileRef} type="file" accept=".csv,text/csv" className="hidden" onChange={onUpload} />
                <Button disabled={busy !== null} onClick={() => fileRef.current?.click()}>
                  {busy === "upload" ? "업로드 중…" : "거래내역 CSV 업로드"}
                </Button>
                <Button variant="primary" disabled={busy !== null} onClick={onRun}>
                  {busy === "run" ? "대사 중…" : "자동 대사 실행"}
                </Button>
              </div>
            )}
          </div>

          <div className="mb-4 space-y-2">
            {notice && <p className="rounded-md border border-accent/30 bg-accent-soft px-3 py-2 text-sm text-accent">{notice}</p>}
            <ErrorBanner error={error} />
          </div>

          <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-5">
            {[
              { label: "은행 거래", value: summary.data?.total },
              { label: "대사 완료", value: summary.data?.matched },
              { label: "검토 대기", value: summary.data?.suggested },
              { label: "미매칭", value: summary.data?.unmatched },
              {
                label: "대사율 / 자동대사 성공률",
                value: summary.data ? `${percent(summary.data.matchRate)} / ${percent(summary.data.autoSuccessRate)}` : undefined,
              },
            ].map((s) => (
              <div key={s.label} className="rounded-lg border border-line bg-surface p-3">
                <p className="text-xs text-ink-soft">{s.label}</p>
                <p className="num mt-1 text-left text-xl font-medium">{s.value ?? "–"}</p>
              </div>
            ))}
          </div>

          <Card
            title={
              <div className="flex gap-1">
                {STATUS_TABS.map((t) => (
                  <button
                    key={t.value}
                    type="button"
                    onClick={() => {
                      setStatus(t.value);
                      setPage(0);
                    }}
                    className={`rounded px-2 py-1 text-xs ${status === t.value ? "bg-ink text-white" : "text-ink-soft hover:bg-paper"}`}
                  >
                    {t.label}
                  </button>
                ))}
              </div>
            }
          >
            {list.error && (
              <div className="p-3">
                <ErrorBanner error={list.error} />
              </div>
            )}
            {list.loading && !list.data ? (
              <Loading />
            ) : list.data?.content.length === 0 ? (
              <Empty>{status ? "해당 상태의 거래가 없습니다." : "은행 거래내역 CSV를 업로드하세요."}</Empty>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[820px]">
                  <thead>
                    <tr>
                      <th className={th}>거래일</th>
                      <th className={th}>거래처</th>
                      <th className={th}>적요</th>
                      <th className={`${th} text-right`}>입금</th>
                      <th className={`${th} text-right`}>출금</th>
                      <th className={th}>상태</th>
                      <th className={th}>매칭</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.data?.content.map((t) => (
                      <tr key={t.id} onClick={() => setDetail(t)} className="cursor-pointer hover:bg-paper">
                        <td className={`${td} num text-left`}>{t.txDate}</td>
                        <td className={`${td} max-w-44 truncate`}>{t.counterparty ?? "-"}</td>
                        <td className={`${td} max-w-44 truncate text-ink-soft`}>{t.memo ?? ""}</td>
                        <td className={`${td} text-right text-accent`}>{t.direction === "IN" && <Money value={t.amount} />}</td>
                        <td className={`${td} text-right`}>{t.direction === "OUT" && <Money value={t.amount} />}</td>
                        <td className={td}>
                          <StatusBadge status={t.status} />
                        </td>
                        <td className={`${td} whitespace-nowrap`}>
                          {t.match ? (
                            <span className="flex items-center gap-2">
                              <ScoreBadge score={t.match.score} level={t.match.level} />
                              <span className="text-xs text-ink-soft">전표 #{t.match.journalEntryIds.join(", #")}</span>
                            </span>
                          ) : (
                            <span className="text-xs text-ink-soft">후보 보기 →</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {list.data && <Pagination page={page} size={30} total={list.data.totalElements} onChange={setPage} />}
          </Card>
        </>
      )}

      <Modal open={creating} onClose={() => setCreating(false)} title="은행 계좌 등록">
        {creating && (
          <BankAccountForm
            base={base}
            onCreated={(a) => {
              setCreating(false);
              setSelectedId(a.id);
              accounts.reload();
            }}
          />
        )}
      </Modal>

      <Modal open={detail !== null} onClose={() => setDetail(null)} title={`은행 거래 #${detail?.id ?? ""}`} wide>
        {detail && (
          <BankTxDetail
            key={`${detail.id}-${detail.status}-${detail.match?.matchId ?? 0}`}
            base={base}
            companyId={company.id}
            tx={detail}
            canWrite={canWrite}
            onChanged={() => {
              setDetail(null);
              refresh();
            }}
          />
        )}
      </Modal>
    </>
  );
}
