// The chat on the canvas: chat room actors and user actors, laid out by the process (cluster node)
// each one runs on. Each process is its own circle, tinted in its colour; the circles sit side by side
// (in a row on a wide screen, stacked on a narrow one). Inside its circle a process's rooms sit on an
// inner ring and its users on the outer ring, each user near its first room on that node, so the
// lines from a user to a room on another process cross between the circles.
// With blue-green, the nodes of each color sit together in their own band, side by side on a wide
// screen and stacked on a narrow one.
// Pure functions only: no DOM, no clock.

import { bareId } from './ids.ts';

export interface ChatRoomInfo {
  id: string;
  members: readonly string[];
  /** The process it runs on, when the backend knows it. */
  node?: number | null;
}

export interface ChatUserInfo {
  id: string;
  rooms: readonly string[];
  node?: number | null;
}

export interface Point {
  x: number;
  y: number;
}

export interface Placed extends Point {
  id: string;
  kind: 'room' | 'user';
  node: number;
}

/** One process's circle. */
export interface Zone {
  node: number;
  centre: Point;
  radius: number;
  rooms: number;
  users: number;
  /** Where its label goes: just under the circle. */
  label: Point;
}

/** The band of one color's nodes, with room at its top for its header. */
export interface Band {
  key: string;
  x: number;
  y: number;
  width: number;
  height: number;
  nodes: number[];
}

export interface ChatLayout {
  width: number;
  height: number;
  /** The middle of the canvas. */
  centre: Point;
  /** The drawn radius of a user's avatar and of a room's square (half its side). */
  avatar: number;
  room: number;
  zones: Zone[];
  /** One per color when the nodes have colors; empty otherwise. */
  bands: Band[];
  rooms: Placed[];
  users: Placed[];
}

export interface LayoutInput {
  width: number;
  height: number;
  rooms: readonly ChatRoomInfo[];
  users: readonly ChatUserInfo[];
  /** The process each actor was seen on in its messages, for an actor the state does not place yet. */
  nodeOf: ReadonlyMap<string, number>;
  /** The processes that are up, so an empty one still has its circle. */
  nodes: readonly number[];
  /** The color of each node (blue or green), which groups the circles in bands; none groups nothing. */
  groupOf?: (node: number) => string | null;
}

/** Room around each circle for its label, in px. */
export const MARGIN = 36;
/** The gap between two circles, in px. */
export const GAP = 28;
/** The height of a band's header, and the gap between two bands, in px. */
export const BAND_HEADER = 30;
export const BAND_GAP = 16;
/** Room under the last row of circles for their labels, inside the band's border. */
export const BAND_FOOTER = 18;

const TAU = Math.PI * 2;

/** The point at `angle` (clockwise from 12 o'clock) and `r` from `c`. */
export function polar(c: Point, r: number, angle: number): Point {
  return { x: c.x + r * Math.sin(angle), y: c.y - r * Math.cos(angle) };
}

/** The process an actor runs on: the state's node, else the node its messages came from, else 1. */
export function nodeOfActor(actor: { id: string; node?: number | null }, nodeOf: ReadonlyMap<string, number>): number {
  return actor.node ?? nodeOf.get(actor.id) ?? 1;
}

/** The processes that get a circle: those up and those an actor runs on, in order. */
export function zoneNodes(input: Pick<LayoutInput, 'rooms' | 'users' | 'nodeOf' | 'nodes'>): number[] {
  const set = new Set<number>(input.nodes);
  for (const r of input.rooms) set.add(nodeOfActor(r, input.nodeOf));
  for (const u of input.users) set.add(nodeOfActor(u, input.nodeOf));
  if (set.size === 0) set.add(1);
  return [...set].sort((a, b) => a - b);
}

/** The grid (columns × rows) that gives `count` circles the largest radius in the box. */
export function grid(width: number, height: number, count: number): { cols: number; rows: number; radius: number } {
  let best = { cols: 1, rows: count, radius: 0 };
  for (let cols = 1; cols <= count; cols++) {
    const rows = Math.ceil(count / cols);
    const cellW = (width - GAP * (cols - 1)) / cols;
    const cellH = (height - GAP * (rows - 1)) / rows;
    const radius = Math.min(cellW, cellH) / 2 - MARGIN / 2;
    if (radius > best.radius) best = { cols, rows, radius };
  }
  return { ...best, radius: Math.max(24, best.radius) };
}

/** Places `count` items evenly round the circle, the first at 12 o'clock. */
function around(count: number, offset = 0): number[] {
  return Array.from({ length: count }, (_, i) => offset + (TAU * i) / Math.max(1, count));
}

/** The bands: one per color, in the order the colors first appear; side by side when the box is wide. */
export function bandsOf(width: number, height: number, nodes: readonly number[], groupOf?: (node: number) => string | null): Band[] {
  if (!groupOf) return [];
  const keys: string[] = [];
  const byKey = new Map<string, number[]>();
  for (const n of nodes) {
    const key = groupOf(n);
    if (key === null) return [];
    if (!byKey.has(key)) {
      keys.push(key);
      byKey.set(key, []);
    }
    byKey.get(key)!.push(n);
  }
  const wide = width >= height;
  const count = keys.length;
  return keys.map((key, i) => {
    const w = wide ? (width - BAND_GAP * (count - 1)) / count : width;
    const h = wide ? height : (height - BAND_GAP * (count - 1)) / count;
    return { key, x: wide ? i * (w + BAND_GAP) : 0, y: wide ? 0 : i * (h + BAND_GAP), width: w, height: h, nodes: byKey.get(key)! };
  });
}

export function layoutChat(input: LayoutInput): ChatLayout {
  const { width, height } = input;
  const centre = { x: width / 2, y: height / 2 };
  const nodes = zoneNodes(input);
  const bands = bandsOf(width, height, nodes, input.groupOf);
  // Each band places its nodes like a canvas of its own, below its header.
  const areas =
    bands.length > 0
      ? bands.map((b) => ({ x: b.x, y: b.y + BAND_HEADER, width: b.width, height: b.height - BAND_HEADER - BAND_FOOTER, nodes: b.nodes }))
      : [{ x: 0, y: 0, width, height, nodes }];
  // One radius for every circle, so the nodes of both colors are the same size.
  const radius = Math.min(...areas.map((area) => grid(area.width, area.height, area.nodes.length).radius));

  const roomsOn = new Map(nodes.map((n) => [n, [] as ChatRoomInfo[]]));
  const usersOn = new Map(nodes.map((n) => [n, [] as ChatUserInfo[]]));
  for (const r of input.rooms) roomsOn.get(nodeOfActor(r, input.nodeOf))!.push(r);
  for (const u of input.users) usersOn.get(nodeOfActor(u, input.nodeOf))!.push(u);

  const zones: Zone[] = [];
  const rooms: Placed[] = [];
  const users: Placed[] = [];
  let avatar = 18;
  const room = Math.max(12, Math.min(22, radius * 0.11));

  for (const area of areas) {
    const { cols, rows } = grid(area.width, area.height, area.nodes.length);
    const cellW = (area.width - GAP * (cols - 1)) / cols;
    const cellH = (area.height - GAP * (rows - 1)) / rows;
    // Centre the last row when it is not full.
    const lastRowCount = area.nodes.length - cols * (rows - 1);
    area.nodes.forEach((n, i) => {
      const row = Math.floor(i / cols);
      const inRow = row === rows - 1 ? lastRowCount : cols;
      const col = i % cols;
      const rowWidth = inRow * cellW + (inRow - 1) * GAP;
      const left = area.x + (area.width - rowWidth) / 2;
      const c = {
        x: left + col * (cellW + GAP) + cellW / 2,
        y: area.y + row * (cellH + GAP) + cellH / 2 - MARGIN / 4,
      };
      const myRooms = roomsOn.get(n)!;
      const myUsers = usersOn.get(n)!;
      const roomR = myRooms.length <= 1 ? 0 : radius * 0.38;
      const roomAngle = new Map<string, number>();
      around(myRooms.length).forEach((angle, k) => {
        roomAngle.set(myRooms[k].id, angle);
        rooms.push({ id: myRooms[k].id, kind: 'room', node: n, ...polar(c, roomR, angle) });
      });
      // Users in the order of their first room on this node, so each room's members sit together.
      const key = (u: ChatUserInfo) => {
        const local = u.rooms.find((r) => roomAngle.has(r));
        return local === undefined ? TAU : roomAngle.get(local)!;
      };
      const sorted = [...myUsers].sort((a, b) => key(a) - key(b) || a.id.localeCompare(b.id, undefined, { numeric: true }));
      const userR = radius * 0.8;
      around(sorted.length, Math.PI / Math.max(1, sorted.length)).forEach((angle, k) => {
        users.push({ id: sorted[k].id, kind: 'user', node: n, ...polar(c, userR, angle) });
      });
      if (sorted.length > 0) avatar = Math.min(avatar, ((Math.PI * userR) / sorted.length) * 0.85);
      zones.push({ node: n, centre: c, radius, rooms: myRooms.length, users: myUsers.length, label: { x: c.x, y: c.y + radius + 16 } });
    });
  }

  avatar = Math.max(7, Math.min(18, avatar, radius * 0.14));
  return { width, height, centre, avatar, room, zones, bands, rooms, users };
}

/** Every membership as one line: user to room. */
export function memberships(users: readonly ChatUserInfo[]): { user: string; room: string }[] {
  return users.flatMap((u) => u.rooms.map((room) => ({ user: u.id, room })));
}

/** The starter's default number of shards per entity type (ShardingStrategy.DEFAULT_SHARDS). */
export const SHARDS = 100;

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

/** CRC32 of the text's UTF-8 bytes, as java.util.zip.CRC32 computes it. */
export function crc32(text: string): number {
  let c = 0xffffffff;
  for (const byte of new TextEncoder().encode(text)) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** The shard of an entity id under the starter's default strategy: crc32(id) % 100 (the id without its color). */
export const shardOf = (entityId: string, shards = SHARDS): number => crc32(bareId(entityId)) % shards;

/**
 * The node a new room or user is expected on. Its shard decides: if another entity of the same type
 * is already in that shard, it joins it on that node. Otherwise the type's shard coordinator places
 * the new shard on the node holding the fewest shards of that type; with far more shards than
 * entities that is about the node with the fewest entities of that kind (ties: the lowest number here,
 * any of them in the cluster).
 */
export function nextNode(
  layout: { zones: readonly Pick<Zone, 'node' | 'rooms' | 'users'>[] },
  kind: 'room' | 'user',
  entityId?: string,
  placed: readonly { id: string; node: number }[] = [],
): number | null {
  if (entityId !== undefined) {
    const shard = shardOf(entityId);
    const mate = placed.find((p) => p.id !== entityId && shardOf(p.id) === shard);
    if (mate) return mate.node;
  }
  if (layout.zones.length === 0) return null;
  const count = (z: Pick<Zone, 'rooms' | 'users'>) => (kind === 'room' ? z.rooms : z.users);
  return [...layout.zones].sort((a, b) => count(a) - count(b) || a.node - b.node)[0].node;
}

/** How many rooms and users each process holds now, for every process that is up or holds one. */
export function loadByNode(
  rooms: readonly ChatRoomInfo[],
  users: readonly ChatUserInfo[],
  nodeOf: ReadonlyMap<string, number>,
  nodes: readonly number[],
): Pick<Zone, 'node' | 'rooms' | 'users'>[] {
  return zoneNodes({ rooms, users, nodeOf, nodes }).map((node) => ({
    node,
    rooms: rooms.filter((r) => nodeOfActor(r, nodeOf) === node).length,
    users: users.filter((u) => nodeOfActor(u, nodeOf) === node).length,
  }));
}
