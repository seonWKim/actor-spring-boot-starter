import type { ChatLine } from '../lib/chatLines.ts';
import { relativeTime } from '../lib/chatLines.ts';
import { Avatar } from './Avatar.tsx';

/**
 * A chat line in the console: the user's 24 px avatar, the name in bold, the room, the relative time
 * and the text in a message bubble. A sent line shows its room as the target; a received line shows
 * the sender and the room; a timeout greys the bubble and tags it "timed out".
 */
export function ChatLineRow({ line, now }: { line: ChatLine; now: number }) {
  const where = line.target ? `→ ${line.target}` : line.room;
  return (
    <>
      <Avatar userId={line.user.id} size={24} />
      <div className="min-w-0 flex-1 font-sans">
        <div className="flex items-baseline gap-2 whitespace-nowrap">
          <b className="text-log-ink">{line.user.name}</b>
          {line.sender && <span className="text-log-muted">{`from ${line.sender.name}`}</span>}
          {line.manual && (
            <span className="flex items-center gap-1 text-log-muted">
              from <span className="shrink-0 rounded-full bg-manual px-1.5 text-[10px] font-bold leading-4 text-log-bg">You</span>
            </span>
          )}
          {where && <span className="text-log-muted">{where}</span>}
          <span className="text-log-muted">{relativeTime(line.at, now) || line.time}</span>
        </div>
        <div
          data-bubble={line.kind}
          data-timed-out={line.timedOut}
          data-manual={line.manual || undefined}
          className={`mt-0.5 inline-flex max-w-full items-center gap-2 rounded-lg border px-2 py-0.5 ${
            line.timedOut
              ? 'border-log-line text-log-muted opacity-60'
              : line.manual
                ? 'border-manual bg-manual font-semibold text-log-bg'
                : 'border-log-line bg-log-hover text-log-ink'
          }`}
        >
          <span className="truncate">{line.bubble}</span>
          {line.timedOut && <span className="shrink-0 rounded bg-log-line px-1 text-[10px] text-log-muted">timed out</span>}
        </div>
      </div>
    </>
  );
}
