"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { useCompany } from "@/components/company-context";
import { Card, ErrorBanner, PageHeader } from "@/components/ui";
import { JournalForm } from "../journal-form";

function NewJournal() {
  const router = useRouter();
  const params = useSearchParams();
  const { company, canWrite } = useCompany();
  const txId = params.get("transactionId");

  if (!canWrite) return <ErrorBanner error="전표를 작성할 권한이 없습니다." />;

  return (
    <Card className="p-4">
      <JournalForm transactionId={txId && /^\d+$/.test(txId) ? Number(txId) : null} onSaved={(e) => router.push(`/c/${company.id}/journal-entries/${e.id}`)} />
    </Card>
  );
}

export default function NewJournalPage() {
  return (
    <>
      <PageHeader title="전표 작성" description="각 라인은 차변 또는 대변 중 하나에만 금액을 입력합니다." />
      <Suspense>
        <NewJournal />
      </Suspense>
    </>
  );
}
