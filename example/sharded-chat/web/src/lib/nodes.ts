// The nodes on the page: each node is its own circle in its own colour, and the rooms and users it runs
// take that colour. Blue nodes take the three blue shades, green nodes the three green ones, whatever
// their numbers (the platform numbers them: 1 to 6 here, 51 up for green on Kubernetes). Functions only,
// tested with node --test; the one piece of state is the colour each node got (assignColours).

/** One node as GET /api/cluster/nodes returns it. */
export interface ClusterNode {
  node: number;
  /** Its remote (Artery) port, or null before the nodes list names it. */
  port: number | null;
  /** joining, up, leaving, unreachable, removed */
  status: string;
  /** How many rooms and users run on it. */
  actors: number;
  /** Its deployment color (blue or green) and the version it runs, when the control app names them. */
  color: string | null;
  version: string | null;
}

/** The palette has this many colours (tokens.css --color-node-1 to 6); node 7 takes node 1's. */
export const NODE_COLOURS = 6;

const assigned = new Map<number, string>();

/**
 * Gives each node its colour token from its deployment color: blue nodes node-1 to node-3 in number
 * order, green nodes node-4 to node-6 (a fourth node of a color takes its first shade again). The page
 * calls it whenever the nodes list changes.
 */
export function assignColours(nodes: readonly Pick<ClusterNode, 'node' | 'color'>[]) {
  assigned.clear();
  for (const [color, first] of [['blue', 1], ['green', 4]] as const) {
    nodes
      .filter((n) => n.color === color)
      .map((n) => n.node)
      .sort((a, b) => a - b)
      .forEach((node, i) => assigned.set(node, `node-${first + (i % 3)}`));
  }
}

/** The colour token of node n: its assigned one, else by number (node-1 to node-6). */
export const nodeColour = (node: number) => assigned.get(node) ?? `node-${((Math.max(1, node) - 1) % NODE_COLOURS) + 1}`;

/** The node's label, its second cue beside the colour: "node 2 · :2552". */
export const hubLabel = (node: ClusterNode) => (node.port === null ? `node ${node.node}` : `node ${node.node} · :${node.port}`);

const num = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : null);

/** Reads one node of the nodes reply; null without a node number. */
export function toClusterNode(raw: Record<string, unknown>): ClusterNode | null {
  const node = num(raw.node);
  if (node === null) return null;
  const text = (value: unknown) => (typeof value === 'string' && value !== '' ? value : null);
  return {
    node,
    port: num(raw.port),
    status: String(raw.status ?? 'unknown'),
    actors: num(raw.actors) ?? 0,
    color: text(raw.color),
    version: text(raw.version),
  };
}

/** The node list after a member event (actorId node-<n>, message its status): a new node is added. */
export function applyMember(nodes: ClusterNode[], event: { actorId: string; data: Record<string, unknown> }): ClusterNode[] {
  const match = /^node-(\d+)$/.exec(event.actorId);
  const status = String(event.data.message ?? '');
  if (!match || status === '') return nodes;
  const node = Number(match[1]);
  if (!nodes.some((n) => n.node === node)) {
    return [...nodes, { node, port: null, status, actors: 0, color: null, version: null }].sort((a, b) => a.node - b.node);
  }
  return nodes.map((n) => (n.node === node ? { ...n, status } : n));
}

/** What the node drawer lists, in order: color and version, port, status and how many rooms and users run there. */
export function nodeFacts(node: ClusterNode): [string, string][] {
  return [
    ...(node.color ? ([['color', `${node.color}${node.version ? ` · ${node.version}` : ''}`]] as [string, string][]) : []),
    ['port', node.port === null ? '–' : `:${node.port}`],
    ['status', node.status],
    ['rooms and users', String(node.actors)],
  ];
}
