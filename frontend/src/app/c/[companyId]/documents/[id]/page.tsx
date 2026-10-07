"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { DOC_TYPE_LABEL, type DocumentDetail } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Button, Card, ErrorBanner, Loading, PageHeader, formatDateTime } from "@/components/ui";
import { Confidence, DocStatusBadge } from "../status";
import { FieldsForm } from "./fields-form";
import { ReviewPanel } from "./review-panel";

export default function DocumentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { base, canWrite, company } = useCompany();
  const { data: doc, error, reload } = useApi<DocumentDetail>(/^\d+$/.test(id) ? `${base}/documents/${id}` : null);
  const [retryError, setRetryError] = useState<ApiError>();

  // AI 처리 중이면 결과가 올 때까지 새로고침
  const pendingNow = doc?.status === "PENDING";
  useEffect(() => {
    if (!pendingNow) return;
    const timer = setInterval(reload, 2500);
    return () => clearInterval(timer);
  }, [pendingNow, reload]);

  if (error) return <ErrorBanner error={error} />;
  if (!doc) return <Loading />;

  const fileUrl = `/api/backend${base}/documents/${doc.id}/file`;
  const editable = canWrite && (doc.status === "AI_REVIEWED" || doc.status === "REVIEW_REQUIRED" || doc.status === "FAILED");
  const errors = doc.validation.filter((i) => i.severity === "ERROR");
  const warnings = doc.validation.filter((i) => i.severity === "WARNING");

  return (
    <>
      <PageHeader
        title={`${DOC_TYPE_LABEL[doc.docType]} #${doc.id}`}
        description={doc.fileName}
        actions={
          <>
            <DocStatusBadge status={doc.status} />
            <Link href={`/c/${company.id}/documents`}>
              <Button variant="ghost">목록</Button>
            </Link>
          </>
        }
      />

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)]">
        {/* 원본과 OCR 원문 */}
        <div className="space-y-4">
          <Card title="원본">
            <div className="bg-paper p-3">
              {doc.contentType === "application/pdf" ? (
                <iframe src={fileUrl} title="증빙 원본 PDF" className="h-[640px] w-full rounded border border-line bg-white" />
              ) : (
                // eslint-disable-next-line @next/next/no-img-element -- 인증된 동적 파일이라 next/image 최적화 대상이 아니다
                <img src={fileUrl} alt="증빙 원본" className="mx-auto max-h-[720px] w-auto rounded border border-line bg-white" />
              )}
            </div>
          </Card>
          {doc.ocrLines.length > 0 && (
            <Card title="OCR 원문" actions={<span className="text-xs text-ink-soft">{doc.ocrInfo.engine} · {doc.ocrInfo.durationMs}ms</span>}>
              <details className="px-4 py-2 text-sm">
                <summary className="cursor-pointer text-xs text-ink-soft">{doc.ocrLines.length}줄 보기</summary>
                <ol className="mt-2 space-y-0.5 font-mono text-xs">
                  {doc.ocrLines.map((l, i) => (
                    <li key={i} className="flex justify-between gap-3">
                      <span>{l.text}</span>
                      <Confidence value={l.score} />
                    </li>
                  ))}
                </ol>
                <p className="mt-2 text-[11px] text-ink-soft">모델: {doc.ocrInfo.model}</p>
              </details>
            </Card>
          )}
        </div>

        {/* 추출 필드 · 검증 · 승인 */}
        <div className="space-y-4">
          {doc.status === "PENDING" && (
            <Card>
              <div className="flex items-center gap-3 px-4 py-4 text-sm">
                <span className="h-2.5 w-2.5 animate-pulse rounded-full bg-warn" />
                <div>
                  <p className="font-medium">AI가 증빙을 읽는 중입니다… (시도 {doc.attempt})</p>
                  <p className="text-xs text-ink-soft">PaddleOCR → 필드 추출 → 계정과목 규칙. 첫 처리는 모델 로딩 때문에 더 걸릴 수 있습니다.</p>
                </div>
              </div>
            </Card>
          )}

          {doc.status === "FAILED" && (
            <Card>
              <div className="space-y-2 px-4 py-3 text-sm">
                <p className="font-medium text-danger">AI 처리 실패</p>
                <p className="text-xs text-ink-soft">{doc.errorMessage}</p>
                <p className="text-xs text-ink-soft">다시 시도하거나, 아래 필드를 직접 입력해 진행할 수 있습니다.</p>
                <ErrorBanner error={retryError} />
                {canWrite && (
                  <Button
                    onClick={async () => {
                      try {
                        await api(`${base}/documents/${doc.id}/retry`, { method: "POST" });
                        reload();
                      } catch (err) {
                        setRetryError(err as ApiError);
                      }
                    }}
                  >
                    AI 재처리
                  </Button>
                )}
              </div>
            </Card>
          )}

          {doc.status !== "PENDING" && (
            <Card
              title="검증 결과"
              actions={
                doc.aiConfidence !== null && (
                  <span className="flex items-center gap-1 text-xs text-ink-soft">
                    AI 신뢰도 <Confidence value={doc.aiConfidence} />
                  </span>
                )
              }
            >
              <div className="px-4 py-3">
                {doc.validation.length === 0 ? (
                  <p className="text-sm text-accent">✓ 모든 규칙을 통과했습니다. (합계 일치, 사업자번호 검증, 날짜, 중복)</p>
                ) : (
                  <ul className="space-y-1.5 text-sm">
                    {[...errors, ...warnings].map((i, idx) => (
                      <li key={idx} className={`flex gap-2 ${i.severity === "ERROR" ? "text-danger" : "text-warn"}`}>
                        <span className="shrink-0 font-medium">{i.severity === "ERROR" ? "오류" : "경고"}</span>
                        <span>{i.message}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </Card>
          )}

          {doc.status !== "PENDING" && (
            <Card title="추출 필드" actions={doc.processedAt && <span className="text-xs text-ink-soft">AI 처리 {formatDateTime(doc.processedAt)}</span>}>
              <div className="p-4">
                <FieldsForm key={doc.updatedAt} base={base} doc={doc} editable={editable} onSaved={reload} />
              </div>
            </Card>
          )}

          {doc.status !== "PENDING" && (
            <Card title="계정과목 · 승인">
              <div className="p-4">
                <ReviewPanel key={doc.updatedAt} base={base} companyId={company.id} doc={doc} canWrite={canWrite} onChanged={reload} />
              </div>
            </Card>
          )}
        </div>
      </div>
    </>
  );
}
