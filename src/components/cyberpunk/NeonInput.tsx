"use client";

import { Input, InputProps } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { forwardRef } from "react";

export interface NeonInputProps extends InputProps {
  accent?: "neon" | "magenta" | "amber";
}

export const NeonInput = forwardRef<HTMLInputElement, NeonInputProps>(
  ({ className, accent = "neon", ...props }, ref) => {
    const color =
      accent === "neon"
        ? "var(--neon)"
        : accent === "magenta"
          ? "var(--magenta)"
          : "var(--amber)";
    return (
      <Input
        ref={ref}
        className={cn(
          "bg-black/60 font-mono text-sm rounded-none",
          className,
        )}
        style={{
          color: "var(--foreground)",
          borderColor: `${color}50`,
          boxShadow: `inset 0 0 8px ${color}15`,
        }}
        onFocus={(e) => {
          e.target.style.borderColor = color;
          e.target.style.boxShadow = `0 0 8px ${color}50, inset 0 0 8px ${color}20`;
        }}
        onBlur={(e) => {
          e.target.style.borderColor = `${color}50`;
          e.target.style.boxShadow = `inset 0 0 8px ${color}15`;
        }}
        {...props}
      />
    );
  },
);
NeonInput.displayName = "NeonInput";
