"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useApi } from "@/lib/api";
import type { Account, JournalEntrySummary, PageResponse } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Button, Card, Empty, ErrorBanner, Input, Loading, Money, PageHeader, Pagination, Select, StatusBadge, td, th } from "@/components/ui";

export default function JournalEntriesPage() {
  const router = useRouter();
  const { base, canWrite, company } = useCompany();
  const [filters, setFilters] = useState({ from: "", to: "", status: "", accountId: "" });
  const [page, setPage] = useState(0);
  const list = useApi<PageResponse<JournalEntrySummary>>(`${base}/journal-entries`, { ...filters, page, size: 20 });
  const accounts = useApi<Account[]>(`${base}/accounts`);

  function onFilter(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setFilters({
      from: String(f.get("from") ?? ""),
      to: String(f.get("to") ?? ""),
      status: String(f.get("status") ?? ""),
      accountId: String(f.get("accountId") ?? ""),
    });
    setPage(0);
  }

  return (
    <>
      <PageHeader
        title="전표"
        description="복식부기 전표. 차변 합계와 대변 합계는 서버와 DB 제약으로 이중 검증됩니다."
        actions={
          canWrite && (
            <Link href={`/c/${company.id}/journal-entries/new`}>
              <Button variant="primary">전표 작성</Button>
            </Link>
          )
        }
      />
      <Card>
        <form onSubmit={onFilter} className="grid grid-cols-2 gap-2 border-b border-line p-3 md:grid-cols-[1fr_1fr_1fr_2fr_auto]">
          <Input name="from" type="date" aria-label="시작일" />
          <Input name="to" type="date" aria-label="종료일" />
          <Select name="status" aria-label="상태">
            <option value="">상태 전체</option>
            <option value="PENDING">미승인</option>
            <option value="VOID">취소</option>
          </Select>
          <Select name="accountId" aria-label="계정과목">
            <option value="">계정과목 전체</option>
            {accounts.data?.map((a) => (
              <option key={a.id} value={a.id}>
                {a.code} {a.name}
              </option>
            ))}
          </Select>
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
          <Empty>조건에 맞는 전표가 없습니다.</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px]">
              <thead>
                <tr>
                  <th className={th}>#</th>
                  <th className={th}>전표일자</th>
                  <th className={th}>적요</th>
                  <th className={th}>연결 거래</th>
                  <th className={`${th} text-right`}>금액</th>
                  <th className={th}>상태</th>
                </tr>
              </thead>
              <tbody>
                {list.data?.content.map((j) => (
                  <tr
                    key={j.id}
                    onClick={() => router.push(`/c/${company.id}/journal-entries/${j.id}`)}
                    className={`cursor-pointer hover:bg-paper ${j.status === "VOID" ? "text-ink-soft line-through decoration-ink-soft/40" : ""}`}
                  >
                    <td className={`${td} num text-left text-ink-soft`}>{j.id}</td>
                    <td className={`${td} num text-left`}>{j.entryDate}</td>
                    <td className={`${td} max-w-72 truncate`}>{j.description ?? "-"}</td>
                    <td className={`${td} text-ink-soft`}>{j.transactionId ? `#${j.transactionId}` : "-"}</td>
                    <td className={`${td} text-right`}>
                      <Money value={j.totalAmount} />
                    </td>
                    <td className={td}>
                      <StatusBadge status={j.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {list.data && <Pagination page={page} size={20} total={list.data.totalElements} onChange={setPage} />}
      </Card>
    </>
  );
}
