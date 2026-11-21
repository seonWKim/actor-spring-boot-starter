import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bandsOf, crc32, grid, layoutChat, loadByNode, memberships, nextNode, shardOf, type LayoutInput } from './chatGraph.ts';

const rooms = [
  { id: 'chat-room-1', members: ['chat-user-1', 'chat-user-2'], node: 1 },
  { id: 'chat-room-2', members: ['chat-user-2', 'chat-user-3'], node: 2 },
];
const users = [
  { id: 'chat-user-1', rooms: ['chat-room-1'], node: 1 },
  { id: 'chat-user-2', rooms: ['chat-room-1', 'chat-room-2'], node: 3 },
  { id: 'chat-user-3', rooms: ['chat-room-2'], node: null },
];
const input = (over: Partial<LayoutInput> = {}): LayoutInput => ({ width: 1200, height: 600, rooms, users, nodeOf: new Map(), nodes: [1, 2, 3], ...over });
const inside = (p: { x: number; y: number }, z: { centre: { x: number; y: number }; radius: number }) =>
  Math.hypot(p.x - z.centre.x, p.y - z.centre.y) <= z.radius + 1e-9;

test('each process is its own circle, side by side on a wide screen and stacked on a phone', () => {
  const wide = layoutChat(input());
  assert.deepEqual(wide.zones.map((z) => z.node), [1, 2, 3]);
  const ys = new Set(wide.zones.map((z) => Math.round(z.centre.y)));
  assert.equal(ys.size, 1, 'one row');
  for (const [a, b] of [[0, 1], [1, 2]]) {
    const d = Math.hypot(wide.zones[a].centre.x - wide.zones[b].centre.x, wide.zones[a].centre.y - wide.zones[b].centre.y);
    assert.ok(d >= wide.zones[a].radius + wide.zones[b].radius, 'circles do not overlap');
  }
  assert.equal(grid(390, 700, 3).cols, 1);
});

test('each actor sits in the circle of the process the state says it runs on; else its messages, else node 1', () => {
  const layout = layoutChat(input({ nodeOf: new Map([['chat-user-3', 2]]) }));
  const zone = (n: number) => layout.zones.find((z) => z.node === n)!;
  for (const p of [...layout.rooms, ...layout.users]) assert.ok(inside(p, zone(p.node)), `${p.id} in node ${p.node}`);
  assert.equal(layout.users.find((u) => u.id === 'chat-user-2')!.node, 3);
  assert.equal(layout.users.find((u) => u.id === 'chat-user-3')!.node, 2);
  assert.deepEqual(layout.zones.map((z) => [z.rooms, z.users]), [[1, 1], [1, 1], [0, 1]]);
});

test('the shard is crc32 of the id modulo 100, as the starter hashes ids', () => {
  assert.equal(crc32('123456789'), 0xcbf43926);
  assert.equal(shardOf('123456789'), 0xcbf43926 % 100);
});

test('a new room joins the node of a room in the same shard, else the node with the fewest rooms', () => {
  const load = loadByNode(rooms, users, new Map(), [1, 2, 3]);
  assert.equal(nextNode({ zones: load }, 'room'), 3);
  assert.equal(nextNode({ zones: load }, 'user'), 2);
  // Find an id that lands in chat-room-1's shard: it goes where chat-room-1 runs.
  const mate = Array.from({ length: 2000 }, (_, i) => `chat-room-${i + 2}`).find((id) => shardOf(id) === shardOf('chat-room-1'))!;
  assert.equal(nextNode({ zones: load }, 'room', mate, [{ id: 'chat-room-1', node: 1 }]), 1);
});

test('memberships are one line per user and joined room', () => {
  assert.deepEqual(memberships(users), [
    { user: 'chat-user-1', room: 'chat-room-1' },
    { user: 'chat-user-2', room: 'chat-room-1' },
    { user: 'chat-user-2', room: 'chat-room-2' },
    { user: 'chat-user-3', room: 'chat-room-2' },
  ]);
});

test('with two colors each gets a band: side by side on a wide screen, stacked on a narrow one, its circles inside', () => {
  const groupOf = (n: number) => (n <= 3 ? 'blue' : 'green');
  const wide = bandsOf(1400, 700, [1, 2, 3, 4, 5, 6], groupOf);
  assert.deepEqual(wide.map((b) => [b.key, b.nodes, b.x < 700]), [['blue', [1, 2, 3], true], ['green', [4, 5, 6], false]]);
  const narrow = bandsOf(390, 800, [1, 2, 4], groupOf);
  assert.deepEqual(narrow.map((b) => [b.key, b.y < 400]), [['blue', true], ['green', false]]);
  const layout = layoutChat({ width: 1400, height: 700, rooms: [], users: [], nodeOf: new Map(), nodes: [1, 2, 3, 4, 5, 6], groupOf });
  for (const z of layout.zones) {
    const band = layout.bands.find((b) => b.nodes.includes(z.node))!;
    assert.ok(z.centre.x - z.radius >= band.x && z.centre.x + z.radius <= band.x + band.width, `node ${z.node} inside its band`);
  }
  assert.deepEqual(bandsOf(1400, 700, [1, 2], undefined), []);
});
