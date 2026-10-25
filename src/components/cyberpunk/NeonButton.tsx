"use client";

import { Button, ButtonProps } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { forwardRef } from "react";

type Variant = "neon" | "magenta" | "amber" | "danger" | "ghost";

export interface NeonButtonProps extends ButtonProps {
  variant?: Variant;
  glow?: boolean;
  scan?: boolean;
}

const variantClass: Record<Variant, string> = {
  neon:
    "bg-transparent text-[var(--neon)] border border-[var(--neon)] hover:bg-[var(--neon)] hover:text-black",
  magenta:
    "bg-transparent text-[var(--magenta)] border border-[var(--magenta)] hover:bg-[var(--magenta)] hover:text-black",
  amber:
    "bg-transparent text-[var(--amber)] border border-[var(--amber)] hover:bg-[var(--amber)] hover:text-black",
  danger:
    "bg-transparent text-[var(--destructive)] border border-[var(--destructive)] hover:bg-[var(--destructive)] hover:text-black",
  ghost:
    "bg-transparent text-[var(--muted-foreground)] border border-transparent hover:text-[var(--neon)] hover:border-[var(--neon)]",
};

const glowClass: Record<Variant, string> = {
  neon: "shadow-[0_0_8px_rgba(0,255,156,0.4),inset_0_0_8px_rgba(0,255,156,0.1)] hover:shadow-[0_0_16px_rgba(0,255,156,0.7),inset_0_0_12px_rgba(0,255,156,0.2)]",
  magenta:
    "shadow-[0_0_8px_rgba(255,0,255,0.4),inset_0_0_8px_rgba(255,0,255,0.1)] hover:shadow-[0_0_16px_rgba(255,0,255,0.7)]",
  amber:
    "shadow-[0_0_8px_rgba(255,176,0,0.4)] hover:shadow-[0_0_16px_rgba(255,176,0,0.7)]",
  danger:
    "shadow-[0_0_8px_rgba(255,51,68,0.4)] hover:shadow-[0_0_16px_rgba(255,51,68,0.7)]",
  ghost: "",
};

export const NeonButton = forwardRef<HTMLButtonElement, NeonButtonProps>(
  ({ variant = "neon", glow = true, scan = false, className, ...props }, ref) => {
    return (
      <Button
        ref={ref}
        className={cn(
          "font-mono uppercase tracking-wider text-xs px-4 py-2 transition-all duration-150 rounded-none active:translate-y-px",
          variantClass[variant],
          glow && glowClass[variant],
          scan && "scan-bar relative overflow-hidden",
          className,
        )}
        {...props}
      />
    );
  },
);
NeonButton.displayName = "NeonButton";
