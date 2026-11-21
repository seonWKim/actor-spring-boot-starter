import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';

const webDir = join(import.meta.dirname, '..');
const repoDir = join(webDir, '..');
const TOKENS_FILE = 'src/styles/tokens.css';
const SKIP_DIRS = new Set(['node_modules', 'dist']);
const SOURCE_FILE = /\.(ts|tsx|js|jsx|css|html|json)$/;

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return SKIP_DIRS.has(entry.name) ? [] : sourceFiles(path);
    return SOURCE_FILE.test(entry.name) ? [path] : [];
  });
}

test('hex colours appear only in the tokens file', () => {
  const hex = /#[0-9a-fA-F]{3,8}\b/;
  const offenders = sourceFiles(webDir)
    .map((path) => relative(webDir, path))
    .filter((path) => path !== TOKENS_FILE)
    .filter((path) => hex.test(readFileSync(join(webDir, path), 'utf8')));
  assert.deepEqual(offenders, []);
  assert.match(readFileSync(join(webDir, TOKENS_FILE), 'utf8'), hex);
});

test('package.json has no chart library', () => {
  const pkg = JSON.parse(readFileSync(join(webDir, 'package.json'), 'utf8'));
  const deps = Object.keys({ ...pkg.dependencies, ...pkg.devDependencies });
  const chart = /(chart|d3|recharts|nivo|victory|plotly|highcharts|visx)/i;
  assert.deepEqual(deps.filter((name) => chart.test(name)), []);
});

test('web/node_modules is ignored by git', () => {
  const lines = readFileSync(join(repoDir, '.gitignore'), 'utf8').split('\n').map((l) => l.trim());
  assert.ok(lines.includes('web/node_modules/'), 'web/node_modules/ in .gitignore');
});

test('the build writes to the control app\'s static folder', () => {
  const config = readFileSync(join(webDir, 'vite.config.ts'), 'utf8');
  assert.match(config, /outDir:\s*'\.\.\/control\/src\/main\/resources\/static'/);
});
