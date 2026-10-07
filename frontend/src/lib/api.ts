"use client";

import { useCallback, useEffect, useState } from "react";
import type { ApiErrorBody } from "./types";

// 브라우저에서 사용하는 API 클라이언트. 항상 같은 출처의 BFF(/api/backend/**)만 호출한다.

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: ApiErrorBody,
  ) {
    super(body.message);
  }

  /** 필드 오류를 사람이 읽을 수 있는 한 줄 목록으로 */
  get detailLines(): string[] {
    return this.body.details.map((d) => (d.field ? `${d.field}: ${d.reason}` : d.reason));
  }
}

type Query = Record<string, string | number | boolean | null | undefined>;

export function withQuery(path: string, query?: Query): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined && v !== null && v !== "") params.set(k, String(v));
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

/** body가 FormData(파일 업로드)면 그대로 보내고, 브라우저가 multipart boundary를 붙이게 한다. */
export async function api<T>(path: string, init?: { method?: string; body?: unknown; query?: Query }): Promise<T> {
  const body = init?.body;
  const isForm = typeof FormData !== "undefined" && body instanceof FormData;
  let res: Response;
  try {
    res = await fetch(withQuery(`/api/backend${path}`, init?.query), {
      method: init?.method ?? "GET",
      headers: body !== undefined && !isForm ? { "Content-Type": "application/json" } : undefined,
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, { code: "NETWORK_ERROR", message: "네트워크 오류가 발생했습니다.", details: [], timestamp: "" });
  }

  if (res.status === 401 && typeof window !== "undefined") {
    // React 트리 밖(fetch 래퍼)에서 세션 만료를 처리하므로 router 대신 전체 이동한다.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.href = `/login?next=${encodeURIComponent(window.location.pathname)}`;
  }
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    const body: ApiErrorBody =
      data && typeof data === "object" && "code" in data
        ? data
        : { code: "UNKNOWN", message: `요청이 실패했습니다. (HTTP ${res.status})`, details: [], timestamp: "" };
    throw new ApiError(res.status, body);
  }
  return data as T;
}

/** 간단한 조회 훅. path가 null이면 요청하지 않는다. */
export function useApi<T>(path: string | null, query?: Query) {
  const [version, setVersion] = useState(0);
  // 마지막으로 완료된 요청의 결과. 새 요청이 진행되는 동안에도 이전 data를 유지해 화면 깜빡임을 줄인다.
  const [result, setResult] = useState<{ request: string; data?: T; error?: ApiError }>();
  const key = path === null ? null : withQuery(path, query);
  const request = `${key}#${version}`;

  useEffect(() => {
    if (key === null) return;
    let cancelled = false;
    api<T>(key)
      .then((data) => !cancelled && setResult({ request, data }))
      .catch((error: ApiError) => !cancelled && setResult((prev) => ({ request, data: prev?.data, error })));
    return () => {
      cancelled = true;
    };
  }, [key, request]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return {
    data: result?.data,
    error: result?.request === request ? result.error : undefined,
    loading: key !== null && result?.request !== request,
    reload,
  };
}
