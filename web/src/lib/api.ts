export interface Tenant {
  slug: string;
  displayName: string;
  branding: { logoUrl: string | null; primaryColor: string | null };
  teaser: string | null;
  // The tenant's wording for the header and input; placeholder may contain {name}.
  texts: { online: string; waiting: string; closed: string; placeholder: string; offline: string };
  // Inside the tenant's opening hours (always, when it has none).
  open: boolean;
  // Images can be sent (the company allows them and storage works).
  attachments: boolean;
  // Someone of the team is answering chats right now.
  teamOnline: boolean;
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
  // An image; the file is fetched separately with the same token.
  attachment?: Attachment | null;
  // The earlier message this one answers, as it was when sent.
  replyTo?: Quote | null;
}

export interface Quote {
  id: string;
  senderType: SenderType;
  senderName: string | null;
  preview: string;
}

export interface Attachment {
  name: string;
  contentType: string;
  size: number;
}

// Matches the server's limit, so a big file is refused before uploading it.
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const IMAGE_TYPES = "image/png,image/jpeg,image/gif,image/webp";

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
      // A FormData body sets its own multipart type.
      ...(rest.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
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

// Agent and admin calls: a 401 means the token ran out, so ask for a fresh one (refresh) and try once more.
// Without refresh (local dev sign-in), the 401 goes to the caller as before.
export type FreshToken = () => Promise<string>;

async function authed<T>(
  path: string,
  init: RequestInit,
  getToken: () => string,
  refresh?: FreshToken,
): Promise<T> {
  try {
    return await request<T>(path, { ...init, token: getToken() });
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 401 || !refresh) throw e;
    return request<T>(path, { ...init, token: await refresh() });
  }
}

function imageForm(file: File, clientMessageId: string, replyTo?: string) {
  const form = new FormData();
  form.append("file", file);
  form.append("clientMessageId", clientMessageId);
  if (replyTo) form.append("replyTo", replyTo);
  return form;
}

// An image the page can show: fetched with the token, since an <img> can't send one.
async function image(path: string, token: string, refresh?: FreshToken): Promise<Blob> {
  let res = await fetch(`/api/v1${path}`, { headers: { Authorization: `Bearer ${token}` } });
  if (res.status === 401 && refresh) {
    res = await fetch(`/api/v1${path}`, { headers: { Authorization: `Bearer ${await refresh()}` } });
  }
  if (!res.ok) throw new ApiError(res.status, "IMAGE_FAILED", res.statusText);
  return res.blob();
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

  send: (token: string, message: { clientMessageId: string; body?: string; optionId?: string; replyTo?: string }) =>
    request<{ message: Message; conversation: Conversation }>("/widget/conversation/messages", {
      method: "POST",
      token,
      body: JSON.stringify(message),
    }),

  sendImage: (token: string, file: File, clientMessageId: string, replyTo?: string) =>
    request<{ message: Message; conversation: Conversation }>("/widget/conversation/attachments", {
      method: "POST",
      token,
      body: imageForm(file, clientMessageId, replyTo),
    }),

  image: (token: string, messageId: string) => image(`/widget/conversation/messages/${messageId}/attachment`, token),

  // The customer is typing (sent every few seconds while they do).
  typing: (token: string) => request<void>("/widget/conversation/typing", { method: "POST", token }),
};

// ---- Agent console ----

export type Priority = "NORMAL" | "HIGH" | "URGENT";
export type HandoffReason = "CUSTOMER_REQUEST" | "FALLBACK_LIMIT" | "URGENT" | "BOT_ERROR";

export interface Person {
  sub: string;
  name: string;
  // Answering chats right now (in the transfer list).
  online?: boolean;
}

export type PresenceStatus = "ONLINE" | "AWAY";

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
  attachmentsEnabled: boolean;
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
  attachmentsEnabled: boolean;
}

// Whether image storage works (GET /admin/storage).
export interface StorageStatus {
  ok: boolean;
  store: string;
  problem: string | null;
}

export interface AdminAgents {
  agents: { sub: string; name: string; email: string | null; online: boolean }[];
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

export function adminApi(getToken: () => string, refresh?: FreshToken) {
  const call = <T,>(method: string, path: string, body?: unknown) =>
    authed<T>(
      `/admin${path}`,
      { method, body: body === undefined ? undefined : JSON.stringify(body) },
      getToken,
      refresh,
    );
  const slug = (s: string) => encodeURIComponent(s);
  return {
    bots: () => call<{ items: string[] }>("GET", "/bots"),
    storage: () => call<StorageStatus>("GET", "/storage"),
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
  // Agents can send images in this company's chats.
  attachments: boolean;
  primaryColor: string | null;
}

// getToken returns the agent's current token, so a refreshed token is used without rebuilding anything.
export function agentApi(getToken: () => string, refresh?: FreshToken) {
  const get = <T,>(path: string) => authed<T>(`/agent${path}`, {}, getToken, refresh);
  const post = <T,>(path: string, body?: unknown) =>
    authed<T>(
      `/agent${path}`,
      { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) },
      getToken,
      refresh,
    );
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
    reply: (id: string, clientMessageId: string, body: string, replyTo?: string) =>
      post<{ message: Message }>(`/conversations/${id}/messages`, { clientMessageId, body, replyTo }),
    sendImage: (id: string, file: File, clientMessageId: string, replyTo?: string) =>
      authed<{ message: Message }>(
        `/agent/conversations/${id}/attachments`,
        { method: "POST", body: imageForm(file, clientMessageId, replyTo) },
        getToken,
        refresh,
      ),
    image: (id: string, messageId: string) =>
      image(`/agent/conversations/${id}/messages/${messageId}/attachment`, getToken(), refresh),
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
    typing: (id: string) => post<void>(`/conversations/${id}/typing`),
    // The console's check-in; with a status, also switches Online / Away.
    presence: (status?: PresenceStatus) => post<{ status: PresenceStatus }>("/presence", status ? { status } : undefined),
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
