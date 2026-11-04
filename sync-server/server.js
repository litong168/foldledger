#!/usr/bin/env node
// ============================================================
// 滴水记账 双端同步服务端（单文件、零依赖、Node 18+）
//
// 用法:
//   node server.js                       # 默认 127.0.0.1:8701，数据 ./data.json
//   node server.js --port 8701 --data ./data.json --token 你的密钥
//   node server.js --host 0.0.0.0         # 对外监听（建议配反向代理 TLS / 仅内网）
//
// 环境变量（Docker/NAS Web UI 无命令行能力时使用；CLI 参数优先）:
//   SYNC_HOST / SYNC_PORT / SYNC_DATA / SYNC_TOKEN  与上方 --* 参数一一对应
//
// 协议:
//   POST /register
//     请求 { "token"? }                  --token 启用时必填
//     响应 { "deviceId": 1, "serverTime": 1700000000000 }
//       deviceId 递增分配；serverTime 是权威时钟，客户端据此算
//       offset = serverTime - localTime 并对齐 LWW 时间戳
//   POST /sync
//     请求 {
//       "token"?, "deviceId": 1,
//       "watermarks": { "transactions": 123, ... },   // 每表上次同步到的 max(updatedAt)+1
//       "rows": [ { "table","id","updatedAt","updatedByDeviceId","deletedAt"?,"payload" } ]
//     }
//     响应 { "serverTime": ..., "rows": [增量行（updatedAt >= 该表水位线，含墓碑）], "merged": n }
//
// 水位线语义：客户端推进到「已见行 max(updatedAt) + 1」，服务端按 `>=` 返回——
// 同一毫秒的多行更新（批量导入、连续操作）不会因为严格大于而被永久漏掉，
// 已同步行（updatedAt < 水位线）也绝不会重发。
// LWW 规则与客户端 Kotlin 实现（common/data/sync/Lww.kt）完全一致：
//   updatedAt 大者胜；同刻 updatedByDeviceId 大者胜；同刻同设备幂等。
//   存储以 (table, id) 为行身份。
// ============================================================
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const TABLES = ['ledgers', 'categories', 'transactions', 'projects', 'accounts', 'recurring', 'goals'];
const MAX_BODY_BYTES = 64 * 1024 * 1024; // 64MB 防异常大包

// ---------------- 启动参数 ----------------
const args = process.argv.slice(2);
function arg(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] !== undefined ? args[i + 1] : def;
}
const HOST = arg('host', process.env.SYNC_HOST || '127.0.0.1');
const PORT = parseInt(arg('port', process.env.SYNC_PORT || '8701'), 10);
const DATA_FILE = path.resolve(arg('data', process.env.SYNC_DATA || './data.json'));
const TOKEN = arg('token', process.env.SYNC_TOKEN || ''); // 空 = 不启用门禁（仅建议内网/本机使用）

// ---------------- 存储（单 JSON 文件，原子写） ----------------
// 结构: { "nextDeviceId": n, "rows": { "<table>": { "<id>": row } } }
let store = { nextDeviceId: 1, rows: {} };

function load() {
  try {
    const raw = fs.readFileSync(DATA_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    store = {
      nextDeviceId: Number.isInteger(parsed.nextDeviceId) && parsed.nextDeviceId > 0 ? parsed.nextDeviceId : 1,
      rows: parsed.rows && typeof parsed.rows === 'object' ? parsed.rows : {},
    };
  } catch (_) {
    store = { nextDeviceId: 1, rows: {} }; // 首次启动或文件损坏 → 空库
  }
}

function save() {
  const tmp = DATA_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(store), 'utf8');
  fs.renameSync(tmp, DATA_FILE); // 原子替换：崩溃不会留下半个文件
}

// ---------------- LWW（与客户端一致） ----------------
function aWins(updatedAtA, deviceA, updatedAtB, deviceB) {
  return updatedAtA > updatedAtB || (updatedAtA === updatedAtB && deviceA >= deviceB);
}

// ---------------- 请求处理 ----------------
function readBody(req, cb) {
  const chunks = [];
  let size = 0;
  req.on('data', (c) => {
    size += c.length;
    if (size > MAX_BODY_BYTES) {
      req.destroy();
      return;
    }
    chunks.push(c);
  });
  req.on('end', () => {
    let body = {};
    try {
      if (chunks.length) body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch (_) {
      cb(null); // 非法 JSON → 400
      return;
    }
    cb(body);
  });
  req.on('error', () => cb(null));
}

function sendJson(res, code, obj) {
  const raw = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
  });
  res.end(raw);
}

function ok(res, obj) {
  sendJson(res, 200, obj);
}

function fail(res, code, msg) {
  sendJson(res, code, { error: msg });
}

function checkToken(body) {
  return TOKEN === '' || body.token === TOKEN;
}

function isValidRow(r) {
  return (
    r !== null && typeof r === 'object' &&
    TABLES.includes(r.table) &&
    Number.isInteger(r.id) && r.id >= 0 &&
    Number.isInteger(r.updatedAt) &&
    Number.isInteger(r.updatedByDeviceId) &&
    typeof r.payload === 'string'
  );
}

// ---------------- 路由 ----------------
function handleRegister(body, res) {
  if (!checkToken(body)) return fail(res, 401, 'invalid token');
  const deviceId = store.nextDeviceId++;
  save();
  ok(res, { deviceId: deviceId, serverTime: Date.now() });
}

function handleSync(body, res) {
  if (!checkToken(body)) return fail(res, 401, 'invalid token');
  const deviceId = body.deviceId;
  if (!Number.isInteger(deviceId) || deviceId < 1) {
    return fail(res, 400, 'missing or invalid deviceId (register first)');
  }

  // 1) LWW 合并本机上传的行
  let merged = 0;
  const uploaded = Array.isArray(body.rows) ? body.rows : [];
  for (const row of uploaded) {
    if (!isValidRow(row)) continue; // 坏行跳过（个人自托管，宽松处理）
    const table = row.table;
    if (!store.rows[table]) store.rows[table] = {};
    const key = String(row.id);
    const local = store.rows[table][key];
    if (!local || aWins(row.updatedAt, row.updatedByDeviceId, local.updatedAt, local.updatedByDeviceId)) {
      store.rows[table][key] = row;
      merged++;
    }
  }
  save();

  // 2) 按请求方水位线收集增量（>= 语义：客户端推进到 max+1，同刻行不漏、已见行不重发）
  const watermarks = body.watermarks && typeof body.watermarks === 'object' ? body.watermarks : {};
  const out = [];
  for (const table of TABLES) {
    const wm = Number(watermarks[table]) || 0;
    const rows = store.rows[table] || {};
    for (const key in rows) {
      const row = rows[key];
      if (row.updatedAt >= wm) out.push(row);
    }
  }

  ok(res, { serverTime: Date.now(), rows: out, merged: merged });
}

const server = http.createServer((req, res) => {
  if (req.method !== 'POST') return fail(res, 405, 'only POST allowed');
  const url = req.url.split('?')[0];
  if (url !== '/register' && url !== '/sync') return fail(res, 404, 'not found');
  readBody(req, (body) => {
    if (body === null) return fail(res, 400, 'invalid JSON body');
    const t0 = Date.now();
    if (url === '/register') handleRegister(body, res);
    else handleSync(body, res);
    console.log(`[${new Date().toISOString()}] ${url} from=${req.socket.remoteAddress} ${Date.now() - t0}ms`);
  });
});

load();
server.listen(PORT, HOST, () => {
  console.log(`foldledger sync server on http://${HOST}:${PORT}  data=${DATA_FILE}` +
    (TOKEN ? '  token: enabled' : '  token: NONE (仅建议内网使用)'));
});
