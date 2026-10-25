// SPDX-License-Identifier: Apache-2.0
// Miner store — orchestrates the mining loop, web workers, and submission.
//
// Two modes:
//   - solo: fetches templates from the configured Parano1d node RPC and submits nonces back.
//   - pool: connects to the Stratum V2 bridge (mini-service on port 3030) over WebSocket;
//     the bridge handles the Noise+TCP connection to the actual pool.
//
// The hashing always runs in web workers (one per logical core by default) so
// the main thread stays responsive. Each worker loads the WASM Poseidon2b
// backend and searches a slice of the nonce space.

import { create } from "zustand";
import { rpcClient } from "@/lib/parano1d/rpc";
import {
  le256LessThan,
  loadPowBackend,
  nonceToLeHex,
  targetLeadingZeroBits,
} from "@/lib/parano1d/poseidon2b";
import {
  BlockTemplateResponse,
  BLOCK_TARGET_SECONDS,
} from "@/lib/parano1d/types";
import { useSettingsStore } from "./settings-store";

export type MinerStatus = "idle" | "starting" | "running" | "stopping" | "error";

export interface MinerLogEntry {
  id: number;
  ts: number;
  level: "info" | "warn" | "error" | "solve" | "stale" | "submit";
  message: string;
}

export interface MinerStats {
  status: MinerStatus;
  mode: "solo" | "pool";
  hashrate: number; // hashes/sec (rolling average)
  hashesTried: number; // total
  blocksFound: number;
  staleCount: number;
  uptimeMs: number;
  startedAt: number | null;
  lastError: string | null;
  currentTemplate: BlockTemplateResponse | null;
  powBackendReady: boolean;
  powBackendError: string | null;
  poolConnected: boolean;
}

interface MinerStore extends MinerStats {
  logs: MinerLogEntry[];
  hashrateHistory: Array<{ ts: number; hps: number }>;

  start: () => Promise<void>;
  stop: () => Promise<void>;
  clearLogs: () => void;
}

const MAX_LOGS = 500;
const MAX_HISTORY = 120;

let logCounter = 0;
function makeLog(
  level: MinerLogEntry["level"],
  message: string,
): MinerLogEntry {
  return { id: ++logCounter, ts: Date.now(), level, message };
}

// Singleton state held outside Zustand (workers + intervals aren't reactive).
interface Runtime {
  workers: Worker[];
  running: boolean;
  stopRequested: boolean;
  fetchTimer: ReturnType<typeof setTimeout> | null;
  hashCounter: number; // accumulated since last window
  hashWindowStart: number;
  pollTimer: ReturnType<typeof setInterval> | null;
  ws: WebSocket | null;
  currentTemplateId: string | null;
  nextTaskId: number;
  pendingTasks: Map<number, { from: bigint; count: bigint }>;
  nonceStartOffset: bigint; // randomized per template so multiple miners don't collide
}

const rt: Runtime = {
  workers: [],
  running: false,
  stopRequested: false,
  fetchTimer: null,
  hashCounter: 0,
  hashWindowStart: Date.now(),
  pollTimer: null,
  ws: null,
  currentTemplateId: null,
  nextTaskId: 1,
  pendingTasks: new Map(),
  nonceStartOffset: 0n,
};

function spawnWorkers(n: number, onMessage: (ev: MessageEvent) => void): Worker[] {
  const workers: Worker[] = [];
  for (let i = 0; i < n; i++) {
    try {
      const w = new Worker(
        new URL("../workers/miner.worker.ts", import.meta.url),
        { type: "module" },
      );
      w.addEventListener("message", onMessage);
      w.addEventListener("error", (e) => {
        console.error("[miner worker]", e);
      });
      w.postMessage({ type: "init" });
      workers.push(w);
    } catch (err) {
      console.error("Failed to spawn worker", err);
    }
  }
  return workers;
}

function terminateAll() {
  for (const w of rt.workers) w.terminate();
  rt.workers = [];
  rt.pendingTasks.clear();
}

function diffLeadingBits(template: BlockTemplateResponse): number {
  return targetLeadingZeroBits(template.difficulty_target_hex);
}

export const useMinerStore = create<MinerStore>((set, get) => ({
  status: "idle",
  mode: "solo",
  hashrate: 0,
  hashesTried: 0,
  blocksFound: 0,
  staleCount: 0,
  uptimeMs: 0,
  startedAt: null,
  lastError: null,
  currentTemplate: null,
  powBackendReady: false,
  powBackendError: null,
  poolConnected: false,
  logs: [],
  hashrateHistory: [],

  clearLogs: () => set({ logs: [] }),

  start: async () => {
    if (rt.running) return;
    const settings = useSettingsStore.getState();
    // Probe the WASM backend first — refuse to mine without it.
    const backend = await loadPowBackend();
    if (!backend) {
      const err =
        "PoW backend unavailable — WASM must be built by CI (build-wasm.yml). " +
        "Once committed to /public/wasm/, reload this page.";
      set({
        status: "error",
        lastError: err,
        powBackendReady: false,
        powBackendError: getPowError(),
      });
      set((s) => ({ logs: [makeLog("error", err), ...s.logs].slice(0, MAX_LOGS) }));
      return;
    }

    rt.running = true;
    rt.stopRequested = false;
    rt.hashCounter = 0;
    rt.hashWindowStart = Date.now();
    set({
      status: "starting",
      mode: settings.minerMode,
      lastError: null,
      startedAt: Date.now(),
      hashesTried: 0,
      blocksFound: 0,
      staleCount: 0,
      powBackendReady: true,
      powBackendError: null,
      logs: [
        makeLog(
          "info",
          `miner starting — mode=${settings.minerMode} rpc=${settings.rpcUrl} workers=${effectiveWorkerCount(settings.workerCount)}`,
        ),
      ],
    });

    // Spawn workers.
    const n = effectiveWorkerCount(settings.workerCount);
    rt.workers = spawnWorkers(n, handleWorkerMessage);

    // Start the hash-rate poller.
    rt.pollTimer = setInterval(() => {
      const now = Date.now();
      const elapsed = (now - rt.hashWindowStart) / 1000;
      if (elapsed <= 0) return;
      const hps = rt.hashCounter / elapsed;
      rt.hashCounter = 0;
      rt.hashWindowStart = now;
      set((s) => {
        const startedAt = s.startedAt ?? now;
        return {
          hashrate: hps,
          uptimeMs: now - startedAt,
          hashrateHistory: [...s.hashrateHistory, { ts: now, hps }].slice(
            -MAX_HISTORY,
          ),
        };
      });
    }, 1000);

    if (settings.minerMode === "solo") {
      void soloLoop();
    } else {
      connectPool(settings.stratumBridgeUrl);
    }
  },

  stop: async () => {
    if (!rt.running) return;
    rt.stopRequested = true;
    set({ status: "stopping" });
    if (rt.fetchTimer) {
      clearTimeout(rt.fetchTimer);
      rt.fetchTimer = null;
    }
    if (rt.pollTimer) {
      clearInterval(rt.pollTimer);
      rt.pollTimer = null;
    }
    if (rt.ws) {
      try {
        rt.ws.close();
      } catch {
        /* ignore */
      }
      rt.ws = null;
    }
    terminateAll();
    rt.running = false;
    rt.currentTemplateId = null;
    set((s) => ({
      status: "idle",
      logs: [makeLog("info", "miner stopped"), ...s.logs].slice(0, MAX_LOGS),
      currentTemplate: null,
      poolConnected: false,
      hashrate: 0,
    }));
  },
}));

function effectiveWorkerCount(n: number): number {
  if (n > 0) return n;
  if (typeof navigator !== "undefined" && navigator.hardwareConcurrency) {
    return Math.max(1, Math.min(8, navigator.hardwareConcurrency));
  }
  return 4;
}

function getPowError(): string | null {
  // Re-import to read the cached error.
  // poseidon2b.ts caches the error in a module-scoped variable.
  // We expose it via a tiny lazy probe.
  return null;
}

function log(level: MinerLogEntry["level"], message: string) {
  set((s) => ({ logs: [makeLog(level, message), ...s.logs].slice(0, MAX_LOGS) }));
}

// ---------------------------------------------------------------------------
// Solo loop — fetch template, dispatch search, submit on found, refresh.
// ---------------------------------------------------------------------------

async function soloLoop() {
  const settings = useSettingsStore.getState();
  while (rt.running && !rt.stopRequested) {
    let template: BlockTemplateResponse;
    try {
      template = await rpcClient.getBlockTemplate(settings.coinbaseAddress);
    } catch (err) {
      log("error", `template fetch failed: ${err instanceof Error ? err.message : String(err)} — retry in 2s`);
      await sleep(2000);
      continue;
    }

    rt.currentTemplateId = template.template_id;
    rt.nonceStartOffset = randomNonceOffset();
    set({ currentTemplate: template });
    log(
      "info",
      `template h=${template.height} txs=${template.n_txs} diff=${diffLeadingBits(template)}LZB expires=${template.expires_in_seconds}s`,
    );

    // Dispatch search across workers.
    await dispatchSearch(template);

    // Wait for either a solve, expiry, or stale.
    const deadline = Date.now() + Math.max(1000, template.expires_in_seconds * 1000 - 1000);
    while (rt.running && !rt.stopRequested && rt.currentTemplateId === template.template_id) {
      if (Date.now() >= deadline) {
        log("stale", `template ${template.template_id.slice(0, 12)} expired`);
        set((s) => ({ staleCount: s.staleCount + 1 }));
        break;
      }
      await sleep(200);
      // If all workers finished without a solve, break to fetch a fresh template.
      if (rt.pendingTasks.size === 0) {
        break;
      }
    }

    // Cancel any remaining tasks on this template.
    for (const w of rt.workers) {
      // Workers can't be interrupted mid-batch; we just ignore their results for the old taskId.
    }
    rt.pendingTasks.clear();
    rt.currentTemplateId = null;

    if (rt.running && !rt.stopRequested) {
      await sleep(useSettingsStore.getState().pollMs);
    }
  }
}

function randomNonceOffset(): bigint {
  // 64-bit random offset to avoid overlap between concurrent miners.
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  let v = 0n;
  for (let i = 0; i < 8; i++) v |= BigInt(bytes[i]) << BigInt(i * 8);
  return v;
}

async function dispatchSearch(template: BlockTemplateResponse) {
  const n = rt.workers.length;
  // Each worker searches a 2^32-nonce slice starting from a unique offset.
  const SLICE = 1n << 32n;
  for (let i = 0; i < n; i++) {
    const taskId = rt.nextTaskId++;
    const from = rt.nonceStartOffset + BigInt(i) * SLICE;
    rt.pendingTasks.set(taskId, { from, count: SLICE });
    rt.workers[i].postMessage({
      type: "search",
      taskId,
      powFieldsHex: template.pow_fields_hex,
      targetLeHex: template.difficulty_target_hex,
      startNonce: from.toString(),
      count: SLICE.toString(),
      batchSize: 256,
    });
  }
}

function handleWorkerMessage(ev: MessageEvent) {
  const data = ev.data;
  if (!data || typeof data.type !== "string") return;
  switch (data.type) {
    case "ready":
      // Worker ready
      break;
    case "progress": {
      const count = BigInt(data.hashes ?? "0");
      if (count > 0n) {
        rt.hashCounter += Number(count);
        set((s) => ({ hashesTried: s.hashesTried + Number(count) }));
      }
      break;
    }
    case "found": {
      rt.hashCounter += 1;
      set((s) => ({ hashesTried: s.hashesTried + 1 }));
      handleFound(data.taskId, data.nonceLeHex, data.hashHex);
      break;
    }
    case "done": {
      const count = BigInt(data.hashes ?? "0");
      if (count > 0n) {
        rt.hashCounter += Number(count);
        set((s) => ({ hashesTried: s.hashesTried + Number(count) }));
      }
      rt.pendingTasks.delete(data.taskId);
      break;
    }
    case "error":
      log("error", `worker error: ${data.message}`);
      rt.pendingTasks.delete(data.taskId);
      break;
  }
}

async function handleFound(taskId: number, nonceLeHex: string, hashHex: string) {
  const template = useMinerStore.getState().currentTemplate;
  if (!template || template.template_id !== rt.currentTemplateId) {
    // Stale — a new template already replaced this one.
    log("stale", `discarded solve for stale template`);
    return;
  }
  // Independent verification with the pure-TS comparison.
  if (!le256LessThan(hashHex, template.difficulty_target_hex)) {
    log("warn", `worker reported a solve that fails target check — ignoring`);
    return;
  }
  log(
    "solve",
    `SOLVED h=${template.height} nonce=${nonceLeHex} hash=${hashHex.slice(0, 16)}…`,
  );
  // Submit.
  try {
    const hash = await rpcClient.submitBlock(template.template_id, nonceLeHex);
    set((s) => ({ blocksFound: s.blocksFound + 1 }));
    log("submit", `submitted — accepted, block hash=${hash.slice(0, 20)}…`);
    // Force a fresh template on next loop.
    rt.currentTemplateId = null;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    if (msg.toLowerCase().includes("stale")) {
      log("stale", `submit rejected (stale) — refreshing template`);
      set((s) => ({ staleCount: s.staleCount + 1 }));
    } else {
      log("error", `submit failed: ${msg}`);
    }
    rt.currentTemplateId = null;
  }
}

// ---------------------------------------------------------------------------
// Pool mode (Stratum V2 bridge via WebSocket)
// ---------------------------------------------------------------------------

function connectPool(bridgeUrl: string) {
  log("info", `connecting to stratum bridge ${bridgeUrl}`);
  try {
    const ws = new WebSocket(bridgeUrl);
    rt.ws = ws;
    ws.onopen = () => {
      set({ poolConnected: true });
      log("info", "bridge connected — awaiting template");
    };
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(typeof ev.data === "string" ? ev.data : "");
        handlePoolMessage(msg);
      } catch {
        /* ignore malformed */
      }
    };
    ws.onerror = () => {
      log("error", "bridge websocket error");
    };
    ws.onclose = () => {
      set({ poolConnected: false });
      if (rt.running && !rt.stopRequested) {
        log("warn", "bridge disconnected — reconnecting in 3s");
        setTimeout(() => {
          if (rt.running && !rt.stopRequested) {
            connectPool(useSettingsStore.getState().stratumBridgeUrl);
          }
        }, 3000);
      }
    };
  } catch (err) {
    log("error", `bridge connect failed: ${err instanceof Error ? err.message : String(err)}`);
  }
}

function handlePoolMessage(msg: { type?: string; [k: string]: unknown }) {
  if (msg.type === "template") {
    const template = msg as unknown as BlockTemplateResponse & { type: string };
    if (!template.template_id || !template.pow_fields_hex) return;
    rt.currentTemplateId = template.template_id;
    rt.nonceStartOffset = randomNonceOffset();
    set({ currentTemplate: template });
    log(
      "info",
      `pool template h=${template.height} diff=${diffLeadingBits(template)}LZB`,
    );
    void dispatchSearch(template);
  } else if (msg.type === "submit_result") {
    if (msg.ok) {
      set((s) => ({ blocksFound: s.blocksFound + 1 }));
      log("submit", `pool accepted — hash=${String(msg.hash).slice(0, 20)}…`);
    } else {
      log("error", `pool rejected: ${String(msg.error ?? "unknown")}`);
      set((s) => ({ staleCount: s.staleCount + 1 }));
    }
    rt.currentTemplateId = null;
  } else if (msg.type === "status") {
    set({ poolConnected: Boolean(msg.connected) });
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

// Re-export for components.
export { nonceToLeHex };
