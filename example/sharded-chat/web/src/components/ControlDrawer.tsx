import { useEffect, useId, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { Avatar } from './Avatar.tsx';
import { roomLabel } from './ChatCanvas.tsx';
import type { Api, ChatSettings } from '../lib/api.ts';
import { nodeColour } from '../lib/nodes.ts';
import { SHARDS, nextNode, shardOf } from '../lib/chatGraph.ts';
import { bareId } from '../lib/ids.ts';
import type { DeployState } from '../lib/api.ts';
import { BlueGreenPanel } from './BlueGreenPanel.tsx';
import { BUTTON, INPUT, PRIMARY } from './ui.ts';
import { nameOf } from '../lib/persona.ts';

type SettingField = 'rooms' | 'users' | 'messageRate';

const FIELDS: { field: SettingField; label: string; route: 'rooms' | 'users' | 'rate'; min: number; max: number; step: number; hint: string }[] = [
  { field: 'rooms', label: 'Chat rooms', route: 'rooms', min: 0, max: 50, step: 1, hint: '0 to 50' },
  { field: 'users', label: 'Users', route: 'users', min: 0, max: 1000, step: 1, hint: '0 to 1000' },
  { field: 'messageRate', label: 'Messages / second', route: 'rate', min: 0, max: 100, step: 0.5, hint: 'all users together, 0 to 100' },
];


export interface Target {
  id: string;
  node: number;
}

interface PickerProps {
  rooms: readonly Target[];
  users: readonly Target[];
  memberCount: (room: string) => number;
  value: string;
  onChange: (id: string) => void;
}

const isRoom = (id: string) => id.startsWith('chat-room-');

/** The send target: a button showing the pick, and a searchable list grouped into users and rooms. */
export function TargetPicker({ rooms, users, memberCount, value, onChange }: PickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const listId = useId();
  const box = useRef<HTMLDivElement>(null);
  const search = useRef<HTMLInputElement>(null);

  const q = query.trim().toLowerCase();
  const match = (id: string) => !q || id.toLowerCase().includes(q) || (isRoom(id) ? roomLabel(id) : nameOf(id)).toLowerCase().includes(q);
  const groups = useMemo(
    () => [
      { title: 'Users', items: users.filter((t) => match(t.id)) },
      { title: 'Rooms', items: rooms.filter((t) => match(t.id)) },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [users, rooms, q],
  );
  const flat = groups.flatMap((g) => g.items);

  useEffect(() => {
    if (!open) return;
    setActive(0);
    search.current?.focus();
    const onDown = (e: PointerEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', onDown);
    return () => document.removeEventListener('pointerdown', onDown);
  }, [open]);

  const pick = (id: string) => {
    onChange(id);
    setOpen(false);
    setQuery('');
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => Math.min(flat.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (flat[active]) pick(flat[active].id);
    } else if (e.key === 'Escape') {
      e.stopPropagation();
      setOpen(false);
    }
  };

  const chosen = [...users, ...rooms].find((t) => t.id === value) ?? null;
  return (
    <div ref={box} className="relative">
      <button
        type="button"
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        onClick={() => setOpen((o) => !o)}
        className={`${INPUT} flex cursor-pointer items-center gap-2 text-left`}
      >
        {chosen ? (
          <>
            {isRoom(chosen.id) ? <span className="w-5 text-center font-bold text-log-muted">#</span> : <Avatar userId={chosen.id} size={20} />}
            <span className="truncate">{isRoom(chosen.id) ? roomLabel(chosen.id).slice(1) : nameOf(chosen.id)}</span>
            <NodeChip node={chosen.node} />
          </>
        ) : (
          <span className="text-log-muted">Pick a user or a room</span>
        )}
        <span className="ml-auto text-log-muted" aria-hidden>
          ▾
        </span>
      </button>
      {open && (
        <div className="absolute left-0 right-0 top-full z-40 mt-1 overflow-hidden rounded-lg border border-log-line bg-log-bg shadow-2xl">
          <div className="border-b border-log-line p-2">
            <input
              ref={search}
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setActive(0);
              }}
              onKeyDown={onKey}
              placeholder="Search users and rooms"
              aria-activedescendant={flat[active] ? `${listId}-${flat[active].id}` : undefined}
              aria-controls={listId}
              className={INPUT}
            />
          </div>
          <ul id={listId} role="listbox" className="max-h-80 overflow-y-auto py-1">
            {flat.length === 0 && <li className="px-3 py-3 text-[13px] text-log-muted">Nothing matches</li>}
            {groups.map(
              (g) =>
                g.items.length > 0 && (
                  <li key={g.title} role="presentation">
                    <div className="px-3 pb-1 pt-2 text-[11px] uppercase tracking-wide text-log-muted">
                      {g.title} · {g.items.length}
                    </div>
                    <ul role="presentation">
                      {g.items.map((t) => {
                        const index = flat.indexOf(t);
                        return (
                          <li
                            key={t.id}
                            id={`${listId}-${t.id}`}
                            role="option"
                            aria-selected={t.id === value}
                            onPointerEnter={() => setActive(index)}
                            onClick={() => pick(t.id)}
                            className={`flex cursor-pointer items-center gap-2 px-3 py-1.5 text-[13px] ${index === active ? 'bg-log-hover' : ''}`}
                          >
                            {isRoom(t.id) ? (
                              <span className="flex h-6 w-6 items-center justify-center rounded-md border border-log-line font-bold text-log-muted">#</span>
                            ) : (
                              <Avatar userId={t.id} size={24} />
                            )}
                            <span className="truncate">{isRoom(t.id) ? roomLabel(t.id).slice(1) : nameOf(t.id)}</span>
                            {isRoom(t.id) && <span className="text-[11px] text-log-muted">{memberCount(t.id)} joined</span>}
                            <NodeChip node={t.node} />
                          </li>
                        );
                      })}
                    </ul>
                  </li>
                ),
            )}
          </ul>
        </div>
      )}
    </div>
  );
}

/** The highest room number the backend takes (ChatSimulatorActor.MAX_ROOM_NUMBER). */
const MAX_ROOM_NUMBER = 999_999;

/** How the new room becomes a sharded entity, and where it is expected to run. */
function ShardingNote({
  distributed,
  roomId,
  rooms,
  load,
}: {
  distributed: boolean;
  roomId: string | null;
  rooms: readonly { id: string; node: number }[];
  load: readonly { node: number; rooms: number; users: number }[];
}) {
  const shard = roomId === null ? null : shardOf(roomId);
  const mate = shard === null ? undefined : rooms.find((r) => bareId(r.id) !== roomId && shardOf(r.id) === shard);
  const expected = nextNode({ zones: load }, 'room', roomId ?? undefined, rooms);
  return (
    <details className="text-[12px] leading-5 text-log-muted">
      <summary className="cursor-pointer select-none text-log-ink">
        Where it runs: {expected === null ? 'the node with the fewest rooms' : <>expected on <NodeName node={expected} /></>}
      </summary>
      <ol className="mt-1 list-decimal space-y-1 pl-4">
        <li>
          The room is a sharded entity of type <code className="text-log-ink">ChatRoom</code> with entity id{' '}
          <code className="text-log-ink">{roomId ?? 'chat-room-<next free number>'}</code>.
        </li>
        <li>
          The id picks its shard: crc32(id) % {SHARDS}
          {shard !== null && (
            <>
              {' '}
              = <code className="text-log-ink">{shard}</code>
            </>
          )}
          .
        </li>
        <li>
          {mate ? (
            <>
              {mate.id} is already in that shard, so the room starts where the shard is: <NodeName node={mate.node} />.
            </>
          ) : (
            <>
              A shard with no room yet goes to the node holding the fewest ChatRoom shards
              {load.length > 0 && <> (now {load.map((n) => `node ${n.node}: ${n.rooms}`).join(', ')})</>}
              {distributed ? '' : '; with one node, that node'}.
            </>
          )}
        </li>
        <li>
          It starts on the simulator's first message and says Hello with its node; the simulator, a cluster singleton,
          sends it its settings and members. Members on other nodes get its messages over the network.
        </li>
        <li>If its node goes down, its shard moves to a node that is still up, and the room starts there and says Hello again.</li>
      </ol>
    </details>
  );
}

function NodeName({ node }: { node: number }) {
  return <span style={{ color: `var(--color-${nodeColour(node)})` }}>node {node}</span>;
}

function NodeChip({ node }: { node: number }) {
  return (
    <span
      className="ml-auto shrink-0 rounded-full border px-1.5 text-[10px] leading-4"
      style={{ borderColor: `var(--color-${nodeColour(node)})`, color: `var(--color-${nodeColour(node)})` }}
    >
      node {node}
    </span>
  );
}

interface Props {
  api: Api;
  rooms: readonly Target[];
  users: readonly Target[];
  memberCount: (room: string) => number;
  /** Whether rooms and users are sharded over several processes. */
  distributed: boolean;
  /** How many rooms and users each node holds now. */
  load: readonly { node: number; rooms: number; users: number }[];
  /** The blue-green state, for the pipeline's buttons. */
  deploy: DeployState;
  onDeployed: () => void;
  open: boolean;
  onOpen: (open: boolean) => void;
}

/**
 * The menu: a small ☰ button on the canvas that opens a drawer on the right with the chat's load
 * (rooms, users, messages a second) and a form to send a message to one user, or to every user who
 * joined a room. Each value goes to the backend on Enter or when the field loses focus.
 */
export function ControlDrawer({ api, rooms, users, memberCount, distributed, load, deploy, onDeployed, open, onOpen }: Props) {
  const [settings, setSettings] = useState<ChatSettings | null>(null);
  const [drafts, setDrafts] = useState<Partial<Record<SettingField, string>>>({});
  const [errors, setErrors] = useState<Partial<Record<SettingField | 'send' | 'number', string>>>({});
  const [target, setTarget] = useState('');
  const [text, setText] = useState('');
  const [result, setResult] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [roomNumber, setRoomNumber] = useState('');
  const [added, setAdded] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    api.readChatRun().then((run) => setSettings(run.settings), () => undefined);
    const onKey = (e: globalThis.KeyboardEvent) => e.key === 'Escape' && onOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [api, open, onOpen]);

  const commit = async (f: (typeof FIELDS)[number]) => {
    const raw = drafts[f.field];
    if (raw === undefined) return;
    const value = Number(raw);
    if (raw.trim() === '' || !Number.isFinite(value) || value < f.min || value > f.max || (f.step === 1 && !Number.isInteger(value))) {
      setErrors((e) => ({ ...e, [f.field]: `${f.label.toLowerCase()} must be ${f.hint}` }));
      return;
    }
    try {
      const after = await api.setChatSetting(f.route, value);
      if (after) setSettings(after);
      setDrafts((d) => ({ ...d, [f.field]: undefined }));
      setErrors((e) => ({ ...e, [f.field]: undefined }));
    } catch (err) {
      setErrors((e) => ({ ...e, [f.field]: err instanceof Error ? err.message : 'not saved' }));
    }
  };

  const addRoom = async (e?: FormEvent) => {
    e?.preventDefault();
    const raw = roomNumber.trim();
    const number = raw === '' ? undefined : Number(raw);
    if (number !== undefined && (!Number.isInteger(number) || number < 1 || number > MAX_ROOM_NUMBER)) {
      setErrors((x) => ({ ...x, number: `room number must be 1 to ${MAX_ROOM_NUMBER}` }));
      return;
    }
    try {
      const { roomId, settings: after } = await api.addChatRoom(number);
      if (after) setSettings(after);
      setAdded(roomId);
      setRoomNumber('');
      setErrors((x) => ({ ...x, rooms: undefined, number: undefined }));
    } catch (err) {
      setErrors((x) => ({ ...x, number: err instanceof Error ? err.message : 'no room added' }));
    }
  };

  const onSend = async (e: FormEvent) => {
    e.preventDefault();
    if (!target || !text.trim()) return;
    setSending(true);
    setResult(null);
    try {
      const delivery = isRoom(target) ? await api.broadcastToRoom(target, text.trim()) : await api.sendToActor(target, text.trim());
      setResult(
        isRoom(target)
          ? `Broadcast to ${delivery.delivered.length} joined user${delivery.delivered.length === 1 ? '' : 's'}`
          : delivery.replied
            ? `${nameOf(target)} got it`
            : `${nameOf(target)} did not answer in time`,
      );
      setText('');
      setErrors((x) => ({ ...x, send: undefined }));
    } catch (err) {
      setErrors((x) => ({ ...x, send: err instanceof Error ? err.message : 'not sent' }));
    } finally {
      setSending(false);
    }
  };

  return (
    <>
      <button
        type="button"
        aria-label="Menu"
        aria-expanded={open}
        onClick={() => onOpen(!open)}
        className="fixed right-3 top-3 z-20 flex h-10 w-10 cursor-pointer items-center justify-center rounded-lg border border-log-line bg-log-bg/90 text-lg text-log-ink shadow-lg backdrop-blur hover:bg-log-hover"
      >
        ☰
      </button>
      {open && (
        <aside
          role="dialog"
          aria-label="Menu"
          className="fixed right-0 top-0 z-30 flex w-full max-w-[380px] flex-col border-l border-log-line bg-log-bg text-log-ink shadow-2xl"
          style={{ bottom: 'var(--console-h)' }}
        >
          <header className="flex items-center border-b border-log-line px-4 py-3">
            <h2 className="text-[15px] font-semibold">Chat</h2>
            <button type="button" onClick={() => onOpen(false)} aria-label="Close" className="ml-auto h-8 w-8 cursor-pointer rounded text-log-muted hover:bg-log-hover hover:text-log-ink">
              ✕
            </button>
          </header>
          <div className="flex-1 overflow-y-auto">
            <BlueGreenPanel api={api} deploy={deploy} onChanged={onDeployed} />
            <section className="space-y-3 border-b border-log-line px-4 py-4">
              <h3 className="text-[11px] uppercase tracking-wide text-log-muted">Load</h3>
              {FIELDS.map((f) => (
                <div key={f.field}>
                  <label className="mb-1 block text-[12px] text-log-muted" htmlFor={`setting-${f.field}`}>
                    {f.label}
                  </label>
                  <div className="flex gap-2">
                    <input
                      id={`setting-${f.field}`}
                      inputMode="decimal"
                      value={drafts[f.field] ?? (settings ? String(settings[f.field]) : '')}
                      onChange={(e) => setDrafts((d) => ({ ...d, [f.field]: e.target.value }))}
                      onBlur={() => commit(f)}
                      onKeyDown={(e) => e.key === 'Enter' && commit(f)}
                      className={INPUT}
                    />
                  </div>
                  {errors[f.field] && (
                    <p role="alert" className="mt-1 text-[12px] text-log-error">
                      {errors[f.field]}
                    </p>
                  )}
                </div>
              ))}
            </section>
            <form onSubmit={addRoom} className="space-y-2 border-b border-log-line px-4 py-4">
              <h3 className="text-[11px] uppercase tracking-wide text-log-muted">Add a room</h3>
              <div className="flex gap-2">
                <label className="flex h-9 min-w-0 flex-1 items-center rounded-md border border-log-line bg-log-hover pl-2.5 text-[13px] text-log-muted focus-within:border-log-muted">
                  chat-room-
                  <input
                    aria-label="Room number"
                    inputMode="numeric"
                    value={roomNumber}
                    onChange={(e) => setRoomNumber(e.target.value.replace(/[^0-9]/g, ''))}
                    placeholder="next free"
                    className="h-full min-w-0 flex-1 bg-transparent pr-2 text-log-ink outline-none placeholder:text-log-muted"
                  />
                </label>
                <button type="submit" className={BUTTON}>
                  Add room
                </button>
              </div>
              {errors.number && (
                <p role="alert" className="text-[12px] text-log-error">
                  {errors.number}
                </p>
              )}
              {added && <p className="text-[12px] text-log-muted">Added {added}.</p>}
              <ShardingNote
                distributed={distributed}
                roomId={roomNumber ? `chat-room-${roomNumber}` : null}
                rooms={rooms.filter((r) => deploy.active === null || r.id.endsWith(`@${deploy.active.color}`))}
                load={load}
              />
            </form>
            <form onSubmit={onSend} className="space-y-3 px-4 py-4">
              <h3 className="text-[11px] uppercase tracking-wide text-log-muted">Send a message</h3>
              <TargetPicker rooms={rooms} users={users} memberCount={memberCount} value={target} onChange={setTarget} />
              <div className="flex gap-2">
                <input value={text} onChange={(e) => setText(e.target.value)} maxLength={200} placeholder="Message" className={INPUT} />
                <button type="submit" disabled={!target || !text.trim() || sending} className={PRIMARY}>
                  {isRoom(target) ? 'Broadcast' : 'Send'}
                </button>
              </div>
              {result && <p className="text-[12px] text-log-muted">{result}</p>}
              {errors.send && (
                <p role="alert" className="text-[12px] text-log-error">
                  {errors.send}
                </p>
              )}
            </form>
          </div>
        </aside>
      )}
    </>
  );
}
