"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { Button, ErrorBanner, Field, Input } from "@/components/ui";

export default function SignupPage() {
  const [error, setError] = useState<ApiError | string>();
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const email = String(form.get("email"));
    const password = String(form.get("password"));
    if (password !== form.get("passwordConfirm")) {
      setError("비밀번호 확인이 일치하지 않습니다.");
      return;
    }
    setPending(true);
    setError(undefined);
    try {
      await api("/auth/signup", { method: "POST", body: { email, password, name: form.get("name") } });
      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      window.location.href = res.ok ? "/companies" : "/login";
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">회원가입</h1>
        <p className="mt-1 text-sm text-ink-soft">가입 후 회사를 등록하면 기본 계정과목이 함께 생성됩니다.</p>
      </div>
      <ErrorBanner error={error} />
      <Field label="이름">
        <Input name="name" required maxLength={100} autoComplete="name" />
      </Field>
      <Field label="이메일">
        <Input name="email" type="email" required maxLength={255} autoComplete="email" />
      </Field>
      <Field label="비밀번호" hint="8~72자">
        <Input name="password" type="password" required minLength={8} maxLength={72} autoComplete="new-password" />
      </Field>
      <Field label="비밀번호 확인">
        <Input name="passwordConfirm" type="password" required minLength={8} maxLength={72} autoComplete="new-password" />
      </Field>
      <Button type="submit" variant="primary" className="w-full py-2" disabled={pending}>
        {pending ? "가입 중…" : "가입하기"}
      </Button>
      <p className="text-center text-sm text-ink-soft">
        이미 계정이 있나요?{" "}
        <Link href="/login" className="font-medium text-accent hover:underline">
          로그인
        </Link>
      </p>
    </form>
  );
}
