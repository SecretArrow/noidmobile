"use client";

import { useEffect, useState } from "react";
import { useWalletStore } from "@/store/wallet-store";
import { useSettingsStore } from "@/store/settings-store";
import { rpcClient } from "@/lib/parano1d/rpc";
import {
  formatNoid,
  parseNoid,
  WalletAddressInfo,
  WalletBalance,
  WalletHistoryEntry,
  WalletSendPlan,
  WalletSendResult,
  WalletStatus,
  WalletUtxoInfo,
} from "@/lib/parano1d/types";
import {
  isValidAddress,
} from "@/lib/parano1d/wallet";
import { NeonButton } from "@/components/cyberpunk/NeonButton";
import { NeonInput } from "@/components/cyberpunk/NeonInput";
import { ScanlineBox } from "@/components/cyberpunk/ScanlineBox";
import { StatCard } from "@/components/cyberpunk/StatCard";
import { useToast } from "@/hooks/use-toast";
import {
  AlertCircle,
  ArrowDownToLine,
  ArrowUpFromLine,
  Copy,
  Eye,
  EyeOff,
  Image as ImageIcon,
  KeyRound,
  Lock,
  Plus,
  RefreshCw,
  Shield,
  Trash2,
  Unlock,
  Wallet,
} from "lucide-react";
import QRCode from "qrcode";
import { cn } from "@/lib/utils";

type Subview = "overview" | "send" | "history" | "addresses" | "backup";

export function WalletPanel() {
  const wallet = useWalletStore();
  const settings = useSettingsStore();
  const { toast } = useToast();
  const [subview, setSubview] = useState<Subview>("overview");

  // New wallet dialog state
  const [showCreate, setShowCreate] = useState(false);
  const [createLabel, setCreateLabel] = useState("");
  const [createPassword, setCreatePassword] = useState("");
  const [createdMnemonic, setCreatedMnemonic] = useState<string | null>(null);

  // Import dialog
  const [showImport, setShowImport] = useState(false);
  const [importType, setImportType] = useState<"mnemonic" | "hex" | "photo">("mnemonic");
  const [importValue, setImportValue] = useState("");
  const [importLabel, setImportLabel] = useState("");
  const [importPassword, setImportPassword] = useState("");
  const [photoPreview, setPhotoPreview] = useState<string | null>(null);

  // Unlock dialog
  const [unlockPassword, setUnlockPassword] = useState("");

  // Send form
  const [sendTo, setSendTo] = useState("");
  const [sendAmount, setSendAmount] = useState("");
  const [sendFee, setSendFee] = useState("0");
  const [sendPlan, setSendPlan] = useState<WalletSendPlan | null>(null);
  const [sending, setSending] = useState(false);

  // Live wallet data from RPC
  const [status, setStatus] = useState<WalletStatus | null>(null);
  const [balance, setBalance] = useState<WalletBalance | null>(null);
  const [utxos, setUtxos] = useState<WalletUtxoInfo[]>([]);
  const [history, setHistory] = useState<WalletHistoryEntry[]>([]);
  const [addresses, setAddresses] = useState<WalletAddressInfo[]>([]);
  const [walletError, setWalletError] = useState<string | null>(null);
  const [receiveQr, setReceiveQr] = useState<string | null>(null);

  // Mnemonic reveal
  const [revealMnemonicPassword, setRevealMnemonicPassword] = useState("");
  const [revealedMnemonic, setRevealedMnemonic] = useState<string | null>(null);

  const activeWallet = wallet.wallets.find((w) => w.id === wallet.activeWalletId);
  const isUnlocked = wallet.unlocked?.walletId === wallet.activeWalletId;

  // Refresh wallet data when active wallet changes or on demand.
  async function refreshWallet() {
    if (!isUnlocked) return;
    setWalletError(null);
    try {
      const [s, b, u, h, a] = await Promise.all([
        rpcClient.walletStatus().catch(() => null),
        rpcClient.walletGetBalance().catch(() => null),
        rpcClient.walletListUtxos().catch(() => []),
        rpcClient.walletHistory().catch(() => []),
        rpcClient.walletListAddresses().catch(() => []),
      ]);
      if (s) {
        setStatus(s);
        if (activeWallet) wallet.setCachedAddress(activeWallet.id, s.address);
      }
      if (b) setBalance(b);
      if (u) setUtxos(u);
      if (h) setHistory(h);
      if (a) setAddresses(a);
    } catch (err) {
      setWalletError(err instanceof Error ? err.message : String(err));
    }
  }

  useEffect(() => {
    if (isUnlocked) void refreshWallet();
    
  }, [wallet.activeWalletId, wallet.unlocked, settings.rpcUrl, settings.bearerToken]);

  // Generate receive QR for the active address.
  useEffect(() => {
    if (!status?.address) {
      setReceiveQr(null);
      return;
    }
    QRCode.toDataURL(status.address, {
      margin: 1,
      width: 256,
      color: { dark: "#00ff9c", light: "#000000" },
    })
      .then(setReceiveQr)
      .catch(() => setReceiveQr(null));
  }, [status?.address]);

  // --- Actions ---

  async function handleCreate() {
    try {
      const { mnemonic, walletId } = await wallet.createWallet(
        createLabel,
        createPassword,
      );
      setCreatedMnemonic(mnemonic);
      toast({
        title: "Wallet created",
        description: `ID: ${walletId.slice(0, 8)} — back up your mnemonic now.`,
      });
    } catch (err) {
      toast({
        title: "Create failed",
        description: err instanceof Error ? err.message : String(err),
        variant: "destructive",
      });
    }
  }

  async function handleImport() {
    try {
      if (importType === "mnemonic") {
        await wallet.importFromMnemonic(
          importValue,
          importLabel,
          importPassword,
        );
      } else if (importType === "hex") {
        await wallet.importFromHex(importValue, importLabel, importPassword);
      } else if (importType === "photo") {
        // Photo imports create a wallet from a derived 256-bit secret.
        // The image itself is processed client-side; we treat the resulting
        // hex as the master entropy.
        await wallet.importFromHex(importValue, importLabel, importPassword);
      }
      toast({ title: "Wallet imported" });
      setShowImport(false);
      setImportValue("");
      setImportLabel("");
      setImportPassword("");
      setPhotoPreview(null);
    } catch (err) {
      toast({
        title: "Import failed",
        description: err instanceof Error ? err.message : String(err),
        variant: "destructive",
      });
    }
  }

  async function handlePhotoKey(file: File) {
    const { deriveSecretFromImage } = await import("@/lib/parano1d/wallet");
    const { secret } = await deriveSecretFromImage(file);
    const { bytesToHex } = await import("@/lib/parano1d/wallet");
    const hex = bytesToHex(secret);
    setImportValue(hex);
    setPhotoPreview(URL.createObjectURL(file));
    toast({
      title: "Photo key derived",
      description: "256-bit secret extracted locally — image never uploaded.",
    });
  }

  async function handleUnlock() {
    if (!wallet.activeWalletId) return;
    try {
      await wallet.unlock(wallet.activeWalletId, unlockPassword);
      setUnlockPassword("");
      toast({ title: "Wallet unlocked" });
    } catch (err) {
      toast({
        title: "Unlock failed",
        description: err instanceof Error ? err.message : String(err),
        variant: "destructive",
      });
    }
  }

  async function handlePlanSend() {
    if (!sendTo || !sendAmount) return;
    if (!isValidAddress(sendTo)) {
      toast({
        title: "Invalid address",
        description: "Recipient must be a valid bech32m o1… address.",
        variant: "destructive",
      });
      return;
    }
    try {
      const amount = parseNoid(sendAmount);
      const fee = sendFee === "0" ? 0n : parseNoid(sendFee);
      const plan = await rpcClient.walletPlanSend(sendTo, amount, fee);
      setSendPlan(plan);
    } catch (err) {
      toast({
        title: "Plan failed",
        description: err instanceof Error ? err.message : String(err),
        variant: "destructive",
      });
    }
  }

  async function handleSend() {
    if (!sendPlan) return;
    setSending(true);
    try {
      const amount = parseNoid(sendAmount);
      const fee = BigInt(sendPlan.fee_micronoid);
      const result: WalletSendResult = await rpcClient.walletSend(
        sendTo,
        amount,
        fee,
      );
      if (result.submitted) {
        toast({
          title: "Transaction submitted",
          description: `txid: ${result.txid.slice(0, 24)}…`,
        });
        setSendTo("");
        setSendAmount("");
        setSendFee("0");
        setSendPlan(null);
        void refreshWallet();
      } else {
        toast({
          title: "Send failed",
          description: result.error ?? "unknown",
          variant: "destructive",
        });
      }
    } catch (err) {
      toast({
        title: "Send failed",
        description: err instanceof Error ? err.message : String(err),
        variant: "destructive",
      });
    } finally {
      setSending(false);
    }
  }

  async function handleRevealMnemonic() {
    if (!wallet.activeWalletId) return;
    try {
      const m = await wallet.revealMnemonic(
        wallet.activeWalletId,
        revealMnemonicPassword,
      );
      setRevealedMnemonic(m);
    } catch (err) {
      toast({
        title: "Reveal failed",
        description: err instanceof Error ? err.message : String(err),
        variant: "destructive",
      });
    }
  }

  function copyToClipboard(text: string, label: string) {
    navigator.clipboard.writeText(text);
    toast({ title: `${label} copied` });
  }

  // --- Render ---

  if (wallet.wallets.length === 0) {
    return (
      <div className="space-y-4">
        <ScanlineBox title="// wallet — no wallet loaded" accent="amber">
          <div className="p-8 text-center space-y-4">
            <KeyRound className="w-12 h-12 mx-auto text-[var(--amber)]" />
            <div className="text-sm text-[var(--muted-foreground)] font-mono">
              You have no Parano1d wallets yet. Generate a 256-bit master
              secret below — it will be encrypted with a password and stored in
              your browser. The same secret can be loaded into a local{" "}
              <code className="text-[var(--neon)]">parano1d</code> node to
              enable balance queries and sends.
            </div>
            <div className="flex gap-2 justify-center">
              <NeonButton variant="neon" onClick={() => setShowCreate(true)}>
                <Plus className="w-3 h-3 mr-2" /> NEW WALLET
              </NeonButton>
              <NeonButton variant="magenta" onClick={() => setShowImport(true)}>
                <ArrowDownToLine className="w-3 h-3 mr-2" /> IMPORT
              </NeonButton>
            </div>
          </div>
        </ScanlineBox>
        {renderCreateDialog()}
        {renderImportDialog()}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Wallet switcher */}
      <ScanlineBox title="// active wallet" accent="neon">
        <div className="p-3 flex flex-wrap items-center gap-3">
          <select
            value={wallet.activeWalletId ?? ""}
            onChange={(e) => {
              wallet.setActive(e.target.value);
              setStatus(null);
              setBalance(null);
              setUtxos([]);
              setHistory([]);
            }}
            className="bg-black border border-[var(--neon)]/40 px-2 py-1 font-mono text-sm text-[var(--neon)] rounded-none"
          >
            {wallet.wallets.map((w) => (
              <option key={w.id} value={w.id} className="bg-black">
                {w.label} — {w.id.slice(0, 8)}
              </option>
            ))}
          </select>
          <div className="flex-1" />
          <NeonButton variant="ghost" onClick={() => setShowCreate(true)}>
            <Plus className="w-3 h-3 mr-1" /> NEW
          </NeonButton>
          <NeonButton variant="ghost" onClick={() => setShowImport(true)}>
            <ArrowDownToLine className="w-3 h-3 mr-1" /> IMPORT
          </NeonButton>
          <NeonButton
            variant="ghost"
            onClick={() => {
              if (wallet.activeWalletId) {
                if (confirm("Remove this wallet? Make sure you have a backup.")) {
                  wallet.removeWallet(wallet.activeWalletId);
                }
              }
            }}
          >
            <Trash2 className="w-3 h-3 mr-1" /> REMOVE
          </NeonButton>
        </div>
      </ScanlineBox>

      {/* Locked state */}
      {!isUnlocked ? (
        <ScanlineBox title="// unlock" accent="amber">
          <div className="p-6 space-y-3 max-w-md">
            <Lock className="w-8 h-8 text-[var(--amber)]" />
            <div className="text-sm text-[var(--muted-foreground)] font-mono">
              Enter your password to decrypt the wallet secret. The decrypted
              secret stays in memory only — it is never written back to disk.
            </div>
            <NeonInput
              type="password"
              placeholder="password"
              value={unlockPassword}
              onChange={(e) => setUnlockPassword(e.target.value)}
              accent="amber"
              onKeyDown={(e) => e.key === "Enter" && handleUnlock()}
            />
            <NeonButton variant="amber" onClick={() => void handleUnlock()}>
              <Unlock className="w-3 h-3 mr-2" /> UNLOCK
            </NeonButton>
          </div>
        </ScanlineBox>
      ) : (
        <>
          {/* Stats row */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard
              label="Balance"
              value={balance ? formatNoid(balance.confirmed_micronoid) : "—"}
              unit="NOID"
              accent="neon"
            >
              <div className="text-[10px] text-[var(--muted-foreground)] mt-1 font-mono">
                pending: {balance ? formatNoid(balance.pending_micronoid) : "—"}{" "}
                NOID
              </div>
            </StatCard>
            <StatCard
              label="UTXOs"
              value={status?.utxo_count ?? utxos.length}
              accent="cyan"
            >
              <div className="text-[10px] text-[var(--muted-foreground)] mt-1 font-mono">
                sync: {status?.synced ? "OK" : "…"}
              </div>
            </StatCard>
            <StatCard
              label="Addresses"
              value={addresses.length}
              accent="magenta"
            >
              <div className="text-[10px] text-[var(--muted-foreground)] mt-1 font-mono">
                active idx:{" "}
                {addresses.find((a) => a.is_active)?.index ?? 0}
              </div>
            </StatCard>
            <StatCard
              label="History"
              value={history.length}
              accent="amber"
            >
              <div className="text-[10px] text-[var(--muted-foreground)] mt-1 font-mono">
                txs tracked
              </div>
            </StatCard>
          </div>

          {/* Subview tabs */}
          <div className="flex flex-wrap gap-1 border-b border-[var(--neon)]/20 pb-2">
            {(
              [
                ["overview", "Overview"],
                ["send", "Send"],
                ["history", "History"],
                ["addresses", "Addresses"],
                ["backup", "Backup"],
              ] as Array<[Subview, string]>
            ).map(([key, label]) => (
              <button
                key={key}
                onClick={() => setSubview(key)}
                className={cn(
                  "px-3 py-1 text-xs font-mono uppercase tracking-wider transition-all",
                  subview === key
                    ? "text-[var(--neon)] neon-text border-b-2 border-[var(--neon)]"
                    : "text-[var(--muted-foreground)] hover:text-[var(--neon)]",
                )}
              >
                {label}
              </button>
            ))}
            <div className="flex-1" />
            <NeonButton variant="ghost" onClick={() => void refreshWallet()}>
              <RefreshCw className="w-3 h-3 mr-1" /> REFRESH
            </NeonButton>
            <NeonButton variant="ghost" onClick={() => wallet.lock()}>
              <Lock className="w-3 h-3 mr-1" /> LOCK
            </NeonButton>
          </div>

          {walletError && (
            <div className="text-[11px] text-[var(--destructive)] font-mono p-2 border border-[var(--destructive)]/40 bg-[var(--destructive)]/5">
              <AlertCircle className="w-3 h-3 inline mr-1" />
              {walletError}
            </div>
          )}

          {/* Subview body */}
          {subview === "overview" && (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              <ScanlineBox title="// receive" accent="neon">
                <div className="p-4 space-y-3">
                  <div className="text-[10px] uppercase tracking-[0.2em] text-[var(--muted-foreground)]">
                    Active Address
                  </div>
                  <div className="flex items-center gap-2">
                    <code className="text-sm text-[var(--neon)] font-mono break-all flex-1">
                      {status?.address ?? activeWallet?.cachedAddress ?? "(no address — connect node)"}
                    </code>
                    {status?.address && (
                      <NeonButton
                        variant="ghost"
                        onClick={() =>
                          copyToClipboard(status.address, "Address")
                        }
                      >
                        <Copy className="w-3 h-3" />
                      </NeonButton>
                    )}
                  </div>
                  {receiveQr ? (
                    <div className="bg-black p-3 inline-block border border-[var(--neon)]/30">
                      {}
                      <img
                        src={receiveQr}
                        alt="receive QR"
                        className="w-48 h-48"
                      />
                    </div>
                  ) : (
                    <div className="text-[var(--muted-foreground)] text-xs italic">
                      Connect a local node to generate a receive QR.
                    </div>
                  )}
                </div>
              </ScanlineBox>
              <ScanlineBox title="// UTXOs" accent="cyan">
                <div className="p-2 max-h-72 overflow-y-auto">
                  {utxos.length === 0 ? (
                    <div className="text-[var(--muted-foreground)] italic p-3 text-xs">
                      No live UTXOs.
                    </div>
                  ) : (
                    <table className="w-full text-xs font-mono">
                      <thead>
                        <tr className="text-[var(--muted-foreground)] border-b border-[var(--neon)]/20">
                          <th className="text-left p-1">SLOT</th>
                          <th className="text-right p-1">VALUE</th>
                          <th className="text-right p-1">CONF</th>
                        </tr>
                      </thead>
                      <tbody>
                        {utxos.map((u) => (
                          <tr
                            key={u.slot_index}
                            className="border-b border-[var(--neon)]/10"
                          >
                            <td className="p-1 text-[var(--cyan)]">
                              {u.slot_index}
                            </td>
                            <td className="p-1 text-right text-[var(--neon)]">
                              {formatNoid(u.value_micronoid)}
                            </td>
                            <td className="p-1 text-right text-[var(--muted-foreground)]">
                              {u.confirmations}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </ScanlineBox>
            </div>
          )}

          {subview === "send" && (
            <ScanlineBox title="// send NOID" accent="magenta">
              <div className="p-4 space-y-3 max-w-2xl">
                <div>
                  <label className="text-[10px] uppercase tracking-[0.2em] text-[var(--muted-foreground)]">
                    To Address (bech32m o1…)
                  </label>
                  <NeonInput
                    value={sendTo}
                    onChange={(e) => setSendTo(e.target.value)}
                    placeholder="o1..."
                    accent="magenta"
                    className="w-full mt-1"
                  />
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <label className="text-[10px] uppercase tracking-[0.2em] text-[var(--muted-foreground)]">
                      Amount (NOID)
                    </label>
                    <NeonInput
                      value={sendAmount}
                      onChange={(e) => setSendAmount(e.target.value)}
                      placeholder="1.5"
                      accent="magenta"
                      className="w-full mt-1"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] uppercase tracking-[0.2em] text-[var(--muted-foreground)]">
                      Fee (NOID, 0 = auto)
                    </label>
                    <NeonInput
                      value={sendFee}
                      onChange={(e) => setSendFee(e.target.value)}
                      placeholder="0"
                      accent="magenta"
                      className="w-full mt-1"
                    />
                  </div>
                </div>
                <div className="flex gap-2">
                  <NeonButton
                    variant="magenta"
                    onClick={() => void handlePlanSend()}
                  >
                    PREVIEW
                  </NeonButton>
                  <NeonButton
                    variant="neon"
                    onClick={() => void handleSend()}
                    disabled={!sendPlan || sending}
                  >
                    <ArrowUpFromLine className="w-3 h-3 mr-2" />
                    {sending ? "SENDING…" : "SEND"}
                  </NeonButton>
                </div>
                {sendPlan && (
                  <div className="text-xs font-mono p-3 border border-[var(--magenta)]/40 bg-[var(--magenta)]/5 space-y-1">
                    <div className="flex justify-between">
                      <span className="text-[var(--muted-foreground)]">
                        inputs
                      </span>
                      <span className="text-[var(--cyan)]">
                        {sendPlan.inputs.length}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[var(--muted-foreground)]">
                        outputs
                      </span>
                      <span className="text-[var(--cyan)]">
                        {sendPlan.outputs.length}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[var(--muted-foreground)]">
                        fee
                      </span>
                      <span className="text-[var(--amber)]">
                        {formatNoid(sendPlan.fee_micronoid)} NOID
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-[var(--muted-foreground)]">
                        total
                      </span>
                      <span className="text-[var(--neon)]">
                        {formatNoid(sendPlan.total_input_micronoid)} NOID
                      </span>
                    </div>
                  </div>
                )}
                <div className="text-[10px] text-[var(--muted-foreground)] font-mono">
                  The actual proof + signing happens on your local node — the
                  web wallet never holds or transmits the secret over the
                  network.
                </div>
              </div>
            </ScanlineBox>
          )}

          {subview === "history" && (
            <ScanlineBox title="// transaction history" accent="cyan">
              <div className="p-2 max-h-96 overflow-y-auto">
                {history.length === 0 ? (
                  <div className="text-[var(--muted-foreground)] italic p-3 text-xs">
                    No transactions yet.
                  </div>
                ) : (
                  <table className="w-full text-xs font-mono">
                    <thead>
                      <tr className="text-[var(--muted-foreground)] border-b border-[var(--neon)]/20">
                        <th className="text-left p-1">DIR</th>
                        <th className="text-left p-1">TXID</th>
                        <th className="text-right p-1">AMOUNT</th>
                        <th className="text-right p-1">HEIGHT</th>
                        <th className="text-right p-1">CONF</th>
                      </tr>
                    </thead>
                    <tbody>
                      {history.map((h) => (
                        <tr
                          key={h.txid}
                          className="border-b border-[var(--neon)]/10"
                        >
                          <td
                            className={cn(
                              "p-1 uppercase",
                              h.direction === "received" &&
                                "text-[var(--neon)]",
                              h.direction === "sent" &&
                                "text-[var(--magenta)]",
                              h.direction === "mined" &&
                                "text-[var(--amber)]",
                              h.direction === "fee" &&
                                "text-[var(--muted-foreground)]",
                            )}
                          >
                            {h.direction.slice(0, 4)}
                          </td>
                          <td className="p-1 text-[var(--cyan)]">
                            {h.txid.slice(0, 16)}…
                          </td>
                          <td
                            className={cn(
                              "p-1 text-right",
                              h.direction === "received" || h.direction === "mined"
                                ? "text-[var(--neon)]"
                                : "text-[var(--magenta)]",
                            )}
                          >
                            {formatNoid(h.amount_micronoid)}
                          </td>
                          <td className="p-1 text-right text-[var(--muted-foreground)]">
                            {h.height.toLocaleString()}
                          </td>
                          <td className="p-1 text-right text-[var(--muted-foreground)]">
                            {h.confirmations}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </ScanlineBox>
          )}

          {subview === "addresses" && (
            <ScanlineBox title="// wallet addresses" accent="neon">
              <div className="p-2 max-h-96 overflow-y-auto">
                {addresses.length === 0 ? (
                  <div className="text-[var(--muted-foreground)] italic p-3 text-xs">
                    No addresses — connect a local node to query.
                  </div>
                ) : (
                  <table className="w-full text-xs font-mono">
                    <thead>
                      <tr className="text-[var(--muted-foreground)] border-b border-[var(--neon)]/20">
                        <th className="text-left p-1">IDX</th>
                        <th className="text-left p-1">ADDRESS</th>
                        <th className="text-right p-1">BALANCE</th>
                        <th className="text-right p-1">UTXO</th>
                        <th className="p-1"></th>
                      </tr>
                    </thead>
                    <tbody>
                      {addresses.map((a) => (
                        <tr
                          key={a.index}
                          className={cn(
                            "border-b border-[var(--neon)]/10",
                            a.is_active && "bg-[var(--neon)]/5",
                          )}
                        >
                          <td className="p-1 text-[var(--cyan)]">{a.index}</td>
                          <td className="p-1 text-[var(--neon)] break-all">
                            {a.address}
                          </td>
                          <td className="p-1 text-right text-[var(--neon)]">
                            {formatNoid(a.balance_micronoid)}
                          </td>
                          <td className="p-1 text-right text-[var(--muted-foreground)]">
                            {a.utxo_count}
                          </td>
                          <td className="p-1 text-right">
                            {!a.is_active && (
                              <NeonButton
                                variant="ghost"
                                onClick={() =>
                                  void rpcClient
                                    .walletSetActiveAddress(a.index)
                                    .then(() => refreshWallet())
                                }
                              >
                                SET
                              </NeonButton>
                            )}
                            {a.is_active && (
                              <span className="text-[var(--neon)] text-[10px]">
                                ● ACTIVE
                              </span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <div className="p-2">
                  <NeonButton
                    variant="ghost"
                    onClick={() =>
                      void rpcClient
                        .walletNextAddress()
                        .then(() => refreshWallet())
                    }
                  >
                    <Plus className="w-3 h-3 mr-1" /> GENERATE NEXT ADDRESS
                  </NeonButton>
                </div>
              </div>
            </ScanlineBox>
          )}

          {subview === "backup" && (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              <ScanlineBox title="// mnemonic backup" accent="amber">
                <div className="p-4 space-y-3">
                  <Shield className="w-8 h-8 text-[var(--amber)]" />
                  <div className="text-xs text-[var(--muted-foreground)] font-mono">
                    Reveal your 24-word BIP39 mnemonic. Anyone with these words
                    controls your wallet — never share, never type into untrusted
                    software.
                  </div>
                  {revealedMnemonic ? (
                    <div className="p-3 border border-[var(--amber)]/40 bg-[var(--amber)]/5 font-mono text-sm text-[var(--amber)] grid grid-cols-3 gap-1">
                      {revealedMnemonic.split(" ").map((w, i) => (
                        <div key={i} className="flex gap-1">
                          <span className="text-[var(--muted-foreground)] text-[10px]">
                            {i + 1}.
                          </span>
                          <span>{w}</span>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <>
                      <NeonInput
                        type="password"
                        placeholder="password to reveal"
                        value={revealMnemonicPassword}
                        onChange={(e) =>
                          setRevealMnemonicPassword(e.target.value)
                        }
                        accent="amber"
                      />
                      <NeonButton
                        variant="amber"
                        onClick={() => void handleRevealMnemonic()}
                      >
                        <Eye className="w-3 h-3 mr-2" /> REVEAL
                      </NeonButton>
                    </>
                  )}
                  {revealedMnemonic && (
                    <NeonButton
                      variant="ghost"
                      onClick={() => {
                        setRevealedMnemonic(null);
                        setRevealMnemonicPassword("");
                      }}
                    >
                      <EyeOff className="w-3 h-3 mr-2" /> HIDE
                    </NeonButton>
                  )}
                </div>
              </ScanlineBox>
              <ScanlineBox title="// encrypted keystore" accent="neon">
                <div className="p-4 space-y-3">
                  <Wallet className="w-8 h-8 text-[var(--neon)]" />
                  <div className="text-xs text-[var(--muted-foreground)] font-mono">
                    Export an encrypted JSON keystore (AES-256-GCM, PBKDF2
                    600k). Import it on any device with this app or any
                    Parano1d-compatible wallet.
                  </div>
                  <NeonButton
                    variant="neon"
                    onClick={() => {
                      if (!wallet.activeWalletId) return;
                      const ks = wallet.exportKeystore(wallet.activeWalletId);
                      if (!ks) return;
                      const blob = new Blob([JSON.stringify(ks, null, 2)], {
                        type: "application/json",
                      });
                      const url = URL.createObjectURL(blob);
                      const a = document.createElement("a");
                      a.href = url;
                      a.download = `noidmobile-${
                        wallet.activeWalletId.slice(0, 8)
                      }.json`;
                      a.click();
                      URL.revokeObjectURL(url);
                    }}
                  >
                    <ArrowDownToLine className="w-3 h-3 mr-2" /> EXPORT KEYSTORE
                  </NeonButton>
                </div>
              </ScanlineBox>
            </div>
          )}
        </>
      )}

      {renderCreateDialog()}
      {renderImportDialog()}
    </div>
  );

  function renderCreateDialog() {
    return (
      <>
        {showCreate && (
          <div
            className="fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4"
            onClick={() => !createdMnemonic && setShowCreate(false)}
          >
            <ScanlineBox
              title={createdMnemonic ? "// backup your mnemonic" : "// new wallet"}
              accent={createdMnemonic ? "amber" : "neon"}
              className="w-full max-w-lg"
            >
              <div className="p-5 space-y-3">
                {!createdMnemonic ? (
                  <>
                    <div className="text-xs text-[var(--muted-foreground)] font-mono">
                      A 256-bit master secret will be generated with{" "}
                      <code>crypto.getRandomValues</code>. Choose a password —
                      it encrypts the secret at rest in your browser. The
                      password cannot be recovered.
                    </div>
                    <NeonInput
                      placeholder="wallet label (optional)"
                      value={createLabel}
                      onChange={(e) => setCreateLabel(e.target.value)}
                    />
                    <NeonInput
                      type="password"
                      placeholder="password (min 8 chars)"
                      value={createPassword}
                      onChange={(e) => setCreatePassword(e.target.value)}
                    />
                    <div className="flex gap-2">
                      <NeonButton
                        variant="neon"
                        onClick={() => void handleCreate()}
                        disabled={createPassword.length < 8}
                        className="flex-1"
                      >
                        CREATE
                      </NeonButton>
                      <NeonButton
                        variant="ghost"
                        onClick={() => setShowCreate(false)}
                      >
                        CANCEL
                      </NeonButton>
                    </div>
                  </>
                ) : (
                  <>
                    <div className="text-xs text-[var(--amber)] font-mono">
                      Write these 24 words down on paper, in order. They are
                      the ONLY way to recover your wallet if your browser data
                      is lost.
                    </div>
                    <div className="p-3 border border-[var(--amber)]/40 bg-[var(--amber)]/5 font-mono text-sm text-[var(--amber)] grid grid-cols-3 gap-1">
                      {createdMnemonic.split(" ").map((w, i) => (
                        <div key={i} className="flex gap-1">
                          <span className="text-[var(--muted-foreground)] text-[10px]">
                            {i + 1}.
                          </span>
                          <span>{w}</span>
                        </div>
                      ))}
                    </div>
                    <div className="flex gap-2">
                      <NeonButton
                        variant="ghost"
                        onClick={() =>
                          copyToClipboard(createdMnemonic, "Mnemonic")
                        }
                      >
                        <Copy className="w-3 h-3 mr-1" /> COPY
                      </NeonButton>
                      <NeonButton
                        variant="amber"
                        onClick={() => {
                          setCreatedMnemonic(null);
                          setShowCreate(false);
                          setCreateLabel("");
                          setCreatePassword("");
                        }}
                        className="flex-1"
                      >
                        IVE BACKED IT UP
                      </NeonButton>
                    </div>
                  </>
                )}
              </div>
            </ScanlineBox>
          </div>
        )}
      </>
    );
  }

  function renderImportDialog() {
    return (
      <>
        {showImport && (
          <div
            className="fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4"
            onClick={() => setShowImport(false)}
          >
            <ScanlineBox
              title="// import wallet"
              accent="magenta"
              className="w-full max-w-lg"
            >
              <div
                className="p-5 space-y-3"
                onClick={(e) => e.stopPropagation()}
              >
                <div className="flex gap-1">
                  {(
                    [
                      ["mnemonic", "Mnemonic"],
                      ["hex", "Hex Secret"],
                      ["photo", "Photo Key"],
                    ] as Array<[typeof importType, string]>
                  ).map(([t, label]) => (
                    <button
                      key={t}
                      onClick={() => {
                        setImportType(t);
                        setImportValue("");
                        setPhotoPreview(null);
                      }}
                      className={cn(
                        "px-3 py-1 text-[10px] font-mono uppercase tracking-wider",
                        importType === t
                          ? "text-[var(--magenta)] border-b-2 border-[var(--magenta)]"
                          : "text-[var(--muted-foreground)]",
                      )}
                    >
                      {label}
                    </button>
                  ))}
                </div>

                {importType === "mnemonic" && (
                  <textarea
                    placeholder="24 words separated by spaces"
                    value={importValue}
                    onChange={(e) => setImportValue(e.target.value)}
                    className="w-full bg-black border border-[var(--magenta)]/40 p-2 font-mono text-xs text-[var(--foreground)] h-20"
                  />
                )}
                {importType === "hex" && (
                  <NeonInput
                    placeholder="64 hex chars (256-bit secret)"
                    value={importValue}
                    onChange={(e) => setImportValue(e.target.value)}
                    accent="magenta"
                  />
                )}
                {importType === "photo" && (
                  <div className="space-y-2">
                    <input
                      type="file"
                      accept="image/*"
                      onChange={(e) => {
                        const f = e.target.files?.[0];
                        if (f) void handlePhotoKey(f);
                      }}
                      className="block w-full text-xs text-[var(--muted-foreground)] file:mr-2 file:py-1 file:px-2 file:border file:border-[var(--magenta)]/40 file:bg-transparent file:text-[var(--magenta)] file:font-mono file:text-xs file:uppercase"
                    />
                    {photoPreview && (
                      <div className="text-[10px] text-[var(--muted-foreground)]">
                        Image processed locally — never uploaded.
                      </div>
                    )}
                    {importValue && (
                      <div className="text-[10px] text-[var(--neon)] break-all font-mono">
                        derived secret: {importValue.slice(0, 32)}…
                      </div>
                    )}
                  </div>
                )}

                <NeonInput
                  placeholder="wallet label (optional)"
                  value={importLabel}
                  onChange={(e) => setImportLabel(e.target.value)}
                />
                <NeonInput
                  type="password"
                  placeholder="encryption password (min 8 chars)"
                  value={importPassword}
                  onChange={(e) => setImportPassword(e.target.value)}
                />
                <div className="flex gap-2">
                  <NeonButton
                    variant="magenta"
                    onClick={() => void handleImport()}
                    disabled={
                      !importValue || importPassword.length < 8
                    }
                    className="flex-1"
                  >
                    IMPORT
                  </NeonButton>
                  <NeonButton
                    variant="ghost"
                    onClick={() => setShowImport(false)}
                  >
                    CANCEL
                  </NeonButton>
                </div>
              </div>
            </ScanlineBox>
          </div>
        )}
      </>
    );
  }
}
