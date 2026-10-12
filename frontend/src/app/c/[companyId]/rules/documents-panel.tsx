"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { RULE_CATEGORY_LABEL, RULE_STATUS_LABEL } from "@/lib/rules";
import type { RuleCategory, RuleChunk, RuleDocument, RuleDocumentStatus } from "@/lib/types";
import { Badge, Button, Card, Empty, ErrorBanner, Field, Input, Loading, Modal, Select, formatDateTime } from "@/components/ui";

const STATUS_TONE: Record<RuleDocumentStatus, "accent" | "warn" | "danger" | "neutral"> = {
  PENDING: "warn",
  INDEXED: "accent",
  FAILED: "danger",
  ARCHIVED: "neutral",
};

const CATEGORIES = Object.keys(RULE_CATEGORY_LABEL) as RuleCategory[];

function UploadForm({ base, onUploaded }: { base: string; onUploaded: () => void }) {
  const [title, setTitle] = useState("");
  const [category, setCategory] = useState<RuleCategory>("EXPENSE_POLICY");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    setPending(true);
    setError(undefined);
    const form = new FormData();
    form.append("title", title.trim());
    form.append("category", category);
    form.append("file", file);
    try {
      await api(`${base}/rules/documents`, { method: "POST", body: form });
      setTitle("");
      setFile(null);
      if (fileRef.current) fileRef.current.value = "";
      onUploaded();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-2 border-b border-line px-4 py-3">
      <ErrorBanner error={error} />
      <Field label="제목">
        <Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} required placeholder="예: 경비 처리 규정 2026" />
      </Field>
      <Field label="분류">
        <Select value={category} onChange={(e) => setCategory(e.target.value as RuleCategory)}>
          {CATEGORIES.map((c) => (
            <option key={c} value={c}>
              {RULE_CATEGORY_LABEL[c]}
            </option>
          ))}
        </Select>
      </Field>
      <Field label="파일" hint="PDF(텍스트 레이어), DOCX, TXT, MD · 최대 10MB">
        <input
          ref={fileRef}
          type="file"
          accept=".pdf,.docx,.txt,.md"
          onChange={(e) => {
            const f = e.target.files?.[0] ?? null;
            setFile(f);
            if (f && !title.trim()) setTitle(f.name.replace(/\.[^.]+$/, "").replace(/[_-]+/g, " "));
          }}
          className="block w-full text-sm"
        />
      </Field>
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-ink-soft">
          샘플:{" "}
          <a href="/samples/경비처리규정_가상.md" download className="text-accent hover:underline">
            경비 처리 규정
          </a>
          ,{" "}
          <a href="/samples/법인카드사용규정_가상.txt" download className="text-accent hover:underline">
            법인카드 규정
          </a>
        </span>
        <Button type="submit" variant="primary" disabled={pending || !file || !title.trim()}>
          등록
        </Button>
      </div>
    </form>
  );
}

function ChunksModal({ base, doc, onClose }: { base: string; doc: RuleDocument; onClose: () => void }) {
  const { data, error } = useApi<RuleChunk[]>(`${base}/rules/documents/${doc.id}/chunks`);
  return (
    <Modal open onClose={onClose} title={`${doc.title} · 청크 ${doc.chunkCount}개`} wide>
      <ErrorBanner error={error} />
      {!data ? (
        <Loading />
      ) : data.length === 0 ? (
        <Empty>색인된 청크가 없습니다.</Empty>
      ) : (
        <ol className="max-h-[60vh] space-y-3 overflow-y-auto text-sm">
          {data.map((c, i) => (
            <li key={c.id} className="rounded-md border border-line p-3">
              <p className="mb-1 text-xs text-ink-soft">
                #{i + 1} {c.section ?? "(섹션 없음)"}
                {c.page ? ` · p.${c.page}` : ""} · {c.content.length}자
              </p>
              <p className="whitespace-pre-line">{c.content}</p>
            </li>
          ))}
        </ol>
      )}
    </Modal>
  );
}

function ArchiveModal({ base, doc, onClose, onDone }: { base: string; doc: RuleDocument; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiError>();
  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      await api(`${base}/rules/documents/${doc.id}/archive`, { method: "POST", body: { reason: reason.trim() } });
      onDone();
    } catch (err) {
      setError(err as ApiError);
    }
  }
  return (
    <Modal open onClose={onClose} title={`보관: ${doc.title}`}>
      <form onSubmit={submit} className="space-y-3">
        <ErrorBanner error={error} />
        <p className="text-sm text-ink-soft">보관한 문서는 검색에서 빠지고 되돌릴 수 없습니다. 이전 질문의 근거 발췌는 그대로 남습니다.</p>
        <Field label="사유 (필수)">
          <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} required placeholder="예: 2027년 개정판으로 교체" />
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            취소
          </Button>
          <Button type="submit" variant="danger" disabled={!reason.trim()}>
            보관
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** 규정 문서 목록·업로드·청크 보기·다시 색인·보관 */
export function DocumentsPanel({ base, canWrite }: { base: string; canWrite: boolean }) {
  const { data, error, reload } = useApi<RuleDocument[]>(`${base}/rules/documents`);
  const [showArchived, setShowArchived] = useState(false);
  const [chunksOf, setChunksOf] = useState<RuleDocument | null>(null);
  const [archiving, setArchiving] = useState<RuleDocument | null>(null);
  const [actionError, setActionError] = useState<ApiError>();
  const indexing = data?.some((d) => d.status === "PENDING") ?? false;

  // 색인 중인 문서가 있으면 끝날 때까지 새로고침한다
  useEffect(() => {
    if (!indexing) return;
    const timer = setInterval(reload, 3000);
    return () => clearInterval(timer);
  }, [indexing, reload]);

  async function reindex(doc: RuleDocument) {
    setActionError(undefined);
    try {
      await api(`${base}/rules/documents/${doc.id}/reindex`, { method: "POST" });
      reload();
    } catch (err) {
      setActionError(err as ApiError);
    }
  }

  const docs = (data ?? []).filter((d) => showArchived || d.status !== "ARCHIVED");
  const archivedCount = (data ?? []).filter((d) => d.status === "ARCHIVED").length;

  return (
    <Card
      title="규정 문서"
      actions={
        archivedCount > 0 && (
          <label className="flex items-center gap-1 text-xs text-ink-soft">
            <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
            보관 {archivedCount}
          </label>
        )
      }
    >
      {canWrite && <UploadForm base={base} onUploaded={reload} />}
      <ErrorBanner error={error ?? actionError} />
      {!data ? (
        <Loading />
      ) : docs.length === 0 ? (
        <Empty>등록된 규정이 없습니다. 샘플 규정을 내려받아 등록해 보세요.</Empty>
      ) : (
        <ul>
          {docs.map((d) => (
            <li key={d.id} className="space-y-1 border-b border-line px-4 py-2.5 text-sm last:border-b-0">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="truncate font-medium" title={d.title}>
                    {d.title}
                  </p>
                  <p className="text-xs text-ink-soft">
                    {RULE_CATEGORY_LABEL[d.category]} · {d.fileName}
                  </p>
                </div>
                <Badge tone={STATUS_TONE[d.status]}>{RULE_STATUS_LABEL[d.status]}</Badge>
              </div>
              {d.status === "INDEXED" && (
                <p className="text-xs text-ink-soft">
                  청크 {d.chunkCount}개 · {d.charCount.toLocaleString()}자 · {formatDateTime(d.indexedAt)}
                </p>
              )}
              {d.status === "FAILED" && <p className="text-xs text-danger">{d.error}</p>}
              {d.status === "ARCHIVED" && <p className="text-xs text-ink-soft">보관 사유: {d.archiveReason}</p>}
              <div className="flex flex-wrap gap-1">
                {d.chunkCount > 0 && (
                  <Button variant="ghost" onClick={() => setChunksOf(d)}>
                    청크 보기
                  </Button>
                )}
                <a href={`/api/backend${base}/rules/documents/${d.id}/file`} className="inline-flex items-center rounded-md px-3 py-1.5 text-sm text-ink-soft hover:bg-line/50">
                  원본
                </a>
                {canWrite && (d.status === "FAILED" || d.status === "INDEXED") && (
                  <Button variant="ghost" onClick={() => reindex(d)}>
                    다시 색인
                  </Button>
                )}
                {canWrite && d.status !== "ARCHIVED" && d.status !== "PENDING" && (
                  <Button variant="ghost" onClick={() => setArchiving(d)}>
                    보관
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {chunksOf && <ChunksModal base={base} doc={chunksOf} onClose={() => setChunksOf(null)} />}
      {archiving && (
        <ArchiveModal
          base={base}
          doc={archiving}
          onClose={() => setArchiving(null)}
          onDone={() => {
            setArchiving(null);
            reload();
          }}
        />
      )}
    </Card>
  );
}
