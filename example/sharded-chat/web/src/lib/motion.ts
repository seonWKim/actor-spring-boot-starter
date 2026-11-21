// Rooms and users glide to where the layout puts them instead of jumping. An actor that moved to
// another node (its shard was rebalanced, or its node left) travels there slowly, with a trail, so the
// move is easy to follow; one that shifts on its own node (others came or went) slides quickly. An actor
// that appears in one color while its twin runs in the other (green's chat-room-8@green beside blue's
// chat-room-8@blue) starts from the twin, so a deploy shows the shards crossing to the new cluster.
// Pure: every call gets `now`.
import { bareId, colorOfId } from './ids.ts';
import type { Point } from './chatGraph.ts';

/** How long a move to another node takes, and a shift on the same node. */
export const MOVE_MS = 1600;
export const SHIFT_MS = 500;

export interface Spot extends Point {
  node: number;
}

export interface Motion {
  from: Point;
  to: Point;
  start: number;
  duration: number;
  /** It changed node (or crossed from the other color): drawn with a trail. */
  resharded: boolean;
}

/** The other color's id of the same entity: "chat-room-8@green" → "chat-room-8@blue". */
export function twinOf(id: string): string | null {
  const color = colorOfId(id);
  if (color === null) return null;
  return `${bareId(id)}@${color === 'green' ? 'blue' : 'green'}`;
}

const ease = (t: number) => (t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2);

/** Where a motion is at `now`. */
export function positionAt(m: Motion, now: number): Point {
  const t = ease(Math.min(1, Math.max(0, (now - m.start) / m.duration)));
  return { x: m.from.x + (m.to.x - m.from.x) * t, y: m.from.y + (m.to.y - m.from.y) * t };
}

export const done = (m: Motion, now: number) => now - m.start >= m.duration;

/**
 * The motions for a new layout. `shown` is where each actor is drawn now (mid-move included) and on which
 * node; `next` is the new layout. An actor with nothing to start from (new, no twin) appears in place.
 */
export function plan(shown: ReadonlyMap<string, Spot>, next: ReadonlyMap<string, Spot>, now: number): Map<string, Motion> {
  const motions = new Map<string, Motion>();
  for (const [id, to] of next) {
    const here = shown.get(id);
    const twin = here ? undefined : shown.get(twinOf(id) ?? '');
    const from = here ?? twin;
    if (!from || (Math.abs(from.x - to.x) < 0.5 && Math.abs(from.y - to.y) < 0.5)) continue;
    const resharded = twin !== undefined || from.node !== to.node;
    motions.set(id, { from: { x: from.x, y: from.y }, to: { x: to.x, y: to.y }, start: now, duration: resharded ? MOVE_MS : SHIFT_MS, resharded });
  }
  return motions;
}

/** How long a started actor takes to grow in, and a stopped one to fade out. */
export const BIRTH_MS = 1200;
export const DEATH_MS = 1400;

/** A stopped actor, still drawn where it was until it has faded out. */
export interface Death<T extends Spot> {
  id: string;
  at: T;
  start: number;
}

/**
 * The actors that started and stopped between two layouts. Started: in `next`, neither it nor its twin
 * was drawn before (a twin crossing from the other color is a move, not a start). Stopped: drawn
 * before, gone from `next`. The first layout starts nothing: the page has only just opened.
 */
export function lifeChanges<T extends Spot>(
  shown: ReadonlyMap<string, T>,
  next: ReadonlyMap<string, T>,
  now: number,
): { born: Map<string, number>; died: Death<T>[] } {
  const born = new Map<string, number>();
  const died: Death<T>[] = [];
  if (shown.size === 0) return { born, died };
  for (const id of next.keys()) {
    if (!shown.has(id) && !shown.has(twinOf(id) ?? '')) born.set(id, now);
  }
  for (const [id, at] of shown) {
    if (!next.has(id)) died.push({ id, at, start: now });
  }
  return { born, died };
}

/** 0 to 1 over `duration` from `start`. */
export const phase = (start: number, now: number, duration: number) => Math.min(1, Math.max(0, (now - start) / duration));
