import { useCallback, type CSSProperties, useEffect, useMemo, useRef, useState } from 'react';
import { NO_DEPLOY, createApi, type ChatGraph, type DeployState } from './lib/api.ts';
import { addFlow, createFlowState, flowOf } from './lib/chatFlow.ts';
import { loadByNode, nodeOfActor, type Point } from './lib/chatGraph.ts';
import { createFrameBatcher } from './lib/frameBatch.ts';
import { openEventSource } from './lib/eventSource.ts';
import { parseEvent, type ActorEvent } from './lib/events.ts';
import { CONSOLE_LINE_PX, EMPTY_LOG, appendLines, clampConsoleHeight, defaultConsoleHeight, initialCollapsed, loadConsoleHeight, saveConsoleHeight } from './lib/log.ts';
import { applyMember, type ClusterNode } from './lib/nodes.ts';
import { nameOf } from './lib/persona.ts';
import { useChatLogs } from './hooks/useChatLogs.ts';
import { ActorDrawer } from './components/ActorDrawer.tsx';
import { ChatCanvas, roomLabel } from './components/ChatCanvas.tsx';
import { ControlDrawer } from './components/ControlDrawer.tsx';
import { LogConsole } from './components/LogConsole.tsx';
import { NodeModal } from './components/NodeModal.tsx';

/** How often the rooms and their members are read again; joins and leaves also come as log lines. */
const GRAPH_EVERY_MS = 1500;
/** How often the blue-green state is read: the pipeline moves in seconds. */
const DEPLOY_EVERY_MS = 1000;
const NODES_EVERY_MS = 5000;
/** The width of every right drawer (actor, node, menu). */
const DRAWER_PX = 380;

type Panel = { kind: 'actor'; id: string } | { kind: 'node'; node: number } | { kind: 'menu' } | null;

/**
 * The page: the chat full screen (ChatCanvas): chat room actors and user actors on the process they
 * run on, every real message travelling along its line; the log console docked at the bottom with a
 * draggable divider; a hover card; and one right drawer at a time: an actor's messages, a process's
 * actions, or the menu (☰).
 */
export function App() {
  const api = useMemo(() => createApi(), []);
  const [graph, setGraph] = useState<ChatGraph>({ rooms: [], users: [] });
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [deploy, setDeploy] = useState<DeployState>(NO_DEPLOY);
  const [panel, setPanel] = useState<Panel>(null);
  const [hover, setHover] = useState<{ id: string; at: Point } | null>(null);
  const [log, setLog] = useState(EMPTY_LOG);
  const [tick, setTick] = useState(0);
  const flows = useMemo(() => createFlowState(), []);

  const [collapsed, setCollapsed] = useState(() => initialCollapsed(window.innerWidth));
  const [viewportHeight, setViewportHeight] = useState(() => window.innerHeight);
  const [expandedPx, setExpandedPx] = useState<number | null>(() => loadConsoleHeight(window.innerHeight));
  useEffect(() => {
    const onResize = () => setViewportHeight(window.innerHeight);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);
  const expandedHeight = clampConsoleHeight(expandedPx ?? defaultConsoleHeight(viewportHeight), viewportHeight);
  const consoleHeight = collapsed ? CONSOLE_LINE_PX : expandedHeight;
  const resizeConsole = useCallback(
    (px: number) => {
      if (px <= CONSOLE_LINE_PX) {
        setCollapsed(true);
      } else {
        setCollapsed(false);
        setExpandedPx(clampConsoleHeight(px, viewportHeight));
      }
    },
    [viewportHeight],
  );
  const saveConsole = useCallback(
    (px: number) => {
      if (px > CONSOLE_LINE_PX) saveConsoleHeight(clampConsoleHeight(px, viewportHeight));
    },
    [viewportHeight],
  );
  const resetConsole = useCallback(() => {
    setExpandedPx(null);
    setCollapsed(false);
    saveConsoleHeight(null);
  }, []);

  useChatLogs(api, setLog, 0);

  // The rooms and members, and the processes, read on a timer; a member event re-reads the nodes at once.
  const readGraph = useRef(() => {});
  readGraph.current = () => api.readChatGraph().then(setGraph, () => undefined);
  useEffect(() => {
    readGraph.current();
    const g = setInterval(() => readGraph.current(), GRAPH_EVERY_MS);
    const readNodes = () => api.readNodes().then(setNodes, () => undefined);
    readNodes();
    const n = setInterval(readNodes, NODES_EVERY_MS);
    const readDeploy = () => api.readDeploy().then(setDeploy, () => undefined);
    readDeploy();
    const d = setInterval(readDeploy, DEPLOY_EVERY_MS);
    return () => {
      clearInterval(g);
      clearInterval(n);
      clearInterval(d);
    };
  }, [api]);

  // The event stream: each message is a dot and a line in both actors' history; log lines go to the console.
  useEffect(() => {
    const flush = (batch: ActorEvent[]) => {
      const now = performance.now();
      let moved = false;
      for (const event of batch) {
        const flow = flowOf(event, now);
        if (flow) addFlow(flows, flow, now);
        if (event.name === 'log' && /joined|left|room/.test(String(event.data.kind ?? ''))) moved = true;
      }
      setLog((current) => appendLines(current, batch));
      setTick((t) => t + 1);
      if (moved) readGraph.current();
    };
    const batcher = createFrameBatcher(flush);
    const stream = openEventSource(api.eventsUrl());
    for (const name of ['message', 'log'] as const) {
      stream.on(name, (raw) => {
        const event = parseEvent(name, raw);
        if (event) batcher.push(event);
      });
    }
    stream.on('member', (raw) => {
      const event = parseEvent('member', raw);
      if (event) setNodes((current) => applyMember(current, event));
      api.readNodes().then(setNodes, () => undefined);
    });
    return () => {
      stream.close();
      batcher.cancel();
    };
  }, [api, flows]);

  const close = useCallback(() => setPanel(null), []);
  const selectActor = useCallback((id: string) => setPanel({ kind: 'actor', id }), []);
  const selectNode = useCallback(
    (node: number) => {
      setPanel({ kind: 'node', node });
      api.readNodes().then(setNodes, () => undefined);
    },
    [api],
  );
  const setMenu = useCallback((open: boolean) => setPanel(open ? { kind: 'menu' } : null), []);

  // The state's node first (where sharding put it), else the node its messages came from.
  const nodeOf = useCallback(
    (id: string) => {
      const known = graph.rooms.find((r) => r.id === id) ?? graph.users.find((u) => u.id === id);
      return nodeOfActor(known ?? { id }, flows.nodeOf);
    },
    [graph, flows],
  );
  // A new room opens on the active color, so its load is what decides where it lands.
  const activeColor = deploy.active?.color ?? null;
  const upNodes = useMemo(
    () => nodes.filter((n) => n.status === 'up' && (activeColor === null || n.color === activeColor)).map((n) => n.node),
    [nodes, activeColor],
  );
  const activeGraph = useMemo(() => {
    const mine = (id: string) => activeColor === null || id.endsWith(`@${activeColor}`);
    return { rooms: graph.rooms.filter((r) => mine(r.id)), users: graph.users.filter((u) => mine(u.id)) };
  }, [graph, activeColor]);
  const load = useMemo(
    () => loadByNode(activeGraph.rooms, activeGraph.users, flows.nodeOf, upNodes),
    [activeGraph, flows, upNodes],
  );
  const distributed = upNodes.length > 1 || load.length > 1;
  const targets = useMemo(
    () => ({
      rooms: graph.rooms.map((r) => ({ id: r.id, node: nodeOf(r.id) })),
      users: graph.users.map((u) => ({ id: u.id, node: nodeOf(u.id) })),
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [graph, tick],
  );
  const memberCount = useCallback((room: string) => graph.rooms.find((r) => r.id === room)?.members.length ?? 0, [graph]);
  const peersOf = (id: string) =>
    graph.rooms.find((r) => r.id === id)?.members ?? graph.users.find((u) => u.id === id)?.rooms ?? [];

  // With a drawer open on a wide screen the canvas ends at the drawer, so the chat stays centred in
  // the space left; on a phone the drawer covers the screen and the canvas keeps its size.
  const [wide, setWide] = useState(() => window.innerWidth >= 900);
  useEffect(() => {
    const onResize = () => setWide(window.innerWidth >= 900);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);
  const drawerWidth = panel && wide ? `${DRAWER_PX}px` : '0px';
  const pickedNode = panel?.kind === 'node' ? (nodes.find((n) => n.node === panel.node) ?? null) : null;
  const card = hover && (
    <div
      className="pointer-events-none fixed z-20 rounded-lg border border-log-line bg-log-bg/95 px-3 py-2 text-[12px] text-log-ink shadow-xl"
      style={{ left: hover.at.x + 18, top: hover.at.y - 12 }}
    >
      <div className="font-semibold">{hover.id.startsWith('chat-room-') ? roomLabel(hover.id) : nameOf(hover.id)}</div>
      <div className="text-log-muted">
        {hover.id.startsWith('chat-room-')
          ? `${peersOf(hover.id).length} joined · node ${nodeOf(hover.id)}`
          : `${peersOf(hover.id).map(roomLabel).join(', ') || 'no room'} · node ${nodeOf(hover.id)}`}
      </div>
    </div>
  );

  const dock = (
    <LogConsole
      lines={log.lines}
      collapsed={collapsed}
      onToggle={() => setCollapsed((c) => !c)}
      height={consoleHeight}
      viewportHeight={viewportHeight}
      onResize={resizeConsole}
      onResizeEnd={saveConsole}
      onResetHeight={resetConsole}
      onHighlight={(id) => id && selectActor(id)}
    />
  );

  return (
    <div
      className="fixed inset-0 overflow-hidden bg-ring-bg"
      style={{ '--console-h': `${consoleHeight}px`, '--drawer-w': drawerWidth } as CSSProperties}
    >
      <ChatCanvas
        rooms={graph.rooms}
        users={graph.users}
        nodes={nodes}
        deploy={deploy}
        flows={flows}
        selectedId={panel?.kind === 'actor' ? panel.id : null}
        onSelect={selectActor}
        onSelectNode={selectNode}
        onHover={(id, at) => setHover(id && at ? { id, at } : null)}
      />
      <ControlDrawer
        api={api}
        rooms={targets.rooms}
        users={targets.users}
        memberCount={memberCount}
        distributed={distributed}
        load={load}
        deploy={deploy}
        onDeployed={() => api.readDeploy().then(setDeploy, () => undefined)}
        open={panel?.kind === 'menu'}
        onOpen={setMenu}
      />
      {dock}
      {card}
      {panel?.kind === 'actor' && (
        <ActorDrawer
          key={panel.id}
          api={api}
          id={panel.id}
          node={nodeOf(panel.id)}
          distributed={distributed}
          peers={peersOf(panel.id)}
          flows={flows}
          tick={tick}
          onSelect={selectActor}
          onClose={close}
        />
      )}
      {pickedNode && (
        <NodeModal
          key={pickedNode.node}
          api={api}
          node={pickedNode}
          onChanged={() => api.readNodes().then(setNodes, () => undefined)}
          onClose={close}
        />
      )}
    </div>
  );
}
