import { useEffect } from 'react';
import type { Api } from '../lib/api.ts';
import { appendLines, type LogState } from '../lib/log.ts';

// The last 500 log events are read once, so the console starts with the lines logged before the page
// opened. Lines that already arrived live win: the read only fills an empty console.
export function useChatLogs(api: Api, setLog: (update: (current: LogState) => LogState) => void, attempt: number) {
  useEffect(() => {
    let cancelled = false;
    api.readChatLogs().then(
      (events) => {
        if (!cancelled) setLog((current) => (current.lines.length === 0 ? appendLines(current, events) : current));
      },
      () => undefined,
    );
    return () => {
      cancelled = true;
    };
  }, [api, setLog, attempt]);
}
