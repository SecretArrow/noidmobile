// SPDX-License-Identifier: Apache-2.0
// Wallet store: holds multiple 256-bit master spend secrets (encrypted at rest
// in localStorage), tracks the active wallet, exposes lock/unlock.
//
// The secrets NEVER leave the browser. The web wallet is a companion to a
// local `parano1d` node — the node holds the secret in its own keystore and
// performs the actual signing/proving. This store only manages backup/import
// of the entropy so the user can recover their wallet on a new node.

import { create } from "zustand";
import { persist } from "zustand/middleware";
import {
  decryptSecret,
  encryptSecret,
  generateMnemonicWallet,
  hexToSecret,
  KeystoreV1,
  mnemonicToSecret,
  secretToHex,
  secretToMnemonic,
  SECRET_BYTES,
} from "@/lib/parano1d/wallet";

export interface StoredWallet {
  /** Stable random ID. */
  id: string;
  /** Human label. */
  label: string;
  /** Encrypted secret (only decrypted when the wallet is unlocked). */
  keystore: KeystoreV1;
  /** Cached active address from the node (bech32m). Empty until the node RPC is queried. */
  cachedAddress: string;
  /** Creation timestamp. */
  createdAt: number;
}

export interface UnlockedSecret {
  walletId: string;
  secret: Uint8Array;
  mnemonic: string;
}

interface WalletState {
  wallets: StoredWallet[];
  activeWalletId: string | null;
  /** Decrypted secret for the active wallet — only in memory, never persisted. */
  unlocked: UnlockedSecret | null;

  // Actions
  createWallet: (label: string, password: string) => Promise<{ mnemonic: string; walletId: string }>;
  importFromMnemonic: (mnemonic: string, label: string, password: string) => Promise<string>;
  importFromHex: (hex: string, label: string, password: string) => Promise<string>;
  importKeystore: (keystore: KeystoreV1, label: string) => string;
  unlock: (walletId: string, password: string) => Promise<void>;
  lock: () => void;
  removeWallet: (walletId: string) => void;
  setActive: (walletId: string) => void;
  updateLabel: (walletId: string, label: string) => void;
  setCachedAddress: (walletId: string, address: string) => void;
  exportKeystore: (walletId: string) => KeystoreV1 | null;
  revealMnemonic: (walletId: string, password: string) => Promise<string>;
}

function randomId(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(8)))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export const useWalletStore = create<WalletState>()(
  persist(
    (set, get) => ({
      wallets: [],
      activeWalletId: null,
      unlocked: null,

      createWallet: async (label, password) => {
        if (password.length < 8) {
          throw new Error("Password must be at least 8 characters.");
        }
        const { mnemonic, secret } = generateMnemonicWallet();
        const keystore = await encryptSecret(secret, password);
        const id = randomId();
        const wallet: StoredWallet = {
          id,
          label: label || `wallet-${id.slice(0, 6)}`,
          keystore,
          cachedAddress: "",
          createdAt: Date.now(),
        };
        set((s) => ({
          wallets: [...s.wallets, wallet],
          activeWalletId: s.activeWalletId ?? id,
        }));
        // Auto-unlock so the user can immediately use it.
        const unlocked: UnlockedSecret = { walletId: id, secret, mnemonic };
        set({ unlocked });
        return { mnemonic, walletId: id };
      },

      importFromMnemonic: async (mnemonic, label, password) => {
        if (password.length < 8) {
          throw new Error("Password must be at least 8 characters.");
        }
        const secret = mnemonicToSecret(mnemonic);
        const keystore = await encryptSecret(secret, password);
        const id = randomId();
        const wallet: StoredWallet = {
          id,
          label: label || `imported-${id.slice(0, 6)}`,
          keystore,
          cachedAddress: "",
          createdAt: Date.now(),
        };
        set((s) => ({
          wallets: [...s.wallets, wallet],
          activeWalletId: s.activeWalletId ?? id,
        }));
        return id;
      },

      importFromHex: async (hex, label, password) => {
        if (password.length < 8) {
          throw new Error("Password must be at least 8 characters.");
        }
        const secret = hexToSecret(hex);
        if (secret.length !== SECRET_BYTES) {
          throw new Error("Secret must be 256 bits (32 bytes / 64 hex chars).");
        }
        const keystore = await encryptSecret(secret, password);
        const id = randomId();
        const wallet: StoredWallet = {
          id,
          label: label || `imported-${id.slice(0, 6)}`,
          keystore,
          cachedAddress: "",
          createdAt: Date.now(),
        };
        set((s) => ({
          wallets: [...s.wallets, wallet],
          activeWalletId: s.activeWalletId ?? id,
        }));
        return id;
      },

      importKeystore: (keystore, label) => {
        const id = randomId();
        const wallet: StoredWallet = {
          id,
          label: label || `keystore-${id.slice(0, 6)}`,
          keystore,
          cachedAddress: "",
          createdAt: Date.now(),
        };
        set((s) => ({
          wallets: [...s.wallets, wallet],
          activeWalletId: s.activeWalletId ?? id,
        }));
        return id;
      },

      unlock: async (walletId, password) => {
        const wallet = get().wallets.find((w) => w.id === walletId);
        if (!wallet) throw new Error("Wallet not found.");
        const secret = await decryptSecret(wallet.keystore, password);
        const mnemonic = secretToMnemonic(secret);
        set({
          unlocked: { walletId, secret, mnemonic },
          activeWalletId: walletId,
        });
      },

      lock: () => set({ unlocked: null }),

      removeWallet: (walletId) =>
        set((s) => {
          const wallets = s.wallets.filter((w) => w.id !== walletId);
          const activeWalletId =
            s.activeWalletId === walletId
              ? (wallets[0]?.id ?? null)
              : s.activeWalletId;
          const unlocked =
            s.unlocked?.walletId === walletId ? null : s.unlocked;
          return { wallets, activeWalletId, unlocked };
        }),

      setActive: (walletId) =>
        set((s) =>
          s.wallets.some((w) => w.id === walletId)
            ? { activeWalletId: walletId }
            : s,
        ),

      updateLabel: (walletId, label) =>
        set((s) => ({
          wallets: s.wallets.map((w) =>
            w.id === walletId ? { ...w, label } : w,
          ),
        })),

      setCachedAddress: (walletId, address) =>
        set((s) => ({
          wallets: s.wallets.map((w) =>
            w.id === walletId ? { ...w, cachedAddress: address } : w,
          ),
        })),

      exportKeystore: (walletId) => {
        const w = get().wallets.find((x) => x.id === walletId);
        return w ? w.keystore : null;
      },

      revealMnemonic: async (walletId, password) => {
        const wallet = get().wallets.find((w) => w.id === walletId);
        if (!wallet) throw new Error("Wallet not found.");
        const secret = await decryptSecret(wallet.keystore, password);
        return secretToMnemonic(secret);
      },
    }),
    {
      name: "noidmobile-wallets",
      version: 1,
      // Persist the encrypted keystores + metadata, but NEVER the unlocked secret.
      partialize: (s) => ({
        wallets: s.wallets,
        activeWalletId: s.activeWalletId,
      }),
    },
  ),
);
