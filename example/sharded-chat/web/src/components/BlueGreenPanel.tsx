import { useState } from 'react';
import type { Api, DeployState } from '../lib/api.ts';
import { ErrorOrCommand } from './ErrorOrCommand.tsx';
import { BUTTON, PRIMARY, SMALL_BUTTON } from './ui.ts';

type Step = 'deploy' | 'activate' | 'retire';

/** The most nodes a color runs (ControlProperties.MAX_PER_COLOR). */
export const MAX_NODES_PER_COLOR = 6;

const versionNumber = (v: string | null) => (v ? Number(v.replace(/^v/, '')) : 0);

/**
 * The three buttons of the pipeline, each with the color it would act on; the next step is the
 * primary one. Activating a color older than the active one is the rollback.
 */
export function stepsOf(deploy: DeployState): { step: Step; label: string; color: string | null }[] {
  const { next, active, colors } = deploy;
  const older = (c: string | null) =>
    c !== null && active !== null && versionNumber(colors[c as 'blue' | 'green'].version) < versionNumber(colors[active.color as 'blue' | 'green']?.version ?? null);
  const name = (c: string | null) => (c === null ? '' : ` ${c} ${colors[c as 'blue' | 'green'].version ?? ''}`.trimEnd());
  return [
    { step: 'deploy', label: next.deploy ? `Deploy ${next.deploy}` : 'Deploy', color: next.deploy },
    { step: 'activate', label: next.activate ? (older(next.activate) ? `Roll back to${name(next.activate)}` : `Activate${name(next.activate)}`) : 'Activate', color: next.activate },
    { step: 'retire', label: next.retire ? `Retire${name(next.retire)}` : 'Retire', color: next.retire },
  ];
}

const swatch = (color: string) => ({ background: `var(--color-${color})` });

/**
 * Blue-green in the menu: the active generation (color and epoch), both colors with their version,
 * role and warmed-up nodes, and the pipeline's buttons. How it works is folded away.
 */
export function BlueGreenPanel({ api, deploy, onChanged }: { api: Api; deploy: DeployState; onChanged: () => void }) {
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState<Step | 'add' | null>(null);
  const steps = stepsOf(deploy);
  const primary = steps.find((s) => s.color !== null)?.step ?? null;

  const run = async (step: Step) => {
    setRunning(step);
    setError(null);
    setResult(null);
    try {
      setResult(await api.deployStep(step));
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'not done');
    } finally {
      setRunning(null);
    }
  };

  const addNode = async (color: 'blue' | 'green') => {
    setRunning('add');
    setError(null);
    setResult(null);
    try {
      setResult(await api.addNode(color));
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'not done');
    } finally {
      setRunning(null);
    }
  };

  return (
    <section className="space-y-3 border-b border-log-line px-4 py-4" aria-label="Blue-green">
      <h3 className="text-[11px] uppercase tracking-wide text-log-muted">Blue-green</h3>
      <p className="text-[13px]" data-active-generation>
        {deploy.active ? (
          <>
            <span className="mr-1.5 inline-block h-2.5 w-2.5 rounded-full align-middle" style={swatch(deploy.active.color)} />
            <span className="font-semibold">{deploy.active.color}</span> is active at epoch{' '}
            <span className="font-mono tabular-nums">{deploy.active.epoch}</span>
          </>
        ) : (
          <span className="text-log-muted">No color is active yet</span>
        )}
      </p>
      <dl className="grid grid-cols-[auto_1fr_auto_auto] items-center gap-x-3 gap-y-1.5 text-[12px]">
        {(['blue', 'green'] as const).map((c) => {
          const s = deploy.colors[c];
          return (
            <div key={c} className="contents" data-color={c}>
              <dt className="flex items-center gap-1.5 font-medium">
                <span className="inline-block h-2.5 w-2.5 rounded-sm" style={swatch(c)} />
                {c} {s.version ?? ''}
              </dt>
              <dd className="text-log-muted">{s.role === 'none' ? 'not running' : s.role}</dd>
              <dd className="text-right font-mono text-log-muted tabular-nums">{s.nodes > 0 ? `${s.up}/${s.nodes} warm` : ''}</dd>
              <dd>
                <button
                  type="button"
                  aria-label={`Add a ${c} node`}
                  title={s.nodes >= MAX_NODES_PER_COLOR ? `${c} runs ${MAX_NODES_PER_COLOR} nodes, the most` : `Add a ${c} node`}
                  disabled={s.nodes === 0 || s.nodes >= MAX_NODES_PER_COLOR || running !== null}
                  onClick={() => addNode(c)}
                  className={SMALL_BUTTON}
                >
                  + node
                </button>
              </dd>
            </div>
          );
        })}
      </dl>
      <div className="flex flex-wrap gap-2">
        {steps.map((s) => (
          <button
            key={s.step}
            type="button"
            disabled={s.color === null || deploy.busy || running !== null}
            onClick={() => run(s.step)}
            className={s.step === primary ? PRIMARY : BUTTON}
          >
            {running === s.step ? '…' : s.label}
          </button>
        ))}
      </div>
      {!deploy.runsNodes && (
        <p className="text-[12px] text-log-muted" data-platform-runs>
          The platform runs the nodes: each button shows the command that does it there.
        </p>
      )}
      {result && <p className="text-[12px] text-log-muted">{result}</p>}
        {error && <ErrorOrCommand error={error} />}
      <details className="text-[12px] leading-5 text-log-muted">
        <summary className="cursor-pointer select-none text-log-ink">How it works</summary>
        <ol className="mt-1 list-decimal space-y-1 pl-4">
          <li>
            <b className="text-log-ink">Deploy</b> starts the other color as its own cluster, with the next version. It is ready
            once each node's <code>/actuator/health/warmup</code> is UP.
          </li>
          <li>
            <b className="text-log-ink">Activate</b> sends <code>POST /actuator/generation</code> with the epoch it expects: a
            compare-and-set on the one generation record both colors share. The epoch grows by one.
          </li>
          <li>
            Every node reads the new generation within a second. The old color pauses: its users can't stamp a post, and its
            rooms refuse posts stamped with an old epoch. Its rooms and users keep their state.
          </li>
          <li>
            <b className="text-log-ink">Retire</b> stops the old color. Until then, activating it again rolls back, to the chat
            as it was.
          </li>
        </ol>
        <p className="mt-1">
          <b className="text-log-ink">+ node</b> adds a node to a running color (up to {MAX_NODES_PER_COLOR}). It joins that
          color's cluster, and new shards can land on it.
        </p>
        <p className="mt-1">The load settings and new rooms go to the active color.</p>
      </details>
    </section>
  );
}
