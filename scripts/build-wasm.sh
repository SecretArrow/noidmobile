#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (C) 2026 SecretArrow.
#
# Local helper that builds the WASM bindings for the web miner.
# CI calls `wasm-pack` directly via .github/workflows/build-wasm.yml —
# this script is for developer convenience only.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> Adding wasm32 target"
rustup target add wasm32-unknown-unknown

echo "==> Installing wasm-pack (if missing)"
cargo install wasm-pack --locked 2>/dev/null || true

echo "==> Building rust/noidmobile-pow (wasm32-unknown-unknown)"
cd rust/noidmobile-pow
wasm-pack build --target web --release

echo "==> Staging artifacts into web/wasm/"
mkdir -p ../../web/wasm
cp -r pkg/* ../../web/wasm/

echo "==> Done. WASM artifacts at web/wasm/"
ls -la ../../web/wasm/
