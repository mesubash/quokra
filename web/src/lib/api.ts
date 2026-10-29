export interface Tenant {
  slug: string;
  displayName: string;
  branding: { logoUrl: string | null; primaryColor: string | null };
}

export interface Session {
  sessionToken: string;
  visitorId: string;
  expiresAt: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api/v1${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new ApiError(res.status, body?.error?.code ?? "UNKNOWN", body?.error?.message ?? res.statusText);
  }
  return res.json() as Promise<T>;
}

export const widgetApi = {
  tenant: (pk: string) => request<Tenant>(`/widget/tenants/${encodeURIComponent(pk)}`),
  startSession: (pk: string) =>
    request<Session>("/widget/sessions", { method: "POST", body: JSON.stringify({ publishableKey: pk }) }),
};
