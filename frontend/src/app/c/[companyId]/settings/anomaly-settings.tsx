"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { formatMoney, normalizeMoneyInput } from "@/lib/money";
import type { AnomalySettings } from "@/lib/types";
import { Button, Card, ErrorBanner, Field, Input, Loading, Select, formatDateTime } from "@/components/ui";

const HOURS = Array.from({ length: 24 }, (_, h) => h);

/** 이상거래 탐지 기준. 조회는 모든 멤버, 변경은 관리자. 변경은 다음 분석부터 적용되고 Audit Log에 남는다. */
export function AnomalySettingsCard({ base, isAdmin }: { base: string; isAdmin: boolean }) {
  const { data, error, reload } = useApi<AnomalySettings>(`${base}/anomaly-settings`);
  const [saveError, setSaveError] = useState<ApiError | string>();
  const [saved, setSaved] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setSaveError(undefined);
    setSaved(false);
    const money = (name: string, label: string) => {
      const v = normalizeMoneyInput(String(f.get(name) ?? ""));
      if (v === null) throw `${label}을(를) 금액 형식으로 입력하세요.`;
      return v;
    };
    try {
      const body = {
        approvalLimit: money("approvalLimit", "승인 기준 금액"),
        nearLimitRatio: (Number(f.get("nearLimitPct")) / 100).toFixed(2),
        evidenceMinAmount: money("evidenceMinAmount", "증빙 필요 금액"),
        newVendorMinAmount: money("newVendorMinAmount", "신규 거래처 고액 기준"),
        spikeMultiplier: String(f.get("spikeMultiplier")),
        nightStartHour: Number(f.get("nightStartHour")),
        nightEndHour: Number(f.get("nightEndHour")),
        mlEnabled: f.get("mlEnabled") === "on",
      };
      await api(`${base}/anomaly-settings`, { method: "PUT", body });
      setSaved(true);
      reload();
    } catch (err) {
      setSaveError(typeof err === "string" ? err : (err as ApiError));
    }
  }

  return (
    <Card
      title="이상거래 탐지 기준"
      actions={data && !data.saved && <span className="text-xs text-ink-soft">기본값</span>}
      className="lg:col-span-2"
    >
      {error ? (
        <div className="p-3">
          <ErrorBanner error={error} />
        </div>
      ) : !data ? (
        <Loading />
      ) : (
        <form key={data.updatedAt ?? "default"} onSubmit={onSubmit} className="space-y-3 p-4">
          <ErrorBanner error={saveError} />
          <fieldset disabled={!isAdmin} className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="승인 기준 금액(원)" hint="쪼개기 결제·기준 직전 금액 규칙의 기준">
              <Input name="approvalLimit" inputMode="decimal" required defaultValue={formatMoney(data.approvalLimit)} />
            </Field>
            <Field label="기준 직전 비율(%)" hint="이 비율 이상 ~ 기준 미만을 '직전'으로 봄">
              <Input name="nearLimitPct" type="number" min={50} max={99} required defaultValue={Math.round(Number(data.nearLimitRatio) * 100)} />
            </Field>
            <Field label="증빙 필요 금액(원)" hint="이 금액 초과 출금에 증빙이 없으면 탐지">
              <Input name="evidenceMinAmount" inputMode="decimal" required defaultValue={formatMoney(data.evidenceMinAmount)} />
            </Field>
            <Field label="신규 거래처 고액 기준(원)">
              <Input name="newVendorMinAmount" inputMode="decimal" required defaultValue={formatMoney(data.newVendorMinAmount)} />
            </Field>
            <Field label="거래처 평균 대비 배수" hint="최근 180일 평균의 몇 배 이상을 고액으로 볼지">
              <Input name="spikeMultiplier" type="number" min={1.5} max={100} step={0.5} required defaultValue={Number(data.spikeMultiplier)} />
            </Field>
            <Field label="심야 시작(시)">
              <Select name="nightStartHour" defaultValue={data.nightStartHour}>
                {HOURS.map((h) => (
                  <option key={h} value={h}>
                    {h}시
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="심야 종료(시)">
              <Select name="nightEndHour" defaultValue={data.nightEndHour}>
                {HOURS.map((h) => (
                  <option key={h} value={h}>
                    {h}시
                  </option>
                ))}
              </Select>
            </Field>
            <label className="flex items-center gap-2 self-center text-sm">
              <input type="checkbox" name="mlEnabled" defaultChecked={data.mlEnabled} className="h-4 w-4 accent-[var(--accent)]" />
              ML(IsolationForest) 점수 사용
            </label>
          </fieldset>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-xs text-ink-soft">
              변경은 다음 분석부터 적용되며, 각 분석에는 그때의 기준이 함께 저장됩니다.
              {data.saved && data.updatedAt && ` 마지막 변경 ${formatDateTime(data.updatedAt)}`}
            </p>
            {isAdmin && (
              <div className="flex items-center gap-3">
                {saved && <span className="text-xs text-accent">저장되었습니다.</span>}
                <Button type="submit" variant="primary">
                  저장
                </Button>
              </div>
            )}
          </div>
        </form>
      )}
    </Card>
  );
}
