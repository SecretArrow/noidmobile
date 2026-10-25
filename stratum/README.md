# noidmobile — Stratum V2 Noise bridge

A Node.js/TypeScript mini-service that bridges browser WebSocket ↔
Stratum V2 (Noise-over-TCP) pool connections.

Browsers cannot do raw TCP or the Noise handshake. This bridge:

1. Accepts WebSocket connections from the web/android miner.
2. For each connection, opens a Stratum V2 Noise connection to the
   configured pool.
3. Translates messages bidirectionally.
4. Handles the Noise XX handshake using `@noble/hashes` +
   `@noble/ciphers`.

The bridge does **NOT** terminate mining — it only forwards template /
submit messages. The miner (browser/android) still does the actual
Poseidon2b hashing.

## Run

```bash
cd stratum
cp .env.example .env
# edit .env — set POOL_HOST, POOL_PORT, POOL_PUBKEY
bun run dev               # or: npm start
```

The bridge listens on `ws://0.0.0.0:3030/`.

## Connect from the browser / Android

The bridge runs as a Next.js mini-service on the same host (port 3030),
reachable through the gateway with `?XTransformPort=3030`:

```typescript
const ws = new WebSocket('/?XTransformPort=3030');
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.type === 'template') { /* mine it */ }
  if (msg.type === 'submit_result') { /* check ok */ }
};
ws.send(JSON.stringify({
  type: 'submit',
  template_id: '...',
  nonce_hex: '...',
}));
```

## Wire protocol

Bridge → Miner (JSON, newline-delimited):

```jsonc
{ "type": "template",         "template_id": "...", "pow_fields_hex": "...",
  "nonce_field_index": 10,    "difficulty_target_hex": "...",
  "height": 12345,            "expires_in_seconds": 30, "n_txs": 3 }
{ "type": "submit_result",    "ok": true, "hash": "..." }
{ "type": "submit_result",    "ok": false, "error": "stale template" }
{ "type": "status",           "connected": true, "pool": "peakpool:8443" }
{ "type": "log",              "level": "info", "message": "..." }
```

Miner → Bridge:

```jsonc
{ "type": "submit", "template_id": "...", "nonce_hex": "..." }
```

The miner never sends secrets over this socket — the bridge
authenticates with the pool out-of-band via its own static Noise key.

## Configuration (.env)

| Variable           | Description                                                  | Default |
| ------------------ | ------------------------------------------------------------ | ------- |
| `POOL_HOST`        | Stratum V2 pool hostname / IP                                | (none)  |
| `POOL_PORT`        | Stratum V2 pool TCP port                                     | 8443    |
| `POOL_PUBKEY`      | Pool's static X25519 public key (32 bytes, hex)              | (none)  |
| `BRIDGE_PORT`      | Port this service listens on for WebSocket connections       | 3030    |
| `BRIDGE_STATIC_KEY`| Our static X25519 private key (32 bytes, hex) — optional     | (ephem) |
| `LOG_LEVEL`        | error / warn / info / debug                                  | info    |

## Security notes

- The bridge holds NO wallet secrets. It only forwards framed messages.
- The Noise XX handshake authenticates the pool via `POOL_PUBKEY`.
- If `BRIDGE_STATIC_KEY` is unset, an ephemeral key is generated per
  process — the pool must accept unknown clients.
- This is a v0.1 Noise implementation. Pool operators should pin to a
  reviewed release before relying on it for production. The full
  Stratum V2 protobuf translation is also out of scope for v0.1 and
  will land in a follow-up patch.
