// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// TCP socket to the pool, wrapped in Noise. Pumps framed Stratum V2
// messages in both directions.

import { Socket } from 'node:net';
import { NoiseState } from './noise.ts';
import { encodeFrame, tryDecodeFrame, messageTypeName } from './stratum-v2.ts';
import type { BridgeConfig } from './types.ts';

export type PoolMessageHandler = (type: number, payload: Uint8Array) => void;

/**
 * One Noise-encrypted Stratum V2 connection to a pool.
 *
 * Lifecycle:
 *   1. open() — TCP connect + Noise XX handshake (sketched — see noise.ts).
 *   2. onMessage(handler) — register a callback for each decrypted frame.
 *   3. send(type, payload) — encrypt + frame + write to the socket.
 *   4. close() — graceful shutdown.
 */
export class PoolClient {
  private socket: Socket | null = null;
  private noise: NoiseState;
  private recvBuf: Uint8Array = new Uint8Array(0);
  private handler: PoolMessageHandler | null = null;
  private closed = false;

  constructor(private cfg: BridgeConfig) {
    this.noise = new NoiseState('Noise_XX_25519_ChaChaPoly_SHA256', cfg.staticKey);
  }

  async open(): Promise<void> {
    return new Promise((resolve, reject) => {
      const sock = new Socket();
      sock.connect(this.cfg.poolPort, this.cfg.poolHost);
      sock.once('connect', () => {
        this.socket = sock;
        this.runHandshake().then(resolve, reject);
      });
      sock.once('error', reject);
    });
  }

  /** Run the Noise XX handshake. See noise.ts for the limitations. */
  private async runHandshake(): Promise<void> {
    if (!this.socket) throw new Error('socket not open');
    // Write handshake message 1.
    const m1 = this.noise.buildHandshake1();
    this.socket.write(Buffer.from(encodeFrame(0xfe, m1)));
    // Wait for message 2, then send message 3, then finalize.
    await this.readHandshakeMessage(2);
    const m3 = this.noise.buildHandshake3();
    this.socket.write(Buffer.from(encodeFrame(0xfe, m3)));
    this.noise.finalize();
    // Start the message pump.
    this.startPump();
  }

  private readHandshakeMessage(expected: number): Promise<void> {
    return new Promise((resolve, reject) => {
      if (!this.socket) return reject(new Error('socket not open'));
      const onFrame = (data: Buffer) => {
        const frame = tryDecodeFrame(new Uint8Array(data));
        if (!frame) return;
        try {
          if (expected === 2) this.noise.consumeHandshake2(frame.payload);
          resolve();
        } catch (e) {
          reject(e);
        } finally {
          this.socket?.off('data', onFrame);
        }
      };
      this.socket.on('data', onFrame);
    });
  }

  private startPump(): void {
    if (!this.socket) return;
    this.socket.on('data', (chunk: Buffer) => {
      this.recvBuf = concat(this.recvBuf, new Uint8Array(chunk));
      let frame;
      while ((frame = tryDecodeFrame(this.recvBuf))) {
        this.recvBuf = this.recvBuf.slice(frame.consumed);
        try {
          const plaintext = this.noise.decrypt(frame.payload);
          this.handler?.(frame.type, plaintext);
        } catch (e) {
          console.error(`[pool] decrypt failed for ${messageTypeName(frame.type)}:`, e);
        }
      }
    });
    this.socket.on('close', () => { this.closed = true; });
    this.socket.on('error', (e) => console.error('[pool] socket error:', e));
  }

  onMessage(handler: PoolMessageHandler): void {
    this.handler = handler;
  }

  send(type: number, payload: Uint8Array): void {
    if (!this.socket || this.closed) return;
    const ct = this.noise.encrypt(payload);
    this.socket.write(Buffer.from(encodeFrame(type, ct)));
  }

  close(): void {
    this.closed = true;
    this.socket?.end();
    this.socket = null;
  }
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length);
  out.set(a);
  out.set(b, a.length);
  return out;
}
