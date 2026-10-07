"use client";

import { Fragment, useState, type FormEvent } from "react";
import { useApi } from "@/lib/api";
import type { AuditLog, Member, PageResponse } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Button, Card, Empty, ErrorBanner, Input, Loading, PageHeader, Pagination, Select, formatDateTime, td, th } from "@/components/ui";
import { ACTION_LABEL, TARGET_LABEL, actionLabel } from "./labels";

/** before/after에서 값이 달라진 키만 골라 보여준다. */
function diffKeys(before: Record<string, unknown> | null, after: Record<string, unknown> | null): string[] {
  const keys = new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})]);
  return [...keys].filter((k) => JSON.stringify(before?.[k]) !== JSON.stringify(after?.[k]));
}

function show(v: unknown): string {
  if (v === undefined) return "—";
  if (v === null) return "null";
  return typeof v === "string" ? v : JSON.stringify(v);
}

export default function AuditLogsPage() {
  const { base, company } = useCompany();
  const [filters, setFilters] = useState({ action: "", targetType: "", targetId: "", from: "", to: "" });
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  const allowed = company.role !== "VIEWER";
  const list = useApi<PageResponse<AuditLog>>(allowed ? `${base}/audit-logs` : null, { ...filters, page, size: 30 });
  const members = useApi<Member[]>(`${base}/members`);
  const actorName = (id: number | null) => (id == null ? "시스템" : (members.data?.find((m) => m.userId === id)?.name ?? `사용자 #${id}`));

  function onFilter(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setFilters({
      action: String(f.get("action") ?? ""),
      targetType: String(f.get("targetType") ?? ""),
      targetId: String(f.get("targetId") ?? "").trim(),
      from: String(f.get("from") ?? ""),
      to: String(f.get("to") ?? ""),
    });
    setPage(0);
  }

  return (
    <>
      <PageHeader title="Audit Log" description="누가, 언제, 무엇을, 어떤 값에서 어떤 값으로 바꿨는지 기록합니다. 이력은 수정·삭제할 수 없습니다." />
      {!allowed ? (
        <ErrorBanner error="Audit Log는 관리자와 회계담당자만 조회할 수 있습니다." />
      ) : (
        <Card>
          <form onSubmit={onFilter} className="grid grid-cols-2 gap-2 border-b border-line p-3 lg:grid-cols-3 xl:grid-cols-[1.4fr_1fr_100px_1fr_1fr_auto]">
            <Select name="action" aria-label="작업">
              <option value="">작업 전체</option>
              {Object.entries(ACTION_LABEL).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </Select>
            <Select name="targetType" aria-label="대상">
              <option value="">대상 전체</option>
              {Object.entries(TARGET_LABEL).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </Select>
            <Input name="targetId" placeholder="대상 ID" inputMode="numeric" pattern="\d*" />
            <Input name="from" type="date" aria-label="시작일" />
            <Input name="to" type="date" aria-label="종료일" />
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
            <Empty>이력이 없습니다.</Empty>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px]">
                <thead>
                  <tr>
                    <th className={th}>일시</th>
                    <th className={th}>사용자</th>
                    <th className={th}>작업</th>
                    <th className={th}>대상</th>
                    <th className={th}>변경 항목</th>
                  </tr>
                </thead>
                <tbody>
                  {list.data?.content.map((a) => {
                    const keys = diffKeys(a.before, a.after);
                    return (
                      <Fragment key={a.id}>
                        <tr onClick={() => setOpen(open === a.id ? null : a.id)} className="cursor-pointer hover:bg-paper">
                          <td className={`${td} whitespace-nowrap text-ink-soft`}>{formatDateTime(a.createdAt)}</td>
                          <td className={`${td} whitespace-nowrap`}>{actorName(a.actorId)}</td>
                          <td className={`${td} whitespace-nowrap`}>{actionLabel(a.action)}</td>
                          <td className={`${td} whitespace-nowrap`}>
                            {a.targetType ? (TARGET_LABEL[a.targetType] ?? a.targetType) : "-"}
                            {a.targetId != null && <span className="text-ink-soft"> #{a.targetId}</span>}
                          </td>
                          <td className={`${td} max-w-60 truncate text-xs text-ink-soft`}>{keys.join(", ")}</td>
                        </tr>
                        {open === a.id && (
                          <tr>
                            <td colSpan={5} className="border-b border-line bg-paper/50 px-3 py-3">
                              {keys.length === 0 ? (
                                <p className="text-xs text-ink-soft">변경된 값이 없습니다.</p>
                              ) : (
                                <table className="w-full text-xs">
                                  <thead className="text-ink-soft">
                                    <tr>
                                      <th className="w-40 py-1 text-left font-medium">항목</th>
                                      <th className="w-[45%] py-1 pr-2 text-left font-medium">Before</th>
                                      <th className="py-1 text-left font-medium">After</th>
                                    </tr>
                                  </thead>
                                  <tbody className="font-mono">
                                    {keys.map((k) => (
                                      <tr key={k} className="align-top">
                                        <td className="py-1 pr-2">{k}</td>
                                        <td className="py-1 pr-2 break-all text-danger">{show(a.before?.[k])}</td>
                                        <td className="py-1 break-all text-accent">{show(a.after?.[k])}</td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              )}
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          {list.data && <Pagination page={page} size={30} total={list.data.totalElements} onChange={setPage} />}
        </Card>
      )}
    </>
  );
}
