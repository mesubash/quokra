import { useCallback, useEffect, useRef, useState } from "react";
import type { AgentApi, ConversationSummary, ListStatus, Priority, TenantItem } from "../lib/api";
import type { Activity, QueueSnapshot } from "../lib/realtime";

export type Tab = "queue" | "mine" | "resolved";

const STATUS: Record<Tab, ListStatus> = { queue: "WAITING", mine: "HUMAN", resolved: "RESOLVED" };
const RANK: Record<Priority, number> = { URGENT: 0, HIGH: 1, NORMAL: 2 };

// Same order as the server: the queue by priority then longest wait, the rest newest first.
export function compare(tab: Tab, a: ConversationSummary, b: ConversationSummary) {
  if (tab === "queue") {
    return RANK[a.priority] - RANK[b.priority] || (a.waitingSince ?? "").localeCompare(b.waitingSince ?? "");
  }
  return b.lastMessageAt.localeCompare(a.lastMessageAt);
}

export function belongs(tab: Tab, s: { status: string; assignee: { sub: string } | null }, meSub: string) {
  if (tab === "mine") return s.status === "HUMAN" && s.assignee?.sub === meSub;
  return s.status === STATUS[tab];
}

// Puts a live change into the list: updates the row, adds it, or drops it if it no longer belongs here.
export function applySnapshot(
  items: ConversationSummary[],
  tab: Tab,
  snapshot: QueueSnapshot,
  meSub: string,
  tenantSlug: string,
): ConversationSummary[] {
  const rest = items.filter((c) => c.id !== snapshot.id);
  if (!belongs(tab, snapshot, meSub)) return rest.length === items.length ? items : rest;
  const old = items.find((c) => c.id === snapshot.id);
  const row: ConversationSummary = {
    id: snapshot.id,
    tenantSlug,
    status: snapshot.status,
    priority: snapshot.priority,
    handoffReason: snapshot.handoffReason,
    customerName: snapshot.customerName,
    lastMessagePreview: old?.lastMessagePreview ?? "",
    lastMessageAt: snapshot.lastMessageAt,
    waitingSince: snapshot.waitingSince,
    assignee: snapshot.assignee,
    unreadCount: old?.unreadCount ?? 0,
  };
  return [...rest, row].sort((a, b) => compare(tab, a, b));
}

// A new message: update the row's preview and time, and count it as unread unless the agent has it open.
export function applyActivity(
  items: ConversationSummary[],
  tab: Tab,
  activity: Activity,
  openId: string | null,
): ConversationSummary[] {
  const old = items.find((c) => c.id === activity.id);
  if (!old) return items;
  const unread = activity.senderType === "CUSTOMER" && activity.id !== openId;
  const row = {
    ...old,
    lastMessagePreview: activity.lastMessagePreview,
    lastMessageAt: activity.lastMessageAt,
    unreadCount: old.unreadCount + (unread ? 1 : 0),
  };
  return items.map((c) => (c.id === row.id ? row : c)).sort((a, b) => compare(tab, a, b));
}

// One page at a time; live queue events patch the rows in place instead of reloading the list.
// search: only conversations matching it (name, email or a message); "" for all.
export function useConversationList(
  api: AgentApi,
  tab: Tab,
  meSub: string | null,
  tenants: TenantItem[],
  search = "",
) {
  const [items, setItems] = useState<ConversationSummary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const generation = useRef(0);

  const refresh = useCallback(() => {
    const run = ++generation.current;
    setLoading(true);
    api
      .list(STATUS[tab], { mine: tab === "mine", q: search })
      .then((page) => {
        if (run !== generation.current) return;
        setItems(page.items);
        setCursor(page.nextCursor);
        setError(null);
      })
      .catch((e) => run === generation.current && setError(e))
      .finally(() => run === generation.current && setLoading(false));
  }, [api, tab, search]);

  useEffect(() => {
    setItems([]);
    setCursor(null);
    refresh();
  }, [refresh]);

  const loadMore = useCallback(() => {
    if (!cursor || loading) return;
    const run = generation.current;
    setLoading(true);
    api
      .list(STATUS[tab], { mine: tab === "mine", cursor, q: search })
      .then((page) => {
        if (run !== generation.current) return;
        // A row can already be here if it arrived live; keep one copy.
        setItems((current) => {
          const known = new Set(current.map((c) => c.id));
          return [...current, ...page.items.filter((c) => !known.has(c.id))];
        });
        setCursor(page.nextCursor);
      })
      .catch((e) => run === generation.current && setError(e))
      .finally(() => run === generation.current && setLoading(false));
  }, [api, tab, cursor, loading, search]);

  const apply = useCallback(
    (snapshot: QueueSnapshot) => {
      if (!meSub) return;
      const slug = tenants.find((t) => t.id === snapshot.tenantId)?.slug ?? "";
      setItems((current) => {
        // While searching, a live change can't tell whether a new row would match: only update shown rows.
        if (search && !current.some((c) => c.id === snapshot.id)) return current;
        return applySnapshot(current, tab, snapshot, meSub, slug);
      });
    },
    [tab, meSub, tenants, search],
  );

  const activity = useCallback(
    (event: Activity, openId: string | null) => setItems((current) => applyActivity(current, tab, event, openId)),
    [tab],
  );

  const markSeen = useCallback(
    (id: string) => setItems((current) => current.map((c) => (c.id === id && c.unreadCount ? { ...c, unreadCount: 0 } : c))),
    [],
  );

  return { items, hasMore: cursor !== null, loading, error, refresh, loadMore, apply, activity, markSeen };
}
