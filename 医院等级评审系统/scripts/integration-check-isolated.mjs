import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn, execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';
import net from 'node:net';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const jar = resolve(projectRoot, 'backend', 'target', 'hospital-review-demo-1.0.0.jar');
const java = process.env.JAVA_CMD || 'java';
const execFile = promisify(execFileCallback);

async function terminateProcessTree(child) {
  if (child.exitCode !== null || !child.pid) return;
  if (process.platform === 'win32') {
    try {
      await execFile('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true });
    } catch {
      // 进程可能已经退出，继续等待并清理临时目录。
    }
  } else {
    child.kill('SIGTERM');
  }
  await Promise.race([
    new Promise(resolve => child.once('exit', resolve)),
    new Promise(resolve => setTimeout(resolve, 5000)),
  ]);
}

function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address ? address.port : 0;
      server.close(() => resolvePort(port));
    });
  });
}

function waitForHealthy(url, child) {
  return new Promise((resolveReady, reject) => {
    const deadline = Date.now() + 60_000;
    const tick = async () => {
      if (child.exitCode !== null) {
        reject(new Error(`隔离后端提前退出，exit=${child.exitCode}`));
        return;
      }
      try {
        const response = await fetch(url);
        if (response.ok) {
          // Tomcat 可先于 ApplicationRunner 就绪；必须等场景事务提交后再验收。
          const reports = await fetch(url.replace('/api/health', '/api/v3/reports'), { headers: { 'X-Demo-Role': 'quality' } });
          if (reports.ok && (await reports.json()).items?.length >= 3) {
            resolveReady();
            return;
          }
        }
      } catch {
        // 服务尚未启动，继续等待。
      }
      if (Date.now() >= deadline) {
        reject(new Error(`隔离后端未在 60 秒内就绪：${url}`));
        return;
      }
      setTimeout(tick, 500);
    };
    tick();
  });
}

const port = await freePort();
const root = await mkdtemp(join(tmpdir(), 'hospital-review-v3-integration-'));
const baseUrl = `http://127.0.0.1:${port}`;
const child = spawn(java, [
  '-jar', jar,
  '--server.address=127.0.0.1',
  `--server.port=${port}`,
  `--spring.datasource.url=jdbc:h2:file:${join(root, 'demo').replaceAll('\\', '/')};DB_CLOSE_ON_EXIT=FALSE`,
  `--demo.upload-dir=${join(root, 'uploads')}`,
  `--review.storage-dir=${join(root, 'v3-evidence')}`,
  '--spring.main.banner-mode=off',
  '--demo.rich-seed.enabled=true',
], { cwd: resolve(projectRoot, 'backend'), env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });

let output = '';
child.stdout.on('data', chunk => { output += chunk.toString(); });
child.stderr.on('data', chunk => { output += chunk.toString(); });

try {
  await waitForHealthy(`${baseUrl}/api/health`, child);
  const check = spawn(process.execPath, [resolve(projectRoot, 'scripts', 'integration-check.mjs')], {
    cwd: projectRoot,
    env: { ...process.env, DEMO_BASE_URL: baseUrl },
    stdio: 'inherit',
  });
  const result = await new Promise(resolveResult => {
    check.once('error', error => resolveResult({ code: 1, error }));
    check.once('exit', (code, signal) => resolveResult({ code: code ?? 1, signal }));
  });
  if (result.code !== 0) {
    throw new Error(`隔离集成检查失败，exit=${result.code}${result.signal ? ` signal=${result.signal}` : ''}`);
  }
} finally {
  await terminateProcessTree(child);
  await rm(root, { recursive: true, force: true });
  if (output && process.env.SHOW_ISOLATED_LOGS === '1') process.stderr.write(output);
}
