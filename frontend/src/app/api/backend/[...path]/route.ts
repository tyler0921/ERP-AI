import { NextResponse, type NextRequest } from "next/server";
import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  backendUnavailableBody,
  backendUrl,
  clearSessionCookies,
  refreshTokens,
  setSessionCookies,
  type TokenResponse,
} from "@/lib/server/session";

// 브라우저 → /api/backend/<path> → Spring Boot /api/v1/<path>
// 쿠키의 Access 토큰을 Authorization 헤더로 붙이고, 만료(401)되면 Refresh 토큰으로 한 번 재발급 후 재시도한다.

type Ctx = { params: Promise<{ path: string[] }> };

const SAFE_SEGMENT = /^[A-Za-z0-9_-]+$/;

async function forward(request: NextRequest, path: string, body: ArrayBuffer | undefined, token: string | undefined) {
  const headers: Record<string, string> = { Accept: "application/json" };
  const contentType = request.headers.get("content-type");
  if (contentType) headers["Content-Type"] = contentType;
  if (token) headers.Authorization = `Bearer ${token}`;
  return fetch(backendUrl(`/api/v1/${path}${request.nextUrl.search}`), {
    method: request.method,
    headers,
    body,
    cache: "no-store",
  });
}

async function handle(request: NextRequest, { params }: Ctx) {
  const { path: segments } = await params;
  // 경로 조작(../, 인코딩된 슬래시 등)으로 다른 백엔드 경로에 접근하지 못하게 한다.
  if (!segments.every((s) => SAFE_SEGMENT.test(s))) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "잘못된 경로입니다.", details: [], timestamp: new Date().toISOString() }, { status: 400 });
  }
  const path = segments.join("/");
  const body = request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer();

  let accessToken = request.cookies.get(ACCESS_COOKIE)?.value;
  const refreshToken = request.cookies.get(REFRESH_COOKIE)?.value;
  let refreshed: TokenResponse | null = null;

  try {
    if (!accessToken && refreshToken) {
      refreshed = await refreshTokens(refreshToken);
      accessToken = refreshed?.accessToken;
    }
    let res = await forward(request, path, body, accessToken);
    if (res.status === 401 && refreshToken && !refreshed) {
      refreshed = await refreshTokens(refreshToken);
      if (refreshed) res = await forward(request, path, body, refreshed.accessToken);
    }

    const response = new NextResponse(res.status === 204 ? null : await res.arrayBuffer(), {
      status: res.status,
      headers: { "Content-Type": res.headers.get("content-type") ?? "application/json" },
    });
    if (refreshed) setSessionCookies(response, refreshed);
    else if (res.status === 401) clearSessionCookies(response);
    return response;
  } catch {
    return NextResponse.json(backendUnavailableBody(), { status: 502 });
  }
}

export { handle as GET, handle as POST, handle as PUT, handle as PATCH, handle as DELETE };
