export interface Tenant {
  slug: string;
  displayName: string;
  branding: { logoUrl: string | null; primaryColor: string | null };
  teaser: string | null;
}

export interface Session {
  sessionToken: string;
  visitorId: string;
  expiresAt: string;
}

export type Status = "BOT" | "WAITING" | "HUMAN" | "RESOLVED";
export type SenderType = "CUSTOMER" | "BOT" | "AGENT" | "SYSTEM";

export interface Option {
  id: string;
  title: string;
}

export interface Message {
  id: string;
  senderType: SenderType;
  senderName: string | null;
  body: string;
  options: Option[] | null;
  clientMessageId: string | null;
  createdAt: string;
}

export interface Conversation {
  id: string;
  status: Status;
  assignee: { name: string } | null;
  createdAt: string;
  lastMessageAt: string;
  resolvedAt: string | null;
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

async function request<T>(path: string, init: RequestInit & { token?: string; base?: string } = {}): Promise<T> {
  const { token, headers, base = "/api/v1", ...rest } = init;
  const res = await fetch(`${base}${path}`, {
    ...rest,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
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

  startConversation: (token: string) =>
    request<{ conversation: Conversation; messages: Message[] }>("/widget/conversation", { method: "POST", token }),

  messagesAfter: (token: string, after: string) =>
    request<{ items: Message[]; nextCursor: string | null }>(
      `/widget/conversation/messages?after=${encodeURIComponent(after)}&limit=200`,
      { token },
    ),

  send: (token: string, message: { clientMessageId: string; body?: string; optionId?: string }) =>
    request<{ message: Message; conversation: Conversation }>("/widget/conversation/messages", {
      method: "POST",
      token,
      body: JSON.stringify(message),
    }),
};

// ---- Agent console ----

export type Priority = "NORMAL" | "HIGH" | "URGENT";
export type HandoffReason = "CUSTOMER_REQUEST" | "FALLBACK_LIMIT" | "URGENT" | "BOT_ERROR";

export interface Person {
  sub: string;
  name: string;
}

export interface ConversationSummary {
  id: string;
  tenantSlug: string;
  status: Status;
  priority: Priority;
  handoffReason: HandoffReason | null;
  customerName: string | null;
  lastMessagePreview: string;
  lastMessageAt: string;
  waitingSince: string | null;
  assignee: Person | null;
}

export interface AgentConversation {
  id: string;
  tenantSlug: string;
  status: Status;
  priority: Priority;
  handoffReason: HandoffReason | null;
  customer: { sub: string; name: string | null; email: string | null } | null;
  assignee: Person | null;
  createdAt: string;
  lastMessageAt: string;
  waitingSince: string | null;
  resolvedAt: string | null;
}

export interface ConversationEvent {
  id: string;
  type: string;
  actorType: SenderType;
  actorName: string | null;
  reason: string | null;
  createdAt: string;
}

export interface Me {
  sub: string;
  name: string;
  tenants: string[];
}

export interface TenantItem {
  id: string;
  slug: string;
  displayName: string;
}

// getToken returns the agent's current token, so a refreshed token is used without rebuilding anything.
export function agentApi(getToken: () => string) {
  const get = <T,>(path: string) => request<T>(`/agent${path}`, { token: getToken() });
  const post = <T,>(path: string, body?: unknown) =>
    request<T>(`/agent${path}`, {
      method: "POST",
      token: getToken(),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  return {
    me: () => get<Me>("/me"),
    tenants: () => get<{ items: TenantItem[] }>("/tenants"),
    list: (status: "WAITING" | "HUMAN" | "RESOLVED", mine = false) =>
      get<{ items: ConversationSummary[] }>(`/conversations?status=${status}${mine ? "&assignee=me" : ""}`),
    detail: (id: string) =>
      get<{ conversation: AgentConversation; messages: Message[]; events: ConversationEvent[] }>(`/conversations/${id}`),
    accept: (id: string) => post<{ conversation: AgentConversation }>(`/conversations/${id}/accept`),
    reply: (id: string, clientMessageId: string, body: string) =>
      post<{ message: Message }>(`/conversations/${id}/messages`, { clientMessageId, body }),
    resolve: (id: string) => post<{ conversation: AgentConversation }>(`/conversations/${id}/resolve`),
    returnToBot: (id: string) => post<{ conversation: AgentConversation }>(`/conversations/${id}/return-to-bot`),
  };
}

export type AgentApi = ReturnType<typeof agentApi>;

// Local development only: the server signs agent tokens itself (dev profile).
export const devApi = {
  agentToken: (sub: string, name: string) =>
    request<{ accessToken: string }>("/dev/agent-token", { method: "POST", base: "", body: JSON.stringify({ sub, name }) }),
};
