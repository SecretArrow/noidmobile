"use client";

import { cn } from "@/lib/utils";
import { useEffect, useRef } from "react";
import { MinerLogEntry } from "@/store/miner-store";

const levelColor: Record<MinerLogEntry["level"], string> = {
  info: "var(--neon)",
  warn: "var(--amber)",
  error: "var(--destructive)",
  solve: "var(--magenta)",
  stale: "var(--muted-foreground)",
  submit: "var(--cyan)",
};

const levelTag: Record<MinerLogEntry["level"], string> = {
  info: "INFO ",
  warn: "WARN ",
  error: "ERR  ",
  solve: "SOLVE",
  stale: "STALE",
  submit: "OK  ",
};

interface LogViewProps {
  logs: MinerLogEntry[];
  className?: string;
  emptyMessage?: string;
}

export function LogView({
  logs,
  className,
  emptyMessage = "// no log entries",
}: LogViewProps) {
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // Snap to top (logs are newest-first).
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
  }, [logs]);

  return (
    <div
      ref={scrollRef}
      className={cn(
        "font-mono text-xs leading-relaxed overflow-y-auto",
        className,
      )}
    >
      {logs.length === 0 ? (
        <div className="text-[var(--muted-foreground)] italic px-2 py-1">
          {emptyMessage}
        </div>
      ) : (
        logs.map((entry) => (
          <div key={entry.id} className="px-2 py-0.5 hover:bg-[var(--neon)]/5">
            <span className="text-[var(--muted-foreground)]">
              {new Date(entry.ts).toISOString().slice(11, 19)}{" "}
            </span>
            <span style={{ color: levelColor[entry.level] }}>
              [{levelTag[entry.level]}]
            </span>{" "}
            <span className="text-[var(--foreground)]">{entry.message}</span>
          </div>
        ))
      )}
    </div>
  );
}
