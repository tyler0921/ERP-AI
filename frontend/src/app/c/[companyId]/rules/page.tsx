"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { RULE_EXAMPLES, sourceLabel, splitCitations } from "@/lib/rules";
import type { PageResponse, RuleQuestion, RuleQuestionSummary } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Badge, Button, Card, Empty, ErrorBanner, Loading, PageHeader, formatDateTime } from "@/components/ui";
import { ExplanationPanel, usePollWhilePending } from "../anomalies/explanation";
import { DocumentsPanel } from "./documents-panel";

function AskForm({ base, onAsked }: { base: string; onAsked: (id: number) => void }) {
  const [question, setQuestion] = useState("");
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setPending(true);
    setError(undefined);
    try {
      const q = await api<RuleQuestion>(`${base}/rules/questions`, { method: "POST", body: { question: question.trim() } });
      setQuestion("");
      onAsked(q.id);
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-2 p-4">
      <ErrorBanner error={error} />
      <div className="flex gap-2">
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          maxLength={500}
          placeholder="회사 규정에 대해 물어보세요 — 예: 접대비 한도는 얼마인가요?"
          aria-label="규정 질문"
          className="min-w-0 flex-1 rounded-md border border-line-strong bg-surface px-3 py-2 text-sm outline-none focus:border-accent focus:ring-2 focus:ring-accent/15"
        />
        <Button type="submit" variant="primary" disabled={pending || question.trim() === ""}>
          {pending ? "검색 중…" : "질문"}
        </Button>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {RULE_EXAMPLES.map((q) => (
          <button
            key={q}
            type="button"
            onClick={() => setQuestion(q)}
            className="rounded-full border border-line px-2.5 py-0.5 text-xs text-ink-soft hover:border-line-strong hover:text-ink"
          >
            {q}
          </button>
        ))}
      </div>
    </form>
  );
}

function QuestionDetail({ base, id }: { base: string; id: number }) {
  const { data, error, reload } = useApi<RuleQuestion>(`${base}/rules/questions/${id}`);
  const q = data?.id === id ? data : undefined;
  usePollWhilePending(q?.answer?.status, reload);
  const [highlight, setHighlight] = useState<number | null>(null);

  if (error) return <ErrorBanner error={error} />;
  if (!q) return <Loading />;

  const focus = (n: number) => {
    setHighlight(n);
    document.getElementById(`rule-src-${id}-${n}`)?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  };
  const renderAnswer = (text: string) =>
    splitCitations(text, q.sources.length).map((p, i) =>
      "cite" in p ? (
        <button
          key={i}
          type="button"
          onClick={() => focus(p.cite)}
          className="mx-0.5 rounded bg-accent-soft px-1 align-super text-[10px] font-semibold text-accent hover:bg-accent hover:text-white"
          aria-label={`근거 ${p.cite}로 이동`}
        >
          {p.cite}
        </button>
      ) : (
        <span key={i}>{p.text}</span>
      ),
    );

  return (
    <div className="space-y-4">
      <Card>
        <div className="space-y-1 px-4 py-3">
          <div className="flex flex-wrap items-center gap-2 text-xs text-ink-soft">
            <Badge tone={q.status === "ANSWERED" ? "accent" : "neutral"}>{q.status === "ANSWERED" ? "근거 찾음" : "관련 규정 없음"}</Badge>
            {q.retrievalMode === "KEYWORD" && <Badge tone="warn">키워드 검색(임베딩 대체)</Badge>}
            <span>{formatDateTime(q.createdAt)}</span>
          </div>
          <p className="text-base font-medium">{q.question}</p>
        </div>
      </Card>

      {q.status === "NO_MATCH" ? (
        <Card>
          <Empty>
            {q.retrievalMeta.reason ?? "등록된 규정에서 관련 내용을 찾지 못했습니다."}
            {q.retrievalMeta.bestScore != null && (
              <span className="mt-1 block text-xs">
                가장 가까운 발췌의 유사도 {q.retrievalMeta.bestScore.toFixed(3)} (기준 {q.retrievalMeta.minScore})
              </span>
            )}
          </Empty>
        </Card>
      ) : (
        <Card title="AI 답변">
          <ExplanationPanel
            explanation={q.answer}
            canWrite
            regeneratePath={`${base}/rules/questions/${id}/answer/regenerate`}
            editPath={`${base}/rules/questions/${id}/answer`}
            memberName={() => "본인"}
            onChanged={reload}
            emptyText="답변을 준비하고 있습니다."
            renderText={renderAnswer}
          />
        </Card>
      )}

      {q.sources.length > 0 && (
        <Card title={`근거 규정 발췌 · ${q.sources.length}개`}>
          {q.retrievalMode === "KEYWORD" && (
            <p className="border-b border-line px-4 py-2 text-xs text-warn">
              임베딩 서비스에 연결하지 못해 키워드({q.retrievalMeta.terms?.join(", ")})로 찾았습니다. 관련도가 낮을 수 있습니다.
            </p>
          )}
          <ol>
            {q.sources.map((s, i) => (
              <li
                key={s.chunkId}
                id={`rule-src-${id}-${i + 1}`}
                className={`border-b border-line px-4 py-3 text-sm last:border-b-0 ${highlight === i + 1 ? "bg-accent-soft/50" : ""}`}
              >
                <p className="mb-1 flex items-start justify-between gap-2 text-xs">
                  <span className="font-medium text-ink">
                    [{i + 1}] {sourceLabel(s.title, s.section, s.page)}
                  </span>
                  <span className="num shrink-0 text-ink-soft" title={q.retrievalMode === "VECTOR" ? "코사인 유사도" : "검색어 포함 비율"}>
                    {s.score.toFixed(3)}
                  </span>
                </p>
                <p className="whitespace-pre-line leading-relaxed text-ink-soft">{s.content}</p>
              </li>
            ))}
          </ol>
          <p className="border-t border-line px-4 py-2 text-[11px] text-ink-soft">
            발췌는 질문한 시점의 규정입니다. AI 답변은 이 발췌만 근거로 하며, 최종 판단은 담당자가 원문을 확인해 합니다.
          </p>
        </Card>
      )}
    </div>
  );
}

function RulesView() {
  const router = useRouter();
  const params = useSearchParams();
  const { base, company, canWrite } = useCompany();
  const list = useApi<PageResponse<RuleQuestionSummary>>(`${base}/rules/questions`);
  const requested = params.get("q");
  const selected = requested && /^\d+$/.test(requested) ? Number(requested) : (list.data?.content[0]?.id ?? null);
  const select = (id: number) => router.replace(`/c/${company.id}/rules?q=${id}`);

  return (
    <>
      <PageHeader
        title="회계 규정"
        description="등록한 회사 규정을 의미 검색(pgvector)으로 찾아, 로컬 LLM이 발췌만 근거로 답합니다. 답변의 번호를 누르면 근거 원문으로 이동합니다."
      />
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <AskForm
              base={base}
              onAsked={(id) => {
                list.reload();
                select(id);
              }}
            />
          </Card>
          {selected ? (
            <QuestionDetail key={selected} base={base} id={selected} />
          ) : list.data ? (
            <Card>
              <Empty>아직 질문이 없습니다. 규정 문서를 등록한 뒤 예시 질문으로 시작해 보세요.</Empty>
            </Card>
          ) : null}
        </div>

        <div className="space-y-4 self-start">
          <DocumentsPanel base={base} canWrite={canWrite} />
          <Card title="내 질문">
            {!list.data ? (
              <Loading />
            ) : list.data.content.length === 0 ? (
              <Empty>질문 기록이 없습니다.</Empty>
            ) : (
              <ul>
                {list.data.content.map((q) => (
                  <li key={q.id}>
                    <button
                      type="button"
                      onClick={() => select(q.id)}
                      className={`block w-full border-b border-line px-4 py-2 text-left text-sm last:border-b-0 hover:bg-paper ${q.id === selected ? "bg-paper" : ""}`}
                    >
                      <span className="line-clamp-2">{q.question}</span>
                      <span className="mt-0.5 flex items-center gap-1.5 text-xs text-ink-soft">
                        <Badge tone={q.status === "ANSWERED" ? "accent" : "neutral"}>{q.status === "ANSWERED" ? `근거 ${q.sourceCount}` : "없음"}</Badge>
                        <span className="ml-auto">{formatDateTime(q.createdAt).slice(6)}</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </>
  );
}

export default function RulesPage() {
  return (
    <Suspense>
      <RulesView />
    </Suspense>
  );
}
