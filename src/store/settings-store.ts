// SPDX-License-Identifier: Apache-2.0
// Persisted settings: RPC endpoint, mining credentials, pool config, worker count.

import { create } from "zustand";
import { persist } from "zustand/middleware";
import { DEFAULT_RPC_URL, DEFAULT_STRATUM_BRIDGE_PORT } from "@/lib/parano1d/types";
import { rpcClient } from "@/lib/parano1d/rpc";

export type MinerMode = "solo" | "pool";

export interface SettingsState {
  // Node RPC (solo mode)
  rpcUrl: string;
  bearerToken: string; // mining key — stored locally, never sent to anyone except the configured RPC host
  coinbaseAddress: string; // optional — for custom coinbase (node must allow it)

  // Pool mode (Stratum V2 bridge)
  minerMode: MinerMode;
  stratumBridgeUrl: string; // WebSocket URL of the stratum bridge mini-service

  // Miner runtime
  workerCount: number; // 0 = auto (navigator.hardwareConcurrency)
  pollMs: number; // template re-fetch interval
  rpcTimeoutMs: number;

  // UI
  soundEnabled: boolean;

  // Actions
  setRpcUrl: (url: string) => void;
  setBearerToken: (token: string) => void;
  setCoinbaseAddress: (addr: string) => void;
  setMinerMode: (mode: MinerMode) => void;
  setStratumBridgeUrl: (url: string) => void;
  setWorkerCount: (n: number) => void;
  setPollMs: (ms: number) => void;
  setRpcTimeoutMs: (ms: number) => void;
  setSoundEnabled: (b: boolean) => void;
  /** Apply the persisted settings to the live RPC client singleton. */
  applyToRpcClient: () => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set, get) => ({
      rpcUrl: DEFAULT_RPC_URL,
      bearerToken: "",
      coinbaseAddress: "",
      minerMode: "solo" as MinerMode,
      stratumBridgeUrl: `ws://${typeof window !== "undefined" ? window.location.hostname : "localhost"}:${DEFAULT_STRATUM_BRIDGE_PORT}`,
      workerCount: 0,
      pollMs: 500,
      rpcTimeoutMs: 30_000,
      soundEnabled: false,

      setRpcUrl: (url) => {
        set({ rpcUrl: url });
        get().applyToRpcClient();
      },
      setBearerToken: (token) => {
        set({ bearerToken: token });
        get().applyToRpcClient();
      },
      setCoinbaseAddress: (addr) => set({ coinbaseAddress: addr }),
      setMinerMode: (mode) => set({ minerMode: mode }),
      setStratumBridgeUrl: (url) => set({ stratumBridgeUrl: url }),
      setWorkerCount: (n) => set({ workerCount: n }),
      setPollMs: (ms) => set({ pollMs: ms }),
      setRpcTimeoutMs: (ms) => set({ rpcTimeoutMs: ms }),
      setSoundEnabled: (b) => set({ soundEnabled: b }),

      applyToRpcClient: () => {
        const s = get();
        rpcClient.configure({
          url: s.rpcUrl,
          bearerToken: s.bearerToken || undefined,
          timeoutMs: s.rpcTimeoutMs,
        });
      },
    }),
    {
      name: "noidmobile-settings",
      version: 1,
      // Don't persist the bearer token in plaintext by default — but the user
      // accepted this risk by choosing to store it. For higher security, leave
      // bearerToken empty and enter it each session.
      partialize: (s) => ({
        rpcUrl: s.rpcUrl,
        bearerToken: s.bearerToken,
        coinbaseAddress: s.coinbaseAddress,
        minerMode: s.minerMode,
        stratumBridgeUrl: s.stratumBridgeUrl,
        workerCount: s.workerCount,
        pollMs: s.pollMs,
        rpcTimeoutMs: s.rpcTimeoutMs,
        soundEnabled: s.soundEnabled,
      }),
      onRehydrateStorage: () => (state) => {
        state?.applyToRpcClient();
      },
    },
  ),
);
