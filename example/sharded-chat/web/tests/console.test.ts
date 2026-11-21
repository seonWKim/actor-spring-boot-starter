// The page at 1440x900 and 390x844: the chat canvas and the bottom log console, no scroll. Source-level
// checks and pure layout math, with fakes: no browser, no backend.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createFrameBatcher } from '../src/lib/frameBatch.ts';
import { AUTO_SCROLL_KEY, EMPTY_LOG, MAX_LOG_LINES, appendLines, consoleHeight, countUnseen, initialCollapsed, loadAutoScroll, saveAutoScroll } from '../src/lib/log.ts';
import type { ActorEvent } from '../src/lib/events.ts';

const srcDir = join(import.meta.dirname, '..', 'src');
const read = (...parts: string[]) => readFileSync(join(srcDir, ...parts), 'utf8');
const app = read('App.tsx');
const consoleSource = read('components', 'LogConsole.tsx');

const DESKTOP = { width: 1440, height: 900 };
const PHONE = { width: 390, height: 844 };

test('the first screen is the chat canvas and the log console, nothing else', () => {
  const returned = app.slice(app.lastIndexOf('return ('));
  const components = new Set([...returned.matchAll(/<([A-Z]\w*)/g)].map((m) => m[1]));
  assert.deepEqual([...components].sort(), ['ActorDrawer', 'ChatCanvas', 'ControlDrawer', 'NodeModal']);
  assert.match(returned, /\{dock\}/);
  assert.match(app, /const dock = \(\s*<LogConsole/);
  assert.doesNotMatch(returned, /<(h\d|nav|header|aside|table|ul|ol)\b/);
});

test('at 1440x900 the page does not scroll: the root is fixed and the canvas and console share the viewport', () => {
  assert.match(app, /fixed inset-0 overflow-hidden/);
  assert.match(read('styles', 'index.css'), /#root \{[^}]*overflow: hidden/);
  const ring = consoleHeight(DESKTOP.height, false);
  assert.ok(ring + (DESKTOP.height - ring) <= DESKTOP.height);
  // The canvas stops above the console and the console is docked to the bottom.
  assert.match(read('components', 'ChatCanvas.tsx'), /bottom: 'var\(--console-h\)'/);
  assert.match(consoleSource, /absolute inset-x-0 bottom-0/);
  assert.match(consoleSource, /var\(--console-h\)/);
});

test('on desktop the console is about 20% of the height; on 390 px it starts collapsed to one line', () => {
  const desktop = consoleHeight(DESKTOP.height, initialCollapsed(DESKTOP.width));
  assert.ok(Math.abs(desktop / DESKTOP.height - 0.2) < 0.01, `${desktop}`);
  assert.equal(initialCollapsed(PHONE.width), true);
  assert.equal(consoleHeight(PHONE.height, initialCollapsed(PHONE.width)), 32);
  assert.match(app, /useState\(\(\) => initialCollapsed\(window\.innerWidth\)\)/);
});

test('the console is dark, monospace and its timeout and error lines have their own colour tokens', () => {
  assert.match(consoleSource, /bg-log-bg/);
  assert.match(consoleSource, /font-mono/);
  assert.match(consoleSource, /timeout: 'text-log-timeout'/);
  assert.match(consoleSource, /error: 'text-log-error'/);
  const tokens = read('styles', 'tokens.css');
  for (const token of ['log-bg', 'log-ink', 'log-timeout', 'log-error']) assert.match(tokens, new RegExp(`--color-${token}:`));
});

test('the DOM draws the same list the cap keeps, and the console follows the newest line', () => {
  assert.match(app, /lines=\{log\.lines\}/);
  assert.match(consoleSource, /lines\.map\(/);
  assert.doesNotMatch(consoleSource, /\.slice\(/);
  assert.match(consoleSource, /scrollTop = el\.scrollHeight/);
});

const log = (n: number): ActorEvent => ({ name: 'log', actorId: 'chat-user-1', data: { message: `line ${n}` } });

test('a burst of log lines within one animation frame is one state update', () => {
  const frames: (() => void)[] = [];
  let updates = 0;
  let state = EMPTY_LOG;
  const batcher = createFrameBatcher<ActorEvent>(
    (batch) => {
      updates++;
      state = appendLines(state, batch);
    },
    (callback) => frames.push(callback),
    () => undefined,
  );
  for (let i = 0; i < 600; i++) batcher.push(log(i));
  assert.equal(updates, 0);
  frames.shift()!();
  assert.equal(updates, 1);
  assert.equal(state.lines.length, MAX_LOG_LINES);
  // The page applies a frame's lines with one setLog call.
  assert.equal(app.match(/setLog\(/g)?.length, 1);
});

test('clicking a line opens its actor', () => {
  assert.match(consoleSource, /onHighlight\(highlightOf\(line\)\)/);
  assert.match(app, /onHighlight=\{\(id\) => id && selectActor\(id\)\}/);
});

test('auto-scroll: the follow effect is guarded by the toggle, which is a sibling button with aria-pressed', () => {
  assert.match(consoleSource, /autoScroll && following\.current/);
  assert.match(consoleSource, /\[lines, collapsed, autoScroll, newestId\]/);
  assert.match(consoleSource, /aria-pressed=\{autoScroll\}/);
  assert.match(consoleSource, /Auto-scroll/);
  assert.match(consoleSource, /saveAutoScroll\(next\)/);
  assert.match(consoleSource, /if \(next\) \{\s*following\.current = true;\s*setSeenId\(newestId\)/);
  assert.match(consoleSource, /!autoScroll && unseen > 0/);
  assert.match(consoleSource, /`\$\{unseen\} new`/);
  // Sibling of the collapse button: the collapse button closes before the toggle opens.
  const collapse = consoleSource.slice(consoleSource.indexOf('aria-expanded'), consoleSource.indexOf('aria-pressed'));
  assert.match(collapse, /<\/button>/);
  assert.match(consoleSource, /min-w-0 flex-1/);
  assert.match(consoleSource, /shrink-0/);
});

test('the unseen count rises by one per line and clears at the newest id', () => {
  let state = appendLines(EMPTY_LOG, [log(0)]);
  const seen = state.lines[state.lines.length - 1].id;
  assert.equal(countUnseen(state.lines, seen), 0);
  for (let i = 1; i <= 3; i++) {
    state = appendLines(state, [log(0)]);
    assert.equal(countUnseen(state.lines, seen), i);
  }
  assert.equal(countUnseen(state.lines, state.lines[state.lines.length - 1].id), 0);
});

test('the auto-scroll setting is saved and read back; it is on without storage or when storage throws', () => {
  const data = new Map<string, string>();
  const fake = {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
  };
  assert.equal(loadAutoScroll(fake), true);
  saveAutoScroll(false, fake);
  assert.equal(data.get(AUTO_SCROLL_KEY), 'off');
  assert.equal(loadAutoScroll(fake), false);
  saveAutoScroll(true, fake);
  assert.equal(loadAutoScroll(fake), true);
  const throwing = {
    getItem: (): string | null => {
      throw new Error('denied');
    },
    setItem: (): void => {
      throw new Error('denied');
    },
  };
  assert.equal(loadAutoScroll(throwing), true);
  assert.doesNotThrow(() => saveAutoScroll(false, throwing));
  assert.equal(loadAutoScroll(undefined), true);
  assert.doesNotThrow(() => saveAutoScroll(false, undefined));
});

test('the drag handle is a focusable horizontal separator before the header row, with a row-resize cursor', () => {
  assert.match(consoleSource, /role="separator"/);
  assert.match(consoleSource, /aria-orientation="horizontal"/);
  assert.match(consoleSource, /tabIndex=\{0\}/);
  assert.match(consoleSource, /aria-valuemin=\{CONSOLE_LINE_PX\}/);
  assert.match(consoleSource, /aria-valuemax=\{maxConsoleHeight\(viewportHeight\)\}/);
  assert.match(consoleSource, /aria-valuenow=\{height\}/);
  assert.match(consoleSource, /cursor-row-resize/);
  assert.match(consoleSource, /touchAction: 'none'/);
  assert.match(consoleSource, /h-2 /);
  assert.match(consoleSource, /setPointerCapture/);
  assert.match(consoleSource, /requestAnimationFrame/);
  assert.match(consoleSource, /onDoubleClick=\{onResetHeight\}/);
  assert.ok(consoleSource.indexOf('role="separator"') < consoleSource.indexOf('aria-expanded'));
});

test('App sets --console-h in px, keeps the last expanded height and saves and resets it', () => {
  assert.match(app, /'--console-h': `\$\{consoleHeight\}px`/);
  assert.match(app, /loadConsoleHeight\(/);
  assert.match(app, /saveConsoleHeight\(null\)/);
  assert.match(app, /setCollapsed\(true\)/);
});

test('the height storage is wrapped in try/catch', () => {
  const log = readFileSync(join(srcDir, 'lib', 'log.ts'), 'utf8');
  const body = log.slice(log.indexOf('export function loadConsoleHeight'));
  assert.equal((body.match(/try \{/g) ?? []).length, 2);
});

test('the canvas keeps measuring its container, which ends at --console-h', () => {
  const canvas = read('components', 'ChatCanvas.tsx');
  assert.match(canvas, /new ResizeObserver\(/);
  assert.match(canvas, /bottom: 'var\(--console-h\)'/);
});
