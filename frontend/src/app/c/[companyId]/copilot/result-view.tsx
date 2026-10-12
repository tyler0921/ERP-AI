"use client";

import Link from "next/link";
import type { CopilotResult, CopilotValueType } from "@/lib/types";
import { Money, td, th } from "@/components/ui";

function Value({ value, type }: { value: string | number | null | undefined; type: CopilotValueType }) {
  if (value === null || value === undefined || value === "") return <span className="text-ink-soft">-</span>;
  if (type === "money") return <Money value={String(value)} />;
  if (type === "pct") {
    const s = String(value);
    return <span className="num">{`${s.startsWith("-") ? "" : "+"}${s}%`}</span>;
  }
  if (type === "number") return <span className="num">{String(value)}</span>;
  return <>{String(value)}</>;
}

/**
 * Query Tool 결과: 핵심 수치, 계산 요약 문장, 표. 모두 백엔드가 계산한 값이며, AI 답변은 이것을 옮긴 것이다.
 * 이상거래 행은 상세 화면으로 연결한다.
 */
export function ResultView({ result, companyId }: { result: CopilotResult; companyId: number }) {
  return (
    <div className="space-y-4">
      {result.highlights.length > 0 && (
        <dl className="grid grid-cols-2 gap-3 px-4 pt-3 md:grid-cols-4">
          {result.highlights.map((h) => (
            <div key={h.label} className="rounded-md border border-line px-3 py-2">
              <dt className="text-xs text-ink-soft">{h.label}</dt>
              <dd className="mt-0.5 text-lg font-medium">
                <Value value={h.value} type={h.type} />
                {h.type === "money" && h.value !== null && <span className="ml-0.5 text-xs text-ink-soft">원</span>}
              </dd>
            </div>
          ))}
        </dl>
      )}

      <ul className="space-y-1 px-4 text-sm">
        {result.summary.map((s) => (
          <li key={s} className="flex gap-2">
            <span aria-hidden className="text-ink-soft">
              ·
            </span>
            {s}
          </li>
        ))}
      </ul>

      {result.tables.map((t) => (
        <div key={t.title}>
          <p className="border-t border-line px-4 pb-1 pt-3 text-xs font-medium text-ink-soft">
            {t.title} · {t.rows.length}행
          </p>
          {t.rows.length === 0 ? (
            <p className="px-4 pb-3 text-sm text-ink-soft">해당하는 데이터가 없습니다.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr>
                    {t.columns.map((c) => (
                      <th key={c.key} className={`${th} ${c.type === "text" ? "" : "text-right"}`}>
                        {c.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {t.rows.map((row, i) => (
                    <tr key={i} className={row.anomalyId ? "hover:bg-paper" : undefined}>
                      {t.columns.map((c, ci) => (
                        <td key={c.key} className={`${td} ${c.type === "text" ? "" : "text-right"} ${ci === 0 ? "" : "whitespace-nowrap"}`}>
                          {ci === 0 && row.anomalyId ? (
                            <Link href={`/c/${companyId}/anomalies/${row.anomalyId}`} className="text-accent hover:underline">
                              <Value value={row[c.key]} type={c.type} />
                            </Link>
                          ) : (
                            <Value value={row[c.key]} type={c.type} />
                          )}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
