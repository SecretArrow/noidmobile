"use client";

import { useEffect, useState } from "react";
import { useSettingsStore } from "@/store/settings-store";
import { rpcClient } from "@/lib/parano1d/rpc";
import { NeonButton } from "@/components/cyberpunk/NeonButton";
import { NeonInput } from "@/components/cyberpunk/NeonInput";
import { ScanlineBox } from "@/components/cyberpunk/ScanlineBox";
import { useToast } from "@/hooks/use-toast";
import {
  Activity,
  Cpu,
  Database,
  Network,
  Save,
  TestTube,
  Volume2,
  VolumeX,
} from "lucide-react";

export function SettingsPanel() {
  const s = useSettingsStore();
  const { toast } = useToast();
  const [rpcUrl, setRpcUrl] = useState(s.rpcUrl);
  const [token, setToken] = useState(s.bearerToken);
  const [coinbase, setCoinbase] = useState(s.coinbaseAddress);
  const [bridgeUrl, setBridgeUrl] = useState(s.stratumBridgeUrl);
  const [workers, setWorkers] = useState(s.workerCount.toString());
  const [pollMs, setPollMs] = useState(s.pollMs.toString());
  const [timeout, setTimeout_] = useState(s.rpcTimeoutMs.toString());
  const [testResult, setTestResult] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);

  // Sync local state when the store changes externally.
  useEffect(() => {
    setRpcUrl(s.rpcUrl);
    setToken(s.bearerToken);
    setCoinbase(s.coinbaseAddress);
    setBridgeUrl(s.stratumBridgeUrl);
    setWorkers(s.workerCount.toString());
    setPollMs(s.pollMs.toString());
    setTimeout_(s.rpcTimeoutMs.toString());
  }, [s.rpcUrl, s.bearerToken, s.coinbaseAddress, s.stratumBridgeUrl, s.workerCount, s.pollMs, s.rpcTimeoutMs]);

  function save() {
    s.setRpcUrl(rpcUrl);
    s.setBearerToken(token);
    s.setCoinbaseAddress(coinbase);
    s.setStratumBridgeUrl(bridgeUrl);
    s.setWorkerCount(parseInt(workers || "0", 10) || 0);
    s.setPollMs(parseInt(pollMs || "500", 10) || 500);
    s.setRpcTimeoutMs(parseInt(timeout || "30000", 10) || 30000);
    s.applyToRpcClient();
    toast({ title: "Settings saved" });
  }

  async function testConnection() {
    setTesting(true);
    setTestResult(null);
    try {
      rpcClient.configure({
        url: rpcUrl,
        bearerToken: token || undefined,
        timeoutMs: 5000,
      });
      const status = await rpcClient.getNodeStatus();
      setTestResult(
        `OK — synced=${status.synced} peers=${status.peer_count} backend=${status.cpu_backend}`,
      );
      toast({ title: "Connection OK", description: `${status.peers} peers` });
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setTestResult(`FAIL — ${msg}`);
      toast({
        title: "Connection failed",
        description: msg,
        variant: "destructive",
      });
    } finally {
      setTesting(false);
      // Restore the saved timeout.
      rpcClient.configure({ timeoutMs: parseInt(timeout || "30000", 10) });
    }
  }

  return (
    <div className="space-y-4">
      <ScanlineBox title="// node RPC (solo mode)" accent="neon">
        <div className="p-4 space-y-3 max-w-2xl">
          <div className="text-xs text-[var(--muted-foreground)] font-mono">
            Connect directly to a local Parano1d node. Default:{" "}
            <code className="text-[var(--neon)]">http://127.0.0.1:9601</code>.
            Solo mining does NOT require a bearer token when the node is bound
            to loopback.
          </div>
          <Field label="RPC URL" icon={<Network className="w-3 h-3" />}>
            <NeonInput
              value={rpcUrl}
              onChange={(e) => setRpcUrl(e.target.value)}
              placeholder="http://127.0.0.1:9601"
            />
          </Field>
          <Field
            label="Bearer Token (mining key — leave empty for solo localhost)"
            icon={<Activity className="w-3 h-3" />}
          >
            <NeonInput
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="(empty = no auth)"
            />
          </Field>
          <Field
            label="Custom Coinbase Address (optional — node must allow)"
            icon={<Database className="w-3 h-3" />}
          >
            <NeonInput
              value={coinbase}
              onChange={(e) => setCoinbase(e.target.value)}
              placeholder="o1..."
            />
          </Field>
          <Field label="RPC Timeout (ms)" icon={<Cpu className="w-3 h-3" />}>
            <NeonInput
              value={timeout}
              onChange={(e) => setTimeout_(e.target.value)}
            />
          </Field>
          <div className="flex gap-2">
            <NeonButton variant="neon" onClick={() => void testConnection()} disabled={testing}>
              <TestTube className="w-3 h-3 mr-2" />
              {testing ? "TESTING…" : "TEST CONNECTION"}
            </NeonButton>
          </div>
          {testResult && (
            <div className="text-xs font-mono p-2 border border-[var(--neon)]/30 bg-[var(--neon)]/5 text-[var(--neon)]">
              {testResult}
            </div>
          )}
        </div>
      </ScanlineBox>

      <ScanlineBox title="// stratum V2 pool mode" accent="magenta">
        <div className="p-4 space-y-3 max-w-2xl">
          <div className="text-xs text-[var(--muted-foreground)] font-mono">
            Connect via the local Stratum V2 bridge mini-service (in{" "}
            <code className="text-[var(--magenta)]">/stratum</code>). The
            bridge handles the Noise-encrypted TCP connection to the pool so
            the browser (which can&apos;t do raw TCP) can still mine against a
            real Stratum V2 pool like peakpool.
          </div>
          <Field label="Bridge WebSocket URL" icon={<Network className="w-3 h-3" />}>
            <NeonInput
              value={bridgeUrl}
              onChange={(e) => setBridgeUrl(e.target.value)}
              accent="magenta"
              placeholder="ws://localhost:3030"
            />
          </Field>
          <div className="text-[10px] text-[var(--muted-foreground)] font-mono">
            Start the bridge:{" "}
            <code className="text-[var(--magenta)]">cd stratum &amp;&amp; cp .env.example .env &amp;&amp; bun run src/index.ts</code>
          </div>
        </div>
      </ScanlineBox>

      <ScanlineBox title="// miner runtime" accent="cyan">
        <div className="p-4 space-y-3 max-w-2xl">
          <div className="text-xs text-[var(--muted-foreground)] font-mono">
            Hashing happens in web workers (one per logical core by default).
            More workers = more CPU but each worker must load its own copy of
            the WASM backend.
          </div>
          <Field label="Worker Count (0 = auto)" icon={<Cpu className="w-3 h-3" />}>
            <NeonInput
              value={workers}
              onChange={(e) => setWorkers(e.target.value)}
              accent="cyan"
            />
          </Field>
          <Field label="Template Poll Interval (ms)" icon={<Activity className="w-3 h-3" />}>
            <NeonInput
              value={pollMs}
              onChange={(e) => setPollMs(e.target.value)}
              accent="cyan"
            />
          </Field>
          <Field label="Sound Effects">
            <NeonButton
              variant={s.soundEnabled ? "neon" : "ghost"}
              onClick={() => s.setSoundEnabled(!s.soundEnabled)}
            >
              {s.soundEnabled ? (
                <>
                  <Volume2 className="w-3 h-3 mr-1" /> ON
                </>
              ) : (
                <>
                  <VolumeX className="w-3 h-3 mr-1" /> OFF
                </>
              )}
            </NeonButton>
          </Field>
        </div>
      </ScanlineBox>

      <div className="flex gap-2">
        <NeonButton variant="neon" onClick={save}>
          <Save className="w-3 h-3 mr-2" /> SAVE SETTINGS
        </NeonButton>
      </div>

      <ScanlineBox title="// about" accent="amber">
        <div className="p-4 text-xs font-mono text-[var(--muted-foreground)] space-y-2 max-w-2xl">
          <div>
            <span className="text-[var(--neon)]">NOID//MOBILE</span> — Parano1d
            (NOID) web + Android miner and wallet.
          </div>
          <div>
            Real PoW via Poseidon2b (WASM, built from the upstream{" "}
            <code className="text-[var(--neon)]">noid_poseidon2b</code> Rust
            crate). No simulation, no fake hashing — the miner refuses to start
            until the WASM backend is loaded.
          </div>
          <div>
            Wallet uses 256-bit master spend entropy (no keypair, no signature
            — Parano1d proves ownership in zero-knowledge). The secret is
            encrypted at rest with AES-256-GCM (PBKDF2 600k iterations).
          </div>
          <div>
            Source:{" "}
            <a
              href="https://github.com/SecretArrow/noidmobile"
              className="text-[var(--cyan)] hover:underline"
              target="_blank"
              rel="noreferrer"
            >
              github.com/SecretArrow/noidmobile
            </a>
          </div>
        </div>
      </ScanlineBox>
    </div>
  );
}

function Field({
  label,
  icon,
  children,
}: {
  label: string;
  icon?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="text-[10px] uppercase tracking-[0.2em] text-[var(--muted-foreground)] flex items-center gap-1 mb-1">
        {icon}
        {label}
      </label>
      {children}
    </div>
  );
}
