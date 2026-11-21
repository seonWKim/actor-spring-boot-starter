// The log console's state: the `log` events (GET /api/chat/logs and the event stream) as lines, kept at
// most MAX_LOG_LINES, newest last. Pure: the page batches events per animation frame (App.tsx), so a
// burst is one appendLines call and one state update.
import { asText } from './api.ts';
import type { ActorEvent } from './events.ts';

/** The console keeps, and the DOM draws, at most this many lines. */
export const MAX_LOG_LINES = 500;

export type LogLevel = 'info' | 'timeout' | 'error';

export interface LogLine {
  /** Counts up per line, so a list key stays the same while older lines drop off. */
  id: number;
  /** The time the actor logged it, as `HH:MM:SS` (UTC, as sent), or the text as received. */
  time: string;
  actorId: string;
  level: LogLevel;
  text: string;
  /** What happened, from the log event's `kind` (joined, sent, timeout, ...); empty when it has none. */
  kind: string;
  /** The time the actor logged it in ms since the epoch, or NaN when the stamp is not a time. */
  at: number;
}

/** The level of a log event: a timeout (kind `timeout`, level `warn`), an error, or info. */
export function levelOf(data: Record<string, unknown>): LogLevel {
  const level = asText(data.level).toLowerCase();
  if (asText(data.kind) === 'timeout' || level === 'timeout' || level === 'warn') return 'timeout';
  return level === 'error' ? 'error' : 'info';
}

const CLOCK = /T(\d{2}:\d{2}:\d{2})/;

/** The line a log event stands for, or null for any other event. `id` is set by appendLines. */
export function lineOf(event: ActorEvent): Omit<LogLine, 'id'> | null {
  if (event.name !== 'log') return null;
  const stamp = asText(event.data.timestamp);
  return {
    time: CLOCK.exec(stamp)?.[1] ?? stamp,
    actorId: event.actorId,
    level: levelOf(event.data),
    text: asText(event.data.message),
    kind: asText(event.data.kind),
    at: Date.parse(stamp),
  };
}

/** What to keep of a list: the numbering and the lines. */
export interface LogState {
  next: number;
  lines: readonly LogLine[];
}

export const EMPTY_LOG: LogState = { next: 0, lines: [] };

/** The state after a batch of events: its log lines added, only the newest MAX_LOG_LINES kept. */
export function appendLines(state: LogState, events: readonly ActorEvent[]): LogState {
  let next = state.next;
  const added: LogLine[] = [];
  for (const event of events) {
    const line = lineOf(event);
    if (line) added.push({ ...line, id: next++ });
  }
  if (added.length === 0) return state;
  return { next, lines: [...state.lines, ...added].slice(-MAX_LOG_LINES) };
}

/** The actor a log line lights on the ring when the reader hovers or clicks it. */
export function highlightOf(line: Pick<LogLine, 'actorId'> | null): string | null {
  return line?.actorId ?? null;
}

/** The console is this share of the viewport height on a desktop screen, and one line when collapsed. */
export const CONSOLE_VH = 20;
export const CONSOLE_LINE_PX = 32;
/** Below this viewport width (a phone) the console starts collapsed. */
export const COLLAPSE_BELOW_PX = 640;

export const initialCollapsed = (viewportWidth: number): boolean => viewportWidth < COLLAPSE_BELOW_PX;

/** The console's height in px: one line when collapsed, else CONSOLE_VH of the viewport. */
export const consoleHeight = (viewportHeight: number, collapsed: boolean): number =>
  collapsed ? CONSOLE_LINE_PX : Math.round((viewportHeight * CONSOLE_VH) / 100);

/** The console's height as a CSS length, which the ring above it leaves free. */
export const consoleHeightCss = (collapsed: boolean): string => (collapsed ? `${CONSOLE_LINE_PX}px` : `${CONSOLE_VH}vh`);

/** The console is at most this share of the viewport height. */
export const CONSOLE_MAX_VH = 70;
/** One arrow key press moves the console edge by this many px. */
export const CONSOLE_STEP_PX = 24;

/** The tallest the console may be in px: CONSOLE_MAX_VH of the viewport height (630 at 900, 590 at 844). */
export const maxConsoleHeight = (viewportHeight: number): number =>
  Math.max(CONSOLE_LINE_PX, Math.floor((viewportHeight * CONSOLE_MAX_VH) / 100));

/** A height held within one line (the header, equal to collapsed) and the most the viewport allows. */
export const clampConsoleHeight = (height: number, viewportHeight: number): number =>
  Math.min(Math.max(height, CONSOLE_LINE_PX), maxConsoleHeight(viewportHeight));

/** The default height of an expanded console (CONSOLE_VH of the viewport). */
export const defaultConsoleHeight = (viewportHeight: number): number => consoleHeight(viewportHeight, false);

/**
 * The console after an arrow key: ArrowUp grows and ArrowDown shrinks it by CONSOLE_STEP_PX, clamped.
 * Reaching the minimum collapses it; from collapsed, ArrowUp expands to one line plus a step. Other keys: null.
 */
export function stepConsoleHeight(
  height: number,
  collapsed: boolean,
  key: string,
  viewportHeight: number,
): { height: number; collapsed: boolean } | null {
  if (key !== 'ArrowUp' && key !== 'ArrowDown') return null;
  const from = collapsed ? CONSOLE_LINE_PX : height;
  const next = clampConsoleHeight(from + (key === 'ArrowUp' ? CONSOLE_STEP_PX : -CONSOLE_STEP_PX), viewportHeight);
  return { height: next, collapsed: next <= CONSOLE_LINE_PX };
}

/** Whether a scroll position is at the newest line (within a few px), so new lines keep it there. */
export const atBottom = (scrollTop: number, clientHeight: number, scrollHeight: number): boolean =>
  scrollHeight - scrollTop - clientHeight <= 4;

/** The localStorage key of the console's auto-scroll setting. */
export const AUTO_SCROLL_KEY = 'sharded-chat.log.autoScroll';

/** The browser's storage when there is one (it can be missing or throw on access), else undefined. */
function browserStorage(): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | undefined {
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

/** The saved auto-scroll setting: true when it is missing, unreadable or anything but "off". */
export function loadAutoScroll(storage: Pick<Storage, 'getItem'> | undefined = browserStorage()): boolean {
  try {
    return storage?.getItem(AUTO_SCROLL_KEY) !== 'off';
  } catch {
    return true;
  }
}

/** Saves the auto-scroll setting; does nothing when storage is missing or throws. */
export function saveAutoScroll(on: boolean, storage: Pick<Storage, 'setItem'> | undefined = browserStorage()): void {
  try {
    storage?.setItem(AUTO_SCROLL_KEY, on ? 'on' : 'off');
  } catch {
    // Storage is unavailable: the setting just lasts for this page.
  }
}

/** How many lines have an id above the last seen one. */
export const countUnseen = (lines: readonly LogLine[], seenId: number): number => {
  let n = 0;
  for (const line of lines) if (line.id > seenId) n++;
  return n;
};

/** The localStorage key of the console's chosen height in px. */
export const CONSOLE_HEIGHT_KEY = 'sharded-chat.log.height';

/** The saved height in px, or null (use the default) when it is missing, unreadable or out of range. */
export function loadConsoleHeight(viewportHeight: number, storage: Pick<Storage, 'getItem'> | undefined = browserStorage()): number | null {
  try {
    const raw = storage?.getItem(CONSOLE_HEIGHT_KEY);
    if (raw === null || raw === undefined || raw.trim() === '') return null;
    const height = Number(raw);
    return Number.isFinite(height) && height > CONSOLE_LINE_PX && height <= maxConsoleHeight(viewportHeight) ? height : null;
  } catch {
    return null;
  }
}

/** Saves the height in px, or clears it for null; does nothing when storage is missing or throws. */
export function saveConsoleHeight(height: number | null, storage: Pick<Storage, 'setItem' | 'removeItem'> | undefined = browserStorage()): void {
  try {
    if (height === null) storage?.removeItem(CONSOLE_HEIGHT_KEY);
    else storage?.setItem(CONSOLE_HEIGHT_KEY, String(Math.round(height)));
  } catch {
    // Storage is unavailable: the height just lasts for this page.
  }
}
