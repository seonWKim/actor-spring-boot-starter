import { useEffect, useState } from 'react';
import type { Api } from '../lib/api.ts';
import { hubLabel, nodeFacts, type ClusterNode } from '../lib/nodes.ts';
import { ErrorOrCommand } from './ErrorOrCommand.tsx';
import { BUTTON } from './ui.ts';

const closeButton = 'h-8 w-8 cursor-pointer rounded text-log-muted hover:bg-log-hover hover:text-log-ink';

interface Props {
  api: Api;
  node: ClusterNode;
  /** After a stop or a start, so the page reads the nodes again. */
  onChanged(): void;
  onClose(): void;
}

/**
 * The right-side dialog of a clicked node: its color and version, port, status and how many rooms and
 * users run there, and a button to stop it (gracefully: it leaves its cluster and its shards move) or
 * start it again. Esc or the close button closes it.
 */
export function NodeModal({ api, node, onChanged, onClose }: Props) {
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const running = node.status !== 'removed';
  const act = async () => {
    setError(null);
    try {
      setResult(await api.nodeAction(node.node, running ? 'stop' : 'start'));
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'not done');
    }
  };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <aside
      role="dialog"
      aria-label={hubLabel(node)}
      className="fixed right-0 top-0 z-30 flex w-full max-w-[380px] min-w-0 flex-col gap-3 border-l border-log-line bg-log-bg p-4 text-log-ink shadow-2xl"
      style={{ bottom: 'var(--console-h)' }}
    >
      <header className="flex min-w-0 items-start justify-between gap-2">
        <span className="truncate text-[15px] font-semibold text-log-ink">{hubLabel(node)}</span>
        <button type="button" onClick={onClose} aria-label="Close" className={closeButton}>
          ✕
        </button>
      </header>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        {nodeFacts(node).map(([name, value]) => (
          <div key={name} className="contents">
            <dt className="text-log-muted">{name}</dt>
            <dd className="font-mono text-log-ink tabular-nums" data-fact={name}>
              {value}
            </dd>
          </div>
        ))}
      </dl>
      <div>
        <button type="button" onClick={act} disabled={node.status === 'stopping'} className={BUTTON}>
          {running ? 'Stop node' : 'Start node'}
        </button>
        <p className="mt-2 text-[12px] leading-5 text-log-muted">
          {running
            ? 'It leaves its cluster on purpose: its shards move to the other nodes of its color, and its rooms and users start there.'
            : 'It starts again with its color\'s version and joins its color\'s cluster.'}
        </p>
        {result && <p className="mt-1 text-[12px] text-log-muted">{result}</p>}
        {error && <ErrorOrCommand error={error} />}
      </div>
    </aside>
  );
}
