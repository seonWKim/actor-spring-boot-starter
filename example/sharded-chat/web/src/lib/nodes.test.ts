import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseEvent } from './events.ts';
import { NODE_COLOURS, applyMember, hubLabel, nodeColour, nodeFacts, toClusterNode, type ClusterNode } from './nodes.ts';

const node5: ClusterNode = { node: 5, port: 2555, status: 'up', actors: 3, color: 'green', version: 'v2' };
const member = (data: Record<string, unknown>) => parseEvent('member', JSON.stringify(data))!;

test('each of the six nodes has its own colour, and is labelled with its number and port', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6].map(nodeColour), ['node-1', 'node-2', 'node-3', 'node-4', 'node-5', 'node-6']);
  assert.equal(NODE_COLOURS, 6);
  assert.equal(hubLabel(node5), 'node 5 · :2555');
  assert.equal(hubLabel({ ...node5, port: null }), 'node 5');
});

test('the node drawer lists the color and version, port, status and how many rooms and users run there', () => {
  assert.deepEqual(nodeFacts(node5), [['color', 'green · v2'], ['port', ':2555'], ['status', 'up'], ['rooms and users', '3']]);
  assert.deepEqual(toClusterNode({ node: 5, port: 2555, status: 'up', actors: 3, color: 'green', version: 'v2' }), node5);
  assert.equal(toClusterNode({ port: 1 }), null);
});

test('a member event changes the node status and adds a node it does not know', () => {
  const down = applyMember([node5], member({ actorId: 'node-5', message: 'stopping' }));
  assert.equal(down[0].status, 'stopping');
  const joined = applyMember([node5], member({ actorId: 'node-6', message: 'starting' }));
  assert.deepEqual(joined.map((n) => [n.node, n.status]), [[5, 'up'], [6, 'starting']]);
});
