// The messages the chat actors really send (the `message` events of GET /api/chat/events), as the canvas
// draws them: each is one dot that travels along the line between its sender and its receiver. A
// user's post goes user → room; the room then sends one Deliver to each member, room → member, so a
// post visibly fans out to everyone who joined that room; the room's reply (true) goes back to the
// poster. A message from the page or between two users goes straight from one to the other.
// Every message is also kept, per actor, for the actor's panel: in memory only, the last few hundred.
// Pure: no DOM; every call gets `now`.
import { asText } from './api.ts';
import type { ActorEvent } from './events.ts';

export type FlowKind = 'post' | 'deliver' | 'reply' | 'direct';

export interface Flow {
  sender: string;
  receiver: string;
  kind: FlowKind;
  type: string;
  /** The chat text, when the message carries one; else the payload as the backend sent it. */
  text: string;
  /** The user who wrote the text: the poster of a post and of each of its delivers. */
  author: string | null;
  /** The process that sent it (the sender's node), when the event says. */
  node: number | null;
  /** Sent by hand from the page (a Direct, a Broadcast, or a room's copy of a Broadcast). */
  manual: boolean;
  at: number;
}

export interface FlowDot extends Flow {
  start: number;
  duration: number;
}

/** How long a dot takes from sender to receiver. */
export const FLOW_MS = 900;
/** A message sent by hand travels slower, so it is easy to follow. */
export const MANUAL_MS = 1600;
/** The sender id the backend gives the page's own sends. */
export const PAGE = 'page';
/** Above this many dots in flight, new ones are faster so the canvas keeps up. */
export const BUSY_DOTS = 400;
const BUSY_MS = 450;
/** The most messages kept per actor for its panel. */
export const HISTORY = 200;

// A post also carries the epoch it was stamped with, after its text: Post{from=…, text=…, epoch=3}.
const TEXT = /text=(.*?)(?:, epoch=\d+)?\}$/s;
const FROM = /from=([^,}]+)/;

function kindOf(type: string, sender: string, receiver: string): FlowKind {
  if (type === 'Post') return 'post';
  if (type === 'Deliver') return 'deliver';
  if (type === 'Boolean' && sender.startsWith('chat-room-')) return 'reply';
  if (receiver.startsWith('chat-room-') && type !== 'Broadcast') return 'post';
  return 'direct';
}

/** The send a `message` event reports; null for any other event or one without both ends. */
export function flowOf(event: ActorEvent, at: number): Flow | null {
  if (event.name !== 'message') return null;
  const data = event.data;
  const sender = asText(data.sender ?? event.actorId);
  const receiver = asText(data.receiver);
  if (!sender || !receiver) return null;
  const type = asText(data.message_type);
  const payload = asText(data.payload ?? data.message);
  const text = TEXT.exec(payload)?.[1]?.trim() ?? payload;
  const kind = kindOf(type, sender, receiver);
  const author = kind === 'post' ? sender : kind === 'deliver' ? (FROM.exec(payload)?.[1]?.trim() ?? null) : null;
  const node = typeof data.node === 'number' ? data.node : null;
  const from = FROM.exec(payload)?.[1]?.trim() ?? null;
  const manual = sender === PAGE || from === PAGE;
  return { sender, receiver, kind, type, text, author: kind === 'direct' && manual ? PAGE : author, node, at, manual };
}

export interface FlowState {
  dots: FlowDot[];
  /** Per actor: the messages it sent or received, oldest first, at most HISTORY. */
  history: Map<string, Flow[]>;
  /** The process each actor was last seen sending from. */
  nodeOf: Map<string, number>;
}

export function createFlowState(): FlowState {
  return { dots: [], history: new Map(), nodeOf: new Map() };
}

function remember(state: FlowState, id: string, flow: Flow) {
  let list = state.history.get(id);
  if (!list) {
    list = [];
    state.history.set(id, list);
  }
  list.push(flow);
  if (list.length > HISTORY) list.splice(0, list.length - HISTORY);
}

/** Adds one message: a dot on its way, an entry in both actors' history, and the sender's process. */
export function addFlow(state: FlowState, flow: Flow, now: number) {
  const busy = state.dots.length >= BUSY_DOTS;
  state.dots.push({ ...flow, start: now, duration: flow.manual ? MANUAL_MS : busy ? BUSY_MS : FLOW_MS });
  remember(state, flow.sender, flow);
  if (flow.receiver !== flow.sender) remember(state, flow.receiver, flow);
  if (flow.node !== null) state.nodeOf.set(flow.sender, flow.node);
}

/** Drops the dots that arrived; returns whether any is still in flight. */
export function pruneDots(state: FlowState, now: number): boolean {
  if (state.dots.some((d) => now - d.start >= d.duration)) {
    state.dots = state.dots.filter((d) => now - d.start < d.duration);
  }
  return state.dots.length > 0;
}

/** How far along its line a dot is, 0 to 1, eased so it leaves and lands gently. */
export function progress(dot: Pick<FlowDot, 'start' | 'duration'>, now: number): number {
  const t = Math.min(1, Math.max(0, (now - dot.start) / dot.duration));
  return t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2;
}

/** The actor's messages, newest last. */
export const historyOf = (state: FlowState, id: string): readonly Flow[] => state.history.get(id) ?? [];

/** Sent and received counts of the actor's messages in the last `windowMs`. */
export function countsOf(state: FlowState, id: string, now: number, windowMs = 60_000) {
  let sent = 0;
  let received = 0;
  for (const f of historyOf(state, id)) {
    if (now - f.at > windowMs) continue;
    if (f.sender === id) sent++;
    else received++;
  }
  return { sent, received };
}
