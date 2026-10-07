import { NextResponse } from "next/server";
import { clearSessionCookies } from "@/lib/server/session";

// Refresh 토큰은 stateless라 서버에서 즉시 폐기할 수 없다(README 참고). 브라우저 쿠키만 삭제한다.
export async function POST() {
  const response = NextResponse.json({ ok: true });
  clearSessionCookies(response);
  return response;
}
