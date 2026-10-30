export interface Tenant {
  slug: string;
  displayName: string;
  branding: { logoUrl: string | null; primaryColor: string | null };
  teaser: string | null;
  // The tenant's wording for the header and input; placeholder may contain {name}.
  texts: { online: string; waiting: string; closed: string; placeholder: string };
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
  // The bot is asking for the customer's email or name before connecting them to the team.
  asking: "EMAIL" | "NAME" | null;
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
  return (res.status === 204 ? undefined : res.json()) as Promise<T>;
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

  // Swaps a guest session for one that carries the logged-in customer.
  identify: (token: string, identityToken: string) =>
    request<Session & { customer: { sub: string; name: string | null; email: string | null } }>(
      "/widget/sessions/identify",
      { method: "POST", token, body: JSON.stringify({ identityToken }) },
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
  // Customer messages this agent hasn't read yet.
  unreadCount: number;
}

export interface AgentConversation {
  id: string;
  tenantSlug: string;
  status: Status;
  priority: Priority;
  handoffReason: HandoffReason | null;
  customer: {
    sub: string | null;
    contactId: string | null;
    name: string | null;
    email: string | null;
    blocked: boolean;
  } | null;
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

export interface Note {
  id: string;
  author: Person;
  body: string;
  createdAt: string;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export type ListStatus = "WAITING" | "HUMAN" | "RESOLVED";

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
    list: (status: ListStatus, options: { mine?: boolean; cursor?: string | null; q?: string } = {}) => {
      const query = new URLSearchParams({ status });
      if (options.mine) query.set("assignee", "me");
      if (options.q?.trim()) query.set("q", options.q.trim().slice(0, 100));
      if (options.cursor) query.set("cursor", options.cursor);
      return get<Page<ConversationSummary>>(`/conversations?${query}`);
    },
    counts: () => get<{ waiting: number; mine: number }>("/conversations/counts"),
    detail: (id: string) =>
      get<{ conversation: AgentConversation; messages: Message[]; events: ConversationEvent[]; notes: Note[] }>(
        `/conversations/${id}`,
      ),
    accept: (id: string) => post<One>(`/conversations/${id}/accept`),
    reply: (id: string, clientMessageId: string, body: string) =>
      post<{ message: Message }>(`/conversations/${id}/messages`, { clientMessageId, body }),
    resolve: (id: string) => post<One>(`/conversations/${id}/resolve`),
    returnToBot: (id: string) => post<One>(`/conversations/${id}/return-to-bot`),
    reopen: (id: string) => post<One>(`/conversations/${id}/reopen`),
    history: (id: string) => get<Page<ConversationSummary>>(`/conversations/${id}/history`),
    agents: (id: string) => get<Page<Person>>(`/conversations/${id}/agents`),
    // agentSub null puts it back in the queue.
    transfer: (id: string, agentSub: string | null) => post<One>(`/conversations/${id}/transfer`, { agentSub }),
    block: (id: string) => post<One>(`/conversations/${id}/block`),
    unblock: (contactId: string) => post<void>(`/contacts/${contactId}/unblock`),
    markRead: (id: string, messageId: string) => post<void>(`/conversations/${id}/read`, { messageId }),
    note: (id: string, body: string) => post<{ note: Note }>(`/conversations/${id}/notes`, { body }),
  };
}

type One = { conversation: AgentConversation };

export type AgentApi = ReturnType<typeof agentApi>;

// Local development only: the server signs agent tokens itself (dev profile).
export const devApi = {
  agentToken: (sub: string, name: string) =>
    request<{ accessToken: string }>("/dev/agent-token", { method: "POST", base: "", body: JSON.stringify({ sub, name }) }),
};
