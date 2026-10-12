"use client";

import { useState } from "react";
import { formatManWon, monthLabel, niceTicks } from "@/lib/closing";
import type { ClosingTrendPoint } from "@/lib/types";
import { Badge, Money, td, th } from "@/components/ui";

// 매출·비용 두 계열. 색은 dataviz 기준 팔레트 1·2번(파랑·주황), 흰 배경에서 CVD·대비 검증 통과.
const SERIES = [
  { key: "revenue", label: "매출", color: "#2a78d6" },
  { key: "expense", label: "비용", color: "#eb6834" },
] as const;

const W = 640;
const H = 220;
const PAD = { top: 12, right: 40, bottom: 28, left: 52 };

/**
 * 월별 매출·비용 추이 (선 2개, 축 하나). 영업이익은 툴팁과 표에서 보여준다.
 * 좌표 계산에만 Number를 쓰고, 금액 표시는 문자열 그대로 <Money>로 한다.
 */
export function TrendChart({ points, selected, onSelect }: { points: ClosingTrendPoint[]; selected: string; onSelect: (month: string) => void }) {
  const [hover, setHover] = useState<number | null>(null);
  if (points.length === 0) return null;

  const values = points.flatMap((p) => [Number(p.revenue), Number(p.expense)]);
  const ticks = niceTicks(Math.max(0, ...values));
  const top = ticks[ticks.length - 1] || 1;
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const band = innerW / points.length;
  const x = (i: number) => PAD.left + band * (i + 0.5);
  const y = (v: number) => PAD.top + innerH - (Math.max(0, v) / top) * innerH;
  const path = (key: "revenue" | "expense") => points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(Number(p[key])).toFixed(1)}`).join(" ");

  const last = points.length - 1;
  const endY = SERIES.map((s) => y(Number(points[last][s.key])));
  // 끝 라벨은 두 선이 충분히 떨어져 있을 때만 단다 (겹치면 범례·툴팁으로 충분)
  const showEndLabels = Math.abs(endY[0] - endY[1]) >= 14;
  const active = hover ?? points.findIndex((p) => p.month === selected);
  const tip = active >= 0 ? points[active] : null;

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center gap-4 px-4 pt-3 text-xs text-ink-soft">
        {SERIES.map((s) => (
          <span key={s.key} className="inline-flex items-center gap-1.5">
            <span aria-hidden className="inline-block h-0.5 w-4 rounded" style={{ background: s.color }} />
            {s.label}
          </span>
        ))}
        <span className="ml-auto">단위: 원 · 월을 누르면 그 달로 이동</span>
      </div>

      <div className="relative px-2">
        <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label="월별 매출·비용 추이" onMouseLeave={() => setHover(null)}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} stroke="var(--line)" strokeWidth={1} />
              <text x={PAD.left - 6} y={y(t)} dy="0.32em" textAnchor="end" fontSize={10} fill="var(--ink-soft)">
                {formatManWon(t)}
              </text>
            </g>
          ))}
          {points.map((p, i) => (
            <text key={p.month} x={x(i)} y={H - 8} textAnchor="middle" fontSize={10} fill={p.month === selected ? "var(--ink)" : "var(--ink-soft)"} fontWeight={p.month === selected ? 600 : 400}>
              {i === 0 || p.month.endsWith("-01") ? p.month.replace("-", ".") : String(Number(p.month.slice(5)))}
            </text>
          ))}
          {active >= 0 && <line x1={x(active)} x2={x(active)} y1={PAD.top} y2={PAD.top + innerH} stroke="var(--line-strong)" strokeWidth={1} />}
          {SERIES.map((s) => (
            <path key={s.key} d={path(s.key)} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
          ))}
          {active >= 0 &&
            SERIES.map((s) => (
              <circle key={s.key} cx={x(active)} cy={y(Number(points[active][s.key]))} r={4} fill={s.color} stroke="var(--surface)" strokeWidth={2} />
            ))}
          {showEndLabels &&
            SERIES.map((s, i) => (
              <text key={s.key} x={x(last) + 8} y={endY[i]} dy="0.32em" fontSize={10} fill="var(--ink-soft)">
                {s.label}
              </text>
            ))}
          {points.map((p, i) => (
            <rect
              key={p.month}
              x={PAD.left + band * i}
              y={PAD.top}
              width={band}
              height={innerH + PAD.bottom}
              fill="transparent"
              className="cursor-pointer"
              onMouseEnter={() => setHover(i)}
              onClick={() => onSelect(p.month)}
            >
              <title>{monthLabel(p.month)}</title>
            </rect>
          ))}
        </svg>

        {tip && (
          <div
            className="pointer-events-none absolute top-1 whitespace-nowrap rounded-md border border-line bg-surface px-3 py-2 text-xs shadow-sm"
            style={{ left: `${(x(active) / W) * 100}%`, transform: `translateX(${active > points.length / 2 ? "-105%" : "5%"})` }}
          >
            <p className="mb-1 flex items-center gap-1.5 font-medium">
              {monthLabel(tip.month)}
              {tip.status === "CLOSED" && <Badge tone="accent">마감</Badge>}
            </p>
            {SERIES.map((s) => (
              <p key={s.key} className="flex items-center justify-between gap-4">
                <span className="inline-flex items-center gap-1.5 text-ink-soft">
                  <span aria-hidden className="inline-block h-2 w-2 rounded-full" style={{ background: s.color }} />
                  {s.label}
                </span>
                <Money value={tip[s.key]} />
              </p>
            ))}
            <p className="mt-1 flex justify-between gap-4 border-t border-line pt-1">
              <span className="text-ink-soft">영업이익</span>
              <Money value={tip.operatingProfit} />
            </p>
          </div>
        )}
      </div>

      <details className="px-4 pb-3 text-xs">
        <summary className="cursor-pointer text-ink-soft">표로 보기</summary>
        <table className="mt-2 w-full">
          <thead>
            <tr>
              <th className={th}>월</th>
              <th className={`${th} text-right`}>매출</th>
              <th className={`${th} text-right`}>비용</th>
              <th className={`${th} text-right`}>영업이익</th>
              <th className={th}>마감</th>
            </tr>
          </thead>
          <tbody>
            {points.map((p) => (
              <tr key={p.month}>
                <td className={`${td} num text-left`}>{p.month}</td>
                <td className={`${td} text-right`}>
                  <Money value={p.revenue} />
                </td>
                <td className={`${td} text-right`}>
                  <Money value={p.expense} />
                </td>
                <td className={`${td} text-right`}>
                  <Money value={p.operatingProfit} />
                </td>
                <td className={td}>{p.status === "CLOSED" ? "마감" : "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  );
}
