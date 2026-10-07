"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError, useApi } from "@/lib/api";
import { ACCOUNT_TYPE_LABEL, type Account, type AccountType } from "@/lib/types";
import { useCompany } from "@/components/company-context";
import { Badge, Button, Card, Empty, ErrorBanner, Field, Input, Loading, Modal, PageHeader, Select, td, th } from "@/components/ui";

const TYPES = Object.keys(ACCOUNT_TYPE_LABEL) as AccountType[];

export default function AccountsPage() {
  const { base, canWrite } = useCompany();
  const [type, setType] = useState("");
  const list = useApi<Account[]>(`${base}/accounts`, { type });
  const [editing, setEditing] = useState<Account | "new" | null>(null);

  return (
    <>
      <PageHeader
        title="계정과목"
        description="전표에서 사용 중인 계정과목은 코드·유형을 변경할 수 없고, 삭제 대신 비활성화합니다."
        actions={canWrite && <Button variant="primary" onClick={() => setEditing("new")}>계정과목 추가</Button>}
      />
      <Card
        title={
          <div className="flex gap-1">
            {["", ...TYPES].map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => setType(t)}
                className={`rounded px-2 py-1 text-xs ${type === t ? "bg-ink text-white" : "text-ink-soft hover:bg-paper"}`}
              >
                {t ? ACCOUNT_TYPE_LABEL[t as AccountType] : "전체"}
              </button>
            ))}
          </div>
        }
      >
        {list.error && (
          <div className="p-3">
            <ErrorBanner error={list.error} />
          </div>
        )}
        {list.loading && !list.data ? (
          <Loading />
        ) : list.data?.length === 0 ? (
          <Empty>계정과목이 없습니다.</Empty>
        ) : (
          <table className="w-full">
            <thead>
              <tr>
                <th className={th}>코드</th>
                <th className={th}>계정과목명</th>
                <th className={th}>유형</th>
                <th className={th}>상태</th>
                {canWrite && <th className={th} />}
              </tr>
            </thead>
            <tbody>
              {list.data?.map((a) => (
                <tr key={a.id} className={a.active ? "" : "text-ink-soft"}>
                  <td className={`${td} num text-left`}>{a.code}</td>
                  <td className={td}>{a.name}</td>
                  <td className={td}>{ACCOUNT_TYPE_LABEL[a.type]}</td>
                  <td className={td}>{a.active ? <Badge tone="accent">사용</Badge> : <Badge>비활성</Badge>}</td>
                  {canWrite && (
                    <td className={`${td} text-right`}>
                      <Button variant="ghost" onClick={() => setEditing(a)}>
                        수정
                      </Button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Modal open={editing !== null} onClose={() => setEditing(null)} title={editing === "new" ? "계정과목 추가" : "계정과목 수정"}>
        {editing !== null && (
          <AccountForm
            base={base}
            initial={editing === "new" ? null : editing}
            onDone={() => {
              setEditing(null);
              list.reload();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function AccountForm({ base, initial, onDone }: { base: string; initial: Account | null; onDone: () => void }) {
  const [error, setError] = useState<ApiError>();
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const body = {
      code: String(f.get("code")).trim(),
      name: String(f.get("name")).trim(),
      type: f.get("type") as AccountType,
      ...(initial ? { active: f.get("active") === "on" } : {}),
    };
    setPending(true);
    setError(undefined);
    try {
      if (initial) {
        // 변경된 필드만 보낸다 — 사용 중인 계정의 code/type을 그대로 보내도 거절되지 않도록.
        const patch: Record<string, unknown> = {};
        if (body.code !== initial.code) patch.code = body.code;
        if (body.name !== initial.name) patch.name = body.name;
        if (body.type !== initial.type) patch.type = body.type;
        if (body.active !== initial.active) patch.active = body.active;
        if (Object.keys(patch).length > 0) await api(`${base}/accounts/${initial.id}`, { method: "PATCH", body: patch });
      } else {
        await api(`${base}/accounts`, { method: "POST", body });
      }
      onDone();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <ErrorBanner error={error} />
      <div className="grid grid-cols-[140px_1fr] gap-3">
        <Field label="코드">
          <Input name="code" required maxLength={20} pattern="[A-Za-z0-9_\-]+" className="num text-left" defaultValue={initial?.code} />
        </Field>
        <Field label="계정과목명">
          <Input name="name" required maxLength={100} defaultValue={initial?.name} />
        </Field>
      </div>
      <Field label="유형">
        <Select name="type" defaultValue={initial?.type ?? "EXPENSE"}>
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {ACCOUNT_TYPE_LABEL[t]}
            </option>
          ))}
        </Select>
      </Field>
      {initial && (
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" name="active" defaultChecked={initial.active} /> 사용 중 (해제하면 새 전표에서 선택할 수 없습니다)
        </label>
      )}
      <div className="flex justify-end pt-2">
        <Button type="submit" variant="primary" disabled={pending}>
          저장
        </Button>
      </div>
    </form>
  );
}
