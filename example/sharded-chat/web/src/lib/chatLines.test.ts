import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApi } from './api.ts';
import { createAvatarCache } from './avatars.ts';
import { chatLineOf, isUserActor, relativeTime, userCardOf } from './chatLines.ts';
import { EMPTY_LOG, MAX_LOG_LINES, appendLines, type LogLine } from './log.ts';
import { nameOf, type Persona } from './persona.ts';
import type { ActorEvent } from './events.ts';

const event = (actorId: string, kind: string, message: string, level = 'info'): ActorEvent => ({
  name: 'log',
  actorId,
  data: { kind, message, level, timestamp: '2026-11-20T10:00:00.120Z' },
});
const lineOf = (e: ActorEvent): LogLine => appendLines(EMPTY_LOG, [e]).lines[0];

test('a sent line shows the user, the room as the target and the text as the bubble', () => {
  const line = chatLineOf(lineOf(event('chat-user-3', 'sent', 'sent "hello ring" to chat-room-1')));
  assert.ok(line);
  assert.equal(line.kind, 'sent');
  assert.equal(line.user.name, nameOf('chat-user-3'));
  assert.equal(line.room, 'chat-room-1');
  assert.equal(line.target, 'chat-room-1');
  assert.equal(line.bubble, 'hello ring');
  assert.equal(line.timedOut, false);
});

test('a received line shows the sender and the room, and no target', () => {
  const line = chatLineOf(lineOf(event('chat-user-2', 'received', 'received "hi there" from chat-user-3 in chat-room-4')));
  assert.ok(line);
  assert.equal(line.room, 'chat-room-4');
  assert.equal(line.target, null);
  assert.equal(line.sender?.id, 'chat-user-3');
  assert.equal(line.sender?.name, nameOf('chat-user-3'));
  assert.equal(line.bubble, 'hi there');
});

test('joined and left lines read the room from the text', () => {
  const joined = chatLineOf(lineOf(event('chat-user-1', 'joined', 'joined chat-room-2')));
  const left = chatLineOf(lineOf(event('chat-user-1', 'left', 'left chat-room-2 (room stopped)')));
  assert.equal(joined?.room, 'chat-room-2');
  assert.equal(left?.room, 'chat-room-2');
  assert.equal(left?.kind, 'left');
});

test('a timeout line (kind timeout, level warn) is greyed and tagged timed out', () => {
  const line = chatLineOf(lineOf(event('chat-user-5', 'timeout', 'no reply from chat-room-1 within 1000 ms to "are you there"', 'warn')));
  assert.ok(line);
  assert.equal(line.timedOut, true);
  assert.equal(line.room, 'chat-room-1');
  assert.equal(line.bubble, 'are you there');
});

test('system, cluster and account lines are not chat lines', () => {
  for (const kind of ['', 'system', 'cluster', 'account', 'room_created', 'room_stopped']) {
    assert.equal(chatLineOf(lineOf(event('x', kind, 'something happened'))), null, kind);
  }
});

test('the relative time counts seconds, minutes and hours; an unknown time is empty', () => {
  const at = Date.parse('2026-11-20T10:00:00Z');
  assert.equal(relativeTime(at, at + 1000), 'now');
  assert.equal(relativeTime(at, at + 12_000), '12s ago');
  assert.equal(relativeTime(at, at + 3 * 60_000), '3m ago');
  assert.equal(relativeTime(at, at + 2 * 3_600_000), '2h ago');
  assert.equal(relativeTime(Number.NaN, at), '');
});

test('the console keeps at most 500 lines', () => {
  let state = EMPTY_LOG;
  for (let i = 0; i < 12; i++) {
    state = appendLines(state, Array.from({ length: 100 }, (_, n) => event('chat-user-1', 'sent', `sent "m${i * 100 + n}" to chat-room-1`)));
  }
  assert.equal(state.lines.length, MAX_LOG_LINES);
  assert.equal(chatLineOf(state.lines[state.lines.length - 1])?.bubble, 'm1199');
});

test('load check: 100 users at 2 messages a second re-render the lines from the avatar cache, one generation per id', () => {
  let generations = 0;
  const cache = createAvatarCache((seed) => {
    generations++;
    return `uri:${seed}`;
  });
  const persona = (id: string): Persona => ({ id, name: nameOf(id), avatar: cache.get(id) });
  let state = EMPTY_LOG;
  // 60 seconds: 120 messages; the console is drawn again after every second.
  for (let second = 0; second < 60; second++) {
    const batch = [0, 1].map((n) => event(`chat-user-${((second * 2 + n) * 7) % 100 + 1}`, 'sent', `sent "m" to chat-room-1`));
    state = appendLines(state, batch);
    for (const line of state.lines) chatLineOf(line, persona);
  }
  // Every user also shows on the ring each second.
  for (let second = 0; second < 5; second++) for (let i = 1; i <= 100; i++) persona(`chat-user-${i}`);
  assert.equal(generations, 100);
});

test('the chat state gives a user its rooms for the hover card; only user actors have one', () => {
  const state = { users: [{ id: 'chat-user-1', rooms: ['chat-room-1', 'chat-room-3'] }] };
  const card = userCardOf({ id: 'chat-user-1', parent: 'chat-users' }, state);
  assert.equal(card.persona.name, nameOf('chat-user-1'));
  assert.deepEqual(card.rooms, ['chat-room-1', 'chat-room-3']);
  assert.equal(card.process, 'chat-users');
  assert.deepEqual(userCardOf({ id: 'chat-user-9' }, null).rooms, []);
  assert.equal(isUserActor({ kind: 'ChatUserActor' }), true);
  assert.equal(isUserActor({ kind: 'ChatRoomActor' }), false);
  assert.equal(isUserActor({ kind: 'AccountActor' }), false);
});

test('readChatState and readChatLogs read the two chat endpoints', async () => {
  const urls: string[] = [];
  const fn = (async (input: string | URL | Request) => {
    const url = String(input);
    urls.push(url);
    const body = url.endsWith('/state')
      ? { users: [{ id: 'chat-user-1', rooms: ['chat-room-1'], node: 1 }] }
      : [{ type: 'log', actorId: 'chat-user-1', message: 'joined chat-room-1', timestamp: '2026-11-20T10:00:00Z', kind: 'joined', level: 'info' }];
    return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
  }) as typeof fetch;
  const api = createApi(fn);
  assert.deepEqual((await api.readChatGraph()).users, [{ id: 'chat-user-1', rooms: ['chat-room-1'], node: 1 }]);
  const logs = await api.readChatLogs();
  assert.deepEqual(urls, ['/api/chat/state', '/api/chat/logs']);
  assert.equal(logs[0].name, 'log');
  assert.equal(chatLineOf(appendLines(EMPTY_LOG, logs).lines[0])?.room, 'chat-room-1');
});

test('a line received from the page is marked manual, with no persona for the page', () => {
  const direct = chatLineOf(lineOf(event('chat-user-1', 'received', 'received "probe one" from page')))!;
  assert.deepEqual([direct.manual, direct.sender, direct.bubble, direct.room], [true, null, 'probe one', null]);
  const copy = chatLineOf(lineOf(event('chat-user-6', 'received', 'received "probe two" from page in chat-room-57')))!;
  assert.deepEqual([copy.manual, copy.bubble, copy.room], [true, 'probe two', 'chat-room-57']);
  assert.equal(chatLineOf(lineOf(event('chat-user-2', 'received', 'received "x" from chat-user-3 in chat-room-4')))!.manual, false);
});
