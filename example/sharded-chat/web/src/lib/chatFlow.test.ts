import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FLOW_MS, HISTORY, addFlow, countsOf, createFlowState, flowOf, historyOf, progress, pruneDots } from './chatFlow.ts';
import type { ActorEvent } from './events.ts';

const message = (sender: string, receiver: string, type: string, payload: string, node = 1): ActorEvent =>
  ({ name: 'message', actorId: sender, at: 0, data: { sender, receiver, message_type: type, payload, node } }) as unknown as ActorEvent;

test('a post, its delivers and the room reply are read from the real message events', () => {
  const post = flowOf(message('chat-user-4', 'chat-room-1', 'Post', 'Post{from=chat-user-4, text=hello there, epoch=3}'), 5)!;
  assert.deepEqual([post.kind, post.text, post.author, post.node], ['post', 'hello there', 'chat-user-4', 1]);
  const deliver = flowOf(message('chat-room-1', 'chat-user-7', 'Deliver', 'Deliver{roomId=chat-room-1, from=chat-user-4, text=hello there}'), 5)!;
  assert.deepEqual([deliver.kind, deliver.text, deliver.author], ['deliver', 'hello there', 'chat-user-4']);
  assert.equal(flowOf(message('chat-room-1', 'chat-user-4', 'Boolean', 'true'), 5)!.kind, 'reply');
  assert.equal(flowOf(message('page', 'chat-user-4', 'Direct', 'Direct{text=hi}'), 5)!.kind, 'direct');
  assert.equal(flowOf({ name: 'log', actorId: 'x', at: 0, data: {} } as unknown as ActorEvent, 5), null);
});

test('each message is a dot until it lands and an entry in both actors\' history', () => {
  const state = createFlowState();
  addFlow(state, flowOf(message('chat-user-4', 'chat-room-1', 'Post', 'Post{from=chat-user-4, text=a}', 2), 0)!, 0);
  assert.equal(state.nodeOf.get('chat-user-4'), 2);
  assert.equal(historyOf(state, 'chat-user-4').length, 1);
  assert.equal(historyOf(state, 'chat-room-1').length, 1);
  assert.deepEqual(countsOf(state, 'chat-user-4', 10), { sent: 1, received: 0 });
  assert.deepEqual(countsOf(state, 'chat-room-1', 10), { sent: 0, received: 1 });
  assert.equal(pruneDots(state, FLOW_MS - 1), true);
  assert.equal(pruneDots(state, FLOW_MS), false);
  assert.equal(progress({ start: 0, duration: 100 }, 0), 0);
  assert.equal(progress({ start: 0, duration: 100 }, 100), 1);
});

test('the history keeps the newest messages per actor only', () => {
  const state = createFlowState();
  for (let i = 0; i < HISTORY + 10; i++) addFlow(state, flowOf(message('chat-user-1', 'chat-room-1', 'Post', `Post{from=chat-user-1, text=${i}}`), i)!, i);
  const list = historyOf(state, 'chat-user-1');
  assert.equal(list.length, HISTORY);
  assert.equal(list.at(-1)!.text, String(HISTORY + 9));
});

test('a message sent by hand from the page, and each room copy of a broadcast, is marked manual and travels slower', () => {
  const direct = flowOf(message('page', 'chat-user-4', 'Direct', 'Direct{from=page, text=hi}'), 0)!;
  assert.deepEqual([direct.kind, direct.manual, direct.text], ['direct', true, 'hi']);
  const copy = flowOf(message('chat-room-1', 'chat-user-7', 'Deliver', 'Deliver{roomId=chat-room-1, from=page, text=all of you}'), 0)!;
  assert.deepEqual([copy.kind, copy.manual, copy.author], ['deliver', true, 'page']);
  assert.equal(flowOf(message('chat-user-4', 'chat-room-1', 'Post', 'Post{from=chat-user-4, text=a}'), 0)!.manual, false);
  const state = createFlowState();
  addFlow(state, direct, 0);
  assert.equal(pruneDots(state, FLOW_MS + 1), true);
});

test('a room sends on a post only once the post lands at the room', () => {
  const state = createFlowState();
  addFlow(state, flowOf(message('chat-user-4', 'chat-room-1', 'Post', 'Post{from=chat-user-4, text=hello, epoch=1}'), 0)!, 0);
  // The room's events come in right after the post's, while the post is still on its way.
  addFlow(state, flowOf(message('chat-room-1', 'chat-user-7', 'Deliver', 'Deliver{roomId=chat-room-1, from=chat-user-4, text=hello}'), 5)!, 5);
  addFlow(state, flowOf(message('chat-room-1', 'chat-user-4', 'Boolean', 'true'), 6)!, 6);
  addFlow(state, flowOf(message('chat-room-1', 'chat-user-8', 'Deliver', 'Deliver{roomId=chat-room-1, from=chat-user-9, text=other}'), 7)!, 7);
  const [, deliver, reply, unrelated] = state.dots;
  assert.equal(deliver.start, FLOW_MS);
  assert.equal(reply.start, FLOW_MS);
  assert.equal(unrelated.start, 7);
});

test("a room's history holds what it received, and counts what it sent", () => {
  const state = createFlowState();
  addFlow(state, flowOf(message('chat-user-4', 'chat-room-1', 'Post', 'Post{from=chat-user-4, text=hello}'), 0)!, 0);
  for (const member of ['chat-user-7', 'chat-user-8', 'chat-user-9']) {
    addFlow(state, flowOf(message('chat-room-1', member, 'Deliver', 'Deliver{roomId=chat-room-1, from=chat-user-4, text=hello}'), 1)!, 1);
  }
  assert.deepEqual(historyOf(state, 'chat-room-1').map((f) => f.type), ['Post']);
  assert.deepEqual(countsOf(state, 'chat-room-1', 10), { sent: 3, received: 1 });
  assert.equal(historyOf(state, 'chat-user-7').length, 1);
});
