import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import {
  ApiError,
  type AgentApi,
  type AgentConversation,
  type ConversationEvent,
  type ConversationSummary,
  type Me,
  type Message,
  type Note,
  type Person,
  type SavedReply,
} from "../lib/api";
import type { Socket } from "../lib/realtime";
import { BackIcon, CheckIcon, CloseIcon, CopyIcon, ExpandIcon, MoreIcon, PanelIcon } from "../lib/icons";
import { Composer } from "../chat/Composer";
import { MessageList, type TimelineItem } from "../chat/MessageList";
import { mergeMessages } from "../chat/messages";
import { dateTime, eventLabel, eventTone, initials, priorityLabel, reasonLabel, shortTime } from "./format";

interface Props {
  id: string;
  api: AgentApi;
  me: Me;
  socket: Socket;
  // Narrow screens show one pane at a time; this goes back to the list.
  onBack: () => void;
  // In the dashboard drawer: close the whole console.
  onClose?: () => void;
  onExpand?: () => void;
  // Opens another conversation, e.g. one from the customer's history.
  onOpen?: (id: string) => void;
  onChanged: () => void;
  onError: (e: unknown) => void;
}

const STATUS_LABEL = { BOT: "With the bot", WAITING: "Waiting", HUMAN: "Open", RESOLVED: "Resolved" };

type Mode = "reply" | "note";
type Dialog = "transfer" | "block" | null;

export function ConversationView({ id, api, me, socket, onBack, onClose, onExpand, onOpen, onChanged, onError }: Props) {
  const [conversation, setConversation] = useState<AgentConversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [notes, setNotes] = useState<Note[]>([]);
  const [events, setEvents] = useState<ConversationEvent[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<Mode>("reply");
  const [details, setDetails] = useState(false);
  const [menu, setMenu] = useState(false);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [saved, setSaved] = useState<SavedReply[]>([]);

  const load = useCallback(() => {
    api
      .detail(id)
      .then((d) => {
        setConversation(d.conversation);
        setMessages((current) => mergeMessages(current, d.messages));
        setNotes(d.notes);
        setEvents(d.events);
      })
      .catch(onError);
  }, [api, id, onError]);

  useEffect(load, [load]);

  useEffect(() => {
    api
      .replies(id)
      .then((page) => setSaved(page.items))
      .catch(() => setSaved([]));
  }, [api, id]);

  // Messages and notes arrive live; a status change reloads the agent's view of the conversation.
  useEffect(() => {
    const stopChat = socket.subscribe(`/topic/conversations/${id}`, (event) => {
      if (event.type === "message.created") setMessages((current) => mergeMessages(current, [event.data]));
      else load();
    });
    const stopNotes = socket.subscribe(`/topic/conversations/${id}/notes`, (event) => {
      if (event.type === "note.created") setNotes((current) => addNote(current, event.data));
    });
    return () => {
      stopChat();
      stopNotes();
    };
  }, [socket, id, load]);

  // What the agent has on screen counts as read, including messages that arrive while it's open.
  const lastId = messages.length > 0 ? messages[messages.length - 1].id : null;
  useEffect(() => {
    if (lastId) api.markRead(id, lastId).catch(() => {});
  }, [api, id, lastId]);

  async function act(action: () => Promise<{ conversation: AgentConversation }>) {
    setBusy(true);
    setNotice(null);
    setMenu(false);
    setDialog(null);
    try {
      setConversation((await action()).conversation);
      load();
      onChanged();
      return true;
    } catch (e) {
      explain(e);
      load();
      onChanged();
      return false;
    } finally {
      setBusy(false);
    }
  }

  function explain(e: unknown) {
    if (e instanceof ApiError && e.code === "ALREADY_ACCEPTED") setNotice("Someone else already took this conversation.");
    else if (e instanceof ApiError && e.code === "CONTACT_BLOCKED") setNotice("This contact is blocked. Unblock them first.");
    else if (e instanceof ApiError && (e.status === 409 || e.status === 403)) setNotice("This conversation has changed. Showing the latest.");
    else onError(e);
  }

  // Replying to a waiting chat takes it first, so one click does both.
  async function reply(body: string) {
    if (conversation?.status === "WAITING" && !(await act(() => api.accept(id)))) return;
    try {
      const { message } = await api.reply(id, crypto.randomUUID(), body);
      setMessages((current) => mergeMessages(current, [message]));
    } catch (e) {
      setNotice("Couldn't send. Try again.");
      if (!(e instanceof ApiError) || e.status === 401) onError(e);
    }
  }

  async function addPrivateNote(body: string) {
    try {
      const { note } = await api.note(id, body);
      setNotes((current) => addNote(current, note));
    } catch (e) {
      setNotice("Couldn't save the note. Try again.");
      if (!(e instanceof ApiError) || e.status === 401) onError(e);
    }
  }

  async function unblock(contactId: string) {
    setBusy(true);
    try {
      await api.unblock(contactId);
      load();
    } catch (e) {
      explain(e);
    } finally {
      setBusy(false);
    }
  }

  if (!conversation) {
    return (
      <div className="console-empty">
        <p className="muted">Loading…</p>
      </div>
    );
  }

  const { status } = conversation;
  const mine = conversation.assignee?.sub === me.sub;
  const customer = conversation.customer?.name ?? conversation.customer?.email ?? "Guest";
  const canReply = status === "WAITING" || (status === "HUMAN" && mine);
  const canBlock = !!conversation.customer?.contactId && !conversation.customer.blocked && (status !== "HUMAN" || mine);
  const writing: Mode = canReply ? mode : "note";
  const timeline: TimelineItem[] = [...messages, ...notes.map(noteItem)].sort((a, b) => (a.id < b.id ? -1 : 1));

  const actions = [
    status === "HUMAN" && mine && { label: "Transfer…", run: () => (setMenu(false), setDialog("transfer")) },
    status === "HUMAN" && mine && { label: "Return to bot", run: () => act(() => api.returnToBot(id)) },
    (status === "WAITING" || (status === "HUMAN" && mine)) && { label: "Resolve", run: () => act(() => api.resolve(id)) },
    canBlock && { label: "Block contact…", danger: true, run: () => (setMenu(false), setDialog("block")) },
  ].filter(Boolean) as MenuItem[];

  return (
    <div className={`conversation ${details ? "show-details" : ""}`}>
      <div className="conversation-main">
        <header className="conversation-header">
          <button type="button" className="icon-button quiet back" aria-label="Back to the list" onClick={onBack}>
            <BackIcon size={20} />
          </button>
          {/* The customer's name opens their details, like "view profile". */}
          <button
            type="button"
            className="conversation-who"
            aria-label={`${customer}: show details`}
            aria-expanded={details}
            onClick={() => setDetails(true)}
          >
            <span className="avatar avatar-customer">{initials(customer)}</span>
            <span className="conversation-title">
              <span className="conversation-name">{customer}</span>
              <span className={`conversation-sub status-text-${status.toLowerCase()}`}>
                <span className={`dot-status status-${status.toLowerCase()}`} />
                <span>
                  {STATUS_LABEL[status]}
                  {conversation.assignee && !mine && ` · with ${conversation.assignee.name}`}
                  {mine && status === "HUMAN" && " · assigned to you"}
                </span>
                {conversation.customer?.email && conversation.customer.email !== customer && (
                  <span className="conversation-email">· {conversation.customer.email}</span>
                )}
              </span>
            </span>
          </button>
          <div className="conversation-actions">
            {status === "WAITING" && (
              <button type="button" className="button" disabled={busy} onClick={() => act(() => api.accept(id))}>
                Accept
              </button>
            )}
            {status === "RESOLVED" && (
              <button type="button" className="button button-quiet" disabled={busy} onClick={() => act(() => api.reopen(id))}>
                Reopen
              </button>
            )}
            {actions.length > 0 && <Menu open={menu} onToggle={setMenu} items={actions} disabled={busy} />}
            <button
              type="button"
              className={`icon-button quiet ${details ? "on" : ""}`}
              aria-label="Details"
              aria-pressed={details}
              onClick={() => setDetails((d) => !d)}
            >
              <PanelIcon size={20} />
            </button>
            {onExpand && (
              <button type="button" className="icon-button quiet" aria-label="Open full view" title="Open full view" onClick={onExpand}>
                <ExpandIcon size={17} />
              </button>
            )}
            {onClose && (
              <button type="button" className="icon-button quiet" aria-label="Close console" onClick={onClose}>
                <CloseIcon size={18} />
              </button>
            )}
          </div>
        </header>
        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        <MessageList
          messages={timeline}
          options={[]}
          onOption={() => {}}
          disabled
          botName="Bot"
          mine="AGENT"
          customerName={customer}
        />
        <div className="console-composer">
          {canReply ? (
            <div className="composer-modes" role="tablist" aria-label="Write">
              <button type="button" role="tab" aria-selected={writing === "reply"} onClick={() => setMode("reply")}>
                Reply
              </button>
              <button type="button" role="tab" aria-selected={writing === "note"} onClick={() => setMode("note")}>
                Private note
              </button>
              {status === "WAITING" && writing === "reply" && (
                <span className="muted composer-hint">Sending assigns this chat to you</span>
              )}
            </div>
          ) : (
            <p className="composer-hint-line muted">{readOnlyHint(conversation, mine)}</p>
          )}
          <Composer
            key={writing}
            onSend={(body) => void (writing === "note" ? addPrivateNote(body) : reply(body))}
            disabled={busy}
            focus
            note={writing === "note"}
            replies={writing === "reply" ? fillName(saved, conversation.customer?.name) : []}
            placeholder={writing === "note" ? "Only your team sees this…" : `Reply to ${customer}…`}
          />
        </div>
      </div>

      <Details
        api={api}
        onOpen={onOpen}
        conversation={conversation}
        events={events}
        busy={busy}
        onUnblock={unblock}
        onClose={() => setDetails(false)}
      />

      {dialog === "transfer" && (
        <TransferDialog
          api={api}
          id={id}
          meSub={me.sub}
          onPick={(sub) => void act(() => api.transfer(id, sub))}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === "block" && (
        <ConfirmDialog
          title={`Block ${customer}?`}
          body="The chat closes, and they can't message you again with this email until someone unblocks them."
          confirm="Block"
          onConfirm={() => void act(() => api.block(id))}
          onClose={() => setDialog(null)}
        />
      )}
    </div>
  );
}

// {name} becomes the customer's name, or "there" for guests who didn't give one.
export function fillName(replies: SavedReply[], name: string | null | undefined) {
  return replies.map((r) => ({ title: r.title, body: r.body.replaceAll("{name}", name?.trim() || "there") }));
}

function readOnlyHint(c: AgentConversation, mine: boolean) {
  if (c.status === "RESOLVED") return "This chat is closed. Reopen it to reply, or leave a private note.";
  if (c.status === "HUMAN" && !mine) return `${c.assignee?.name ?? "Someone"} is handling this chat. You can leave a private note.`;
  return "You can leave a private note.";
}

function addNote(current: Note[], note: Note) {
  return current.some((n) => n.id === note.id) ? current : [...current, note];
}

function noteItem(n: Note): TimelineItem {
  return {
    id: n.id,
    senderType: "NOTE",
    senderName: n.author.name,
    body: n.body,
    options: null,
    clientMessageId: null,
    createdAt: n.createdAt,
  };
}

interface MenuItem {
  label: string;
  danger?: boolean;
  run: () => void;
}

function Menu({
  open,
  onToggle,
  items,
  disabled,
}: {
  open: boolean;
  onToggle: (open: boolean) => void;
  items: MenuItem[];
  disabled: boolean;
}) {
  const root = useRef<HTMLDivElement>(null);

  // Closes on a click outside or Escape, like any menu.
  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => !root.current?.contains(e.target as Node) && onToggle(false);
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onToggle(false);
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [open, onToggle]);

  return (
    <div className="menu" ref={root}>
      <button
        type="button"
        className="icon-button quiet"
        aria-label="More actions"
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => onToggle(!open)}
      >
        <MoreIcon size={20} />
      </button>
      {open && (
        <div className="menu-list" role="menu">
          {items.map((item) => (
            <button key={item.label} type="button" role="menuitem" className={item.danger ? "danger" : ""} onClick={item.run}>
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function Details({
  api,
  onOpen,
  conversation: c,
  events,
  busy,
  onUnblock,
  onClose,
}: {
  api: AgentApi;
  onOpen?: (id: string) => void;
  conversation: AgentConversation;
  events: ConversationEvent[];
  busy: boolean;
  onUnblock: (contactId: string) => void;
  onClose: () => void;
}) {
  const contact = c.customer;
  return (
    <aside className="details" aria-label="Details">
      <header className="details-header">
        <h2>Details</h2>
        <button type="button" className="icon-button quiet" aria-label="Hide details" onClick={onClose}>
          <CloseIcon size={18} />
        </button>
      </header>

      <section className="details-card details-contact">
        <span className="avatar avatar-customer avatar-lg">{initials(contact?.name ?? contact?.email ?? null)}</span>
        <div className="details-contact-text">
          <strong>{contact?.name ?? "Guest"}</strong>
          {contact?.email ? <CopyEmail email={contact.email} /> : <span className="muted">No email yet</span>}
        </div>
        {contact?.blocked && (
          <div className="details-blocked">
            <span className="badge badge-urgent">Blocked</span>
            <button
              type="button"
              className="link-button"
              disabled={busy}
              onClick={() => contact.contactId && onUnblock(contact.contactId)}
            >
              Unblock
            </button>
          </div>
        )}
      </section>

      <section className="details-section">
        <h3>Conversation</h3>
        <dl className="details-list">
          <dt>Status</dt>
          <dd>
            <span className={`badge status-${c.status.toLowerCase()}`}>{STATUS_LABEL[c.status]}</span>
          </dd>
          <dt>Priority</dt>
          <dd>
            {c.priority === "NORMAL" ? (
              "Normal"
            ) : (
              <span className={`badge badge-${c.priority.toLowerCase()}`}>{priorityLabel(c.priority)}</span>
            )}
          </dd>
          {c.handoffReason && (
            <>
              <dt>Reason</dt>
              <dd>{reasonLabel(c.handoffReason)}</dd>
            </>
          )}
          <dt>Assigned</dt>
          <dd>
            {c.assignee ? (
              <span className="details-person">
                <span className="avatar avatar-agent avatar-xs">{initials(c.assignee.name)}</span>
                {c.assignee.name}
              </span>
            ) : (
              <span className="muted">No one yet</span>
            )}
          </dd>
          <dt>Started</dt>
          <dd title={dateTime(c.createdAt)}>{shortTime(c.createdAt)}</dd>
          <dt>Site</dt>
          <dd>
            <code>{c.tenantSlug}</code>
          </dd>
        </dl>
      </section>

      <History api={api} conversationId={c.id} known={!!contact} onOpen={onOpen} />

      <section className="details-section">
        <h3>Activity</h3>
        <ol className="timeline">
          {events.map((e) => (
            <li key={e.id} className={`timeline-item tone-${eventTone(e.type)}`}>
              <span className="timeline-dot" aria-hidden="true" />
              <span className="timeline-text">{eventLabel(e.type, e.actorName, e.reason)}</span>
              <time dateTime={e.createdAt} title={dateTime(e.createdAt)}>
                {shortTime(e.createdAt)}
              </time>
            </li>
          ))}
        </ol>
      </section>
    </aside>
  );
}

// The same customer's earlier chats. Guests who never left an email have none we can find.
function History({
  api,
  conversationId,
  known,
  onOpen,
}: {
  api: AgentApi;
  conversationId: string;
  known: boolean;
  onOpen?: (id: string) => void;
}) {
  const [items, setItems] = useState<ConversationSummary[] | null>(null);

  useEffect(() => {
    if (!known) return;
    let cancelled = false;
    api
      .history(conversationId)
      .then((page) => !cancelled && setItems(page.items))
      .catch(() => !cancelled && setItems([]));
    return () => {
      cancelled = true;
    };
  }, [api, conversationId, known]);

  if (!known) return null;
  return (
    <section className="details-section">
      <h3>Previous conversations</h3>
      {items === null && <p className="muted details-note">Loading…</p>}
      {items?.length === 0 && <p className="muted details-note">This is their first conversation.</p>}
      {items && items.length > 0 && (
        <ul className="history">
          {items.map((h) => (
            <li key={h.id}>
              <button type="button" className="history-item" onClick={() => onOpen?.(h.id)} disabled={!onOpen}>
                <span className="history-top">
                  <span className={`badge status-${h.status.toLowerCase()}`}>{STATUS_LABEL[h.status]}</span>
                  <time dateTime={h.lastMessageAt} title={dateTime(h.lastMessageAt)}>
                    {shortTime(h.lastMessageAt)}
                  </time>
                </span>
                {h.lastMessagePreview && <span className="history-preview">{h.lastMessagePreview}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// The email, with a button that copies it.
function CopyEmail({ email }: { email: string }) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 1500);
    return () => clearTimeout(timer);
  }, [copied]);

  return (
    <span className="details-email">
      <a href={`mailto:${email}`}>{email}</a>
      <button
        type="button"
        className="icon-button quiet copy-button"
        aria-label={copied ? "Copied" : "Copy email"}
        title={copied ? "Copied" : "Copy email"}
        onClick={() => navigator.clipboard?.writeText(email).then(() => setCopied(true), () => {})}
      >
        {copied ? <CheckIcon size={14} /> : <CopyIcon size={14} />}
      </button>
    </span>
  );
}

// A native <dialog>: it traps focus and closes on Escape by itself.
function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal?.();
  }, []);
  return (
    <dialog ref={ref} className="modal" onClose={onClose} aria-label={title}>
      <h2>{title}</h2>
      {children}
    </dialog>
  );
}

function ConfirmDialog(props: { title: string; body: string; confirm: string; onConfirm: () => void; onClose: () => void }) {
  return (
    <Modal title={props.title} onClose={props.onClose}>
      <p className="muted">{props.body}</p>
      <div className="modal-actions">
        <button type="button" className="button button-quiet" onClick={props.onClose}>
          Cancel
        </button>
        <button type="button" className="button button-danger" onClick={props.onConfirm}>
          {props.confirm}
        </button>
      </div>
    </Modal>
  );
}

function TransferDialog({
  api,
  id,
  meSub,
  onPick,
  onClose,
}: {
  api: AgentApi;
  id: string;
  meSub: string;
  onPick: (agentSub: string | null) => void;
  onClose: () => void;
}) {
  const [agents, setAgents] = useState<Person[] | null>(null);

  useEffect(() => {
    api
      .agents(id)
      .then((page) => setAgents(page.items.filter((a) => a.sub !== meSub)))
      .catch(() => setAgents([]));
  }, [api, id, meSub]);

  return (
    <Modal title="Transfer chat" onClose={onClose}>
      <ul className="pick-list">
        <li>
          <button type="button" onClick={() => onPick(null)}>
            <span className="pick-text">
              <strong>Back to the queue</strong>
              <span className="muted">Anyone on the team can take it</span>
            </span>
          </button>
        </li>
        {agents === null && <li className="muted pick-note">Loading…</li>}
        {agents?.length === 0 && <li className="muted pick-note">No one else works on this site yet.</li>}
        {agents?.map((a) => (
          <li key={a.sub}>
            <button type="button" onClick={() => onPick(a.sub)}>
              <span className="avatar avatar-agent">{initials(a.name)}</span>
              <strong>{a.name}</strong>
            </button>
          </li>
        ))}
      </ul>
      <div className="modal-actions">
        <button type="button" className="button button-quiet" onClick={onClose}>
          Cancel
        </button>
      </div>
    </Modal>
  );
}
