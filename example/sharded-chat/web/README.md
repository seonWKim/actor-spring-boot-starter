# web

The page Spring serves at `/`: a Vite + React + TypeScript + Tailwind app. It is one full-screen
picture of the chat on a dark background (`src/components/ChatCanvas.tsx`), a ☰ menu button in the
top-right corner and a log console docked at the bottom. There is no title, and the page does not scroll.
The page rule: the page allows the chat canvas, the bottom log console, the hover card, the ☰ menu and one right drawer (an actor, a node or the menu), nothing else (`tests/page.test.ts` checks it). The API it reads is in
the example's README (`../README.md`, "HTTP API").

## Chat canvas

Two kinds of actor are drawn: chat room actors (`ChatRoomActor`) and user actors (`ChatUserActor`),
both sharded entities.

- Each cluster node is its own circle tinted in its colour, side by side (stacked on a
  phone); its label (`node 1 · :2551`, rooms and users on it) opens the node drawer. An actor sits in
  the circle of the node the chat state says it runs on (sharding placed it there), so a line from a
  user to a room on another node crosses between circles.
- Rooms are rounded squares with `#`; users are their generated avatars, without names (hover one for
  its card). A thin line joins each user to every room it joined.
- Every real message (the `message` events) travels along its line as a dot: a post goes user →
  room, then the room sends one Deliver to each member (room → member) and its reply (true) back to
  the poster. A post shows its words in a small bubble on the way. Layout: `src/lib/chatGraph.ts`;
  messages: `src/lib/chatFlow.ts`.

## Actor drawer

Click a room or a user and a drawer opens on the right: who it is, its kind and node, its rooms
(a user) or joined users (a room), its sharding (entity type, entity id, shard, node, and why it
runs there), the messages it handles, and every message it sent or received
since the page loaded (live, in memory only), with counts for the last minute. At the bottom you
can send it a message: a Direct to a user (`POST /api/chat/send`) or a Broadcast to every
user who joined a room (`POST /api/chat/broadcast`); the reply shows under the field. A
name in the drawer opens that actor. Esc or ✕ closes it.

## Menu

☰ opens a drawer on the right: the load (chat rooms, users, messages a second; each
applies on Enter or when the field loses focus, `PUT /api/chat/rooms`, `/users`, `/rate`),
Add a room (an optional number gives `chat-room-<number>`, `POST /api/chat/rooms`; a note
explains how the room becomes a `ChatRoom` sharded entity: its shard, crc32(id) % 100, and the node it
is expected on)
and a send form whose target picker is a searchable list of users (with avatars) and rooms, each
with its node.

## Log console and chat

The page is one column: the canvas fills the area above the bottom log console (nothing below the
fold, no page scroll).

- Size: by default 20% of the viewport height on desktop (1440x900: 180 px); at a viewport narrower
  than 640 px (390x844) it starts collapsed. The canvas takes the freed height (`--console-h`, in px,
  set by `App.tsx`). Collapsed is one line (32 px), also set by a click on the header.
- Resize handle: the border between the canvas and the console is a drag handle, an 8 px hit strip
  around the 1 px line (`role="separator"`, `aria-orientation="horizontal"`, cursor `row-resize`,
  `touch-action: none`; mouse and touch via pointer capture, reported once per frame). The height stays
  between 32 px and 70% of the viewport (630 px at 900, 590 at 844); dragging to the minimum collapses
  the console, and expanding (header click or a drag up) restores the last dragged height, not the
  default. The handle is focusable: ArrowUp and ArrowDown move it by 24 px (clamped; reaching 32 px
  collapses, ArrowUp from collapsed gives 56 px). Double-click resets to the default height, expands
  and clears the saved height. `aria-valuenow` is the height in px.
- The chosen height is saved per browser in `localStorage` (`sharded-chat.log.height`; try/catch) on drag
  end and on each key press, and read on load; a missing, unreadable or out-of-range value means the
  default. The collapsed state is not saved (the phone still starts collapsed).
- Dark, monospace. Each line is time, actor id and text, from the `log` event; timeouts (`--color-log-timeout`) and errors (`--color-log-error`) have their
  own colours. With Auto-scroll on (the default) it follows the newest line unless the reader has
  scrolled up.
- Auto-scroll is a toggle button (`aria-pressed`) in the header row, next to the collapse button, and
  still shown when the console is collapsed. Off, new lines are appended but the view stays put, and
  an "N new" button (N = lines not yet seen) jumps to the newest line without turning Auto-scroll back
  on. The setting is per browser, in `localStorage` (try/catch: with storage unavailable it is on).
- Lines live in the browser for the session: `src/lib/log.ts` keeps the newest 500, and the console
  draws that same list, so the DOM holds at most 500 lines. Log lines share the per-frame batch of the
  other events, so a burst in one frame is one state update.
- Click a line to open its actor's drawer.

## Chat personas and avatars

A chat user (`chat-user-N`) has a persona that depends on its id alone (`src/lib/persona.ts`): a
display name from a built-in list of first and last names from varied countries, and an avatar. The
same id gives the same persona on every load.

- Avatar source: DiceBear notionists (`@dicebear/core` + `@dicebear/notionists`), whose artwork is
  CC0. Avatars are generated locally in the browser with the user id as the seed, as an SVG data URI,
  with no network call and no external avatar service. Each id's avatar is generated once and cached
  (`src/lib/avatars.ts`), so 100 users at 2 messages a second re-render from the cache.
- Console: a chat line (kinds `joined`, `left`, `sent`, `received`, `timeout`) shows the 24 px avatar
  circle, the name in bold, the room, the relative time and the text in a message bubble. The log
  event has no room field, so the room is read from the message text (`src/lib/chatLines.ts`). A sent
  line shows its room as the target (the backend has no broadcast kind). A timeout shows its bubble
  greyed with a "timed out" tag. Other lines keep the compact monospace line.
- Canvas: a user is drawn as its avatar in a ring of its node colour; hovering it shows a small
  card with its name, rooms and node.

SVG draws the zones, rooms, users and lines; a canvas over it draws the travelling messages once per
animation frame, and events are applied once per frame (`frameBatch.ts`), so a burst is one render,
not one per message. The layout and message logic are pure modules in `src/lib/` (`chatGraph.ts`,
`chatFlow.ts`, `frameBatch.ts`) with `node --test` tests; there is no d3 or chart dependency.

Check by hand: at 1440x900 and 390x844 the page shows only the canvas, the ☰ button and the
console, and does not scroll.

```sh
npm install      # once; needs network
npm run dev      # dev server, proxies /api to the control app on http://localhost:8080
npm run build    # type-check, then write the bundle to ../control/src/main/resources/static/
npm test         # node --test on the pure modules and the repo rules (needs no install)
```

`./gradlew :example:sharded-chat:control:build` runs `npm run build` before `processResources` when node and npm are on `PATH`
and `web/node_modules` exists; otherwise it skips the web build and says why.

Colours: hex values live only in `src/styles/tokens.css`; use the Tailwind utilities it defines
(`bg-surface`, `text-crash`, ...). The canvas's tokens are in a `@theme static` block so the canvas
can read them as CSS variables.
