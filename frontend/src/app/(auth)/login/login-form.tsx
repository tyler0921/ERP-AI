"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button, ErrorBanner, Field, Input } from "@/components/ui";

export function LoginForm() {
  const searchParams = useSearchParams();
  const [error, setError] = useState<string>();
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setPending(true);
    setError(undefined);
    try {
      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: form.get("email"), password: form.get("password") }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        setError(body?.message ?? "로그인에 실패했습니다.");
        return;
      }
      // 내부 경로로만 이동 (오픈 리다이렉트 방지)
      const next = searchParams.get("next");
      window.location.href = next && next.startsWith("/") && !next.startsWith("//") ? next : "/companies";
    } catch {
      setError("네트워크 오류가 발생했습니다.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <div>
        <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-accent">Welcome back</p>
        <h1 className="text-2xl font-bold tracking-[-0.03em]">로그인</h1>
        <p className="mt-2 text-sm leading-6 text-ink-soft">업무를 이어서 진행하려면 계정으로 로그인하세요.</p>
      </div>
      <ErrorBanner error={error} />
      <Field label="이메일">
        <Input name="email" type="email" autoComplete="email" required />
      </Field>
      <Field label="비밀번호">
        <Input name="password" type="password" autoComplete="current-password" required />
      </Field>
      <Button type="submit" variant="primary" className="mt-1 w-full py-2.5" disabled={pending}>
        {pending ? "로그인 중…" : "로그인"}
      </Button>
      <p className="border-t border-line pt-4 text-center text-sm text-ink-soft">
        계정이 없나요?{" "}
        <Link href="/signup" className="font-medium text-accent hover:underline">
          회원가입
        </Link>
      </p>
    </form>
  );
}
