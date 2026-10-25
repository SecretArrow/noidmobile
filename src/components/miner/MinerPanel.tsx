"use client";

import { useEffect, useState } from "react";
import { useMinerStore } from "@/store/miner-store";
import { useSettingsStore } from "@/store/settings-store";
import { rpcClient } from "@/lib/parano1d/rpc";
import {
  ChainInfo,
  formatNoid,
  MiningInfo,
  NodeStatus,
} from "@/lib/parano1d/types";
import { loadPowBackend } from "@/lib/parano1d/poseidon2b";
import { NeonButton } from "@/components/cyberpunk/NeonButton";
import { StatCard } from "@/components/cyberpunk/StatCard";
import { ScanlineBox } from "@/components/cyberpunk/ScanlineBox";
import { LogView } from "@/components/cyberpunk/LogView";
import { cn } from "@/lib/utils";
import {
  Activity,
  Cpu,
  Gauge,
  Hash,
  Play,
  Square,
  Zap,
} from "lucide-react";

function formatUptime(ms: number): string {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}h ${m}m ${sec}s`;
  if (m > 0) return `${m}m ${sec}s`;
  return `${sec}s`;
}

function formatHashrate(hps: number): string {
  if (hps >= 1e9) return `${(hps / 1e9).toFixed(2)} GH/s`;
  if (hps >= 1e6) return `${(hps / 1e6).toFixed(2)} MH/s`;
  if (hps >= 1e3) return `${(hps / 1e3).toFixed(2)} kH/s`;
  return `${hps.toFixed(1)} H/s`;
}

export function MinerPanel() {
  const miner = useMinerStore();
  const settings = useSettingsStore();
  const [chain, setChain] = useState<ChainInfo | null>(null);
  const [miningInfo, setMiningInfo] = useState<MiningInfo | null>(null);
  const [nodeStatus, setNodeStatus] = useState<NodeStatus | null>(null);
  const [nodeError, setNodeError] = useState<string | null>(null);
  const [wasmReady, setWasmReady] = useState<boolean | null>(null);

  // Probe node + WASM on mount and every 15s.
  useEffect(() => {
    let cancelled = false;
    async function probe() {
      const backend = await loadPowBackend();
      if (!cancelled) setWasmReady(backend !== null);
      try {
        const [c, m, n] = await Promise.all([
          rpcClient.getChainInfo(),
          rpcClient.getMiningInfo(),
          rpcClient.getNodeStatus(),
        ]);
        if (!cancelled) {
          setChain(c);
          setMiningInfo(m);
          setNodeStatus(n);
          setNodeError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setNodeError(err instanceof Error ? err.message : String(err));
        }
      }
    }
    void probe();
    const t = setInterval(probe, 15_000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [settings.rpcUrl, settings.bearerToken]);

  const isRunning = miner.status === "running" || miner.status === "starting";

  return (
    <div className="space-y-4">
      {/* Status row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <StatCard
          label="Status"
          value={
            <span
              className={cn(
                "uppercase text-sm",
                miner.status === "running" && "neon-text",
                miner.status === "error" && "text-[var(--destructive)]",
                (miner.status === "starting" || miner.status === "stopping") &&
                  "neon-text-amber",
              )}
            >
              {miner.status}
            </span>
          }
          accent={
            miner.status === "running"
              ? "neon"
              : miner.status === "error"
                ? "danger"
                : "amber"
          }
        >
          <div className="text-[10px] text-[var(--muted-foreground)] mt-1 font-mono">
            mode: {miner.mode} | pool: {miner.poolConnected ? "ON" : "OFF"}
          </div>
        </StatCard>
        <StatCard
          label="Hashrate"
          value={formatHashrate(miner.hashrate)}
          accent="neon"
        >
          <div className="text-[10px] text-[var(--muted-foreground)] mt-1 font-mono">
            {miner.hashesTried.toLocaleString()} hashes tried
          </div>
        </StatCard>
        <StatCard
          label="Blocks Found"
          value={miner.blocksFound}
          accent="magenta"
        >
          <div className="text-[10px] text-[var(--muted-foreground)] mt-1 font-mono">
            stale: {miner.staleCount}
          </div>
        </StatCard>
        <StatCard
          label="Uptime"
          value={formatUptime(miner.uptimeMs)}
          accent="cyan"
        >
          <div className="text-[10px] text-[var(--muted-foreground)] mt-1 font-mono">
            backend: {wasmReady === null ? "…" : wasmReady ? "WASM" : "MISSING"}
          </div>
        </StatCard>
      </div>

      {/* Control + node info */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <ScanlineBox title="// miner control" className="lg:col-span-1" accent="neon">
          <div className="p-4 space-y-3">
            <div className="flex gap-2">
              {!isRunning ? (
                <NeonButton
                  variant="neon"
                  onClick={() => void miner.start()}
                  disabled={miner.status === "starting"}
                  className="flex-1"
                >
                  <Play className="w-3 h-3 mr-2" /> START MINING
                </NeonButton>
              ) : (
                <NeonButton
                  variant="danger"
                  onClick={() => void miner.stop()}
                  disabled={miner.status === "stopping"}
                  className="flex-1"
                >
                  <Square className="w-3 h-3 mr-2" /> STOP
                </NeonButton>
              )}
            </div>
            <NeonButton
              variant="ghost"
              onClick={() => miner.clearLogs()}
              className="w-full"
            >
              CLEAR LOG
            </NeonButton>

            {wasmReady === false && (
              <div className="text-[11px] text-[var(--amber)] font-mono p-2 border border-[var(--amber)]/40 bg-[var(--amber)]/5">
                ⚠ PoW backend (WASM) not built. Run GitHub Actions
                `build-wasm.yml` — the artifact is committed to `/public/wasm/`
                automatically. Mining refuses to start without a real hash
                function (no simulation).
              </div>
            )}
            {nodeError && (
              <div className="text-[11px] text-[var(--destructive)] font-mono p-2 border border-[var(--destructive)]/40 bg-[var(--destructive)]/5">
                ⚠ Node RPC: {nodeError}
              </div>
            )}
          </div>
        </ScanlineBox>

        <ScanlineBox title="// node info" className="lg:col-span-2" accent="cyan">
          <div className="p-4 grid grid-cols-2 md:grid-cols-3 gap-3">
            <NodeStat
              icon={<Hash className="w-3 h-3" />}
              label="Tip Height"
              value={chain?.tip_height?.toLocaleString() ?? "—"}
            />
            <NodeStat
              icon={<Activity className="w-3 h-3" />}
              label="Difficulty"
              value={miningInfo ? `${miningInfo.difficulty_bits} bits` : "—"}
            />
            <NodeStat
              icon={<Zap className="w-3 h-3" />}
              label="Block Reward"
              value={
                miningInfo
                  ? `${formatNoid(miningInfo.next_block_subsidy_micronoid)} NOID`
                  : "—"
              }
            />
            <NodeStat
              icon={<Gauge className="w-3 h-3" />}
              label="Net Hashrate"
              value={
                miningInfo
                  ? formatHashrate(miningInfo.network_hashrate_estimate)
                  : "—"
              }
            />
            <NodeStat
              icon={<Cpu className="w-3 h-3" />}
              label="Node Synced"
              value={nodeStatus ? (nodeStatus.synced ? "YES" : "NO") : "—"}
            />
            <NodeStat
              icon={<Activity className="w-3 h-3" />}
              label="Peers"
              value={nodeStatus?.peer_count?.toString() ?? "—"}
            />
            <div className="col-span-2 md:col-span-3 text-[10px] text-[var(--muted-foreground)] font-mono truncate">
              RPC: {settings.rpcUrl} | auth:{" "}
              {settings.bearerToken ? "BEARER" : "none"} | coinbase:{" "}
              {settings.coinbaseAddress || "(node default)"}
            </div>
          </div>
        </ScanlineBox>
      </div>

      {/* Current template + hashrate chart */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <ScanlineBox
          title="// current template"
          className="lg:col-span-1"
          accent={miner.currentTemplate ? "magenta" : "neon"}
          scan={isRunning}
        >
          <div className="p-4 space-y-2 font-mono text-xs">
            {miner.currentTemplate ? (
              <>
                <TemplateRow
                  label="HEIGHT"
                  value={miner.currentTemplate.height.toLocaleString()}
                />
                <TemplateRow
                  label="TEMPLATE"
                  value={miner.currentTemplate.template_id.slice(0, 24) + "…"}
                />
                <TemplateRow
                  label="TXS"
                  value={miner.currentTemplate.n_txs.toString()}
                />
                <TemplateRow
                  label="EXPIRES"
                  value={`${miner.currentTemplate.expires_in_seconds}s`}
                />
                <TemplateRow
                  label="NONCE IDX"
                  value={miner.currentTemplate.nonce_field_index.toString()}
                />
                <div className="pt-2">
                  <div className="text-[10px] text-[var(--muted-foreground)]">
                    POW_FIELDS (256 hex)
                  </div>
                  <div className="text-[10px] text-[var(--neon)] break-all max-h-16 overflow-y-auto">
                    {miner.currentTemplate.pow_fields_hex}
                  </div>
                </div>
                <div className="pt-1">
                  <div className="text-[10px] text-[var(--muted-foreground)]">
                    TARGET (LE)
                  </div>
                  <div className="text-[10px] text-[var(--magenta)] break-all">
                    {miner.currentTemplate.difficulty_target_hex}
                  </div>
                </div>
              </>
            ) : (
              <div className="text-[var(--muted-foreground)] italic">
                {isRunning ? "fetching template…" : "miner idle"}
              </div>
            )}
          </div>
        </ScanlineBox>

        <ScanlineBox
          title="// hashrate (last 2 min)"
          className="lg:col-span-2"
          accent="neon"
        >
          <HashrateChart
            history={miner.hashrateHistory}
            current={miner.hashrate}
          />
        </ScanlineBox>
      </div>

      {/* Log */}
      <ScanlineBox title="// event log" accent="neon" className="h-72">
        <LogView
          logs={miner.logs}
          className="h-full p-1"
          emptyMessage="// start mining to populate event log"
        />
      </ScanlineBox>
    </div>
  );
}

function NodeStat({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div className="border-l border-[var(--neon)]/30 pl-2">
      <div className="text-[9px] uppercase tracking-[0.2em] text-[var(--muted-foreground)] flex items-center gap-1">
        {icon}
        {label}
      </div>
      <div className="text-sm font-mono text-[var(--neon)] neon-text">
        {value}
      </div>
    </div>
  );
}

function TemplateRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-2">
      <span className="text-[var(--muted-foreground)]">{label}</span>
      <span className="text-[var(--neon)] text-right truncate">{value}</span>
    </div>
  );
}

function HashrateChart({
  history,
  current,
}: {
  history: Array<{ ts: number; hps: number }>;
  current: number;
}) {
  const max = Math.max(1, ...history.map((h) => h.hps), current);
  const width = 600;
  const height = 100;
  const points =
    history.length > 1
      ? history
          .map((h, i) => {
            const x = (i / (history.length - 1)) * width;
            const y = height - (h.hps / max) * height;
            return `${x.toFixed(1)},${y.toFixed(1)}`;
          })
          .join(" ")
      : "";
  return (
    <div className="p-4">
      <div className="text-2xl font-mono font-bold neon-text mb-2">
        {formatHashrate(current)}
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="w-full h-24"
        preserveAspectRatio="none"
      >
        <defs>
          <linearGradient id="hr-grad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--neon)" stopOpacity="0.4" />
            <stop offset="100%" stopColor="var(--neon)" stopOpacity="0" />
          </linearGradient>
        </defs>
        {/* Grid */}
        {[0.25, 0.5, 0.75].map((p) => (
          <line
            key={p}
            x1="0"
            x2={width}
            y1={height * p}
            y2={height * p}
            stroke="var(--neon)"
            strokeOpacity="0.1"
            strokeWidth="1"
          />
        ))}
        {points && (
          <>
            <polyline
              points={`0,${height} ${points} ${width},${height}`}
              fill="url(#hr-grad)"
            />
            <polyline
              points={points}
              fill="none"
              stroke="var(--neon)"
              strokeWidth="1.5"
              style={{ filter: "drop-shadow(0 0 4px var(--neon))" }}
            />
          </>
        )}
      </svg>
    </div>
  );
}
