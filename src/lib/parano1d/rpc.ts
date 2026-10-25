// SPDX-License-Identifier: Apache-2.0
// JSON-RPC 2.0 client for the Parano1d node (`paranoid_*` namespace).
// Reference: noid_rpc/src/api.rs and noid_extminer/src/main.rs

import {
  AddressInfo,
  BlockTemplateResponse,
  ChainInfo,
  DEFAULT_RPC_URL,
  FeeEstimate,
  JsonRpcRequest,
  JsonRpcResponse,
  MiningInfo,
  NodeStatus,
  WalletAddressInfo,
  WalletBalance,
  WalletHistoryEntry,
  WalletSendPlan,
  WalletSendResult,
  WalletStatus,
  WalletUtxoInfo,
} from "./types";

export interface RpcClientOptions {
  url?: string;
  /** Bearer token for pool/external RPC access. Optional for solo localhost. */
  bearerToken?: string;
  /** Per-request timeout in ms. */
  timeoutMs?: number;
  /** Fetch implementation (defaults to global fetch). */
  fetchImpl?: typeof fetch;
}

export class Parano1dRpcClient {
  private url: string;
  private bearerToken: string | undefined;
  private timeoutMs: number;
  private fetchImpl: typeof fetch;
  private nextId = 1;

  constructor(opts: RpcClientOptions = {}) {
    this.url = (opts.url ?? DEFAULT_RPC_URL).replace(/\/$/, "");
    this.bearerToken = opts.bearerToken;
    this.timeoutMs = opts.timeoutMs ?? 30_000;
    this.fetchImpl = opts.fetchImpl ?? fetch;
  }

  /** Reconfigure at runtime (used by the settings panel). */
  configure(opts: RpcClientOptions): void {
    if (opts.url !== undefined) this.url = opts.url.replace(/\/$/, "");
    if (opts.bearerToken !== undefined) this.bearerToken = opts.bearerToken;
    if (opts.timeoutMs !== undefined) this.timeoutMs = opts.timeoutMs;
  }

  get endpoint(): string {
    return this.url;
  }

  get authenticated(): boolean {
    return Boolean(this.bearerToken);
  }

  // -------------------------------------------------------------------------
  // Low-level JSON-RPC call
  // -------------------------------------------------------------------------

  async call<T>(method: string, params: unknown[]): Promise<T> {
    const body: JsonRpcRequest = {
      jsonrpc: "2.0",
      id: this.nextId++,
      method,
      params,
    };

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);

    let resp: Response;
    try {
      resp = await this.fetchImpl(this.url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(this.bearerToken
            ? { Authorization: `Bearer ${this.bearerToken}` }
            : {}),
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    } catch (err) {
      clearTimeout(timer);
      if (err instanceof DOMException && err.name === "AbortError") {
        throw new Error(`RPC timeout after ${this.timeoutMs}ms — ${this.url}`);
      }
      throw new Error(
        `RPC connection failed: ${err instanceof Error ? err.message : String(err)} — ${this.url}`,
      );
    }
    clearTimeout(timer);

    if (resp.status === 401) {
      throw new Error(
        "401 Unauthorized — node requires a mining credential. Set the bearer token in Settings.",
      );
    }
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status} from ${this.url}`);
    }

    let rpc: JsonRpcResponse<T>;
    try {
      rpc = (await resp.json()) as JsonRpcResponse<T>;
    } catch {
      throw new Error("Malformed JSON-RPC response from node");
    }

    if (rpc.error) {
      throw new Error(
        `RPC error ${rpc.error.code}: ${rpc.error.message}${
          rpc.error.data !== undefined ? ` — ${JSON.stringify(rpc.error.data)}` : ""
        }`,
      );
    }
    if (rpc.result === undefined || rpc.result === null) {
      throw new Error(`RPC returned null result for ${method}`);
    }
    return rpc.result;
  }

  // -------------------------------------------------------------------------
  // Mining (external miner API)
  // -------------------------------------------------------------------------

  /** paranoid_getBlockTemplate — fetch a PoW template. Pass "" for pool mode. */
  getBlockTemplate(minerAddress = ""): Promise<BlockTemplateResponse> {
    return this.call<BlockTemplateResponse>("paranoid_getBlockTemplate", [
      minerAddress,
    ]);
  }

  /** paranoid_submitBlock — submit a 16-byte LE nonce (32 hex chars). */
  submitBlock(templateId: string, nonceHex: string): Promise<string> {
    return this.call<string>("paranoid_submitBlock", [templateId, nonceHex]);
  }

  // -------------------------------------------------------------------------
  // Chain
  // -------------------------------------------------------------------------

  blockCount(): Promise<number> {
    return this.call<number>("paranoid_blockCount", []);
  }

  getChainInfo(): Promise<ChainInfo> {
    return this.call<ChainInfo>("paranoid_getChainInfo", []);
  }

  getMiningInfo(): Promise<MiningInfo> {
    return this.call<MiningInfo>("paranoid_getMiningInfo", []);
  }

  getNodeStatus(): Promise<NodeStatus> {
    return this.call<NodeStatus>("paranoid_getNodeStatus", []);
  }

  estimateFee(nOutputs: number): Promise<number> {
    return this.call<number>("paranoid_estimateFee", [nOutputs]);
  }

  estimateFeeDetailed(
    nInputs: number,
    nOutputs: number,
  ): Promise<FeeEstimate> {
    return this.call<FeeEstimate>("paranoid_estimateFeeDetailed", [
      nInputs,
      nOutputs,
    ]);
  }

  validateAddress(address: string): Promise<AddressInfo> {
    return this.call<AddressInfo>("paranoid_validateAddress", [address]);
  }

  // -------------------------------------------------------------------------
  // Wallet
  // -------------------------------------------------------------------------

  walletStatus(): Promise<WalletStatus> {
    return this.call<WalletStatus>("paranoid_walletStatus", []);
  }

  walletGetAddress(index: number): Promise<string> {
    return this.call<string>("paranoid_walletGetAddress", [index]);
  }

  walletGetBalance(): Promise<WalletBalance> {
    return this.call<WalletBalance>("paranoid_walletGetBalance", []);
  }

  walletListUtxos(): Promise<WalletUtxoInfo[]> {
    return this.call<WalletUtxoInfo[]>("paranoid_walletListUtxos", []);
  }

  walletHistory(): Promise<WalletHistoryEntry[]> {
    return this.call<WalletHistoryEntry[]>("paranoid_walletHistory", []);
  }

  walletPlanSend(
    toAddress: string,
    amountMicronoid: bigint,
    feeMicronoid: bigint,
  ): Promise<WalletSendPlan> {
    return this.call<WalletSendPlan>("paranoid_walletPlanSend", [
      toAddress,
      Number(amountMicronoid),
      Number(feeMicronoid),
    ]);
  }

  walletSend(
    toAddress: string,
    amountMicronoid: bigint,
    feeMicronoid: bigint,
  ): Promise<WalletSendResult> {
    return this.call<WalletSendResult>("paranoid_walletSend", [
      toAddress,
      Number(amountMicronoid),
      Number(feeMicronoid),
    ]);
  }

  walletNextAddress(): Promise<WalletAddressInfo> {
    return this.call<WalletAddressInfo>("paranoid_walletNextAddress", []);
  }

  walletListAddresses(): Promise<WalletAddressInfo[]> {
    return this.call<WalletAddressInfo[]>("paranoid_walletListAddresses", []);
  }

  walletActiveAddress(): Promise<WalletAddressInfo> {
    return this.call<WalletAddressInfo>("paranoid_walletActiveAddress", []);
  }

  walletSetActiveAddress(index: number): Promise<WalletAddressInfo> {
    return this.call<WalletAddressInfo>("paranoid_walletSetActiveAddress", [
      index,
    ]);
  }
}

// Singleton — reconfigured by the settings store.
export const rpcClient = new Parano1dRpcClient();
