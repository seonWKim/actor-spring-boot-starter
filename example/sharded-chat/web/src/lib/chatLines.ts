// The line model of a chat log line in the console (the `log` events of GET /api/chat/events): who, which room, the
// message bubble, whether it timed out and how long ago. The log event has no room field, so the
// room id is read from the message text as the backend writes it (ChatUserActor). Pure.
import type { LogLine } from './log.ts';
import { personaOf, type Persona } from './persona.ts';

/** The log kinds drawn as chat lines; every other line stays the compact monospace line. */
export const CHAT_LINE_KINDS = ['joined', 'left', 'sent', 'received', 'timeout'] as const;
export type ChatLineKind = (typeof CHAT_LINE_KINDS)[number];

export const isChatLineKind = (kind: string): kind is ChatLineKind => (CHAT_LINE_KINDS as readonly string[]).includes(kind);

/** What the text of each kind holds (the `log` events of GET /api/chat/events). */
// Ids may carry their color: chat-room-1@green.
const SENT = /^sent "(.*)" to (chat-room-\d+(?:@\w+)?)$/s;
const RECEIVED = /^received "(.*)" from (chat-user-\d+(?:@\w+)?|page)(?: in (chat-room-\d+(?:@\w+)?))?$/s;
/** The sender id of a message someone sent by hand from the page. */
const PAGE = 'page';
const TIMEOUT = /^no reply from (chat-room-\d+(?:@\w+)?) within \d+ ms to "(.*)"$/s;
const MEMBERSHIP = /^(?:joined|left) (chat-room-\d+(?:@\w+)?)/;

export interface ChatLine {
  id: number;
  kind: ChatLineKind;
  /** The user the line is about. */
  user: Persona;
  /** The room, read from the text; null when the text names none. */
  room: string | null;
  /** A sent line shows its room as the target (the backend has no broadcast kind); else null. */
  target: string | null;
  /** A received line: who sent it. */
  sender: Persona | null;
  /** The text in the bubble: the message, or the join or leave text. */
  bubble: string;
  /** Sent by hand from the page: the sender shows as "You" and the bubble is white. */
  manual: boolean;
  /** A timeout: the bubble is greyed and tagged "timed out". */
  timedOut: boolean;
  time: string;
  /** `at` of the log line, for the relative time. */
  at: number;
}

/** The parts of a chat line's text: its room, bubble and, for a received line, its sender. */
function parts(kind: ChatLineKind, text: string): { room: string | null; bubble: string; sender: string | null } {
  const found = (match: RegExpExecArray | null, room: number, bubble: number, sender?: number) =>
    match ? { room: match[room] ?? null, bubble: match[bubble], sender: sender ? match[sender] : null } : null;
  const fallbackRoom = /chat-room-\d+(?:@\w+)?/.exec(text)?.[0] ?? null;
  const known =
    kind === 'sent'
      ? found(SENT.exec(text), 2, 1)
      : kind === 'received'
        ? found(RECEIVED.exec(text), 3, 1, 2)
        : kind === 'timeout'
          ? found(TIMEOUT.exec(text), 1, 2)
          : null;
  if (known) return known;
  return { room: MEMBERSHIP.exec(text)?.[1] ?? fallbackRoom, bubble: text, sender: null };
}

/** The chat line a log line stands for, or null for a system, cluster or account line. */
export function chatLineOf(line: LogLine, persona: (id: string) => Persona = personaOf): ChatLine | null {
  if (!isChatLineKind(line.kind)) return null;
  const { room, bubble, sender } = parts(line.kind, line.text);
  return {
    id: line.id,
    kind: line.kind,
    user: persona(line.actorId),
    room,
    target: line.kind === 'sent' ? room : null,
    sender: sender && sender !== PAGE ? persona(sender) : null,
    manual: sender === PAGE,
    bubble,
    timedOut: line.kind === 'timeout' && line.level === 'timeout',
    time: line.time,
    at: line.at,
  };
}

/** How long ago, as `now`, `5s ago`, `3m ago`, `2h ago`; empty when the time is unknown. */
export function relativeTime(at: number, now: number): string {
  if (!Number.isFinite(at)) return '';
  const seconds = Math.max(0, Math.round((now - at) / 1000));
  if (seconds < 5) return 'now';
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

/** The chat user actor kind: only these dots show an avatar. */
export const USER_KIND = 'ChatUserActor';

export const isUserActor = (actor: { kind: string }): boolean => actor.kind === USER_KIND;

export interface UserCard {
  persona: Persona;
  rooms: string[];
  /** The group the actor runs under: the only process name the state replies carry. */
  process: string;
}

/** The hover card of a user actor: its persona, its rooms from the chat state, and its process. */
export function userCardOf(
  actor: { id: string; parent?: string },
  chat: { users: readonly { id: string; rooms: readonly string[] }[] } | null,
  persona: (id: string) => Persona = personaOf,
): UserCard {
  const user = chat?.users.find((u) => u.id === actor.id);
  return { persona: persona(actor.id), rooms: user ? [...user.rooms] : [], process: actor.parent ?? '' };
}
