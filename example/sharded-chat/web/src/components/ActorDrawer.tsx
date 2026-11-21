import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Avatar } from './Avatar.tsx';
import { roomLabel } from './ChatCanvas.tsx';
import type { Api } from '../lib/api.ts';
import { countsOf, historyOf, type Flow, type FlowState } from '../lib/chatFlow.ts';
import { nodeColour } from '../lib/nodes.ts';
import { SHARDS, shardOf } from '../lib/chatGraph.ts';
import { nameOf } from '../lib/persona.ts';

interface Props {
  api: Api;
  id: string;
  node: number;
  /** Whether rooms and users are sharded over several processes. */
  distributed: boolean;
  /** A user's rooms, or a room's members. */
  peers: readonly string[];
  flows: FlowState;
  /** Changes whenever messages arrive, so the list follows them. */
  tick: number;
  onSelect: (id: string) => void;
  onClose: () => void;
}

const isRoom = (id: string) => id.startsWith('chat-room-');

/**
 * The messages each actor kind handles (ChatUserActor, ChatRoomActor). The page can send the ones
 * marked `send`; the simulator and the other actors send the rest.
 */
export const ACCEPTS = {
  user: [
    { name: 'Direct', send: true, about: 'a message to this user alone; it answers true' },
    { name: 'Deliver', send: false, about: 'a post another member made in one of its rooms' },
    { name: 'Joined', send: false, about: 'it is now a member of a room' },
    { name: 'Left', send: false, about: 'it is no longer a member of a room' },
    { name: 'Pace', send: false, about: 'how often it posts' },
  ],
  room: [
    { name: 'Broadcast', send: true, about: 'a message to every joined user, never dropped' },
    { name: 'Post', send: false, about: 'a member posts; it forwards it to every other member' },
    { name: 'Join', send: false, about: 'a user joins' },
    { name: 'Leave', send: false, about: 'a user leaves' },
  ],
} as const;

const FIELD =
  'h-9 min-w-0 flex-1 rounded-md border border-log-line bg-log-hover px-2.5 text-[13px] text-log-ink outline-none placeholder:text-log-muted focus:border-log-muted';
const PRIMARY =
  'h-9 shrink-0 cursor-pointer rounded-md bg-log-ink px-4 text-[13px] font-semibold text-log-bg hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40';

/** Where the actor runs and why: its sharding entity type, entity id, shard and node. */
function Sharding({ id, node, distributed }: { id: string; node: number; distributed: boolean }) {
  const room = isRoom(id);
  const type = room ? 'ChatRoom' : 'ChatUser';
  const colour = `var(--color-${nodeColour(node)})`;
  const shard = shardOf(id);
  return (
    <section className="border-b border-log-line px-4 py-2 text-[12px] text-log-muted">
      <h3 className="mb-1 text-[11px] uppercase tracking-wide">Sharding</h3>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
        <dt>Entity type</dt>
        <dd className="font-mono text-log-ink">{type}</dd>
        <dt>Entity id</dt>
        <dd className="font-mono text-log-ink">{id}</dd>
        <dt>Shard</dt>
        <dd className="font-mono text-log-ink">
          {shard} <span className="font-sans text-log-muted">of {SHARDS}</span>
        </dd>
        <dt>Runs on</dt>
        <dd className="font-medium" style={{ color: colour }}>
          node {node}
        </dd>
      </dl>
      <details className="mt-1">
        <summary className="cursor-pointer select-none text-log-ink">Why node {node}?</summary>
        <p className="mt-1 leading-5">
          The id picks the shard: crc32("{id}") % {SHARDS} = {shard}. The first time a {type} lived in shard {shard}, the {type} shard
          coordinator put the shard on the node holding the fewest {type} shards{distributed ? '' : ' (here the only node)'}: node {node}.
          Messages to {id} go through sharding to that node from any node. If node {node} goes down, its shards move to nodes that are
          still up; the {room ? 'room' : 'user'} starts there on its next message and says Hello to the simulator, which sends it its
          settings and {room ? 'members' : 'rooms'} again.
        </p>
      </details>
    </section>
  );
}

/** Sends a Direct to a user, or a Broadcast to a room, from the page; the answer shows under the field. */
function Composer({ api, id, joined }: { api: Api; id: string; joined: number }) {
  const room = isRoom(id);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ ok: boolean; text: string } | null>(null);
  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const words = text.trim();
    if (!words) return;
    setBusy(true);
    setNote(null);
    try {
      const d = room ? await api.broadcastToRoom(id, words) : await api.sendToActor(id, words);
      setNote(
        room
          ? { ok: true, text: `Broadcast reached ${d.delivered.length} joined user${d.delivered.length === 1 ? '' : 's'}` }
          : d.replied
            ? { ok: true, text: `${nameOf(id)} answered true` }
            : { ok: false, text: `${nameOf(id)} did not answer in time` },
      );
      setText('');
    } catch (err) {
      setNote({ ok: false, text: err instanceof Error ? err.message : 'not sent' });
    } finally {
      setBusy(false);
    }
  };
  return (
    <form onSubmit={onSubmit} className="border-t border-log-line px-4 py-3">
      <label htmlFor={`compose-${id}`} className="mb-1.5 block text-[12px] text-log-muted">
        {room ? `Broadcast to the ${joined} joined user${joined === 1 ? '' : 's'}` : `Send ${nameOf(id).split(' ')[0]} a Direct message`}
      </label>
      <div className="flex gap-2">
        <input id={`compose-${id}`} value={text} onChange={(e) => setText(e.target.value)} maxLength={200} placeholder="Message" className={FIELD} />
        <button type="submit" disabled={busy || !text.trim()} className={PRIMARY}>
          {room ? 'Broadcast' : 'Send'}
        </button>
      </div>
      {note && <p className={`mt-1.5 text-[12px] ${note.ok ? 'text-log-muted' : 'text-log-error'}`}>{note.text}</p>}
    </form>
  );
}
const label = (id: string) => (id === 'page' ? 'you' : isRoom(id) ? roomLabel(id) : nameOf(id));

function Who({ id, onSelect }: { id: string; onSelect: (id: string) => void }) {
  if (id === 'page') return <span className="shrink-0 rounded-full bg-manual px-1.5 text-[10px] font-bold leading-4 text-log-bg">You</span>;
  return (
    <button
      type="button"
      onClick={() => onSelect(id)}
      className="inline-flex cursor-pointer items-center gap-1 rounded px-1 py-0.5 text-[12px] font-medium text-log-ink hover:bg-log-hover"
    >
      {isRoom(id) ? <span className="text-log-muted">#</span> : <Avatar userId={id} size={16} />}
      {isRoom(id) ? roomLabel(id).slice(1) : nameOf(id)}
    </button>
  );
}

function ago(at: number, now: number): string {
  const s = Math.max(0, Math.round((now - at) / 1000));
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m`;
}

function Row({ me, flow, now, onSelect }: { me: string; flow: Flow; now: number; onSelect: (id: string) => void }) {
  const sent = flow.sender === me;
  const other = sent ? flow.receiver : flow.sender;
  const reply = flow.kind === 'reply';
  return (
    <li
      data-manual={flow.manual || undefined}
      className={`flex items-start gap-2 px-3 py-1.5 ${reply ? 'opacity-60' : ''} ${flow.manual ? 'border-l-2 border-manual bg-log-hover' : 'border-l-2 border-transparent'}`}
    >
      <span aria-label={sent ? 'sent' : 'received'} className={`mt-0.5 w-4 shrink-0 text-center ${sent ? 'text-log-ink' : 'text-log-muted'}`}>
        {sent ? '→' : '←'}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1 text-[12px]">
          <Who id={other} onSelect={onSelect} />
          <span className="text-log-muted">{flow.type}</span>
          {flow.author && flow.kind === 'deliver' && flow.author !== other && (flow.manual ? (
            <span className="flex items-center gap-1 text-log-muted">from {<span className="shrink-0 rounded-full bg-manual px-1.5 text-[10px] font-bold leading-4 text-log-bg">You</span>}</span>
          ) : (
            <span className="truncate text-log-muted">from {label(flow.author)}</span>
          ))}
          <span className="ml-auto shrink-0 text-[11px] text-log-muted" title={new Date(Date.now() - (now - flow.at)).toLocaleTimeString()}>
            {ago(flow.at, now)}
          </span>
        </div>
        {!reply && <p className={`mt-0.5 break-words text-[13px] ${flow.manual ? 'font-semibold text-manual' : 'text-log-ink'}`}>{flow.text}</p>}
      </div>
    </li>
  );
}

/**
 * The right drawer for one actor: who it is, the process it runs on, its rooms (a user) or members
 * (a room), and every message it sent or received since the page loaded, live, newest at the bottom.
 * Kept in the page's memory only. A name in a row opens that actor instead.
 */
export function ActorDrawer({ api, id, node, distributed, peers, flows, tick, onSelect, onClose }: Props) {
  const list = historyOf(flows, id);
  const now = performance.now();
  const counts = countsOf(flows, id, now);
  const scroller = useRef<HTMLOListElement>(null);
  const [follow, setFollow] = useState(true);

  useEffect(() => {
    const el = scroller.current;
    if (el && follow) el.scrollTop = el.scrollHeight;
  }, [tick, id, follow]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const room = isRoom(id);
  return (
    <aside
      role="dialog"
      aria-label={label(id)}
      className="fixed right-0 top-0 z-30 flex w-full max-w-[380px] flex-col border-l border-log-line bg-log-bg text-log-ink shadow-2xl"
      style={{ bottom: 'var(--console-h)' }}
    >
      <header className="flex items-center gap-3 border-b border-log-line px-4 py-3">
        {room ? (
          <span className="flex h-10 w-10 items-center justify-center rounded-lg border-2 text-lg font-bold" style={{ borderColor: `var(--color-${nodeColour(node)})`, color: `var(--color-${nodeColour(node)})` }}>
            #
          </span>
        ) : (
          <span className="rounded-full p-0.5" style={{ boxShadow: `0 0 0 2px var(--color-${nodeColour(node)})` }}>
            <Avatar userId={id} size={40} />
          </span>
        )}
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-[15px] font-semibold">{label(id)}</h2>
          <p className="text-[12px] text-log-muted">
            {room ? 'ChatRoomActor' : 'ChatUserActor'} · <span style={{ color: `var(--color-${nodeColour(node)})` }}>node {node}</span> · {id}
          </p>
        </div>
        <button type="button" onClick={onClose} aria-label="Close" className="h-8 w-8 cursor-pointer rounded text-log-muted hover:bg-log-hover hover:text-log-ink">
          ✕
        </button>
      </header>
      <section className="border-b border-log-line px-4 py-2">
        <h3 className="mb-1 text-[11px] uppercase tracking-wide text-log-muted">{room ? `Joined users · ${peers.length}` : `Rooms joined · ${peers.length}`}</h3>
        <div className="flex max-h-24 flex-wrap gap-1 overflow-y-auto">
          {peers.length === 0 && <span className="text-[12px] text-log-muted">none</span>}
          {peers.map((p) => (
            <Who key={p} id={p} onSelect={onSelect} />
          ))}
        </div>
      </section>
      <Sharding id={id} node={node} distributed={distributed} />
      <section className="border-b border-log-line px-4 py-2">
        <h3 className="mb-1 text-[11px] uppercase tracking-wide text-log-muted">Messages it handles</h3>
        <ul className="flex flex-wrap gap-1">
          {ACCEPTS[room ? 'room' : 'user'].map((m) => (
            <li
              key={m.name}
              title={m.about}
              className={`rounded-full border px-2 text-[11px] leading-5 ${m.send ? 'border-log-ink text-log-ink' : 'border-log-line text-log-muted'}`}
            >
              {m.name}
              {m.send && ' · you can send'}
            </li>
          ))}
        </ul>
      </section>
      <div className="flex items-center gap-3 px-4 py-2 text-[12px] text-log-muted">
        <span className="font-medium text-log-ink">Messages</span>
        <span>
          last minute: {counts.sent} sent · {counts.received} received
        </span>
        <label className="ml-auto flex cursor-pointer items-center gap-1">
          <input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} />
          Auto-scroll
        </label>
      </div>
      <ol ref={scroller} className="min-h-0 flex-1 overflow-y-auto pb-2" aria-live="polite">
        {list.length === 0 && <li className="px-4 py-6 text-center text-[13px] text-log-muted">No messages yet. They appear here as {label(id)} sends or receives them.</li>}
        {list.map((f, i) => (
          <Row key={`${f.at}-${i}`} me={id} flow={f} now={now} onSelect={onSelect} />
        ))}
      </ol>
      <Composer api={api} id={id} joined={peers.length} />
    </aside>
  );
}
