import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseEvent, type ActorEvent } from './events.ts';
import {
  CONSOLE_LINE_PX,
  EMPTY_LOG,
  MAX_LOG_LINES,
  appendLines,
  atBottom,
  CONSOLE_HEIGHT_KEY,
  clampConsoleHeight,
  consoleHeight,
  defaultConsoleHeight,
  loadConsoleHeight,
  maxConsoleHeight,
  saveConsoleHeight,
  stepConsoleHeight,
  highlightOf,
  initialCollapsed,
  levelOf,
  lineOf,
} from './log.ts';

const log = (n: number, extra: Record<string, unknown> = {}): ActorEvent => ({
  name: 'log',
  actorId: `chat-user-${n}`,
  data: { message: `line ${n}`, timestamp: '2026-11-20T10:00:00.120Z', kind: 'sent', level: 'info', ...extra },
});

test('a log event is a line with time, actor id and text', () => {
  const event = parseEvent('log', JSON.stringify({ actorId: 'chat-user-3', message: 'sent "hi" to chat-room-1', timestamp: '2026-11-20T10:00:00.120Z', kind: 'sent', level: 'info' }))!;
  assert.deepEqual(lineOf(event), { time: '10:00:00', actorId: 'chat-user-3', level: 'info', text: 'sent "hi" to chat-room-1', kind: 'sent', at: Date.parse('2026-11-20T10:00:00.120Z') });
  assert.equal(lineOf({ name: 'message', actorId: 'a', data: {} }), null);
});

test('timeouts and errors have their own level', () => {
  assert.equal(levelOf({ kind: 'timeout', level: 'warn' }), 'timeout');
  assert.equal(levelOf({ level: 'timeout' }), 'timeout');
  assert.equal(levelOf({ level: 'error' }), 'error');
  assert.equal(levelOf({ kind: 'sent', level: 'info' }), 'info');
});

test('600 lines keep the newest 500, newest last', () => {
  const state = appendLines(EMPTY_LOG, Array.from({ length: 600 }, (_, i) => log(i + 1)));
  assert.equal(state.lines.length, MAX_LOG_LINES);
  assert.equal(state.lines[0].text, 'line 101');
  assert.equal(state.lines.at(-1)!.text, 'line 600');
  const more = appendLines(state, [log(601)]);
  assert.equal(more.lines.length, MAX_LOG_LINES);
  assert.equal(more.lines.at(-1)!.text, 'line 601');
  const ids = more.lines.map((l) => l.id);
  assert.deepEqual(ids, [...ids].sort((a, b) => a - b));
});

test('a batch without log events returns the same state', () => {
  assert.equal(appendLines(EMPTY_LOG, [{ name: 'message', actorId: 'a', data: {} }]), EMPTY_LOG);
});

test('hovering or clicking a line maps to its actor', () => {
  const line = lineOf(log(7))!;
  assert.equal(highlightOf(line), 'chat-user-7');
  assert.equal(highlightOf(null), null);
});

test('the console starts collapsed on a phone only, one line when collapsed, about 20% on desktop', () => {
  assert.equal(initialCollapsed(390), true);
  assert.equal(initialCollapsed(1440), false);
  assert.equal(consoleHeight(844, true), CONSOLE_LINE_PX);
  assert.equal(consoleHeight(900, false), 180);
});

test('the console follows the newest line only while the reader is at the bottom', () => {
  assert.equal(atBottom(400, 100, 500), true);
  assert.equal(atBottom(100, 100, 500), false);
});

test('the console height is clamped to one line and 70% of the viewport at both sizes', () => {
  assert.equal(maxConsoleHeight(900), 630);
  assert.equal(maxConsoleHeight(844), 590);
  assert.equal(clampConsoleHeight(5, 900), CONSOLE_LINE_PX);
  assert.equal(clampConsoleHeight(5000, 900), 630);
  assert.equal(clampConsoleHeight(5000, 844), 590);
  assert.equal(clampConsoleHeight(300, 844), 300);
  assert.equal(defaultConsoleHeight(900), 180);
});

test('arrow keys step the height by 24 px, collapse at the minimum and expand from collapsed to 56', () => {
  assert.deepEqual(stepConsoleHeight(180, false, 'ArrowUp', 900), { height: 204, collapsed: false });
  assert.deepEqual(stepConsoleHeight(180, false, 'ArrowDown', 900), { height: 156, collapsed: false });
  assert.deepEqual(stepConsoleHeight(620, false, 'ArrowUp', 900), { height: 630, collapsed: false });
  assert.deepEqual(stepConsoleHeight(50, false, 'ArrowDown', 900), { height: 32, collapsed: true });
  assert.deepEqual(stepConsoleHeight(32, true, 'ArrowUp', 844), { height: 56, collapsed: false });
  assert.deepEqual(stepConsoleHeight(180, true, 'ArrowDown', 844), { height: 32, collapsed: true });
  assert.equal(stepConsoleHeight(180, false, 'Home', 900), null);
});

test('the chosen height is saved and read back; bad or out-of-range values fall back to null (the default)', () => {
  const data = new Map<string, string>();
  const storage = {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
    removeItem: (k: string) => void data.delete(k),
  };
  assert.equal(loadConsoleHeight(900, storage), null);
  saveConsoleHeight(300, storage);
  assert.equal(data.get(CONSOLE_HEIGHT_KEY), '300');
  assert.equal(loadConsoleHeight(900, storage), 300);
  assert.equal(loadConsoleHeight(300, storage), null);
  for (const bad of ['abc', '', '10', '32', '-5', '9999']) {
    data.set(CONSOLE_HEIGHT_KEY, bad);
    assert.equal(loadConsoleHeight(900, storage), null, bad);
  }
  saveConsoleHeight(null, storage);
  assert.equal(data.has(CONSOLE_HEIGHT_KEY), false);
  const broken = {
    getItem: () => {
      throw new Error('denied');
    },
    setItem: () => {
      throw new Error('denied');
    },
    removeItem: () => {
      throw new Error('denied');
    },
  };
  assert.equal(loadConsoleHeight(900, broken), null);
  assert.doesNotThrow(() => saveConsoleHeight(300, broken));
  assert.doesNotThrow(() => saveConsoleHeight(null, broken));
});
