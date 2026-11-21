import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MOVE_MS, SHIFT_MS, plan, positionAt, twinOf, type Spot } from './motion.ts';

const spot = (x: number, y: number, node: number): Spot => ({ x, y, node });

test('an actor that moved to another node travels there slowly, marked resharded', () => {
  const m = plan(new Map([['chat-user-1@blue', spot(0, 0, 2)]]), new Map([['chat-user-1@blue', spot(100, 0, 3)]]), 10).get('chat-user-1@blue')!;
  assert.deepEqual([m.duration, m.resharded], [MOVE_MS, true]);
  assert.deepEqual(positionAt(m, 10), { x: 0, y: 0 });
  assert.deepEqual(positionAt(m, 10 + MOVE_MS / 2), { x: 50, y: 0 });
  assert.deepEqual(positionAt(m, 10 + MOVE_MS), { x: 100, y: 0 });
});

test('an actor shifted on its own node slides quickly; one that stays put does not move', () => {
  const shown = new Map([['a', spot(0, 0, 1)], ['b', spot(5, 5, 1)]]);
  const motions = plan(shown, new Map([['a', spot(10, 0, 1)], ['b', spot(5, 5, 1)]]), 0);
  assert.deepEqual([motions.get('a')!.duration, motions.get('a')!.resharded], [SHIFT_MS, false]);
  assert.equal(motions.has('b'), false);
});

test("a new actor in one color starts from its twin in the other; one without a twin appears in place", () => {
  assert.equal(twinOf('chat-room-8@green'), 'chat-room-8@blue');
  const shown = new Map([['chat-room-8@blue', spot(0, 0, 1)]]);
  const motions = plan(shown, new Map([['chat-room-8@green', spot(300, 0, 4)], ['chat-room-9@green', spot(300, 50, 4)]]), 0);
  assert.deepEqual(motions.get('chat-room-8@green')!.from, { x: 0, y: 0 });
  assert.equal(motions.get('chat-room-8@green')!.resharded, true);
  assert.equal(motions.has('chat-room-9@green'), false);
});
