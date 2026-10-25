"use client";

import { useEffect, useState } from "react";
import { MinerPanel } from "@/components/miner/MinerPanel";
import { WalletPanel } from "@/components/wallet/WalletPanel";
import { SettingsPanel } from "@/components/settings/SettingsPanel";
import { useSettingsStore } from "@/store/settings-store";
import { NeonButton } from "@/components/cyberpunk/NeonButton";
import { cn } from "@/lib/utils";
import {
  Cpu,
  Github,
  Settings,
  Terminal,
  Wallet,
  Zap,
} from "lucide-react";

type Tab = "miner" | "wallet" | "settings";

const TABS: Array<{ key: Tab; label: string; icon: React.ReactNode }> = [
  { key: "miner", label: "MINER", icon: <Cpu className="w-3 h-3" /> },
  { key: "wallet", label: "WALLET", icon: <Wallet className="w-3 h-3" /> },
  { key: "settings", label: "SETTINGS", icon: <Settings className="w-3 h-3" /> },
];

export default function Home() {
  const [tab, setTab] = useState<Tab>("miner");
  const [clock, setClock] = useState<string>("");
  const applySettings = useSettingsStore((s) => s.applyToRpcClient);

  // Apply persisted settings to the RPC client on first mount.
  useEffect(() => {
    applySettings();
  }, [applySettings]);

  // Live clock for the cyberpunk header.
  useEffect(() => {
    const tick = () => {
      const d = new Date();
      const hh = d.getUTCHours().toString().padStart(2, "0");
      const mm = d.getUTCMinutes().toString().padStart(2, "0");
      const ss = d.getUTCSeconds().toString().padStart(2, "0");
      setClock(`${hh}:${mm}:${ss} UTC`);
    };
    tick();
    const t = setInterval(tick, 1000);
    return () => clearInterval(t);
  }, []);

  return (
    <div className="min-h-screen flex flex-col">
      {/* Header */}
      <header className="border-b border-[var(--neon)]/30 bg-black/80 backdrop-blur-sm sticky top-0 z-40">
        <div className="px-3 md:px-6 py-2 flex items-center gap-3 md:gap-6">
          <div className="flex items-center gap-2">
            <div className="w-2 h-2 bg-[var(--neon)] rounded-full pulse-neon" />
            <h1
              className="font-mono font-bold text-sm md:text-base neon-text tracking-wider"
              data-text="NOID//MOBILE"
            >
              NOID<span className="text-[var(--magenta)]">{"//"}</span>MOBILE
            </h1>
          </div>
          <div className="hidden md:flex items-center gap-3 text-[10px] text-[var(--muted-foreground)] font-mono uppercase tracking-[0.2em]">
            <span className="flex items-center gap-1">
              <Terminal className="w-3 h-3" /> Parano1d L1
            </span>
            <span className="flex items-center gap-1">
              <Zap className="w-3 h-3" /> Poseidon2b PoW
            </span>
            <span>{clock}</span>
          </div>
          <div className="flex-1" />
          <a
            href="https://github.com/SecretArrow/noidmobile"
            target="_blank"
            rel="noreferrer"
            className="text-[var(--muted-foreground)] hover:text-[var(--neon)] transition-colors"
            aria-label="GitHub"
          >
            <Github className="w-4 h-4" />
          </a>
        </div>
        {/* Tabs */}
        <div className="px-3 md:px-6 flex gap-1 border-t border-[var(--neon)]/10">
          {TABS.map(({ key, label, icon }) => (
            <button
              key={key}
              onClick={() => setTab(key)}
              className={cn(
                "px-3 md:px-4 py-2 text-[11px] font-mono uppercase tracking-wider transition-all flex items-center gap-1 border-b-2",
                tab === key
                  ? "text-[var(--neon)] neon-text border-[var(--neon)]"
                  : "text-[var(--muted-foreground)] border-transparent hover:text-[var(--neon)]",
              )}
            >
              {icon}
              {label}
            </button>
          ))}
        </div>
      </header>

      {/* Main content */}
      <main className="flex-1 p-3 md:p-6 max-w-7xl mx-auto w-full">
        {tab === "miner" && <MinerPanel />}
        {tab === "wallet" && <WalletPanel />}
        {tab === "settings" && <SettingsPanel />}
      </main>

      {/* Footer */}
      <footer className="border-t border-[var(--neon)]/20 bg-black/60 px-3 md:px-6 py-2">
        <div className="flex flex-wrap items-center justify-between gap-2 text-[9px] md:text-[10px] font-mono text-[var(--muted-foreground)] uppercase tracking-[0.15em]">
          <div>
            {"NOID//MOBILE"} · Parano1d Miner + Wallet ·{" "}
            <span className="text-[var(--neon)]">REAL PoW</span> (no
            simulation)
          </div>
          <div className="flex gap-3">
            <span>1 NOID = 1,000,000 μNOID</span>
            <span>Block target: 30s</span>
            <span>HRP: o1</span>
          </div>
        </div>
      </footer>
    </div>
  );
}
