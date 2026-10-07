"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import type { Account, BankAccount } from "@/lib/types";
import { Button, ErrorBanner, Field, Input, Select } from "@/components/ui";

/** 은행 계좌 등록. 계좌는 자산 계정과목 하나(예: 1020 보통예금)에 연결된다. */
export function BankAccountForm({ base, onCreated }: { base: string; onCreated: (account: BankAccount) => void }) {
  const accounts = useApi<Account[]>(`${base}/accounts`, { type: "ASSET", active: true });
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setPending(true);
    setError(undefined);
    try {
      const accountNo = String(f.get("accountNo") ?? "").trim();
      const created = await api<BankAccount>(`${base}/bank-accounts`, {
        method: "POST",
        body: {
          name: String(f.get("name")).trim(),
          bankName: String(f.get("bankName") ?? "").trim() || null,
          accountNo: accountNo || null,
          glAccountId: Number(f.get("glAccountId")),
        },
      });
      onCreated(created);
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  const defaultGl = accounts.data?.find((a) => a.code === "1020")?.id;

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <ErrorBanner error={error} />
      <div className="grid grid-cols-2 gap-3">
        <Field label="계좌 이름">
          <Input name="name" required maxLength={100} placeholder="주거래 계좌" />
        </Field>
        <Field label="은행 (선택)">
          <Input name="bankName" maxLength={50} placeholder="가상은행" />
        </Field>
      </div>
      <Field label="계좌번호 (선택)" hint="개인정보 보호를 위해 끝 4자리만 저장합니다.">
        <Input name="accountNo" pattern="[0-9\-]{4,30}" placeholder="123-456-789012" />
      </Field>
      <Field label="연결 계정과목" hint="전표에서 이 계정과목이 움직인 금액을 은행 거래와 비교합니다. 출금은 대변, 입금은 차변.">
        {accounts.data && (
          <Select name="glAccountId" required defaultValue={defaultGl}>
            {accounts.data.map((a) => (
              <option key={a.id} value={a.id}>
                {a.code} {a.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <div className="flex justify-end pt-2">
        <Button type="submit" variant="primary" disabled={pending || !accounts.data}>
          계좌 등록
        </Button>
      </div>
    </form>
  );
}
