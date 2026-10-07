import "server-only";
import type { NextResponse } from "next/server";

// BFF(Backend for Frontend) 세션.
// Spring Boot가 발급한 JWT는 httpOnly 쿠키에만 저장하고 브라우저 JS에는 노출하지 않는다 (XSS로 토큰 탈취 방지).
// 브라우저는 같은 출처의 /api/backend/** 만 호출하고, 여기서 Authorization 헤더를 붙여 백엔드로 전달한다.

export const ACCESS_COOKIE = "lg_at";
export const REFRESH_COOKIE = "lg_rt";

const REFRESH_MAX_AGE = 14 * 24 * 60 * 60; // 백엔드 Refresh 토큰 수명(14일)과 맞춘다.

export function backendUrl(path: string): string {
  const base = process.env.BACKEND_URL ?? "http://localhost:8080";
  return `${base.replace(/\/$/, "")}${path}`;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

const baseCookie = {
  httpOnly: true,
  sameSite: "lax" as const,
  secure: process.env.COOKIE_SECURE === "true",
  path: "/",
};

export function setSessionCookies(res: NextResponse, tokens: TokenResponse) {
  res.cookies.set(ACCESS_COOKIE, tokens.accessToken, { ...baseCookie, maxAge: tokens.expiresInSeconds });
  res.cookies.set(REFRESH_COOKIE, tokens.refreshToken, { ...baseCookie, maxAge: REFRESH_MAX_AGE });
}

export function clearSessionCookies(res: NextResponse) {
  res.cookies.set(ACCESS_COOKIE, "", { ...baseCookie, maxAge: 0 });
  res.cookies.set(REFRESH_COOKIE, "", { ...baseCookie, maxAge: 0 });
}

/** Refresh 토큰으로 새 토큰 쌍을 받는다. 실패하면 null. */
export async function refreshTokens(refreshToken: string): Promise<TokenResponse | null> {
  try {
    const res = await fetch(backendUrl("/api/v1/auth/refresh"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
      cache: "no-store",
    });
    if (!res.ok) return null;
    return (await res.json()) as TokenResponse;
  } catch {
    return null;
  }
}

/** 백엔드에 연결할 수 없을 때 공통 오류 형식으로 응답하기 위한 본문. */
export function backendUnavailableBody() {
  return {
    code: "BACKEND_UNAVAILABLE",
    message: "백엔드 서버에 연결할 수 없습니다. 잠시 후 다시 시도하세요.",
    details: [],
    timestamp: new Date().toISOString(),
  };
}
