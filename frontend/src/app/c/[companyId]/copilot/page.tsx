"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { COPILOT_STATUS_LABEL, EXAMPLE_QUESTIONS, PLAN_SOURCE_LABEL, describePlan } from "@/lib/copilot";
import type { CopilotQuestion, CopilotQuestionSummary, CopilotStatus, PageResponse } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Badge, Button, Card, Empty, ErrorBanner, Loading, PageHeader, formatDateTime } from "@/components/ui";
import { ExplanationPanel, usePollWhilePending } from "../anomalies/explanation";
import { PlanEditor } from "./plan-editor";
import { ResultView } from "./result-view";

const STATUS_TONE: Record<CopilotStatus, "accent" | "warn" | "danger" | "neutral"> = {
  PLANNING: "warn",
  ANSWERED: "accent",
  UNSUPPORTED: "neutral",
  FAILED: "danger",
};

function AskForm({ base, onAsked }: { base: string; onAsked: (id: number) => void }) {
  const [question, setQuestion] = useState("");
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setPending(true);
    setError(undefined);
    try {
      const q = await api<CopilotQuestion>(`${base}/copilot/questions`, { method: "POST", body: { question: question.trim() } });
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
          placeholder="회계 데이터에 대해 물어보세요 — 예: 지난달 광고비가 얼마나 늘었어?"
          aria-label="질문"
          className="min-w-0 flex-1 rounded-md border border-line-strong bg-surface px-3 py-2 text-sm outline-none focus:border-accent focus:ring-2 focus:ring-accent/15"
        />
        <Button type="submit" variant="primary" disabled={pending || question.trim() === ""}>
          질문
        </Button>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {EXAMPLE_QUESTIONS.map((q) => (
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

function QuestionDetail({ base, companyId, id, onChanged }: { base: string; companyId: number; id: number; onChanged: () => void }) {
  const { data: q, error, reload } = useApi<CopilotQuestion>(`${base}/copilot/questions/${id}`);
  const [editing, setEditing] = useState(false);
  const [retryError, setRetryError] = useState<ApiError>();
  const shown = q?.id === id ? q : undefined;
  const planning = shown?.status === "PLANNING";

  // 해석 중이면 결과가 올 때까지 주기적으로 새로고침하고, 끝나면 목록도 갱신한다
  useEffect(() => {
    if (!planning) return;
    const timer = setInterval(reload, 3000);
    return () => {
      clearInterval(timer);
      onChanged();
    };
  }, [planning, reload, onChanged]);
  usePollWhilePending(shown?.answer?.status, reload);

  async function retry() {
    setRetryError(undefined);
    try {
      await api(`${base}/copilot/questions/${id}/retry`, { method: "POST" });
      reload();
      onChanged();
    } catch (err) {
      setRetryError(err as ApiError);
    }
  }

  if (error) return <ErrorBanner error={error} />;
  if (!shown) return <Loading />;
  const meta = shown.planMeta ?? {};

  return (
    <div className="space-y-4">
      <Card>
        <div className="space-y-2 px-4 py-3">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={STATUS_TONE[shown.status]}>{COPILOT_STATUS_LABEL[shown.status]}</Badge>
            <span className="text-xs text-ink-soft">{formatDateTime(shown.createdAt)}</span>
          </div>
          <p className="text-base font-medium">{shown.question}</p>
        </div>

        {planning ? (
          <p className="flex items-center gap-2 border-t border-line px-4 py-3 text-sm text-ink-soft">
            <span className="h-2 w-2 animate-pulse rounded-full bg-warn" />
            로컬 LLM이 질문을 조회 계획으로 바꾸는 중입니다… (시도 {shown.attempt})
          </p>
        ) : (
          <div className="border-t border-line">
            <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-2">
              <div className="flex flex-wrap items-center gap-1.5 text-sm">
                <span className="text-xs text-ink-soft">해석</span>
                {shown.plan ? (
                  <>
                    {describePlan(shown.plan).map((p) => (
                      <Badge key={p}>{p}</Badge>
                    ))}
                    {shown.result?.period && <Badge>{shown.result.period}</Badge>}
                    {shown.result?.comparePeriod && <span className="text-xs text-ink-soft">↔ {shown.result.comparePeriod}</span>}
                  </>
                ) : (
                  <span className="text-ink-soft">없음</span>
                )}
                {shown.planSource && <span className="text-xs text-ink-soft">· {PLAN_SOURCE_LABEL[shown.planSource]}</span>}
              </div>
              {!editing && (
                <Button variant="ghost" onClick={() => setEditing(true)}>
                  해석 수정
                </Button>
              )}
            </div>
            {shown.planSource === "RULE" && (meta.llmError || meta.llmIssues) && (
              <p className="px-4 pb-2 text-xs text-ink-soft">
                {meta.llmError ? "로컬 LLM에 연결하지 못해" : `LLM 해석이 형식 검증을 통과하지 못해(${meta.llmIssues?.join(" / ")})`} 키워드 규칙으로
                해석했습니다. 필요하면 해석을 고치세요.
              </p>
            )}
            {editing && (
              <div className="border-t border-line bg-paper/40">
                <PlanEditor
                  base={base}
                  questionId={id}
                  initial={shown.plan}
                  onCancel={() => setEditing(false)}
                  onSaved={() => {
                    setEditing(false);
                    reload();
                    onChanged();
                  }}
                />
              </div>
            )}
          </div>
        )}

        {shown.status === "FAILED" && (
          <div className="space-y-2 border-t border-line px-4 py-3">
            <ErrorBanner error={shown.error ?? "질문을 처리하지 못했습니다."} />
            <ErrorBanner error={retryError} />
            <div className="flex gap-2">
              <Button onClick={retry}>다시 시도</Button>
              {!editing && (
                <Button variant="ghost" onClick={() => setEditing(true)}>
                  해석을 직접 입력
                </Button>
              )}
            </div>
          </div>
        )}
      </Card>

      {shown.status === "ANSWERED" && (
        <Card title="AI 답변">
          <ExplanationPanel
            explanation={shown.answer}
            canWrite
            regeneratePath={`${base}/copilot/questions/${id}/answer/regenerate`}
            editPath={`${base}/copilot/questions/${id}/answer`}
            memberName={() => "본인"}
            onChanged={reload}
            emptyText="답변을 준비하고 있습니다."
          />
        </Card>
      )}

      {shown.result && (shown.status === "ANSWERED" || shown.status === "UNSUPPORTED") && (
        <Card title={shown.status === "UNSUPPORTED" ? "지원하지 않는 질문" : `조회 결과 · ${shown.result.title}`}>
          <div className="pb-3">
            <ResultView result={shown.result} companyId={companyId} />
          </div>
          {shown.status === "ANSWERED" && (
            <p className="border-t border-line px-4 py-2 text-[11px] text-ink-soft">
              금액·증감·순위는 전표와 규칙으로 계산한 값입니다 (취소 전표 제외). AI 답변은 이 결과를 문장으로 옮긴 것입니다.
            </p>
          )}
        </Card>
      )}
    </div>
  );
}

function CopilotView() {
  const router = useRouter();
  const params = useSearchParams();
  const { base, company } = useCompany();
  const [page, setPage] = useState(0);
  const list = useApi<PageResponse<CopilotQuestionSummary>>(`${base}/copilot/questions`, { page });
  const requested = params.get("q");
  const selected = requested && /^\d+$/.test(requested) ? Number(requested) : (list.data?.content[0]?.id ?? null);
  const select = (id: number) => router.replace(`/c/${company.id}/copilot?q=${id}`);

  return (
    <>
      <PageHeader
        title="AI Copilot"
        description="질문을 로컬 LLM이 미리 정의된 조회 도구 하나로 해석하고, 조회·계산은 서버가 합니다. AI는 SQL을 만들지 않으며 데이터를 바꾸지 않습니다."
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
            <QuestionDetail key={selected} base={base} companyId={company.id} id={selected} onChanged={list.reload} />
          ) : list.data ? (
            <Card>
              <Empty>아직 질문이 없습니다. 위의 예시 질문을 눌러 시작해 보세요.</Empty>
            </Card>
          ) : null}
        </div>

        <Card title="내 질문" className="self-start">
          <ErrorBanner error={list.error} />
          {!list.data ? (
            <Loading />
          ) : list.data.content.length === 0 ? (
            <Empty>질문 기록이 없습니다.</Empty>
          ) : (
            <>
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
                        <Badge tone={STATUS_TONE[q.status]}>{COPILOT_STATUS_LABEL[q.status]}</Badge>
                        {q.period && <span>{q.period}</span>}
                        <span className="ml-auto">{formatDateTime(q.createdAt).slice(6)}</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
              {list.data.totalElements > list.data.size && (
                <div className="flex justify-between px-4 py-2 text-xs">
                  <Button variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>
                    이전
                  </Button>
                  <Button variant="ghost" disabled={(page + 1) * list.data.size >= list.data.totalElements} onClick={() => setPage(page + 1)}>
                    다음
                  </Button>
                </div>
              )}
            </>
          )}
        </Card>
      </div>
    </>
  );
}

export default function CopilotPage() {
  return (
    <Suspense>
      <CopilotView />
    </Suspense>
  );
}
