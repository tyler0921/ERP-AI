"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { ROLE_LABEL, type Member, type Role } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, PageHeader, Select, td, th } from "@/components/ui";

const ROLES = Object.keys(ROLE_LABEL) as Role[];

export default function SettingsPage() {
  const { base, company, isAdmin, reloadCompany } = useCompany();
  const members = useApi<Member[]>(`${base}/members`);
  const [companyError, setCompanyError] = useState<ApiError>();
  const [memberError, setMemberError] = useState<ApiError>();
  const [saved, setSaved] = useState(false);

  async function onSaveCompany(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setCompanyError(undefined);
    setSaved(false);
    try {
      const businessNo = String(f.get("businessNo") ?? "").trim();
      await api(base, { method: "PATCH", body: { name: String(f.get("name")).trim(), businessNo: businessNo || null } });
      setSaved(true);
      reloadCompany();
    } catch (err) {
      setCompanyError(err as ApiError);
    }
  }

  async function memberAction(fn: () => Promise<unknown>) {
    setMemberError(undefined);
    try {
      await fn();
      members.reload();
    } catch (err) {
      setMemberError(err as ApiError);
    }
  }

  async function onAddMember(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const f = new FormData(form);
    await memberAction(() => api(`${base}/members`, { method: "POST", body: { email: String(f.get("email")).trim(), role: f.get("role") } }));
    form.reset();
  }

  return (
    <>
      <PageHeader title="설정" description="회사 정보와 멤버 권한을 관리합니다. 회사에는 최소 한 명의 관리자가 있어야 합니다." />

      <div className="grid gap-6 lg:grid-cols-[1fr_1.4fr]">
        <Card title="회사 정보">
          <form onSubmit={onSaveCompany} className="space-y-3 p-4">
            <ErrorBanner error={companyError} />
            <Field label="회사명">
              <Input name="name" required maxLength={200} defaultValue={company.name} disabled={!isAdmin} />
            </Field>
            <Field label="사업자등록번호">
              <Input name="businessNo" pattern="\d{3}-?\d{2}-?\d{5}" defaultValue={company.businessNo ?? ""} disabled={!isAdmin} />
            </Field>
            {isAdmin && (
              <div className="flex items-center justify-end gap-3">
                {saved && <span className="text-xs text-accent">저장되었습니다.</span>}
                <Button type="submit" variant="primary">
                  저장
                </Button>
              </div>
            )}
          </form>
        </Card>

        <Card title="멤버">
          {memberError && (
            <div className="p-3">
              <ErrorBanner error={memberError} />
            </div>
          )}
          {members.loading && !members.data ? (
            <Loading />
          ) : (
            <table className="w-full">
              <thead>
                <tr>
                  <th className={th}>이름</th>
                  <th className={th}>이메일</th>
                  <th className={th}>권한</th>
                  {isAdmin && <th className={th} />}
                </tr>
              </thead>
              <tbody>
                {members.data?.map((m) => (
                  <tr key={m.userId}>
                    <td className={td}>{m.name}</td>
                    <td className={`${td} text-ink-soft`}>{m.email}</td>
                    <td className={td}>
                      {isAdmin ? (
                        <Select
                          value={m.role}
                          onChange={(e) => memberAction(() => api(`${base}/members/${m.userId}`, { method: "PATCH", body: { role: e.target.value } }))}
                          aria-label={`${m.name} 권한`}
                          className="w-32"
                        >
                          {ROLES.map((r) => (
                            <option key={r} value={r}>
                              {ROLE_LABEL[r]}
                            </option>
                          ))}
                        </Select>
                      ) : (
                        <Badge tone={m.role === "ADMIN" ? "accent" : "neutral"}>{ROLE_LABEL[m.role]}</Badge>
                      )}
                    </td>
                    {isAdmin && (
                      <td className={`${td} text-right`}>
                        <Button variant="ghost" onClick={() => memberAction(() => api(`${base}/members/${m.userId}`, { method: "DELETE" }))}>
                          제거
                        </Button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {isAdmin && (
            <form onSubmit={onAddMember} className="grid gap-2 border-t border-line p-3 sm:grid-cols-[1fr_140px_auto]">
              <Input name="email" type="email" required placeholder="가입된 사용자 이메일" />
              <Select name="role" defaultValue="ACCOUNTANT">
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABEL[r]}
                  </option>
                ))}
              </Select>
              <Button type="submit">멤버 추가</Button>
            </form>
          )}
        </Card>
      </div>
    </>
  );
}
