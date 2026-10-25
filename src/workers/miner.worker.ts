// SPDX-License-Identifier: Apache-2.0
// Miner hash worker — runs Poseidon2b PoW search off the main thread.
//
// Each worker loads the WASM backend once, then receives search tasks:
//   { type: 'search', taskId, powFieldsHex, targetLeHex, startNonce, count, batchSize }
// and posts back:
//   { type: 'ready' }
//   { type: 'progress', taskId, hashes }
//   { type: 'found', taskId, nonceLeHex, hashHex }
//   { type: 'done', taskId, hashes }
//   { type: 'error', taskId, message }

/// <reference lib="webworker" />

import {
  batchHash,
  checkTarget,
  le256LessThan,
  loadPowBackend,
  nonceToLeHex,
  type NoidWasmModule,
} from "@/lib/parano1d/poseidon2b";

interface SearchTask {
  type: "search";
  taskId: number;
  powFieldsHex: string;
  targetLeHex: string;
  startNonce: string; // decimal string of u128 bigint
  count: string; // decimal string of u128 bigint
  batchSize: number;
}

interface InitTask {
  type: "init";
}

type Inbound = SearchTask | InitTask;

let backend: NoidWasmModule | null = null;
let backendError: string | null = null;
let ready = false;

const ctx = self as unknown as DedicatedWorkerGlobalScope;

async function ensureBackend() {
  if (ready) return backend;
  backend = await loadPowBackend();
  if (!backend) {
    backendError =
      "WASM PoW backend unavailable — run GitHub Actions build-wasm.yml to compile it from the Rust crate.";
    ready = false;
  } else {
    backendError = null;
    ready = true;
  }
  ctx.postMessage({ type: ready ? "ready" : "error", message: backendError });
  return backend;
}

ctx.addEventListener("message", async (ev: MessageEvent<Inbound>) => {
  const msg = ev.data;
  if (msg.type === "init") {
    await ensureBackend();
    return;
  }
  if (msg.type === "search") {
    if (!backend || !ready) {
      ctx.postMessage({
        type: "error",
        taskId: msg.taskId,
        message: backendError ?? "backend not ready",
      });
      return;
    }
    try {
      const start = BigInt(msg.startNonce);
      const count = BigInt(msg.count);
      const batchSize = msg.batchSize;
      let cursor = start;
      let totalHashes = 0n;
      while (cursor < start + count) {
        const remaining = start + count - cursor;
        const thisBatch = BigInt(Math.min(Number(remaining), batchSize));
        const batchHashesHex = batchHash(
          backend,
          msg.powFieldsHex,
          cursor,
          Number(thisBatch),
        );
        // Each hash is 64 hex chars.
        const hashLen = 64;
        for (let i = 0; i < Number(thisBatch); i++) {
          const hashHex = batchHashesHex.slice(i * hashLen, (i + 1) * hashLen);
          if (checkTarget(backend, hashHex, msg.targetLeHex)) {
            const nonce = cursor + BigInt(i);
            ctx.postMessage({
              type: "found",
              taskId: msg.taskId,
              nonceLeHex: nonceToLeHex(nonce),
              hashHex,
            });
            return;
          }
        }
        totalHashes += thisBatch;
        if (totalHashes >= 50_000n) {
          ctx.postMessage({
            type: "progress",
            taskId: msg.taskId,
            hashes: totalHashes.toString(),
          });
          totalHashes = 0n;
        }
        cursor += thisBatch;
      }
      if (totalHashes > 0n) {
        ctx.postMessage({
          type: "progress",
          taskId: msg.taskId,
          hashes: totalHashes.toString(),
        });
      }
      ctx.postMessage({
        type: "done",
        taskId: msg.taskId,
        hashes: (start + count - start).toString(),
      });
    } catch (err) {
      ctx.postMessage({
        type: "error",
        taskId: msg.taskId,
        message: err instanceof Error ? err.message : String(err),
      });
    }
  }
});

// Kick off backend load immediately.
void ensureBackend();
