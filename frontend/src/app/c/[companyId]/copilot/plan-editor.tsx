"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import {
  ANOMALY_STATUS_FILTER_LABEL,
  COMPARE_LABEL,
  MIN_LEVEL_LABEL,
  ORDER_LABEL,
  PERIOD_LABEL,
  TOOL_LABEL,
  cleanPlan,
  planProblems,
  toolFields,
} from "@/lib/copilot";
import type { Account, CopilotPlan, CopilotTool, PeriodType, RiskLevel } from "@/lib/types";
import { Button, ErrorBanner, Field, Input, Select } from "@/components/ui";

const TOOLS = Object.keys(TOOL_LABEL) as CopilotTool[];
const PERIODS = Object.keys(PERIOD_LABEL) as PeriodType[];

function num(v: string): number | undefined {
  return v.trim() === "" ? undefined : Number(v);
}

/**
 * 해석(조회 계획) 수정. 같은 질문을 고친 해석으로 다시 조회하고 답변을 새로 만든다 (Audit Log 기록).
 * 고를 수 있는 값은 백엔드 Query Tool이 허용하는 것뿐이다.
 */
export function PlanEditor({
  base,
  questionId,
  initial,
  onSaved,
  onCancel,
}: {
  base: string;
  questionId: number;
  initial: CopilotPlan | null;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [plan, setPlan] = useState<CopilotPlan>(initial ?? { tool: "PROFIT_SUMMARY", period: { type: "LAST_MONTH" } });
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);
  const accounts = useApi<Account[]>(`${base}/accounts`);
  const f = toolFields(plan.tool);
  const period = plan.period ?? { type: "LAST_MONTH" as PeriodType };
  const problems = planProblems(plan);
  const accountOptions = (accounts.data ?? []).filter((a) =>
    f.account === "optional" ? a.type === "EXPENSE" : a.type === "REVENUE" || a.type === "EXPENSE",
  );
  const set = (patch: Partial<CopilotPlan>) => setPlan((p) => ({ ...p, ...patch }));
  const setPeriod = (patch: Partial<NonNullable<CopilotPlan["period"]>>) => set({ period: { ...period, ...patch } });

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setPending(true);
    setError(undefined);
    try {
      await api(`${base}/copilot/questions/${questionId}/plan`, { method: "PUT", body: { plan: cleanPlan(plan) } });
      onSaved();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3 px-4 py-3">
      <ErrorBanner error={error} />
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="조회">
          <Select value={plan.tool} onChange={(e) => set({ tool: e.target.value as CopilotTool })}>
            {TOOLS.map((t) => (
              <option key={t} value={t}>
                {TOOL_LABEL[t]}
              </option>
            ))}
          </Select>
        </Field>
        {f.period && (
          <Field label="기간">
            <Select value={period.type} onChange={(e) => setPeriod({ type: e.target.value as PeriodType })}>
              {PERIODS.map((p) => (
                <option key={p} value={p}>
                  {PERIOD_LABEL[p]}
                </option>
              ))}
            </Select>
          </Field>
        )}
        {f.period && (period.type === "MONTH" || period.type === "QUARTER" || period.type === "YEAR") && (
          <div className="grid grid-cols-2 gap-2 sm:col-span-2">
            <Field label="연도" hint={period.type === "YEAR" ? undefined : "비우면 올해"}>
              <Input type="number" min={2000} max={2100} value={period.year ?? ""} onChange={(e) => setPeriod({ year: num(e.target.value) })} />
            </Field>
            {period.type === "MONTH" && (
              <Field label="월">
                <Input type="number" min={1} max={12} value={period.month ?? ""} onChange={(e) => setPeriod({ month: num(e.target.value) })} />
              </Field>
            )}
            {period.type === "QUARTER" && (
              <Field label="분기">
                <Input type="number" min={1} max={4} value={period.quarter ?? ""} onChange={(e) => setPeriod({ quarter: num(e.target.value) })} />
              </Field>
            )}
          </div>
        )}
        {f.compare && (
          <Field label="비교">
            <Select value={plan.compare ?? "PREV_PERIOD"} onChange={(e) => set({ compare: e.target.value as CopilotPlan["compare"] })}>
              {Object.entries(COMPARE_LABEL).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </Select>
          </Field>
        )}
        {f.account && (
          <Field label={f.account === "required" ? "계정과목" : "계정과목 (선택)"}>
            <Select value={plan.account ?? ""} onChange={(e) => set({ account: e.target.value })}>
              <option value="">{f.account === "required" ? "고르세요" : "전체 비용"}</option>
              {/* AI가 별칭(광고비 등)으로 해석했으면 그 값도 남겨 둔다 */}
              {plan.account && !accountOptions.some((a) => a.name === plan.account) && <option value={plan.account}>{plan.account}</option>}
              {accountOptions.map((a) => (
                <option key={a.id} value={a.name}>
                  {a.code} {a.name}
                </option>
              ))}
            </Select>
          </Field>
        )}
        {f.order && (
          <Field label="정렬">
            <Select value={plan.order ?? "AMOUNT"} onChange={(e) => set({ order: e.target.value as CopilotPlan["order"] })}>
              {Object.entries(ORDER_LABEL).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </Select>
          </Field>
        )}
        {f.anomaly && (
          <>
            <Field label="위험 수준">
              <Select value={plan.minLevel ?? "LOW"} onChange={(e) => set({ minLevel: e.target.value as RiskLevel })}>
                {Object.entries(MIN_LEVEL_LABEL).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="상태">
              <Select value={plan.status ?? "OPEN"} onChange={(e) => set({ status: e.target.value as CopilotPlan["status"] })}>
                {Object.entries(ANOMALY_STATUS_FILTER_LABEL).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </Select>
            </Field>
          </>
        )}
        {f.limit && (
          <Field label="건수" hint="1~20, 비우면 기본값">
            <Input type="number" min={1} max={20} value={plan.limit ?? ""} onChange={(e) => set({ limit: num(e.target.value) })} />
          </Field>
        )}
        {f.reason && (
          <Field label="사유">
            <Input value={plan.reason ?? ""} maxLength={200} onChange={(e) => set({ reason: e.target.value })} />
          </Field>
        )}
      </div>
      {problems.length > 0 && <p className="text-xs text-danger">{problems.join(" ")}</p>}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onCancel}>
          취소
        </Button>
        <Button type="submit" variant="primary" disabled={pending || problems.length > 0}>
          이 해석으로 다시 조회
        </Button>
      </div>
    </form>
  );
}
