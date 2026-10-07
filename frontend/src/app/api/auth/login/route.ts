import { NextResponse } from "next/server";
import { backendUnavailableBody, backendUrl, setSessionCookies, type TokenResponse } from "@/lib/server/session";

export async function POST(request: Request) {
  const body = await request.text();
  let res: Response;
  try {
    res = await fetch(backendUrl("/api/v1/auth/login"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      cache: "no-store",
    });
  } catch {
    return NextResponse.json(backendUnavailableBody(), { status: 502 });
  }

  if (!res.ok) {
    return new NextResponse(await res.text(), {
      status: res.status,
      headers: { "Content-Type": "application/json" },
    });
  }

  const tokens = (await res.json()) as TokenResponse;
  const response = NextResponse.json({ ok: true });
  setSessionCookies(response, tokens);
  return response;
}
