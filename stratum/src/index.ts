// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// Stratum V2 Noise bridge — accepts WebSocket connections from the
// browser miner / Android app and translates them to Noise-over-TCP
// pool connections.
//
// Browser ↔ Bridge protocol (JSON, newline-delimited):
//   Bridge → Miner:  {"type":"template", ...}
//   Miner  → Bridge: {"type":"submit","template_id":"...","nonce_hex":"..."}
//   Bridge → Miner:  {"type":"submit_result","ok":true,"hash":"..."}
//   Bridge → Miner:  {"type":"status","connected":true,"pool":"..."}
//
// The bridge does NOT terminate mining — it only forwards template /
// submit messages. The miner (browser/android) still does the actual
// Poseidon2b hashing.
//
// Reach the bridge from the browser via the gateway with
// ?XTransformPort=3030:
//   new WebSocket('/?XTransformPort=3030')

import 'dotenv/config';
import { WebSocketServer, WebSocket } from 'ws';
import { PoolClient } from './pool-client.ts';
import { messageTypeName } from './stratum-v2.ts';
import type { BridgeConfig, BridgeMessage } from './types.ts';

function loadConfig(): BridgeConfig {
  const poolHost = process.env.POOL_HOST ?? '127.0.0.1';
  const poolPort = parseInt(process.env.POOL_PORT ?? '8443', 10);
  const bridgePort = parseInt(process.env.BRIDGE_PORT ?? '3030', 10);
  const poolPubkeyHex = process.env.POOL_PUBKEY ?? '';
  const staticKeyHex = process.env.BRIDGE_STATIC_KEY ?? '';
  return {
    poolHost,
    poolPort,
    poolPubkey: hexToBytes(poolPubkeyHex),
    bridgePort,
    staticKey: hexToBytes(staticKeyHex),
    logLevel: (process.env.LOG_LEVEL as BridgeConfig['logLevel']) ?? 'info',
  };
}

function hexToBytes(hex: string): Uint8Array | null {
  if (!hex || hex.length !== 64) return null;
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i++) {
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

const cfg = loadConfig();

const log = {
  error: (...a: unknown[]) => console.error('[bridge]', ...a),
  warn:  (...a: unknown[]) => console.warn('[bridge]', ...a),
  info:  (...a: unknown[]) => { if (cfg.logLevel !== 'error') console.log('[bridge]', ...a); },
  debug: (...a: unknown[]) => { if (cfg.logLevel === 'debug') console.log('[bridge]', ...a); },
};

const wss = new WebSocketServer({ port: cfg.bridgePort, path: '/' });

wss.on('listening', () => {
  log.info(`stratum bridge listening on ws://0.0.0.0:${cfg.bridgePort}/  →  ${cfg.poolHost}:${cfg.poolPort}`);
});

wss.on('connection', (ws) => {
  log.info('client connected');
  let pool: PoolClient | null = null;

  const send = (msg: BridgeMessage) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(msg));
    }
  };

  // Open the pool connection.
  pool = new PoolClient(cfg);
  pool.onMessage((type, payload) => {
    log.debug(`pool → miner: ${messageTypeName(type)} (${payload.length}B)`);
    // The full SV2 protobuf parsing is out of scope for v0.1 — the
    // bridge passes through template/submit messages. A future patch
    // will translate the protobuf into the JSON shapes the miner expects.
    // For now we emit a status heartbeat so the miner knows we're alive.
    send({ type: 'log', level: 'info', message: `pool msg ${messageTypeName(type)}` });
  });

  pool.open().then(
    () => send({ type: 'status', connected: true, pool: `${cfg.poolHost}:${cfg.poolPort}` }),
    (err) => {
      log.error('pool connect failed:', err);
      send({ type: 'status', connected: false });
    },
  );

  ws.on('message', (raw) => {
    let msg: any;
    try { msg = JSON.parse(raw.toString()); } catch { return; }
    if (!msg || typeof msg.type !== 'string') return;

    switch (msg.type) {
      case 'submit': {
        // Translate the miner's JSON submit into a Stratum V2
        // SUBMIT_SOLUTION (0x0a) frame. The bridge does NOT validate the
        // nonce — the pool will reject it if the PoW is wrong.
        log.debug(`miner → pool: submit template=${msg.template_id} nonce=${msg.nonce_hex}`);
        // Payload layout for SUBMIT_SOLUTION is defined in the SV2 spec;
        // we send the nonce bytes as a placeholder. A full implementation
        // will populate the full solution struct.
        const nonceBytes = hexToBytes(msg.nonce_hex as string) ?? new Uint8Array(0);
        pool?.send(0x0a, nonceBytes);
        break;
      }
      default:
        log.warn(`unknown miner message: ${msg.type}`);
    }
  });

  ws.on('close', () => {
    log.info('client disconnected');
    pool?.close();
  });

  ws.on('error', (err) => log.error('ws error:', err));
});

// Graceful shutdown.
process.on('SIGINT', () => {
  log.info('shutting down…');
  wss.clients.forEach((c) => c.terminate());
  wss.close(() => process.exit(0));
});
