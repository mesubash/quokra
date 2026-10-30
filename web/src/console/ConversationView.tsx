import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import {
  ApiError,
  type AgentApi,
  type AgentConversation,
  type ConversationEvent,
  type Me,
  type Message,
  type Note,
  type Person,
} from "../lib/api";
import type { Socket } from "../lib/realtime";
import { BackIcon, CloseIcon, ExpandIcon, MoreIcon, PanelIcon } from "../lib/icons";
import { Composer } from "../chat/Composer";
import { MessageList, type TimelineItem } from "../chat/MessageList";
import { mergeMessages } from "../chat/messages";
import { dateTime, eventLabel, initials, priorityLabel, reasonLabel } from "./format";

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
  onChanged: () => void;
  onError: (e: unknown) => void;
}

const STATUS_LABEL = { BOT: "With the bot", WAITING: "Waiting", HUMAN: "Open", RESOLVED: "Resolved" };

type Mode = "reply" | "note";
type Dialog = "transfer" | "block" | null;

export function ConversationView({ id, api, me, socket, onBack, onClose, onExpand, onChanged, onError }: Props) {
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
          <span className="avatar avatar-customer">{initials(customer)}</span>
          <div className="conversation-title">
            <h2>{customer}</h2>
            <p className="muted">
              <span className={`dot-status status-${status.toLowerCase()}`} />
              {STATUS_LABEL[status]}
              {conversation.assignee && !mine && ` · with ${conversation.assignee.name}`}
              {mine && status === "HUMAN" && " · assigned to you"}
            </p>
          </div>
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
            placeholder={writing === "note" ? "Only your team sees this…" : `Reply to ${customer}…`}
          />
        </div>
      </div>

      <Details
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
  conversation: c,
  events,
  busy,
  onUnblock,
  onClose,
}: {
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
        <strong>Details</strong>
        <button type="button" className="link-button" onClick={onClose}>
          Hide
        </button>
      </header>
      <section className="details-contact">
        <span className="avatar avatar-customer avatar-lg">{initials(contact?.name ?? contact?.email ?? null)}</span>
        <strong>{contact?.name ?? "Guest"}</strong>
        {contact?.email ? <a href={`mailto:${contact.email}`}>{contact.email}</a> : <span className="muted">No email yet</span>}
        {contact?.blocked && (
          <span className="blocked">
            <span className="badge badge-urgent">Blocked</span>
            <button
              type="button"
              className="link-button"
              disabled={busy}
              onClick={() => contact.contactId && onUnblock(contact.contactId)}
            >
              Unblock
            </button>
          </span>
        )}
      </section>
      <dl className="details-list">
        <dt>Status</dt>
        <dd>{STATUS_LABEL[c.status]}</dd>
        <dt>Priority</dt>
        <dd>{priorityLabel(c.priority)}</dd>
        {c.handoffReason && (
          <>
            <dt>Reason</dt>
            <dd>{reasonLabel(c.handoffReason)}</dd>
          </>
        )}
        <dt>Assigned</dt>
        <dd>{c.assignee?.name ?? "No one"}</dd>
        <dt>Started</dt>
        <dd>{dateTime(c.createdAt)}</dd>
        <dt>Site</dt>
        <dd>{c.tenantSlug}</dd>
      </dl>
      <section className="details-activity">
        <h3>Activity</h3>
        <ol className="activity">
          {events.map((e) => (
            <li key={e.id}>
              <span>{eventLabel(e.type, e.actorName, e.reason)}</span>
              <time className="muted" dateTime={e.createdAt}>
                {dateTime(e.createdAt)}
              </time>
            </li>
          ))}
        </ol>
      </section>
    </aside>
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
