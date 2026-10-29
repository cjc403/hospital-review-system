import { createServer, request as httpRequest } from 'node:http';
import { createReadStream } from 'node:fs';
import { stat, realpath } from 'node:fs/promises';
import { resolve, relative, isAbsolute, sep, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const contentTypes = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };
const outside = (root, path) => { const name = relative(root, path); return name === '..' || name.startsWith('..' + sep) || isAbsolute(name); };

export function createDemoServer({ root, backendPort = 18080 }) {
  const documentRoot = resolve(root);
  const server = createServer(async (req, res) => {
    const respond = (status, message) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify({ message })); };
    const rawPath = (req.url ?? '/').split('?')[0];
    if (rawPath === '/api' || rawPath.startsWith('/api/')) {
      const upstream = httpRequest({ hostname: '127.0.0.1', port: backendPort, path: req.url, method: req.method, headers: { ...req.headers, host: `127.0.0.1:${backendPort}`, connection: 'close' }, timeout: 30000 }, result => {
        res.writeHead(result.statusCode ?? 502, result.headers);
        result.pipe(res);
        result.on('error', () => res.destroy());
      });
      upstream.on('timeout', () => upstream.destroy(new Error('timeout')));
      upstream.on('error', () => { if (!res.headersSent) respond(502, '后端服务暂不可用，请检查演示启动日志。'); else res.destroy(); });
      req.on('aborted', () => upstream.destroy());
      req.pipe(upstream);
      return;
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') { respond(405, '不支持的请求方式'); return; }
    let pathname;
    try { pathname = decodeURIComponent(rawPath).replaceAll('\\', '/'); } catch { respond(400, '无效的网址编码'); return; }
    if (pathname.includes('\0')) { respond(400, '无效的文件路径'); return; }
    let target = resolve(documentRoot, '.' + pathname);
    if (outside(documentRoot, target) || pathname.split('/').some(part => part.startsWith('.') && part !== '')) { respond(403, '禁止访问此路径'); return; }
    try {
      let info;
      try { info = await stat(target); } catch { /* SPA paths are served below. */ }
      if (!info?.isFile()) {
        if (extname(pathname)) { respond(404, '文件不存在'); return; }
        target = resolve(documentRoot, 'index.html');
        info = await stat(target);
      }
      if (outside(await realpath(documentRoot), await realpath(target))) { respond(403, '禁止访问此路径'); return; }
      res.writeHead(200, { 'Content-Type': contentTypes[extname(target)] ?? 'application/octet-stream', 'Content-Length': info.size, 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff' });
      if (req.method === 'HEAD') { res.end(); return; }
      createReadStream(target).on('error', () => res.destroy()).pipe(res);
    } catch { respond(404, '演示页面不存在，请先运行前端构建。'); }
  });
  server.requestTimeout = 35000;
  return server;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const root = args[args.indexOf('--root') + 1];
  const port = Number(args.includes('--port') ? args[args.indexOf('--port') + 1] : 18173);
  if (!args.includes('--root') || !root || !Number.isInteger(port) || port < 1 || port > 65535) {
    console.error('Usage: node serve-demo.mjs --root <dist-directory> --port 18173');
    process.exitCode = 1;
  } else {
    const server = createDemoServer({ root });
    server.on('error', error => { console.error(error.message); process.exitCode = 1; });
    server.listen(port, '127.0.0.1', () => console.log(`Hospital demo ready: http://127.0.0.1:${port}`));
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
  }
}
