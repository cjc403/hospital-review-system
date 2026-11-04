import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { createDemoServer } from './serve-demo.mjs';

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'hospital-static-'));
  await writeFile(join(root, 'index.html'), '<h1>医院演示</h1>');
  await mkdir(join(root, 'assets'));
  await writeFile(join(root, 'assets', 'app.js'), 'export const demo = true;');
  const upstream = createServer((req, res) => {
    if (req.url === '/api/fail') { res.writeHead(409, { 'Content-Type': 'application/json' }); res.end('{"message":"不能重复审核"}'); return; }
    let body = '';
    req.on('data', (chunk) => body += chunk);
    req.on('end', () => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ path: req.url, role: req.headers['x-demo-role'], authorization:req.headers.authorization, body })); });
  });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  const server = createDemoServer({ root, backendPort: upstream.address().port });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    server.closeAllConnections(); upstream.closeAllConnections();
    await Promise.all([new Promise(resolve => server.close(resolve)), new Promise(resolve => upstream.close(resolve))]);
    await rm(root, { recursive: true, force: true });
  });
  return `http://127.0.0.1:${server.address().port}`;
}

test('serves Chinese SPA and nested page routes while missing assets return 404', async t => {
  const base = await fixture(t);
  assert.match(await (await fetch(base + '/')).text(), /医院演示/);
  assert.match(await (await fetch(base + '/monitor')).text(), /医院演示/);
  assert.equal((await fetch(base + '/assets/missing.js')).status, 404);
  assert.match((await fetch(base + '/assets/app.js')).headers.get('content-type'), /javascript/);
});

test('forwards API body, query, role and upstream validation errors', async t => {
  const base = await fixture(t);
  const response = await fetch(base + '/api/manual-values?period=2026-09', { method: 'POST', headers: { 'X-Demo-Role': 'quality', 'Authorization':'Bearer synthetic-session-token', 'Content-Type': 'application/json' }, body: '{"value":2}' });
  assert.deepEqual(await response.json(), { path: '/api/manual-values?period=2026-09', role: 'quality', authorization:'Bearer synthetic-session-token', body: '{"value":2}' });
  const rejected = await fetch(base + '/api/fail');
  assert.equal(rejected.status, 409);
  assert.equal((await rejected.json()).message, '不能重复审核');
});

test('encoded traversal and malformed encoding cannot expose parent files', async t => {
  const base = await fixture(t);
  assert.equal((await fetch(base + '/%2e%2e%2foutside.txt')).status, 403);
  assert.equal((await fetch(base + '/%E0%A4%A')).status, 400);
});
