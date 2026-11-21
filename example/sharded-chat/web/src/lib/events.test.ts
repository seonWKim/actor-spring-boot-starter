import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseEvent } from './events.ts';

test('an event names its actor; a payload that is not a JSON object, or has no actorId, is dropped', () => {
  assert.deepEqual(parseEvent('log', '{"actorId":"chat-user-1","kind":"joined"}'), {
    name: 'log',
    actorId: 'chat-user-1',
    data: { actorId: 'chat-user-1', kind: 'joined' },
  });
  assert.equal(parseEvent('log', 'not json'), null);
  assert.equal(parseEvent('log', '[1]'), null);
  assert.equal(parseEvent('message', '{"sender":"chat-user-1"}'), null);
});
