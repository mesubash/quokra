export interface Tenant {
  slug: string;
  displayName: string;
  branding: { logoUrl: string | null; primaryColor: string | null };
  teaser: string | null;
  // The tenant's wording for the header and input; placeholder may contain {name}.
  texts: { online: string; waiting: string; closed: string; placeholder: string; offline: string };
  // Inside the tenant's opening hours (always, when it has none).
  open: boolean;
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

  // Closes the current conversation (even with an agent) and starts over with the greeting.
  newConversation: (token: string) =>
    request<{ conversation: Conversation; messages: Message[] }>("/widget/conversation/new", { method: "POST", token }),

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
  // Which parts of the console this person may use.
  agent: boolean;
  admin: boolean;
}

// ---- Admin ----

// When the team answers chats. days maps MON..SUN to "09:00-17:00"; days left out are closed.
export interface OpeningHours {
  timezone: string;
  days: Record<string, string>;
}

export interface AdminTenant {
  id: string;
  slug: string;
  displayName: string;
  publishableKey: string;
  widgetOrigins: string[];
  consoleOrigins: string[];
  logoUrl: string | null;
  primaryColor: string | null;
  botConfig: string;
  autoResolveMinutes: number;
  // null means always open.
  openingHours: OpeningHours | null;
  hasSecret: boolean;
  agentCount: number;
  inviteCount: number;
  createdAt: string;
}

export interface TenantForm {
  slug?: string;
  displayName: string;
  widgetOrigins: string[];
  consoleOrigins: string[];
  logoUrl: string | null;
  primaryColor: string | null;
  botConfig: string;
  // 0 = never.
  autoResolveMinutes: number;
  openingHours: OpeningHours | null;
}

export interface AdminAgents {
  agents: { sub: string; name: string; email: string | null }[];
  invites: { email: string; createdAt: string }[];
}

// A saved answer; {name} becomes the customer's name when it's inserted.
export interface SavedReply {
  id: string;
  title: string;
  body: string;
}

// A bot in the shape of a bot file (see the write-the-bot guide).
export interface BotFields {
  greeting: string;
  teaser?: string;
  menu: { id: string; title: string; keywords: string[]; answer: string }[];
  human: { title: string; keywords: string[] };
  urgent: { keywords: string[]; message: string };
  fallback: string;
  max_fallbacks: number;
  handoff_message: string;
  texts: Record<string, string>;
}

// custom: edited in the Admin tab; otherwise the bot file is used.
export interface AdminBot {
  custom: boolean;
  file: string;
  bot: BotFields | null;
}

export function adminApi(getToken: () => string) {
  const call = <T,>(method: string, path: string, body?: unknown) =>
    request<T>(`/admin${path}`, {
      method,
      token: getToken(),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  const slug = (s: string) => encodeURIComponent(s);
  return {
    bots: () => call<{ items: string[] }>("GET", "/bots"),
    tenants: () => call<{ items: AdminTenant[] }>("GET", "/tenants"),
    create: (form: TenantForm) => call<AdminTenant>("POST", "/tenants", form),
    update: (s: string, form: TenantForm) => call<AdminTenant>("PUT", `/tenants/${slug(s)}`, form),
    rotateSecret: (s: string) => call<{ secretKey: string }>("POST", `/tenants/${slug(s)}/secret`),
    agents: (s: string) => call<AdminAgents>("GET", `/tenants/${slug(s)}/agents`),
    addAgent: (s: string, email: string) =>
      call<{ result: "ADDED" | "INVITED" }>("POST", `/tenants/${slug(s)}/agents`, { email }),
    removeAgent: (s: string, sub: string) => call<void>("DELETE", `/tenants/${slug(s)}/agents/${encodeURIComponent(sub)}`),
    removeInvite: (s: string, email: string) =>
      call<void>("DELETE", `/tenants/${slug(s)}/invites/${encodeURIComponent(email)}`),
    bot: (s: string) => call<AdminBot>("GET", `/tenants/${slug(s)}/bot`),
    saveBot: (s: string, bot: BotFields) => call<AdminBot>("PUT", `/tenants/${slug(s)}/bot`, bot),
    resetBot: (s: string) => call<AdminBot>("DELETE", `/tenants/${slug(s)}/bot`),
    replies: (s: string) => call<{ items: SavedReply[] }>("GET", `/tenants/${slug(s)}/replies`),
    addReply: (s: string, reply: Omit<SavedReply, "id">) => call<SavedReply>("POST", `/tenants/${slug(s)}/replies`, reply),
    updateReply: (s: string, { id, ...reply }: SavedReply) =>
      call<SavedReply>("PUT", `/tenants/${slug(s)}/replies/${id}`, reply),
    removeReply: (s: string, id: string) => call<void>("DELETE", `/tenants/${slug(s)}/replies/${id}`),
  };
}

export type AdminApi = ReturnType<typeof adminApi>;

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
    counts: () => get<{ waiting: number; mine: number; myUnread: number }>("/conversations/counts"),
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
    replies: (id: string) => get<Page<SavedReply>>(`/conversations/${id}/replies`),
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
    request<{ accessToken: string }>("/dev/agent-token", { method: "POST", base: "", body: JSON.stringify({ sub, name, admin: true }) }),
};
