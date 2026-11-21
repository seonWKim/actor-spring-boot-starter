// The events on the stream GET /api/chat/events (README.md): message, log and member.
import { asText } from './api.ts';

export const EVENT_NAMES = ['message', 'log', 'member'] as const;
export type EventName = (typeof EVENT_NAMES)[number];

export interface ActorEvent {
  name: EventName;
  actorId: string;
  data: Record<string, unknown>;
}

/** Reads one SSE payload; returns null when it is not a JSON object or names no actor. */
export function parseEvent(name: EventName, raw: string): ActorEvent | null {
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof data !== 'object' || data === null || Array.isArray(data)) return null;
  const body = data as Record<string, unknown>;
  const actorId = asText(body.actorId);
  return actorId ? { name, actorId, data: body } : null;
}
