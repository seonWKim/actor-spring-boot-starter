import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFrameBatcher } from './frameBatch.ts';

/** A fake animation-frame clock: frames run only when the test calls runFrame. */
function fakeFrames() {
  let next = 1;
  const pending = new Map<number, () => void>();
  return {
    schedule: (callback: () => void) => {
      const id = next++;
      pending.set(id, callback);
      return id;
    },
    cancel: (id: number) => void pending.delete(id),
    runFrame: () => {
      const callbacks = [...pending.values()];
      pending.clear();
      for (const callback of callbacks) callback();
    },
    get pending() {
      return pending.size;
    },
  };
}

test('1000 events in one frame make one flush, and the next frame makes the next flush', () => {
  const frames = fakeFrames();
  const flushes: number[][] = [];
  const batcher = createFrameBatcher<number>((batch) => flushes.push(batch), frames.schedule, frames.cancel);

  for (let i = 0; i < 1000; i++) batcher.push(i);
  assert.equal(flushes.length, 0, 'nothing flushes before the frame');
  assert.equal(frames.pending, 1, 'one frame is asked for, not one per event');
  frames.runFrame();
  assert.equal(flushes.length, 1);
  assert.deepEqual(flushes[0], Array.from({ length: 1000 }, (_, i) => i));

  batcher.push(1000);
  batcher.push(1001);
  frames.runFrame();
  assert.equal(flushes.length, 2);
  assert.deepEqual(flushes[1], [1000, 1001]);

  frames.runFrame();
  assert.equal(flushes.length, 2, 'a frame without events flushes nothing');
});

test('cancel drops the waiting events and the frame', () => {
  const frames = fakeFrames();
  const flushes: number[][] = [];
  const batcher = createFrameBatcher<number>((batch) => flushes.push(batch), frames.schedule, frames.cancel);
  batcher.push(1);
  batcher.cancel();
  assert.equal(frames.pending, 0);
  frames.runFrame();
  assert.deepEqual(flushes, []);
  batcher.push(2);
  frames.runFrame();
  assert.deepEqual(flushes, [[2]]);
});
