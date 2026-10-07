"use client";

export function LogoutButton({ className }: { className?: string }) {
  async function logout() {
    await fetch("/api/auth/logout", { method: "POST" });
    // 로그아웃 시 메모리에 남은 회사 데이터를 모두 버리기 위해 전체 페이지를 새로 불러온다.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.href = "/login";
  }
  return (
    <button type="button" onClick={logout} className={className}>
      로그아웃
    </button>
  );
}
