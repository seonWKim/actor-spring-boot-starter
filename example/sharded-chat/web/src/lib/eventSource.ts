/** A stream of named server-sent events. */
export interface EventStream {
  on(name: string, cb: (data: string) => void): void;
  onOpen(cb: () => void): void;
  onError(cb: () => void): void;
  close(): void;
}

/** Wraps the browser EventSource; it reconnects on its own after an error. */
export function openEventSource(url: string): EventStream {
  const source = new EventSource(url);
  return {
    on: (name, cb) => source.addEventListener(name, (e: MessageEvent<string>) => cb(e.data)),
    onOpen: (cb) => {
      source.onopen = () => cb();
    },
    onError: (cb) => {
      source.onerror = () => cb();
    },
    close: () => source.close(),
  };
}
