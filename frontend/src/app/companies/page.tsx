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
    <main className="mx-auto max-w-3xl px-6 py-12">
      <div className="mb-8 flex items-center justify-between">
        <div>
          <p className="text-xs font-semibold tracking-widest text-accent">LEDGERGUARD AI</p>
          <h1 className="mt-1 text-2xl font-semibold">회사 선택</h1>
          {me.data && <p className="mt-1 text-sm text-ink-soft">{me.data.name} ({me.data.email})</p>}
        </div>
        <LogoutButton className="text-sm text-ink-soft hover:text-ink" />
      </div>

      <Card title="내 회사">
        {companies.loading && <Loading />}
        <div className="px-4">
          <ErrorBanner error={companies.error} />
        </div>
        {companies.data?.length === 0 && <Empty>아직 소속된 회사가 없습니다. 아래에서 회사를 등록하세요.</Empty>}
        <ul>
          {companies.data?.map((c) => (
            <li key={c.id} className="border-b border-line last:border-b-0">
              <Link href={`/c/${c.id}/dashboard`} className="flex items-center justify-between px-4 py-3 hover:bg-paper">
                <div>
                  <p className="font-medium">{c.name}</p>
                  <p className="num text-left text-xs text-ink-soft">{c.businessNo ?? "사업자번호 미등록"}</p>
                </div>
                <Badge tone={c.role === "ADMIN" ? "accent" : "neutral"}>{ROLE_LABEL[c.role]}</Badge>
              </Link>
            </li>
          ))}
        </ul>
      </Card>

      <Card title="새 회사 등록" className="mt-6">
        <form onSubmit={onCreate} className="grid gap-3 p-4 sm:grid-cols-[1fr_200px_auto] sm:items-end">
          <Field label="회사명">
            <Input name="name" required maxLength={200} />
          </Field>
          <Field label="사업자등록번호 (선택)">
            <Input name="businessNo" placeholder="123-45-67890" pattern="\d{3}-?\d{2}-?\d{5}" />
          </Field>
          <Button type="submit" variant="primary" disabled={pending}>
            등록
          </Button>
          <div className="sm:col-span-3">
            <ErrorBanner error={error} />
          </div>
        </form>
      </Card>
    </main>
  );
}
