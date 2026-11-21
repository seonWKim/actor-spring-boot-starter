import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, BackendUnreachableError, createApi } from './api.ts';

/** A fetch that answers every call with status and body, and records what was asked. */
function fakeFetch(status: number, body: unknown) {
  const calls: { url: string; method: string; body: unknown }[] = [];
  const fn = (async (url: string, init: RequestInit) => {
    calls.push({ url, method: String(init.method), body: init.body === undefined ? undefined : JSON.parse(String(init.body)) });
    return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status });
  }) as unknown as typeof fetch;
  return { fn, calls };
}

test('the chat graph keeps each room and user with its node, and drops entries without an id', async () => {
  const { fn } = fakeFetch(200, {
    rooms: [{ id: 'chat-room-1', members: ['chat-user-1'], node: 2 }, { members: [] }],
    users: [{ id: 'chat-user-1', rooms: ['chat-room-1'], node: null }],
  });
  const graph = await createApi(fn).readChatGraph();
  assert.deepEqual(graph, {
    rooms: [{ id: 'chat-room-1', members: ['chat-user-1'], node: 2 }],
    users: [{ id: 'chat-user-1', rooms: ['chat-room-1'], node: null }],
  });
});

test('a rate change is sent as messageRate and answers the settings after', async () => {
  const { fn, calls } = fakeFetch(200, { settings: { rooms: 4, users: 30, messageRate: 5, timeoutShare: 0.1 } });
  const settings = await createApi(fn).setChatSetting('rate', 5);
  assert.deepEqual(calls[0], { url: '/api/chat/rate', method: 'PUT', body: { messageRate: 5 } });
  assert.deepEqual(settings, { rooms: 4, users: 30, messageRate: 5 });
});

test('a room number goes in the body; without one there is no body', async () => {
  const { fn, calls } = fakeFetch(200, { roomId: 'chat-room-42', settings: null });
  const api = createApi(fn);
  assert.equal((await api.addChatRoom(42)).roomId, 'chat-room-42');
  await api.addChatRoom();
  assert.deepEqual(calls.map((c) => c.body), [{ number: 42 }, undefined]);
});

test('a 400 is an ApiError with the backend reason; a 5xx or a failed fetch is BackendUnreachableError', async () => {
  await assert.rejects(createApi(fakeFetch(400, { error: 'chat-room-42 already exists', field: 'number' }).fn).addChatRoom(42), (e: unknown) => {
    assert.ok(e instanceof ApiError && !(e instanceof BackendUnreachableError));
    assert.equal(e.message, 'chat-room-42 already exists');
    return true;
  });
  await assert.rejects(createApi(fakeFetch(503, { error: 'starting' }).fn).readChatGraph(), BackendUnreachableError);
  const failing = (async () => {
    throw new Error('connection refused');
  }) as unknown as typeof fetch;
  await assert.rejects(createApi(failing).readNodes(), BackendUnreachableError);
});
