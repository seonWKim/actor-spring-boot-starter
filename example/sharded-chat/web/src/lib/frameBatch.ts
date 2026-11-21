// Collects items (stream events) and hands them over once per animation frame, so a burst of
// events is one flush and one render. Pure apart from the scheduler, which tests replace.

export interface FrameBatcher<T> {
  /** Queues the item; the first item of a frame asks for the frame. */
  push(item: T): void;
  /** Drops the queued items and the frame asked for. */
  cancel(): void;
}

export function createFrameBatcher<T>(
  flush: (batch: T[]) => void,
  schedule: (callback: () => void) => number = (callback) => requestAnimationFrame(callback),
  cancelFrame: (id: number) => void = (id) => cancelAnimationFrame(id),
): FrameBatcher<T> {
  let queue: T[] = [];
  let frame: number | null = null;
  const run = () => {
    frame = null;
    const batch = queue;
    queue = [];
    if (batch.length > 0) flush(batch);
  };
  return {
    push(item) {
      queue.push(item);
      frame ??= schedule(run);
    },
    cancel() {
      if (frame !== null) cancelFrame(frame);
      frame = null;
      queue = [];
    },
  };
}
