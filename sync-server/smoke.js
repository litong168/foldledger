#!/usr/bin/env node
// 冒烟测试：真实启动 server.js，用全局 fetch 走完注册/合并/增量/墓碑/token 全链路。
// 用法: node smoke.js
'use strict';

const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');

const PORT = 18901; // 固定测试端口，避免撞默认 8701
const DATA = path.join(os.tmpdir(), `foldledger-smoke-${Date.now()}.json`);
const TOKEN = 'smoke-secret';
const BASE = `http://127.0.0.1:${PORT}`;

let failures = 0;
let done = false;

function assert(cond, msg) {
  if (cond) console.log('ok  :', msg);
  else {
    failures++;
    console.log('FAIL:', msg);
  }
}

async function post(url, body) {
  const r = await fetch(BASE + url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: r.status, json: await r.json() };
}

function row(table, id, updatedAt, device, payload, deletedAt = 0) {
  return { table, id, updatedAt, updatedByDeviceId: device, deletedAt, payload };
}

const server = spawn(
  process.execPath,
  [path.join(__dirname, 'server.js'), '--port', String(PORT), '--data', DATA, '--token', TOKEN],
  { stdio: ['ignore', 'pipe', 'pipe'] }
);
server.stdout.on('data', (d) => console.log('[server]', String(d).trim()));
server.on('exit', () => {
  if (!done) {
    console.log('server exited early');
    process.exit(1);
  }
});

(async () => {
  await new Promise((resolve, reject) => {
    server.stdout.on('data', (d) => {
      if (String(d).includes('sync server on')) resolve();
    });
    setTimeout(() => reject(new Error('server start timeout')), 5000);
  });

  // token 门禁
  let r = await post('/register', {});
  assert(r.status === 401, 'register without token rejected');

  // 注册两台设备（递增 id + serverTime 时钟源）
  r = await post('/register', { token: TOKEN });
  assert(r.status === 200 && r.json.deviceId === 1 && typeof r.json.serverTime === 'number', 'device 1 registered (id=1, serverTime)');
  const dev1 = r.json.deviceId;
  r = await post('/register', { token: TOKEN });
  const dev2 = r.json.deviceId;
  assert(dev2 === 2, 'device 2 registered (id=2)');

  // 设备1 上传：账本 + 2 笔流水
  const ledgerRow = row('ledgers', 1, 1000, dev1, '{"id":1,"name":"日常"}');
  const txnA = row('transactions', 1, 2000, dev1, '{"id":1,"amount":1000}');
  const txnB = row('transactions', 2, 2000, dev1, '{"id":2,"amount":500}');
  r = await post('/sync', { token: TOKEN, deviceId: dev1, watermarks: {}, rows: [ledgerRow, txnA, txnB] });
  assert(r.status === 200 && r.json.merged === 3, 'device1 uploads 3 rows, all merged');

  // 设备2 首同步（wm=0 全量）
  r = await post('/sync', { token: TOKEN, deviceId: dev2, watermarks: {}, rows: [] });
  assert(r.status === 200 && r.json.rows.length === 3, 'device2 first sync pulls 3 rows');

  // 设备2 水位线推进后 → 空增量（水位线 = max(updatedAt)+1，>= 语义）
  r = await post('/sync', { token: TOKEN, deviceId: dev2, watermarks: { ledgers: 1001, transactions: 2001 }, rows: [] });
  assert(r.status === 200 && r.json.rows.length === 0, 'device2 no increment after watermark');

  // 设备2 改 txn 1（更高时间戳）→ 设备1 拉取覆盖
  r = await post('/sync', { token: TOKEN, deviceId: dev2, watermarks: {}, rows: [row('transactions', 1, 3000, dev2, '{"id":1,"amount":999}')] });
  assert(r.json.merged === 1, 'device2 update merged (LWW newer wins)');
  r = await post('/sync', { token: TOKEN, deviceId: dev1, watermarks: { transactions: 2000 }, rows: [] });
  const got = r.json.rows.find((x) => x.table === 'transactions' && x.id === 1);
  assert(got && got.payload.includes('999'), 'device1 pulls updated txn 1');

  // LWW：旧时间戳不能覆盖新行
  r = await post('/sync', { token: TOKEN, deviceId: dev1, watermarks: {}, rows: [row('transactions', 1, 2500, dev1, '{"id":1,"amount":1}')] });
  assert(r.json.merged === 0, 'older row rejected by LWW (merged=0)');

  // 墓碑：设备2 删 txn 2 → 设备1 拉取拿到墓碑
  r = await post('/sync', { token: TOKEN, deviceId: dev2, watermarks: {}, rows: [row('transactions', 2, 4000, dev2, '{"id":2,"amount":500}', 4000)] });
  assert(r.json.merged === 1, 'tombstone merged');
  r = await post('/sync', { token: TOKEN, deviceId: dev1, watermarks: { transactions: 2000 }, rows: [] });
  const tomb = r.json.rows.find((x) => x.table === 'transactions' && x.id === 2);
  assert(tomb && tomb.deletedAt === 4000, 'device1 receives tombstone');

  // 同刻多行更新不得漏发（>= 水位线语义的回归用例）
  r = await post('/sync', {
    token: TOKEN, deviceId: dev2, watermarks: {},
    rows: [
      row('transactions', 3, 5000, dev2, '{"id":3,"amount":1}'),
      row('transactions', 4, 5000, dev2, '{"id":4,"amount":2}'),
    ],
  });
  assert(r.json.merged === 2, 'two rows with same updatedAt merged');
  r = await post('/sync', { token: TOKEN, deviceId: dev1, watermarks: { transactions: 5000 }, rows: [] });
  const sameTick = r.json.rows.filter((x) => x.table === 'transactions' && (x.id === 3 || x.id === 4));
  assert(sameTick.length === 2, 'same-tick rows not lost with wm = updatedAt (>= semantics)');

  // 未知表名 → 跳过不写盘
  r = await post('/sync', { token: TOKEN, deviceId: dev1, watermarks: {}, rows: [row('evil_table', 1, 1, dev1, '{}')] });
  assert(r.json.merged === 0, 'unknown table row skipped');

  // 坏 JSON → 400
  const rr = await fetch(BASE + '/sync', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{bad',
  });
  assert(rr.status === 400, 'malformed JSON → 400');

  // sync 不带 token → 401
  r = await post('/sync', { deviceId: dev1, watermarks: {}, rows: [] });
  assert(r.status === 401, 'sync without token rejected');

  // ---- 持久化：重启后设备 id 递增不重置、数据仍在 ----
  done = true;
  server.kill();
  await new Promise((r2) => setTimeout(r2, 300));
  const server2 = spawn(
    process.execPath,
    [path.join(__dirname, 'server.js'), '--port', String(PORT), '--data', DATA, '--token', TOKEN],
    { stdio: ['ignore', 'pipe', 'pipe'] }
  );
  await new Promise((resolve, reject) => {
    server2.stdout.on('data', (d) => {
      if (String(d).includes('sync server on')) resolve();
    });
    setTimeout(() => reject(new Error('server2 start timeout')), 5000);
  });

  r = await post('/register', { token: TOKEN });
  assert(r.json.deviceId === 3, 'device id persists across restart (next=3)');
  r = await post('/sync', { token: TOKEN, deviceId: 3, watermarks: {}, rows: [] });
  assert(r.json.rows.length === 5, 'rows persist across restart (5 rows)');

  server2.kill();
  try {
    fs.unlinkSync(DATA);
  } catch (_) { /* 已在超时分支删过则忽略 */ }
  console.log(failures === 0 ? '\nSMOKE PASS' : `\nSMOKE FAIL (${failures})`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error('smoke error:', e.message);
  server.kill();
  process.exit(1);
});
