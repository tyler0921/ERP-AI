import { Suspense } from "react";
import { CompanyShell } from "@/components/company-shell";

// 회사 화면은 모두 로그인 사용자별 데이터라 정적 프리렌더 대상이 아니다.
// URL 파라미터를 읽는 클라이언트 셸을 Suspense 경계 안에 둔다 (Cache Components 요구사항).
export default function CompanyLayout({ children }: LayoutProps<"/c/[companyId]">) {
  return (
    <Suspense>
      <CompanyShell>{children}</CompanyShell>
    </Suspense>
  );
}
