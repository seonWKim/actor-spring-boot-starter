import { platformCommand } from '../lib/api.ts';

/**
 * An action's error, or, when the platform runs the nodes (Kubernetes), the command that does the action
 * there, shown as something to run rather than as a failure.
 */
export function ErrorOrCommand({ error }: { error: string }) {
  const command = platformCommand(error);
  if (command === null) {
    return (
      <p role="alert" className="text-[12px] text-log-error">
        {error}
      </p>
    );
  }
  return (
    <div className="text-[12px]" data-platform-command>
      <p className="text-log-muted">Run on the platform:</p>
      <code className="mt-1 block select-all break-words rounded bg-log-hover px-2 py-1.5 font-mono text-log-ink">{command}</code>
    </div>
  );
}
