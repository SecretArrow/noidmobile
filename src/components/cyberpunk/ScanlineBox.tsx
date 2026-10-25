"use client";

import { cn } from "@/lib/utils";
import { ReactNode } from "react";

interface ScanlineBoxProps {
  children: ReactNode;
  className?: string;
  accent?: "neon" | "magenta" | "amber" | "danger";
  title?: string;
  scan?: boolean;
}

const accentBorder: Record<NonNullable<ScanlineBoxProps["accent"]>, string> = {
  neon: "rgba(0, 255, 156, 0.3)",
  magenta: "rgba(255, 0, 255, 0.3)",
  amber: "rgba(255, 176, 0, 0.3)",
  danger: "rgba(255, 51, 68, 0.3)",
};

const accentGlow: Record<NonNullable<ScanlineBoxProps["accent"]>, string> = {
  neon: "rgba(0, 255, 156, 0.05)",
  magenta: "rgba(255, 0, 255, 0.05)",
  amber: "rgba(255, 176, 0, 0.05)",
  danger: "rgba(255, 51, 68, 0.05)",
};

export function ScanlineBox({
  children,
  className,
  accent = "neon",
  title,
  scan = false,
}: ScanlineBoxProps) {
  return (
    <div
      className={cn(
        "relative bg-[#050805] backdrop-blur-sm",
        className,
      )}
      style={{
        border: `1px solid ${accentBorder[accent]}`,
        boxShadow: `inset 0 0 16px ${accentGlow[accent]}`,
      }}
    >
      {title && (
        <div
          className="px-3 py-1.5 text-[10px] uppercase tracking-[0.25em] font-mono border-b"
          style={{
            color: accent === "neon" ? "var(--neon)" : accent === "magenta" ? "var(--magenta)" : accent === "amber" ? "var(--amber)" : "var(--destructive)",
            borderColor: accentBorder[accent],
            background: accentGlow[accent],
          }}
        >
          {title}
        </div>
      )}
      <div className={cn(scan && "scan-bar", "relative")}>{children}</div>
    </div>
  );
}
