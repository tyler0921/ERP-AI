"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import {
  DOC_STATUS_LABEL,
  DOC_TYPE_LABEL,
  type DocStatus,
  type DocType,
  type DocumentDetail,
  type DocumentListItem,
  type DocumentSummary,
  type PageResponse,
} from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Button, Card, Empty, ErrorBanner, Loading, Money, PageHeader, Pagination, Select, formatDateTime, td, th } from "@/components/ui";
import { Confidence, DocStatusBadge } from "./status";

const TABS: ("" | DocStatus)[] = ["", "REVIEW_REQUIRED", "AI_REVIEWED", "PENDING", "FAILED", "APPROVED", "REJECTED"];

export default function DocumentsPage() {
  const router = useRouter();
  const { base, canWrite, company } = useCompany();
  const [status, setStatus] = useState<"" | DocStatus>("");
  const [page, setPage] = useState(0);
  const list = useApi<PageResponse<DocumentListItem>>(`${base}/documents`, { status, page, size: 20 });
  const summary = useApi<DocumentSummary>(`${base}/documents/summary`);
  const [docType, setDocType] = useState<DocType>("RECEIPT");
  const [error, setError] = useState<ApiError>();
  const [uploading, setUploading] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  // AI 처리 중인 증빙이 있으면 주기적으로 새로고침한다
  const hasPending = (summary.data?.byStatus.PENDING ?? 0) > 0;
  const { reload: reloadList } = list;
  const { reload: reloadSummary } = summary;
  useEffect(() => {
    if (!hasPending) return;
    const timer = setInterval(() => {
      reloadList();
      reloadSummary();
    }, 3000);
    return () => clearInterval(timer);
  }, [hasPending, reloadList, reloadSummary]);

  async function onFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    const form = new FormData();
    form.append("docType", docType);
    form.append("file", file);
    setUploading(true);
    setError(undefined);
    try {
      const doc = await api<DocumentDetail>(`${base}/documents`, { method: "POST", body: form });
      router.push(`/c/${company.id}/documents/${doc.id}`);
    } catch (err) {
      setError(err as ApiError);
      setUploading(false);
    }
  }

  return (
    <>
      <PageHeader
        title="증빙 문서"
        description="영수증·세금계산서를 올리면 PaddleOCR이 읽고 규칙이 검증합니다. 추출값은 담당자가 확인·승인해야 확정됩니다."
      />

      {canWrite && (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-line bg-surface px-4 py-3">
          <div className="flex items-center gap-2">
            <Select value={docType} onChange={(e) => setDocType(e.target.value as DocType)} className="w-36" aria-label="증빙 유형">
              <option value="RECEIPT">영수증</option>
              <option value="TAX_INVOICE">세금계산서</option>
            </Select>
            <input ref={fileRef} type="file" accept="image/png,image/jpeg,application/pdf" className="hidden" onChange={onFile} />
            <Button variant="primary" disabled={uploading} onClick={() => fileRef.current?.click()}>
              {uploading ? "업로드 중…" : "파일 업로드"}
            </Button>
            <span className="text-xs text-ink-soft">PNG·JPEG·PDF, 최대 10MB</span>
          </div>
          <div className="flex gap-3 text-xs text-ink-soft">
            샘플:
            <a href="/samples/receipt-sample.png" download className="hover:text-ink hover:underline">
              영수증
            </a>
            <a href="/samples/tax-invoice-sample.pdf" download className="hover:text-ink hover:underline">
              세금계산서(PDF)
            </a>
          </div>
        </div>
      )}
      <div className="mb-4">
        <ErrorBanner error={error} />
      </div>

      <Card
        title={
          <div className="flex flex-wrap gap-1">
            {TABS.map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => {
                  setStatus(t);
                  setPage(0);
                }}
                className={`rounded px-2 py-1 text-xs ${status === t ? "bg-ink text-white" : "text-ink-soft hover:bg-paper"}`}
              >
                {t ? DOC_STATUS_LABEL[t] : "전체"}
                {t && summary.data ? <span className="num ml-1 opacity-70">{summary.data.byStatus[t]}</span> : null}
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
          <Empty>증빙이 없습니다. 영수증이나 세금계산서를 업로드하세요.</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px]">
              <thead>
                <tr>
                  <th className={th}>#</th>
                  <th className={th}>유형</th>
                  <th className={th}>거래처</th>
                  <th className={th}>거래일</th>
                  <th className={`${th} text-right`}>합계</th>
                  <th className={th}>신뢰도</th>
                  <th className={th}>검증</th>
                  <th className={th}>상태</th>
                  <th className={th}>업로드</th>
                </tr>
              </thead>
              <tbody>
                {list.data?.content.map((d) => (
                  <tr key={d.id} onClick={() => router.push(`/c/${company.id}/documents/${d.id}`)} className="cursor-pointer hover:bg-paper">
                    <td className={`${td} num text-left text-ink-soft`}>{d.id}</td>
                    <td className={`${td} whitespace-nowrap`}>{DOC_TYPE_LABEL[d.docType]}</td>
                    <td className={`${td} max-w-52 truncate`} title={d.fileName}>
                      {d.vendorName ?? <span className="text-ink-soft">{d.fileName}</span>}
                    </td>
                    <td className={`${td} num text-left`}>{d.issueDate ?? "-"}</td>
                    <td className={`${td} text-right`}>
                      <Money value={d.totalAmount} />
                    </td>
                    <td className={td}>
                      <Confidence value={d.aiConfidence} />
                    </td>
                    <td className={`${td} whitespace-nowrap text-xs`}>
                      {d.errorCount > 0 && <span className="mr-2 text-danger">오류 {d.errorCount}</span>}
                      {d.warningCount > 0 && <span className="text-warn">경고 {d.warningCount}</span>}
                      {d.errorCount === 0 && d.warningCount === 0 && d.status !== "PENDING" && d.status !== "FAILED" && (
                        <span className="text-accent">통과</span>
                      )}
                    </td>
                    <td className={`${td} whitespace-nowrap`}>
                      <DocStatusBadge status={d.status} />
                      {d.journalEntryId && <span className="ml-1 text-xs text-ink-soft">전표 #{d.journalEntryId}</span>}
                    </td>
                    <td className={`${td} whitespace-nowrap text-xs text-ink-soft`}>{formatDateTime(d.createdAt)}</td>
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
