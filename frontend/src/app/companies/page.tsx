"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { ROLE_LABEL, type Company, type Me } from "@/lib/types";
import { Badge, Button, Card, Empty, ErrorBanner, Field, Input, Loading } from "@/components/ui";
import { LogoutButton } from "@/components/logout-button";

export default function CompaniesPage() {
  const router = useRouter();
  const me = useApi<Me>("/me");
  const companies = useApi<Company[]>("/companies");
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);

  async function onCreate(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setPending(true);
    setError(undefined);
    try {
      const businessNo = String(form.get("businessNo") ?? "").trim();
      const created = await api<Company>("/companies", {
        method: "POST",
        body: { name: form.get("name"), businessNo: businessNo || null },
      });
      router.push(`/c/${created.id}/dashboard`);
    } catch (err) {
      setError(err as ApiError);
      setPending(false);
    }
  }

  return (
    <main className="mx-auto max-w-5xl px-5 py-8 sm:px-8 sm:py-12">
      <div className="mb-8 flex items-center justify-between">
        <div>
          <div className="flex items-center gap-3">
            <span className="grid size-10 place-items-center rounded-xl bg-sidebar text-sm font-bold text-white shadow-md">LG</span>
            <div>
              <p className="text-sm font-bold">LedgerGuard</p>
              <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-ink-soft">Accounting AI</p>
            </div>
          </div>
        </div>
        <LogoutButton className="text-sm text-ink-soft hover:text-ink" />
      </div>

      <section className="mb-6 rounded-2xl bg-sidebar px-6 py-6 text-white shadow-lg shadow-sidebar/10 sm:px-8">
        <p className="text-xs font-semibold uppercase tracking-[0.16em] text-[#79d8b1]">Workspace</p>
        <h1 className="mt-2 text-2xl font-bold tracking-[-0.035em]">작업할 회사를 선택하세요</h1>
        {me.data && <p className="mt-2 text-sm text-white/55">{me.data.name} · {me.data.email}</p>}
      </section>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
      <Card title="내 회사">
        {companies.loading && <Loading />}
        <div className="px-4">
          <ErrorBanner error={companies.error} />
        </div>
        {companies.data?.length === 0 && <Empty>아직 소속된 회사가 없습니다. 아래에서 회사를 등록하세요.</Empty>}
        <ul>
          {companies.data?.map((c) => (
            <li key={c.id} className="border-b border-line last:border-b-0">
              <Link href={`/c/${c.id}/dashboard`} className="group flex items-center justify-between px-5 py-4 transition hover:bg-accent-soft/35">
                <div className="flex min-w-0 items-center gap-3">
                  <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-accent-soft text-sm font-bold text-accent">{c.name.slice(0, 1)}</span>
                  <div className="min-w-0">
                    <p className="truncate font-semibold">{c.name}</p>
                    <p className="num mt-0.5 text-left text-xs text-ink-soft">{c.businessNo ?? "사업자번호 미등록"}</p>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <Badge tone={c.role === "ADMIN" ? "accent" : "neutral"}>{ROLE_LABEL[c.role]}</Badge>
                  <span className="text-ink-soft/40 transition group-hover:translate-x-0.5 group-hover:text-accent">→</span>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      </Card>

      <Card title="새 회사 등록">
        <form onSubmit={onCreate} className="space-y-4 p-5">
          <Field label="회사명">
            <Input name="name" required maxLength={200} placeholder="예: 레저가드 주식회사" />
          </Field>
          <Field label="사업자등록번호 (선택)">
            <Input name="businessNo" placeholder="123-45-67890" pattern="\d{3}-?\d{2}-?\d{5}" />
          </Field>
          <Button type="submit" variant="primary" className="w-full" disabled={pending}>
            {pending ? "등록 중…" : "회사 등록"}
          </Button>
          <ErrorBanner error={error} />
        </form>
      </Card>
      </div>
    </main>
  );
}
