import { NextResponse, type NextRequest } from "next/server";

// 페이지 접근 가드. 세션 쿠키 유무만 빠르게 확인한다.
// 토큰의 실제 유효성과 회사 접근 권한은 Spring Boot가 매 요청마다 검증한다 (여기는 UX용 1차 관문).

const PUBLIC_PATHS = ["/login", "/signup"];

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const hasSession = request.cookies.has("lg_rt");
  const isPublic = PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`));

  if (!hasSession && !isPublic) {
    const url = new URL("/login", request.url);
    if (pathname !== "/") url.searchParams.set("next", pathname);
    return NextResponse.redirect(url);
  }
  if (hasSession && isPublic) {
    return NextResponse.redirect(new URL("/companies", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico|.*\\.(?:png|svg|ico)$).*)"],
};
