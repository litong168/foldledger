#!/usr/bin/env node
// 冒烟测试（环境变量模式）：验证 server.js 可从 SYNC_HOST/SYNC_PORT/SYNC_DATA/SYNC_TOKEN
// 读取配置（NAS Web UI 无命令行能力时的部署形态）。用法: node smoke-env.js
'use strict';

const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');

const PORT = 18702;
const DATA = path.join(os.tmpdir(), `foldledger-smoke-env-${Date.now()}.json`);
const TOKEN = 'env-secret';
const BASE = `http://127.0.0.1:${PORT}`;

let failures = 0;
let done = false;

function assert(cond, msg) {
  if (cond) console.log('ok  :', msg);
  else { failures++; console.log('FAIL:', msg); }
}

async function post(url, body) {
  const r = await fetch(BASE + url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: r.status, json: await r.json() };
}

const server = spawn(
  process.execPath,
  [path.join(__dirname, 'server.js')],
  {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, SYNC_HOST: '127.0.0.1', SYNC_PORT: String(PORT), SYNC_DATA: DATA, SYNC_TOKEN: TOKEN },
  }
);
server.stdout.on('data', (d) => console.log('[server]', String(d).trim()));
server.on('exit', () => {
  if (!done) { console.log('server exited early'); process.exit(1); }
});

(async () => {
  await new Promise((resolve, reject) => {
    server.stdout.on('data', (d) => {
      if (String(d).includes('sync server on')) resolve();
    });
    setTimeout(() => reject(new Error('server start timeout')), 5000);
  });

  // token 从环境变量注入 → 门禁生效
  let r = await post('/register', {});
  assert(r.status === 401, 'env-token gate: register without token rejected');

  r = await post('/register', { token: TOKEN });
  assert(r.status === 200 && r.json.deviceId === 1, 'env-token register ok (deviceId=1)');

  // 数据文件落在 SYNC_DATA 指定位置
  assert(fs.existsSync(DATA), `env SYNC_DATA used (${path.basename(DATA)})`);

  done = true;
  server.kill();
  try { fs.unlinkSync(DATA); } catch (_) {}
  console.log(failures === 0 ? '\nSMOKE-ENV PASS' : `\nSMOKE-ENV FAIL (${failures})`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error('smoke-env error:', e.message);
  server.kill();
  process.exit(1);
});
