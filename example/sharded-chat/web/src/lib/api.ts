// Client for the HTTP API the page reads (README.md, "HTTP API"), served by the control app: chat
// requests go to the active color (or to the color an id names), reads merge both colors.
import { toClusterNode, type ClusterNode } from './nodes.ts';
import type { ActorEvent } from './events.ts';

/** GET /api/chat/state as the canvas reads it: the rooms with their members, the users with their rooms. */
export interface ChatGraph {
  rooms: { id: string; members: string[]; node: number | null }[];
  users: { id: string; rooms: string[]; node: number | null }[];
}

/** The active generation: the color that chats, its epoch, and the operation that activated it. */
export interface Generation {
  color: string;
  epoch: number;
  operationId: string;
  activatedAt: string;
}

/** One deployment color as GET /api/deploy reports it. */
export interface ColorState {
  version: string | null;
  /** Running nodes, and how many of them are warmed up. */
  nodes: number;
  up: number;
  /** active, standby (running, not active) or none (no node runs). */
  role: 'active' | 'standby' | 'none';
}

/** GET /api/deploy: the active generation, both colors, and what the pipeline can do next. */
export interface DeployState {
  active: Generation | null;
  colors: { blue: ColorState; green: ColorState };
  busy: boolean;
  next: { deploy: string | null; activate: string | null; retire: string | null };
}

export const NO_DEPLOY: DeployState = {
  active: null,
  colors: { blue: { version: null, nodes: 0, up: 0, role: 'none' }, green: { version: null, nodes: 0, up: 0, role: 'none' } },
  busy: false,
  next: { deploy: null, activate: null, retire: null },
};

/** The settings of the simulation the menu changes. */
export interface ChatSettings {
  rooms: number;
  users: number;
  messageRate: number;
}

/** GET /api/chat/state as the menu reads it: the settings (null when unreadable) and the ids. */
export interface ChatRun {
  settings: ChatSettings | null;
  rooms: string[];
  users: string[];
}

/** Where a message from the page went, and whether the target answered in time. */
export interface Delivery {
  target: string;
  delivered: string[];
  replied: boolean;
}

/** A 4xx answer: the request was understood and refused; the message is the backend's reason. */
export class ApiError extends Error {
  override name = 'ApiError';
  readonly body: unknown;
  constructor(message: string, body: unknown = null) {
    super(message);
    this.body = body;
  }
}

/** No usable answer: a network failure, a 404, a 5xx, or a body that is not JSON. */
export class BackendUnreachableError extends ApiError {
  override name = 'BackendUnreachableError';
}

type Json = Record<string, unknown>;

export const isObject = (value: unknown): value is Json => typeof value === 'object' && value !== null;

export const asText = (value: unknown): string =>
  value === undefined || value === null ? '' : typeof value === 'string' ? value : JSON.stringify(value);

function listFrom(body: unknown, ...keys: string[]): unknown[] {
  if (Array.isArray(body)) return body;
  if (isObject(body)) {
    for (const key of keys) {
      const value = body[key];
      if (Array.isArray(value)) return value;
    }
  }
  return [];
}

const nodeField = (value: unknown): number | null => (typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : null);

const textList = (value: unknown): string[] => (Array.isArray(value) ? value.map(asText).filter((t) => t !== '') : []);

const toCount = (value: unknown): number => (typeof value === 'number' && Number.isFinite(value) ? value : 0);

const textOrNull = (value: unknown): string | null => (typeof value === 'string' && value !== '' ? value : null);

function toColorState(raw: unknown): ColorState {
  const c = isObject(raw) ? raw : {};
  const role = c.role === 'active' || c.role === 'standby' ? c.role : 'none';
  return { version: textOrNull(c.version), nodes: toCount(c.nodes), up: toCount(c.up), role };
}

/** Reads the deploy reply; anything missing reads as nothing running. */
export function toDeployState(body: unknown): DeployState {
  const raw = isObject(body) ? body : {};
  const a = isObject(raw.active) ? raw.active : null;
  const colors = isObject(raw.colors) ? raw.colors : {};
  const next = isObject(raw.next) ? raw.next : {};
  return {
    active: a ? { color: asText(a.color), epoch: toCount(a.epoch), operationId: asText(a.operationId), activatedAt: asText(a.activatedAt) } : null,
    colors: { blue: toColorState(colors.blue), green: toColorState(colors.green) },
    busy: raw.busy === true,
    next: { deploy: textOrNull(next.deploy), activate: textOrNull(next.activate), retire: textOrNull(next.retire) },
  };
}

/** Reads the chat state reply; anything malformed is left out. */
export function toChatGraph(body: unknown): ChatGraph {
  const raw = isObject(body) ? body : {};
  const list = (key: string) => listFrom(raw, key).filter(isObject);
  return {
    rooms: list('rooms')
      .map((r) => ({ id: asText(r.id), members: textList(r.members), node: nodeField(r.node) }))
      .filter((r) => r.id !== ''),
    users: list('users')
      .map((u) => ({ id: asText(u.id), rooms: textList(u.rooms), node: nodeField(u.node) }))
      .filter((u) => u.id !== ''),
  };
}

const toChatSettings = (raw: unknown): ChatSettings | null =>
  isObject(raw) ? { rooms: toCount(raw.rooms), users: toCount(raw.users), messageRate: toCount(raw.messageRate) } : null;

const idsOf = (raw: Json, key: string): string[] =>
  listFrom(raw, key)
    .filter(isObject)
    .map((item) => asText(item.id))
    .filter((id) => id !== '');

const toDelivery = (body: unknown): Delivery => {
  const raw = isObject(body) ? body : {};
  return { target: asText(raw.target), delivered: listFrom(raw, 'delivered').map(asText), replied: raw.replied === true };
};

function errorBody(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

const errorReason = (body: unknown): string | null => (isObject(body) && typeof body.error === 'string' ? body.error : null);

const CHAT = '/api/chat';
/** How long the page waits for a message it sent to be answered. */
const COMMAND_WAIT_MS = 5000;

export function createApi(fetchFn: typeof fetch = globalThis.fetch.bind(globalThis), base = '') {
  async function call(method: 'GET' | 'POST' | 'PUT', url: string, body?: unknown, signal?: AbortSignal): Promise<unknown> {
    const label = `${method} ${url}`;
    let res: Response;
    try {
      res = await fetchFn(base + url, {
        method,
        headers: body === undefined ? { Accept: 'application/json' } : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal,
      });
    } catch (err) {
      throw new BackendUnreachableError(`${label} failed: ${err instanceof Error ? err.message : String(err)}`);
    }
    const text = await res.text();
    if (!res.ok) {
      if (res.status >= 400 && res.status < 500 && res.status !== 404) {
        const reply = errorBody(text);
        throw new ApiError(errorReason(reply) ?? `${label} answered ${res.status}`, reply);
      }
      throw new BackendUnreachableError(`${label} answered ${res.status}`);
    }
    if (text === '') return null;
    try {
      return JSON.parse(text);
    } catch {
      throw new BackendUnreachableError(`${label} did not answer JSON`);
    }
  }

  return {
    /** The event stream: message, log and member events. */
    eventsUrl: () => `${base}${CHAT}/events`,
    /** Every room with the users who joined it, and every user with its rooms, each with its node. */
    async readChatGraph(): Promise<ChatGraph> {
      return toChatGraph(await call('GET', `${CHAT}/state`));
    },
    /** The settings and the ids of the rooms and users, for the menu. */
    async readChatRun(): Promise<ChatRun> {
      const body = await call('GET', `${CHAT}/state`);
      const raw = isObject(body) ? body : {};
      return { settings: toChatSettings(raw.settings), rooms: idsOf(raw, 'rooms'), users: idsOf(raw, 'users') };
    },
    /** PUT rooms (0 to 50), users (0 to 1000) or rate (0 to 100); returns the settings after. */
    async setChatSetting(route: 'rooms' | 'users' | 'rate', value: number): Promise<ChatSettings | null> {
      const body = route === 'rate' ? { messageRate: value } : { [route]: value };
      const reply = await call('PUT', `${CHAT}/${route}`, body);
      return toChatSettings(isObject(reply) ? reply.settings : null);
    },
    /** Opens a room now, as chat-room-<number> when a number is given, else the next free one. */
    async addChatRoom(number?: number): Promise<{ roomId: string; settings: ChatSettings | null }> {
      const reply = await call('POST', `${CHAT}/rooms`, number === undefined ? undefined : { number });
      const raw = isObject(reply) ? reply : {};
      return { roomId: asText(raw.roomId), settings: toChatSettings(raw.settings) };
    },
    /** Sends text to one user. */
    async sendToActor(to: string, text: string): Promise<Delivery> {
      return toDelivery(await call('POST', `${CHAT}/send`, { to, text }, AbortSignal.timeout(COMMAND_WAIT_MS)));
    },
    /** Sends text to every member of a room. */
    async broadcastToRoom(room: string, text: string): Promise<Delivery> {
      return toDelivery(await call('POST', `${CHAT}/broadcast`, { room, text }, AbortSignal.timeout(COMMAND_WAIT_MS)));
    },
    /** The log lines the node holds, oldest first, as `log` events. */
    async readChatLogs(): Promise<ActorEvent[]> {
      return listFrom(await call('GET', `${CHAT}/logs`))
        .filter(isObject)
        .filter((raw) => asText(raw.actorId) !== '')
        .map((raw): ActorEvent => ({ name: 'log', actorId: asText(raw.actorId), data: raw }));
    },
    /** The active generation, both colors, and what the pipeline can do next. */
    async readDeploy(): Promise<DeployState> {
      return toDeployState(await call('GET', '/api/deploy'));
    },
    /** A pipeline step: deploy the standby color, activate it (or roll back), or retire the old color. */
    async deployStep(step: 'deploy' | 'activate' | 'retire'): Promise<string> {
      const reply = await call('POST', `/api/deploy/${step}`, undefined, AbortSignal.timeout(10_000));
      return isObject(reply) ? asText(reply.result) : '';
    },
    /** One more node in a color: it joins the color's cluster with the color's version. */
    async addNode(color: 'blue' | 'green'): Promise<string> {
      const reply = await call('POST', '/api/cluster/nodes', { color });
      return isObject(reply) ? asText(reply.result) : '';
    },
    /** Stops a node gracefully, or starts a stopped one again. */
    async nodeAction(node: number, action: 'stop' | 'start'): Promise<string> {
      const reply = await call('POST', `/api/cluster/nodes/${node}/${action}`);
      return isObject(reply) ? asText(reply.result) : '';
    },
    /** The cluster's nodes, by node number. */
    async readNodes(): Promise<ClusterNode[]> {
      return listFrom(await call('GET', '/api/cluster/nodes'), 'nodes')
        .filter(isObject)
        .map(toClusterNode)
        .filter((n): n is ClusterNode => n !== null);
    },
  };
}

export type Api = ReturnType<typeof createApi>;
