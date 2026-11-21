// The page at / allows the chat canvas, the bottom log console, the hover card, the ☰ menu and one right
// drawer (an actor, a process or the menu), nothing else. Source-level checks: no browser and no extra
// dependency.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const srcDir = join(import.meta.dirname, '..', 'src');
const read = (...parts: string[]) => readFileSync(join(srcDir, ...parts), 'utf8');
const app = read('App.tsx');

const DELETED = {
  components: [
    'ActorPanel', 'BackendUnreachable', 'CodeModal', 'CodePanel', 'CommandForm', 'CylinderIcon', 'JournalPanel', 'LinkEdges',
    'LinkKindChooser', 'LinkPanel', 'LiveCodeEditor', 'MessageCard', 'Panel', 'PulseCanvas', 'RadialView', 'RolloutPanel',
    'StreamLane', 'Toolbar', 'TopBar', 'TopicPanel', 'UseCaseList', 'UserCard',
  ],
  hooks: ['useActorKinds', 'useChatState', 'useCodeSnippets', 'useDemoStream', 'useJournal', 'useLinks', 'useReplays', 'useRollout', 'useTopics', 'useUseCases'],
  lib: ['activity', 'chat', 'codeFocus', 'commands', 'links', 'messages', 'positions', 'pulses', 'radial', 'toolbar', 'topics'],
};

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? sourceFiles(join(dir, e.name)) : /\.(ts|tsx)$/.test(e.name) ? [join(dir, e.name)] : [],
  );
}

test('App renders only the canvas, the menu, the console, the hover card and one right drawer', () => {
  const returned = app.slice(app.lastIndexOf('return ('));
  const tags = [...returned.matchAll(/<([A-Za-z][\w.]*)/g)].map((m) => m[1]);
  const components = new Set(tags.filter((t) => /^[A-Z]/.test(t)));
  assert.deepEqual([...components].sort(), ['ActorDrawer', 'ChatCanvas', 'ControlDrawer', 'NodeModal']);
  assert.match(returned, /\{dock\}/);
  assert.match(returned, /\{card\}/);
  assert.match(app, /const dock = \(\s*<LogConsole/);
  // One drawer at a time: the panel state is an actor, a node or the menu.
  assert.match(app, /type Panel = \{ kind: 'actor'; id: string \} \| \{ kind: 'node'; node: number \} \| \{ kind: 'menu' \} \| null/);
  assert.deepEqual(tags.filter((t) => /^[a-z]/.test(t)), ['div']);
  assert.match(returned, /fixed inset-0 overflow-hidden/);
});

test('the deleted components, hooks and lib modules are gone and nothing refers to them', () => {
  const sources = sourceFiles(srcDir).map((path) => readFileSync(path, 'utf8')).join('\n');
  for (const [dir, names] of Object.entries(DELETED)) {
    for (const name of names) {
      assert.ok(!['.ts', '.tsx', '.test.ts'].some((ext) => existsSync(join(srcDir, dir, name + ext))), `${dir}/${name} exists`);
      assert.doesNotMatch(sources, new RegExp(`from '[^']*/${name}(\\.tsx?)?'`), `${name} is imported`);
    }
  }
});

test('the canvas draws only rooms and users, users as avatars without names, each process as its own tinted circle', () => {
  const canvas = read('components', 'ChatCanvas.tsx');
  assert.match(canvas, /<image href=\{avatarOf\(u\.id\)\}/);
  assert.doesNotMatch(canvas, /nameOf/);
  // Each process is its own circle.
  assert.match(canvas, /<circle\s+key=\{z\.node\}\s+cx=\{z\.centre\.x\}/);
  assert.match(canvas, /fillOpacity=\{0\.08\}/);
  assert.match(canvas, /data-actor-id=\{r\.id\}/);
  assert.match(canvas, /data-actor-id=\{u\.id\}/);
  // A zone or its label opens the node drawer.
  assert.match(canvas, /onClick=\{\(\) => onSelectNode\(z\.node\)\}/);
});

test('clicking an actor opens its drawer with its details, the messages it handles, its messages and a composer', () => {
  assert.match(app, /onSelect=\{selectActor\}/);
  const drawer = read('components', 'ActorDrawer.tsx');
  assert.match(drawer, /role="dialog"/);
  assert.match(drawer, /historyOf\(flows, id\)/);
  assert.match(drawer, /ACCEPTS\[room \? 'room' : 'user'\]/);
  assert.match(drawer, /<Composer api=\{api\} id=\{id\}/);
  assert.match(drawer, /api\.broadcastToRoom\(id, words\) : await api\.sendToActor\(id, words\)/);
  assert.match(drawer, /e\.key === 'Escape' && onClose\(\)/);
  assert.match(drawer, /aria-label="Close"/);
});

test('the menu is a right drawer; its target picker is a searchable listbox, not a datalist', () => {
  const menu = read('components', 'ControlDrawer.tsx');
  assert.match(menu, /aria-label="Menu"/);
  assert.match(menu, /role="combobox"/);
  assert.match(menu, /role="listbox"/);
  assert.doesNotMatch(menu, /<datalist/);
  assert.doesNotMatch(menu, /actorsPerProcess|actors\/process/);
});

test('clicking a process zone opens the right-side node drawer with its facts', () => {
  assert.match(app, /onSelectNode=\{selectNode\}/);
  assert.match(app, /\{pickedNode && \(?\s*<NodeModal\s+key=\{pickedNode\.node\}[^>]*node=\{pickedNode\}/);
  const modal = read('components', 'NodeModal.tsx');
  assert.match(modal, /role="dialog"/);
  assert.match(modal, /nodeFacts\(node\)\.map/);
  assert.match(modal, /e\.key === 'Escape'\) onClose\(\)/);
  assert.match(modal, /aria-label="Close"/);
});

test('the page links the favicon, a real icon file in web/public', () => {
  const html = readFileSync(join(srcDir, '..', 'index.html'), 'utf8');
  assert.match(html, /<link rel="icon"[^>]*href="\/favicon\.ico"/);
  const icon = readFileSync(join(srcDir, '..', 'public', 'favicon.ico'));
  // An ICO file: reserved 0, type 1 (icon), at least one image.
  assert.deepEqual([...icon.subarray(0, 4)], [0, 0, 1, 0]);
  assert.ok(icon.readUInt16LE(4) >= 1);
});

test('the node palette has one colour per node of both colors, all distinct and none of them the crash red', () => {
  const tokens = readFileSync(join(srcDir, 'styles', 'tokens.css'), 'utf8');
  const palette = [...tokens.matchAll(/--color-node-(\d+):\s*(#[0-9a-fA-F]{6})/g)].map((m) => m[2].toLowerCase());
  const crash = tokens.match(/--color-crash:\s*(#[0-9a-fA-F]{6})/)?.[1].toLowerCase();
  assert.equal(palette.length, 6, `${palette.length} colours`);
  assert.equal(new Set(palette).size, palette.length);
  assert.ok(!palette.includes(crash ?? ''));
});

test('the page does not scroll', () => {
  const css = readFileSync(join(srcDir, 'styles', 'index.css'), 'utf8');
  assert.match(css, /#root \{[^}]*overflow: hidden/);
  assert.doesNotMatch(css, /overflow-x/);
});

test('the console draws chat lines as bubbles and the other lines as the compact monospace line', () => {
  const consoleSource = read('components', 'LogConsole.tsx');
  assert.match(consoleSource, /chatLine \? \(\s*<ChatLineRow/);
  assert.match(consoleSource, /<span className="truncate">\{line\.text\}<\/span>/);
  const row = read('components', 'ChatLineRow.tsx');
  assert.match(row, /size=\{24\}/);
  assert.match(row, /<b /);
  assert.match(row, /timed out/);
});

test('the README names the avatar source, its licence and that avatars are generated locally', () => {
  const readme = readFileSync(join(srcDir, '..', 'README.md'), 'utf8');
  assert.match(readme, /DiceBear notionists/);
  assert.match(readme, /CC0/);
  assert.match(readme, /generated locally/i);
  assert.match(readme, /no network call/i);
});
