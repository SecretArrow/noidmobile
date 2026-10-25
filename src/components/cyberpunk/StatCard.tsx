"use client";

import { cn } from "@/lib/utils";
import { ReactNode } from "react";

interface StatCardProps {
  label: string;
  value: ReactNode;
  unit?: string;
  accent?: "neon" | "magenta" | "amber" | "cyan" | "danger";
  className?: string;
  children?: ReactNode;
}

const accentColor: Record<NonNullable<StatCardProps["accent"]>, string> = {
  neon: "var(--neon)",
  magenta: "var(--magenta)",
  amber: "var(--amber)",
  cyan: "var(--cyan)",
  danger: "var(--destructive)",
};

export function StatCard({
  label,
  value,
  unit,
  accent = "neon",
  className,
  children,
}: StatCardProps) {
  const color = accentColor[accent];
  return (
    <div
      className={cn(
        "cyber-panel-solid p-3 px-4 relative",
        className,
      )}
      style={{
        borderColor: `${color}40`,
        boxShadow: `inset 0 0 12px ${color}10, 0 0 8px ${color}20`,
      }}
    >
      <div
        className="text-[10px] uppercase tracking-[0.2em] mb-1 font-mono"
        style={{ color: `${color}cc` }}
      >
        {label}
      </div>
      <div className="flex items-baseline gap-1">
        <span
          className="font-mono text-2xl font-bold"
          style={{
            color,
            textShadow: `0 0 6px ${color}80`,
          }}
        >
          {value}
        </span>
        {unit && (
          <span
            className="text-xs font-mono"
            style={{ color: `${color}99` }}
          >
            {unit}
          </span>
        )}
      </div>
      {children}
    </div>
  );
}
