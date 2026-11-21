import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { chatLineOf } from '../lib/chatLines.ts';
import { ChatLineRow } from './ChatLineRow.tsx';
import { CONSOLE_LINE_PX, atBottom, clampConsoleHeight, maxConsoleHeight, stepConsoleHeight, countUnseen, highlightOf, loadAutoScroll, saveAutoScroll, type LogLevel, type LogLine } from '../lib/log.ts';

const LEVEL_CLASS: Record<LogLevel, string> = {
  info: 'text-log-ink',
  timeout: 'text-log-timeout',
  error: 'text-log-error',
};

interface Props {
  lines: readonly LogLine[];
  collapsed: boolean;
  onToggle(): void;
  /** The console's height in px now (one line when collapsed), and the viewport height its limit comes from. */
  height: number;
  viewportHeight: number;
  /** The reader moves the edge: the wanted height in px, at most once per frame while dragging. */
  onResize(px: number): void;
  /** A drag or key press is done at this height: save it. */
  onResizeEnd(px: number): void;
  /** Double-click on the handle: back to the default height. */
  onResetHeight(): void;
  /** The actor to light on the ring while a line is hovered or clicked; null clears it. */
  onHighlight(actorId: string | null): void;
}

/**
 * The log docked at the bottom of the page: dark, monospace, one line each of time, actor id and
 * text; timeouts and errors in their own colours. It follows the newest line unless the reader has
 * scrolled up. Hovering a line lights its actor on the ring; a click keeps it lit (click again to clear).
 */
export function LogConsole({ lines, collapsed, onToggle, height, viewportHeight, onResize, onResizeEnd, onResetHeight, onHighlight }: Props) {
  const list = useRef<HTMLOListElement>(null);
  const following = useRef(true);
  const [pinned, setPinned] = useState<number | null>(null);
  const [autoScroll, setAutoScroll] = useState(loadAutoScroll);
  // Chat lines are drawn as bubbles; the relative times follow this clock.
  const chat = useMemo(() => new Map(lines.map((line) => [line.id, chatLineOf(line)])), [lines]);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (collapsed) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(timer);
  }, [collapsed]);
  const newestId = lines.length > 0 ? lines[lines.length - 1].id : -1;
  const [seenId, setSeenId] = useState(newestId);

  useEffect(() => {
    const el = list.current;
    if (el && autoScroll && following.current) {
      el.scrollTop = el.scrollHeight;
      setSeenId(newestId);
    }
  }, [lines, collapsed, autoScroll, newestId]);

  // Expanding starts at the newest line whatever the setting.
  useEffect(() => {
    const el = list.current;
    if (!collapsed && el) {
      following.current = true;
      el.scrollTop = el.scrollHeight;
      setSeenId(newestId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collapsed]);

  const unseen = countUnseen(lines, seenId);
  const toggleAutoScroll = () => {
    const next = !autoScroll;
    setAutoScroll(next);
    saveAutoScroll(next);
    if (next) {
      following.current = true;
      setSeenId(newestId);
    }
  };
  const jumpToNewest = () => {
    const el = list.current;
    if (el) el.scrollTop = el.scrollHeight;
    following.current = true;
    setSeenId(newestId);
  };

  // Dragging the handle: the height follows the pointer (captured), reported once per animation frame.
  const drag = useRef<{ y: number; height: number; px: number; frame: number } | null>(null);
  const onDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { y: e.clientY, height, px: height, frame: 0 };
  };
  const onMove = (e: PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (!d) return;
    d.px = clampConsoleHeight(d.height + d.y - e.clientY, viewportHeight);
    if (d.frame === 0) {
      d.frame = requestAnimationFrame(() => {
        d.frame = 0;
        onResize(d.px);
      });
    }
  };
  const onUp = () => {
    const d = drag.current;
    if (!d) return;
    drag.current = null;
    if (d.frame !== 0) cancelAnimationFrame(d.frame);
    if (d.px !== height) onResize(d.px);
    onResizeEnd(d.px);
  };
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const next = stepConsoleHeight(height, collapsed, e.key, viewportHeight);
    if (!next) return;
    e.preventDefault();
    onResize(next.height);
    onResizeEnd(next.height);
  };

  const last = lines[lines.length - 1];
  return (
    <section
      aria-label="Log"
      data-collapsed={collapsed}
      className="absolute inset-x-0 bottom-0 flex flex-col border-t border-log-line bg-log-bg font-mono text-xs text-log-ink"
      style={{ height: 'var(--console-h)' }}
    >
      <div
        role="separator"
        aria-orientation="horizontal"
        aria-label="Resize log"
        aria-valuemin={CONSOLE_LINE_PX}
        aria-valuemax={maxConsoleHeight(viewportHeight)}
        aria-valuenow={height}
        tabIndex={0}
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
        onPointerCancel={onUp}
        onKeyDown={onKeyDown}
        onDoubleClick={onResetHeight}
        style={{ touchAction: 'none' }}
        className="absolute inset-x-0 -top-1 z-10 h-2 cursor-row-resize touch-none outline-none focus-visible:bg-log-muted"
      />
      <div style={{ height: CONSOLE_LINE_PX }} className="flex w-full shrink-0 items-center gap-2 pr-2 text-log-muted">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={!collapsed}
          className="flex h-full min-w-0 flex-1 cursor-pointer items-center gap-2 truncate px-2 text-left"
        >
          <span aria-hidden>{collapsed ? '▸' : '▾'}</span>
          <span>log</span>
          {collapsed && last && <span className={`truncate ${LEVEL_CLASS[last.level]}`}>{`${last.time} ${last.actorId} ${last.text}`}</span>}
        </button>
        {!collapsed && !autoScroll && unseen > 0 && (
          <button type="button" onClick={jumpToNewest} className="shrink-0 cursor-pointer rounded px-1 text-log-ink hover:bg-log-hover">
            {`${unseen} new`}
          </button>
        )}
        <button
          type="button"
          onClick={toggleAutoScroll}
          aria-pressed={autoScroll}
          className={`shrink-0 cursor-pointer rounded border border-log-line px-1.5 ${autoScroll ? 'text-log-ink' : 'text-log-muted'}`}
        >
          Auto-scroll
        </button>
      </div>
      {!collapsed && (
        <ol
          ref={list}
          onScroll={(e) => {
            const el = e.currentTarget;
            following.current = atBottom(el.scrollTop, el.clientHeight, el.scrollHeight);
            if (following.current) setSeenId(newestId);
          }}
          // No scroll anchoring: when the oldest lines drop off the top, the browser would move the view
          // and the console would take it for the reader scrolling up, and stop following.
          className="min-h-0 flex-1 overflow-y-auto px-2 pb-1 [overflow-anchor:none]"
        >
          {lines.map((line) => {
            const chatLine = chat.get(line.id) ?? null;
            return (
              <li
                key={line.id}
                data-level={line.level}
                data-actor-id-log={line.actorId}
                data-chat={chatLine ? chatLine.kind : undefined}
                onMouseEnter={() => onHighlight(highlightOf(line))}
                onMouseLeave={() => onHighlight(highlightOf(lines.find((l) => l.id === pinned) ?? null))}
                onClick={(e) => {
                  e.stopPropagation();
                  setPinned(pinned === line.id ? null : line.id);
                  onHighlight(pinned === line.id ? null : highlightOf(line));
                }}
                className={`flex cursor-pointer gap-2 hover:bg-log-hover ${
                  chatLine ? 'items-start py-0.5 text-log-ink' : `whitespace-pre ${LEVEL_CLASS[line.level]}`
                } ${pinned === line.id ? 'bg-log-hover' : ''}`}
              >
                {chatLine ? (
                  <ChatLineRow line={chatLine} now={now} />
                ) : (
                  <>
                    <span className="text-log-muted">{line.time}</span>
                    <span className="shrink-0">{line.actorId}</span>
                    <span className="truncate">{line.text}</span>
                  </>
                )}
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
