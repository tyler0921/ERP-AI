"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { formatMoney, normalizeMoneyInput } from "@/lib/money";
import type { DocumentDetail, DocumentFields, ValidationIssue } from "@/lib/types";
import { Button, ErrorBanner, Input, Select } from "@/components/ui";
import { Confidence } from "../status";

type Key = keyof DocumentFields;

const FIELDS: { key: Key; label: string; kind: "text" | "money" | "date" | "biz" | "payment"; invoiceOnly?: boolean }[] = [
  { key: "vendorName", label: "거래처(공급자)", kind: "text" },
  { key: "vendorBusinessNo", label: "사업자등록번호", kind: "biz" },
  { key: "buyerName", label: "공급받는자", kind: "text", invoiceOnly: true },
  { key: "buyerBusinessNo", label: "공급받는자 사업자번호", kind: "biz", invoiceOnly: true },
  { key: "issueDate", label: "거래일(작성일자)", kind: "date" },
  { key: "supplyAmount", label: "공급가액", kind: "money" },
  { key: "vatAmount", label: "부가세", kind: "money" },
  { key: "totalAmount", label: "합계금액", kind: "money" },
  { key: "paymentMethod", label: "결제수단", kind: "payment" },
  { key: "itemsSummary", label: "품목", kind: "text" },
];

function display(kind: string, value: string | null): string {
  if (value === null || value === undefined) return "";
  return kind === "money" ? formatMoney(value) : value;
}

/**
 * 추출 필드 편집. 각 필드 아래에 AI 원래 값·신뢰도·근거를 보여주고, 검증 이슈가 있는 필드는 색으로 표시한다.
 */
export function FieldsForm({ base, doc, editable, onSaved }: { base: string; doc: DocumentDetail; editable: boolean; onSaved: () => void }) {
  const invoice = doc.docType === "TAX_INVOICE";
  const fields = FIELDS.filter((f) => !f.invoiceOnly || invoice);
  const [values, setValues] = useState<Record<Key, string>>(
    () => Object.fromEntries(FIELDS.map((f) => [f.key, display(f.kind, doc[f.key])])) as Record<Key, string>,
  );
  const [error, setError] = useState<ApiError | string>();
  const [pending, setPending] = useState(false);

  const issueFor = (key: Key): ValidationIssue | undefined =>
    doc.validation.find((i) => i.field === key && i.severity === "ERROR") ?? doc.validation.find((i) => i.field === key);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const body: Record<string, string | null> = {};
    for (const f of FIELDS) {
      const raw = values[f.key].trim();
      if (!raw) {
        body[f.key] = null;
      } else if (f.kind === "money") {
        const n = normalizeMoneyInput(raw);
        if (n === null) {
          setError(`${f.label}: 금액 형식이 올바르지 않습니다.`);
          return;
        }
        body[f.key] = n;
      } else {
        body[f.key] = raw;
      }
    }
    setPending(true);
    setError(undefined);
    try {
      await api(`${base}/documents/${doc.id}/fields`, { method: "PUT", body });
      onSaved();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <div className="grid gap-x-4 gap-y-3 sm:grid-cols-2">
        {fields.map((f) => {
          const ai = doc.aiFields?.[f.key];
          const issue = issueFor(f.key);
          const ring = issue ? (issue.severity === "ERROR" ? "border-danger ring-2 ring-danger/15" : "border-warn ring-2 ring-warn/15") : "";
          const aiDiffers = ai && ai.value !== null && display(f.kind, ai.value) !== values[f.key];
          return (
            <div key={f.key} className={f.key === "itemsSummary" || f.key === "vendorName" ? "sm:col-span-2" : ""}>
              <div className="mb-1 flex items-center justify-between">
                <label htmlFor={`f-${f.key}`} className="text-xs font-medium text-ink-soft">
                  {f.label}
                </label>
                {ai && ai.value !== null && <Confidence value={ai.confidence} />}
              </div>
              {f.kind === "payment" ? (
                <Select id={`f-${f.key}`} value={values[f.key]} disabled={!editable} onChange={(e) => setValues({ ...values, [f.key]: e.target.value })} className={ring}>
                  <option value="">-</option>
                  <option value="CARD">카드</option>
                  <option value="CASH">현금</option>
                  <option value="TRANSFER">계좌이체</option>
                </Select>
              ) : (
                <Input
                  id={`f-${f.key}`}
                  type={f.kind === "date" ? "date" : "text"}
                  inputMode={f.kind === "money" ? "decimal" : undefined}
                  value={values[f.key]}
                  disabled={!editable}
                  onChange={(e) => setValues({ ...values, [f.key]: e.target.value })}
                  placeholder={f.kind === "biz" ? "000-00-00000" : undefined}
                  className={`${f.kind === "money" ? "num" : ""} ${ring}`}
                />
              )}
              {issue && <p className={`mt-1 text-[11px] ${issue.severity === "ERROR" ? "text-danger" : "text-warn"}`}>{issue.message}</p>}
              {ai && (ai.value === null || aiDiffers) && (
                <p className="mt-1 truncate text-[11px] text-ink-soft" title={ai.source}>
                  AI: {ai.value === null ? "찾지 못함" : display(f.kind, ai.value)} · {ai.source}
                </p>
              )}
            </div>
          );
        })}
      </div>
      <ErrorBanner error={error} />
      {editable && (
        <div className="flex justify-end">
          <Button type="submit" disabled={pending}>
            {pending ? "저장 중…" : "수정 저장 및 재검증"}
          </Button>
        </div>
      )}
    </form>
  );
}
