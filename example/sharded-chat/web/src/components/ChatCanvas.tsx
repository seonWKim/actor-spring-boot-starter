import { useEffect, useMemo, useRef, useState } from 'react';
import { avatarOf } from '../lib/avatars.ts';
import { layoutChat, memberships, type ChatRoomInfo, type ChatUserInfo, type Placed, type Point } from '../lib/chatGraph.ts';
import { progress, pruneDots, type FlowDot, type FlowState } from '../lib/chatFlow.ts';
import { nodeColour, type ClusterNode } from '../lib/nodes.ts';
import { bareId, colorOfId } from '../lib/ids.ts';
import type { ColorState, DeployState } from '../lib/api.ts';

interface Props {
  rooms: readonly ChatRoomInfo[];
  users: readonly ChatUserInfo[];
  nodes: readonly ClusterNode[];
  /** The blue-green state: the band headers, and which color is dimmed as the standby. */
  deploy: DeployState;
  flows: FlowState;
  selectedId: string | null;
  onSelect: (id: string) => void;
  onSelectNode: (node: number) => void;
  onHover: (id: string | null, at: Point | null) => void;
}

/** "chat-room-8" (or "chat-room-8@green") as the canvas names it: "#room-8". */
export const roomLabel = (id: string) => `#${bareId(id).replace(/^chat-/, '')}`;

const nodeVar = (node: number) => `var(--color-${nodeColour(node)})`;

/** Nodes 1 to 3 are blue and 4 to 6 green, until the nodes list names a node's color. */
export const NODES_PER_COLOR = 3;

/** What a color's band says beside its name: its role, and how far a deploy got. */
export function bandStatus(color: ColorState, active: DeployState['active'], key: string, hasRun: boolean): string {
  if (color.role === 'active') return active && active.color === key ? `active · epoch ${active.epoch}` : 'active';
  if (color.role === 'standby') {
    if (color.up < color.nodes) return `warming up · ${color.up} of ${color.nodes} nodes`;
    return hasRun ? 'standby · paused, keeps its state' : 'standby · warmed up, ready to activate';
  }
  return 'stopping';
}

function cssColour(name: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback;
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
}

/** An SVG id for a user's avatar clip: letters, digits and dashes only. */
const clipId = (id: string) => `clip-${id.replace(/[^a-zA-Z0-9-]/g, '-')}`;

/** The point a dot is at: on the straight line between two actors, or bowed through the middle for two users. */
function along(a: Point, b: Point, c: Point, t: number, bowed: boolean): Point {
  if (!bowed) return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
  const k = { x: (a.x + b.x) / 2 + (c.x - (a.x + b.x) / 2) * 0.6, y: (a.y + b.y) / 2 + (c.y - (a.y + b.y) / 2) * 0.6 };
  const u = 1 - t;
  return { x: u * u * a.x + 2 * u * t * k.x + t * t * b.x, y: u * u * a.y + 2 * u * t * k.y + t * t * b.y };
}

/**
 * The chat as one picture: each process is a tinted zone with a small label (click it for the node's
 * actions), its chat rooms sit inside and its users, drawn as their avatars, around them. A thin line
 * joins each user to every room it joined. Every real message travels along its line as a dot, and the
 * line lights up under it; a post shows its words in a small bubble as it goes.
 */
export function ChatCanvas({ rooms, users, nodes, deploy, flows, selectedId, onSelect, onSelectNode, onHover }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [hovered, setHovered] = useState<string | null>(null);

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => {
      setSize({ width: entry.contentRect.width, height: entry.contentRect.height });
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // Every node that runs gets its circle, a starting one too, so a deploy shows its nodes coming up.
  const upNodes = useMemo(() => nodes.filter((n) => n.status !== 'removed').map((n) => n.node), [nodes]);
  const colorOfNode = useMemo(() => {
    const known = new Map(nodes.map((n) => [n.node, n.color]));
    return (node: number) => known.get(node) ?? (node <= NODES_PER_COLOR ? 'blue' : 'green');
  }, [nodes]);
  // The layout moves only when the membership or the processes change, not on every message.
  const shape = `${rooms.map((r) => `${r.id}@${r.node ?? ''}`).join(',')}|${users.map((u) => `${u.id}@${u.node ?? ''}:${u.rooms.join('+')}`).join(',')}|${upNodes.join(',')}|${[...flows.nodeOf].map(([k, v]) => `${k}${v}`).join(',')}`;
  const layout = useMemo(
    () => layoutChat({ width: size.width, height: size.height, rooms, users, nodeOf: flows.nodeOf, nodes: upNodes, groupOf: colorOfNode }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [shape, size.width, size.height],
  );
  const where = useMemo(() => {
    const map = new Map<string, Placed>();
    for (const p of [...layout.rooms, ...layout.users]) map.set(p.id, p);
    return map;
  }, [layout]);
  const links = useMemo(() => memberships(users), [users]);
  const focus = hovered ?? selectedId;
  const nodeInfo = useMemo(() => new Map(nodes.map((n) => [n.node, n])), [nodes]);

  // The dot layer: one canvas, drawn each frame while a dot is in flight.
  const whereRef = useRef(where);
  whereRef.current = where;
  const centreRef = useRef(layout.centre);
  centreRef.current = layout.centre;
  const flowsRef = useRef(flows);
  flowsRef.current = flows;
  // One draw loop while the canvas is up: it draws the dots in flight and stays idle when there are none.
  useEffect(() => {
    const el = canvas.current;
    if (!el || size.width === 0) return;
    const ratio = window.devicePixelRatio || 1;
    el.width = Math.round(size.width * ratio);
    el.height = Math.round(size.height * ratio);
    const ctx = el.getContext('2d');
    if (!ctx) return;
    const colours = new Map<number, string>();
    const colourOf = (node: number) => {
      let c = colours.get(node);
      if (!c) {
        c = cssColour(`--color-${nodeColour(node)}`, 'orange');
        colours.set(node, c);
      }
      return c;
    };
    const ink = cssColour('--color-ring-ink', 'white');
    const bg = cssColour('--color-ring-bg', 'black');
    const manual = cssColour('--color-manual', 'white');
    let frame = 0;
    let drewLast = true;
    const draw = () => {
      frame = requestAnimationFrame(draw);
      const now = performance.now();
      const flows = flowsRef.current;
      const live = pruneDots(flows, now);
      if (!live && !drewLast) return;
      drewLast = live;
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      ctx.clearRect(0, 0, size.width, size.height);
      const pos = whereRef.current;
      const top = { x: size.width / 2, y: 0 };
      const bubbles: { p: Point; text: string; alpha: number; colour: string }[] = [];
      const mine: { p: Point; text: string; alpha: number; colour: string }[] = [];
      for (const dot of flows.dots as FlowDot[]) {
        const a = pos.get(dot.sender) ?? (dot.sender === 'page' ? top : null);
        const b = pos.get(dot.receiver) ?? (dot.receiver === 'page' ? top : null);
        if (!a || !b) continue;
        const t = progress(dot, now);
        const bowed = dot.kind === 'direct' && pos.has(dot.sender) && pos.has(dot.receiver);
        const c = centreRef.current;
        const p = along(a, b, c, t, bowed);
        const colour = dot.manual ? manual : colourOf((pos.get(dot.sender) ?? pos.get(dot.receiver))?.node ?? 1);
        // The line under the dot lights up while the message is on it.
        const fade = Math.sin(Math.PI * Math.min(1, (now - dot.start) / dot.duration));
        ctx.globalAlpha = (dot.kind === 'reply' ? 0.15 : 0.45) * fade;
        ctx.strokeStyle = colour;
        ctx.lineWidth = dot.kind === 'post' || dot.kind === 'direct' ? 2 : 1.25;
        ctx.beginPath();
        ctx.moveTo(a.x, a.y);
        if (bowed) {
          ctx.quadraticCurveTo((a.x + b.x) / 2 + (c.x - (a.x + b.x) / 2) * 0.6, (a.y + b.y) / 2 + (c.y - (a.y + b.y) / 2) * 0.6, b.x, b.y);
        } else {
          ctx.lineTo(b.x, b.y);
        }
        ctx.stroke();
        // The dot: a glow and a bright core.
        const r = dot.manual ? 6 : dot.kind === 'reply' ? 2 : dot.kind === 'deliver' ? 3 : 4.5;
        ctx.globalAlpha = dot.kind === 'reply' ? 0.5 : 1;
        ctx.fillStyle = colour;
        ctx.shadowColor = colour;
        ctx.shadowBlur = dot.kind === 'reply' ? 0 : 10;
        ctx.beginPath();
        ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
        ctx.fill();
        ctx.shadowBlur = 0;
        if (dot.manual) {
          // A hand-sent message, and each room copy of it: a white dot with a pulsing halo.
          ctx.globalAlpha = 0.6 * fade;
          ctx.strokeStyle = manual;
          ctx.lineWidth = 1.5;
          ctx.beginPath();
          ctx.arc(p.x, p.y, r + 4 + 3 * Math.sin(now / 90), 0, Math.PI * 2);
          ctx.stroke();
        }
        if ((dot.kind === 'post' || dot.kind === 'direct') && dot.text) {
          if (dot.manual) mine.push({ p, text: `You: ${dot.text}`, alpha: fade, colour });
          else bubbles.push({ p, text: dot.text, alpha: fade, colour });
        }
      }
      // Bubbles last, above every dot; at most a few so a burst stays readable. The page's own
      // messages always show, drawn last (on top), filled white with dark text.
      ctx.font = '12px ui-sans-serif, system-ui, sans-serif';
      const shown = [...bubbles.slice(-6).map((b) => ({ ...b, mine: false })), ...mine.slice(-8).map((b) => ({ ...b, mine: true }))];
      for (const b of shown) {
        const text = b.text.length > 40 ? `${b.text.slice(0, 39)}…` : b.text;
        ctx.font = b.mine ? '600 12px ui-sans-serif, system-ui, sans-serif' : '12px ui-sans-serif, system-ui, sans-serif';
        const w = ctx.measureText(text).width + 14;
        const x = Math.min(size.width - w - 4, Math.max(4, b.p.x + 8));
        const y = Math.max(4, b.p.y - 30);
        ctx.globalAlpha = Math.min(1, b.alpha * 1.4);
        ctx.fillStyle = b.mine ? manual : bg;
        ctx.strokeStyle = b.colour;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.roundRect(x, y, w, 20, 10);
        ctx.fill();
        ctx.stroke();
        ctx.fillStyle = b.mine ? bg : ink;
        ctx.fillText(text, x + 7, y + 14);
      }
      ctx.globalAlpha = 1;
    };
    frame = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(frame);
  }, [size.width, size.height]);

  const hover = (id: string | null, p: Point | null) => {
    setHovered(id);
    onHover(id, p);
  };

  const userIndex = useMemo(() => new Map(users.map((u) => [u.id, u])), [users]);
  const linked = (user: string, room: string) => focus !== null && (focus === user || focus === room);
  // The standby color's rooms and users are drawn faint: they hold their state but do not chat.
  const standby = (id: string) => deploy.active !== null && colorOfId(id) !== null && colorOfId(id) !== deploy.active.color;
  const dimmed = (id: string) => {
    if (focus === null || focus === id) return false;
    const u = userIndex.get(focus);
    if (u) return !u.rooms.includes(id);
    const v = userIndex.get(id);
    return v ? !v.rooms.includes(focus) : true;
  };

  return (
    <div
      ref={box}
      className="absolute left-0 top-0 transition-[right] duration-200"
      style={{ bottom: 'var(--console-h)', right: 'var(--drawer-w, 0px)' }}
      data-chat-canvas
    >
      {size.width > 0 && (
        <svg width={size.width} height={size.height} className="absolute inset-0 select-none" role="img" aria-label="Chat rooms and users by process">
          <defs>
            {layout.users.map((u) => (
              <clipPath key={u.id} id={clipId(u.id)}>
                <circle cx={u.x} cy={u.y} r={layout.avatar} />
              </clipPath>
            ))}
          </defs>
          {layout.bands.map((b) => {
            const key = b.key === 'green' ? 'green' : 'blue';
            const color = deploy.colors[key];
            const hasRun = [...rooms, ...users].some((a) => colorOfId(a.id) === key);
            const active = color.role === 'active';
            return (
              <g key={b.key} data-band={b.key}>
                <rect
                  x={b.x + 1}
                  y={b.y + 1}
                  width={b.width - 2}
                  height={b.height - 2}
                  rx={14}
                  fill={`var(--color-${key})`}
                  fillOpacity={active ? 0.05 : 0.02}
                  stroke={`var(--color-${key})`}
                  strokeOpacity={active ? 0.7 : 0.3}
                  strokeWidth={active ? 2 : 1}
                  strokeDasharray={active ? undefined : '6 6'}
                  pointerEvents="none"
                />
                <text x={b.x + 16} y={b.y + 21} className="text-[12px] font-semibold uppercase tracking-wide" fill={`var(--color-${key})`}>
                  {`${key}${color.version ? ` ${color.version}` : ''}`}
                  <tspan fill={active ? 'var(--color-ring-ink)' : 'var(--color-ring-muted)'} fontWeight={active ? 600 : 400} className="normal-case tracking-normal">
                    {`  ${bandStatus(color, deploy.active, key, hasRun)}`}
                  </tspan>
                </text>
              </g>
            );
          })}
          {layout.zones.map((z) => (
            <circle
              key={z.node}
              cx={z.centre.x}
              cy={z.centre.y}
              r={z.radius}
              fill={nodeVar(z.node)}
              fillOpacity={0.08}
              stroke={nodeVar(z.node)}
              strokeOpacity={0.45}
              strokeWidth={1.5}
              className="cursor-pointer"
              data-zone={z.node}
              onClick={() => onSelectNode(z.node)}
            />
          ))}
          {layout.zones.map((z) => {
            const info = nodeInfo.get(z.node);
            return (
              <g key={z.node} className="cursor-pointer" onClick={() => onSelectNode(z.node)} data-node-id={`node-${z.node}`}>
                {/* The whole label is the click target, not only its letters. */}
                <rect x={z.label.x - 70} y={z.label.y - 13} width={140} height={32} fill="transparent" />
                <text x={z.label.x} y={z.label.y} textAnchor="middle" className="text-[12px] font-semibold" fill={nodeVar(z.node)}>
                  {`node ${z.node}`}
                  {info?.port ? <tspan fill="var(--color-ring-muted)" fontWeight={400}>{` · :${info.port}`}</tspan> : null}
                </text>
                <text x={z.label.x} y={z.label.y + 14} textAnchor="middle" className="text-[11px]" fill="var(--color-ring-muted)">
                  {`${z.rooms} rooms · ${z.users} users${info && info.status !== 'up' ? ` · ${info.status}` : ''}`}
                </text>
              </g>
            );
          })}
          <g>
            {links.map(({ user, room }) => {
              const a = where.get(user);
              const b = where.get(room);
              if (!a || !b) return null;
              const on = linked(user, room);
              return (
                <line
                  key={`${user}>${room}`}
                  x1={a.x}
                  y1={a.y}
                  x2={b.x}
                  y2={b.y}
                  stroke={on ? nodeVar(b.node) : 'var(--color-ring-muted)'}
                  strokeOpacity={on ? 0.8 : focus ? 0.05 : 0.14}
                  strokeWidth={on ? 1.5 : 1}
                />
              );
            })}
          </g>
          {layout.rooms.map((r) => {
            const s = layout.room;
            const room = rooms.find((x) => x.id === r.id);
            return (
              <g
                key={r.id}
                data-actor-id={r.id}
                className="cursor-pointer transition-opacity"
                opacity={dimmed(r.id) ? 0.3 : standby(r.id) ? 0.45 : 1}
                onClick={() => onSelect(r.id)}
                onPointerEnter={() => hover(r.id, r)}
                onPointerLeave={() => hover(null, null)}
              >
                <rect
                  x={r.x - s}
                  y={r.y - s}
                  width={2 * s}
                  height={2 * s}
                  rx={s * 0.35}
                  fill="var(--color-ring-bg)"
                  stroke={nodeVar(r.node)}
                  strokeWidth={selectedId === r.id ? 3 : 2}
                />
                <text x={r.x} y={r.y + 5} textAnchor="middle" className="text-[15px] font-bold" fill={nodeVar(r.node)}>
                  #
                </text>
                <text x={r.x} y={r.y + s + 14} textAnchor="middle" className="text-[11px] font-medium" fill="var(--color-ring-ink)">
                  {roomLabel(r.id)}
                </text>
                <text x={r.x} y={r.y + s + 27} textAnchor="middle" className="text-[10px]" fill="var(--color-ring-muted)">
                  {`${room?.members.length ?? 0} joined`}
                </text>
              </g>
            );
          })}
          {layout.users.map((u) => {
            const a = layout.avatar;
            return (
              <g
                key={u.id}
                data-actor-id={u.id}
                className="cursor-pointer transition-opacity"
                opacity={dimmed(u.id) ? 0.3 : standby(u.id) ? 0.45 : 1}
                onClick={() => onSelect(u.id)}
                onPointerEnter={() => hover(u.id, u)}
                onPointerLeave={() => hover(null, null)}
              >
                <circle cx={u.x} cy={u.y} r={a + 2} fill="var(--color-ring-bg)" stroke={nodeVar(u.node)} strokeWidth={selectedId === u.id ? 3 : 1.5} />
                <image href={avatarOf(u.id)} x={u.x - a} y={u.y - a} width={2 * a} height={2 * a} clipPath={`url(#${clipId(u.id)})`} />
              </g>
            );
          })}
        </svg>
      )}
      <canvas ref={canvas} className="pointer-events-none absolute inset-0" style={{ width: size.width, height: size.height }} />
    </div>
  );
}
