"use client";

import { useEffect, useRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from "react";
import type { ApiError } from "@/lib/api";
import { formatMoney } from "@/lib/money";

function cx(...classes: (string | false | null | undefined)[]) {
  return classes.filter(Boolean).join(" ");
}

type Variant = "primary" | "secondary" | "danger" | "ghost";

const VARIANT: Record<Variant, string> = {
  primary: "bg-accent text-white hover:bg-[#12563e] border-accent shadow-sm shadow-accent/15",
  secondary: "bg-surface text-ink border-line-strong hover:border-accent/30 hover:bg-accent-soft/40",
  danger: "bg-surface text-danger border-danger/30 hover:bg-danger-soft",
  ghost: "bg-transparent text-ink-soft border-transparent hover:bg-line/60 hover:text-ink",
};

export function Button({
  variant = "secondary",
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      {...props}
      className={cx(
        "inline-flex min-h-9 items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border px-3.5 py-1.5 text-sm font-semibold transition-all duration-150 active:translate-y-px disabled:cursor-not-allowed disabled:opacity-50 disabled:active:translate-y-0",
        VARIANT[variant],
        className,
      )}
    />
  );
}

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={cx(
        "min-h-10 w-full rounded-lg border border-line-strong bg-surface px-3 py-2 text-sm shadow-sm shadow-black/[0.02] outline-none transition focus:border-accent focus:ring-3 focus:ring-accent/10",
        className,
      )}
    />
  );
}

export function Select({ className, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      {...props}
      className={cx(
        "min-h-10 w-full rounded-lg border border-line-strong bg-surface px-3 py-2 text-sm shadow-sm shadow-black/[0.02] outline-none transition focus:border-accent focus:ring-3 focus:ring-accent/10",
        className,
      )}
    />
  );
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-semibold text-ink-soft">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-ink-soft">{hint}</span>}
    </label>
  );
}

export function Card({ title, actions, children, className }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cx("overflow-hidden rounded-xl border border-line bg-surface shadow-sm shadow-black/[0.025]", className)}>
      {(title || actions) && (
        <header className="flex items-center justify-between gap-3 border-b border-line px-5 py-3.5">
          <h2 className="text-sm font-bold tracking-[-0.01em]">{title}</h2>
          <div className="flex items-center gap-2">{actions}</div>
        </header>
      )}
      {children}
    </section>
  );
}

type Tone = "neutral" | "accent" | "danger" | "warn";
const TONE: Record<Tone, string> = {
  neutral: "bg-line/60 text-ink-soft",
  accent: "bg-accent-soft text-accent",
  danger: "bg-danger-soft text-danger",
  warn: "bg-warn-soft text-warn",
};

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return <span className={cx("inline-flex items-center rounded-md px-2 py-0.5 text-xs font-semibold", TONE[tone])}>{children}</span>;
}

export function StatusBadge({ status }: { status: string }) {
  if (status === "VOID") return <Badge tone="danger">취소</Badge>;
  if (status === "PENDING") return <Badge tone="warn">미승인</Badge>;
  return <Badge>{status}</Badge>;
}

export function Money({ value, className }: { value: string | null | undefined; className?: string }) {
  return <span className={cx("num", className)}>{formatMoney(value)}</span>;
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-bold tracking-[-0.03em]">{title}</h1>
        {description && <p className="mt-1.5 text-sm leading-6 text-ink-soft">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}

export function ErrorBanner({ error }: { error: ApiError | string | undefined | null }) {
  if (!error) return null;
  const message = typeof error === "string" ? error : error.message;
  const details = typeof error === "string" ? [] : error.detailLines;
  return (
    <div role="alert" className="rounded-lg border border-danger/25 bg-danger-soft px-4 py-3 text-sm text-danger">
      <p className="font-medium">{message}</p>
      {details.length > 0 && (
        <ul className="mt-1 list-inside list-disc text-xs">
          {details.map((d) => (
            <li key={d}>{d}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="px-4 py-10 text-center text-sm text-ink-soft">{children}</div>;
}

export function Loading() {
  return <div className="px-4 py-10 text-center text-sm text-ink-soft">불러오는 중…</div>;
}

export const th = "border-b border-line bg-paper/70 px-4 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-ink-soft";
export const td = "border-b border-line px-4 py-3 text-sm";

export function Pagination({ page, size, total, onChange }: { page: number; size: number; total: number; onChange: (page: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="flex items-center justify-between px-4 py-2 text-xs text-ink-soft">
      <span>총 {total.toLocaleString()}건</span>
      <div className="flex items-center gap-2">
        <Button variant="ghost" disabled={page <= 0} onClick={() => onChange(page - 1)}>
          이전
        </Button>
        <span>
          {page + 1} / {pages}
        </span>
        <Button variant="ghost" disabled={page + 1 >= pages} onClick={() => onChange(page + 1)}>
          다음
        </Button>
      </div>
    </div>
  );
}

/** 네이티브 <dialog> 기반 모달 (포커스 트랩·ESC 처리 내장) */
export function Modal({ open, onClose, title, children, wide }: { open: boolean; onClose: () => void; title: string; children: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (open && !el.open) el.showModal();
    if (!open && el.open) el.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      onClose={onClose}
      className={cx("m-auto w-[calc(100%-2rem)] rounded-2xl border border-line bg-surface p-0 text-ink shadow-2xl shadow-black/15", wide ? "max-w-3xl" : "max-w-lg")}
    >
      {open && (
        <div>
          <header className="flex items-center justify-between border-b border-line px-5 py-3">
            <h2 className="text-base font-semibold">{title}</h2>
            <button type="button" onClick={onClose} className="text-ink-soft hover:text-ink" aria-label="닫기">
              ✕
            </button>
          </header>
          <div className="px-5 py-4">{children}</div>
        </div>
      )}
    </dialog>
  );
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "-";
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(iso));
}
