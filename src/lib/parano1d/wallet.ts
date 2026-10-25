// SPDX-License-Identifier: Apache-2.0
// Wallet utilities: 256-bit master spend secret + bech32m address handling.
//
// Parano1d wallets begin with 256 bits of secret entropy (NOT a keypair).
// Ownership is proven in zero-knowledge — no signature, no public key on the wire.
// Reference: parano1d.org section 06 "Signatureless ownership".
//
// The web wallet is a COMPANION to a local `parano1d` node:
//   - Generate / import / back up the 256-bit master secret (client-side)
//   - Display the secret as a 24-word BIP39 mnemonic for human backup
//   - The secret must be loaded into a local node (parano1d --wallet-import)
//     before the web wallet can query balance or send via RPC.
//
// We never transmit the secret over the network. The RPC wallet methods
// (walletSend, walletGetBalance, etc.) operate on the secret held by the
// user's local node, not in the browser.

import { bech32m } from "bech32";
import { generateMnemonic, mnemonicToEntropy, validateMnemonic, entropyToMnemonic } from "bip39";
import { NOID_HRP } from "./types";

// ---------------------------------------------------------------------------
// Entropy
// ---------------------------------------------------------------------------

export const SECRET_BYTES = 32; // 256 bits

/** Generate 32 bytes of cryptographically secure random entropy. */
export function generateSecret(crypto: Crypto = globalThis.crypto): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(SECRET_BYTES));
}

/** Convert 32 bytes to a 64-char lowercase hex string. */
export function secretToHex(secret: Uint8Array): string {
  if (secret.length !== SECRET_BYTES) {
    throw new Error(`secret must be ${SECRET_BYTES} bytes, got ${secret.length}`);
  }
  return Array.from(secret)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** Convert a 64-char hex string back to 32 bytes. */
export function hexToSecret(hex: string): Uint8Array {
  if (!/^[0-9a-fA-F]{64}$/.test(hex)) {
    throw new Error("secret hex must be 64 lowercase hex chars");
  }
  const out = new Uint8Array(SECRET_BYTES);
  for (let i = 0; i < SECRET_BYTES; i++) {
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

// ---------------------------------------------------------------------------
// Mnemonic (BIP39, 24 words, 256 bits of entropy)
// ---------------------------------------------------------------------------

/** Generate a fresh 24-word BIP39 mnemonic + the underlying 256-bit secret. */
export function generateMnemonicWallet(): { mnemonic: string; secret: Uint8Array } {
  const mnemonic = generateMnemonic(256); // 24 words
  const entropyHex = mnemonicToEntropy(mnemonic); // 64 hex chars
  const secret = hexToSecret(entropyHex);
  return { mnemonic, secret };
}

/** Recover a 256-bit secret from a 24-word BIP39 mnemonic. */
export function mnemonicToSecret(mnemonic: string): Uint8Array {
  const trimmed = mnemonic.trim().toLowerCase();
  if (!validateMnemonic(trimmed)) {
    throw new Error("Invalid BIP39 mnemonic — check the words and order.");
  }
  const entropyHex = mnemonicToEntropy(trimmed);
  return hexToSecret(entropyHex);
}

/** Render a 256-bit secret as a 24-word BIP39 mnemonic (for backup display). */
export function secretToMnemonic(secret: Uint8Array): string {
  if (secret.length !== SECRET_BYTES) {
    throw new Error(`secret must be ${SECRET_BYTES} bytes`);
  }
  const entropyHex = secretToHex(secret);
  return entropyToMnemonic(entropyHex);
}

// ---------------------------------------------------------------------------
// Photo Key — derive 256 bits from an image client-side (never uploaded).
// Reference: parano1d.org section 07 "Photo-derived key".
//
// We hash the raw decoded pixels (RGBA) through SHA-256. The same image yields
// the same secret; change a pixel and you get a different wallet. The image is
// processed entirely in the browser — no network upload.
// ---------------------------------------------------------------------------

export async function deriveSecretFromImage(
  file: File,
): Promise<{ secret: Uint8Array; width: number; height: number }> {
  const bitmap = await createImageBitmap(file);
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) throw new Error("Canvas 2D context unavailable in this browser.");
  ctx.drawImage(bitmap, 0, 0);
  const imageData = ctx.getImageData(0, 0, bitmap.width, bitmap.height);
  // Hash the raw RGBA bytes + dimensions for domain separation.
  const dimHeader = new TextEncoder().encode(
    `parano1d-photo-key|w=${bitmap.width}|h=${bitmap.height}|`,
  );
  const hashBuffer = await crypto.subtle.digest("SHA-256", new Uint8Array([
    ...dimHeader,
    ...imageData.data,
  ]));
  return {
    secret: new Uint8Array(hashBuffer),
    width: bitmap.width,
    height: bitmap.height,
  };
}

// ---------------------------------------------------------------------------
// Bech32m address helpers (display + validation only — derivation needs Poseidon2b,
// which the local node performs. The web wallet never derives addresses itself.)
// ---------------------------------------------------------------------------

/** Validate a Parano1d address (bech32m, HRP "o1"). */
export function isValidAddress(address: string): boolean {
  try {
    const decoded = bech32m.decode(address);
    if (decoded.prefix !== NOID_HRP) return false;
    if (decoded.words.length === 0) return false;
    return true;
  } catch {
    return false;
  }
}

/** Encode bytes as a bech32m address with HRP "o1" (used when the node returns raw bytes; usually the node returns the string directly). */
export function encodeAddressFromBytes(bytes: Uint8Array): string {
  // Convert bytes → 5-bit groups per BIP173.
  const words: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      words.push((buffer >> (bits - 5)) & 0x1f);
      bits -= 5;
    }
  }
  if (bits > 0) words.push((buffer << (5 - bits)) & 0x1f);
  return bech32m.encode(NOID_HRP, words);
}

// ---------------------------------------------------------------------------
// Encrypted keystore (JSON) — for export/import of wallet secrets.
// Uses Web Crypto AES-GCM with a password-derived key (PBKDF2, 600k iterations).
// ---------------------------------------------------------------------------

export interface KeystoreV1 {
  version: 1;
  cipher: "aes-256-gcm";
  kdf: "pbkdf2-sha256";
  kdf_params: { iterations: number; salt_hex: string };
  ciphertext_hex: string;
  iv_hex: string;
  created_at: number;
}

export async function encryptSecret(
  secret: Uint8Array,
  password: string,
): Promise<KeystoreV1> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const iterations = 600_000;
  const baseKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  const aesKey = await crypto.subtle.deriveKey(
    { name: "PBKDF2", salt, iterations, hash: "SHA-256" },
    baseKey,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt"],
  );
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    aesKey,
    secret,
  );
  return {
    version: 1,
    cipher: "aes-256-gcm",
    kdf: "pbkdf2-sha256",
    kdf_params: { iterations, salt_hex: bytesToHex(salt) },
    ciphertext_hex: bytesToHex(new Uint8Array(ciphertext)),
    iv_hex: bytesToHex(iv),
    created_at: Date.now(),
  };
}

export async function decryptSecret(
  keystore: KeystoreV1,
  password: string,
): Promise<Uint8Array> {
  if (keystore.version !== 1 || keystore.cipher !== "aes-256-gcm") {
    throw new Error("Unsupported keystore version");
  }
  const salt = hexToBytes(keystore.kdf_params.salt_hex);
  const iv = hexToBytes(keystore.iv_hex);
  const baseKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  const aesKey = await crypto.subtle.deriveKey(
    {
      name: "PBKDF2",
      salt,
      iterations: keystore.kdf_params.iterations,
      hash: "SHA-256",
    },
    baseKey,
    { name: "AES-GCM", length: 256 },
    false,
    ["decrypt"],
  );
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv },
    aesKey,
    hexToBytes(keystore.ciphertext_hex),
  );
  return new Uint8Array(plaintext);
}

// ---------------------------------------------------------------------------
// Small byte/hex helpers
// ---------------------------------------------------------------------------

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export function hexToBytes(hex: string): Uint8Array {
  if (!/^[0-9a-fA-F]*$/.test(hex) || hex.length % 2 !== 0) {
    throw new Error("invalid hex");
  }
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}
