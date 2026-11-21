import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createAvatarCache, ringAvatarRadius } from './avatars.ts';
import { FIRST_NAMES, LAST_NAMES, nameOf, personaOf } from './persona.ts';

const here = import.meta.dirname;

test('the same user id gives the same name and avatar, also after a fresh module load', async () => {
  const a = personaOf('chat-user-7');
  const b = personaOf('chat-user-7');
  assert.deepEqual(a, b);
  assert.match(a.avatar, /^data:image\/svg\+xml/);
  const fresh = (await import(`./persona.ts?fresh=${Date.now()}`)) as typeof import('./persona.ts');
  assert.deepEqual(fresh.personaOf('chat-user-7'), a);
});

test('two different user ids give different avatars', () => {
  const avatars = new Set(Array.from({ length: 20 }, (_, i) => personaOf(`chat-user-${i + 1}`).avatar));
  assert.equal(avatars.size, 20);
});

test('the name is a first and a last name from the built-in lists, and the names vary', () => {
  const names = Array.from({ length: 100 }, (_, i) => nameOf(`chat-user-${i + 1}`));
  for (const name of names) {
    const [first, last] = name.split(' ');
    assert.ok((FIRST_NAMES as readonly string[]).includes(first), name);
    assert.ok((LAST_NAMES as readonly string[]).includes(last), name);
  }
  assert.ok(new Set(names).size > 60);
});

test('persona.ts and avatars.ts read no random number, clock or stored state', () => {
  for (const file of ['persona.ts', 'avatars.ts']) {
    const source = readFileSync(join(here, file), 'utf8');
    for (const word of ['Math.random', 'Date', 'localStorage', 'sessionStorage', 'performance.now', 'crypto.']) {
      assert.ok(!source.includes(word), `${file} uses ${word}`);
    }
  }
});

test('an avatar is generated locally: no fetch is made', () => {
  const real = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = (() => {
    calls++;
    return Promise.reject(new Error('no network'));
  }) as typeof fetch;
  try {
    personaOf('chat-user-901');
    personaOf('chat-user-902');
  } finally {
    globalThis.fetch = real;
  }
  assert.equal(calls, 0);
  const sources = ['persona.ts', 'avatars.ts'].map((f) => readFileSync(join(here, f), 'utf8')).join('\n');
  assert.match(sources, /@dicebear\/core/);
  assert.match(sources, /@dicebear\/notionists/);
  assert.doesNotMatch(sources, /https?:\/\//);
});

test('each id is generated once: 100 ids with 2 lookups each count 100 generations', () => {
  let generations = 0;
  const cache = createAvatarCache((seed) => {
    generations++;
    return `uri:${seed}`;
  });
  for (let round = 0; round < 2; round++) {
    for (let i = 1; i <= 100; i++) assert.equal(cache.get(`chat-user-${i}`), `uri:chat-user-${i}`);
  }
  assert.equal(generations, 100);
});

test('the ring avatar is at least the minimum, grows with the dot and shrinks to fit its place', () => {
  assert.ok(ringAvatarRadius(1, 300, 10) >= 5);
  assert.ok(ringAvatarRadius(9, 300, 10) >= ringAvatarRadius(6, 300, 10));
  assert.ok(ringAvatarRadius(9, 300, 1000) < 5);
});
