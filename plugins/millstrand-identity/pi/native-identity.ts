import { resolve } from "node:path";
import type {
  ExtensionAPI,
  ExtensionContext,
} from "@earendil-works/pi-coding-agent";
import {
  MILLSTRAND_PARENT_IDENTITY_ENV,
  MILLSTRAND_WORKSPACE_ENV,
  type ActiveMillstrandIdentity,
  type MillstrandIdentityState,
} from "./context.js";

export const MILLSTRAND_IDENTITY_FLAG = "millstrand-identity";
export const MILLSTRAND_WORKSPACE_FLAG = "millstrand-workspace";
export const DEBUG_MILLSTRAND_IDENTITY_FLAG = "debug-millstrand-identity";

export type NativeIdentityResult = ActiveMillstrandIdentity & {
  strandId: string;
  result: "minted" | "recovered" | "attached";
  nativeSessionId: string;
};

export type NativeIdentityState = MillstrandIdentityState;

type StartupResponse = {
  operation: "identity startup";
  identity: string;
  "strand-id": string;
  result: "minted" | "recovered" | "attached";
  instruction: string;
};

type ResolveOptions = {
  cwd: string;
  nativeSessionId: string;
  model?: string;
  thinkingLevel?: string;
  identity?: string;
  parentIdentity?: string;
  workspace?: string;
  signal?: AbortSignal;
};

type Exec = ExtensionAPI["exec"];

function optionalString(value: unknown, label: string): string | undefined {
  if (value === undefined || value === null || value === false)
    return undefined;
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`${label} must be a non-empty string.`);
  }
  return value.trim();
}

function parseStartupResponse(stdout: string): StartupResponse {
  let value: unknown;
  try {
    value = JSON.parse(stdout.trim());
  } catch (error) {
    throw new Error(
      `Strand returned invalid identity startup JSON: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Strand returned an invalid identity startup response.");
  }
  const response = value as Record<string, unknown>;
  if (response.operation !== "identity startup") {
    throw new Error(
      "Strand returned an unexpected identity startup operation.",
    );
  }
  const identity = optionalString(response.identity, "identity");
  const strandId = optionalString(response["strand-id"], "strand-id");
  const instruction = optionalString(response.instruction, "instruction");
  const result = response.result;
  if (
    !identity ||
    !strandId ||
    !instruction ||
    !["minted", "recovered", "attached"].includes(String(result))
  ) {
    throw new Error("Strand returned an incomplete identity startup response.");
  }
  return {
    operation: "identity startup",
    identity,
    "strand-id": strandId,
    instruction,
    result: result as StartupResponse["result"],
  };
}

function diagnostic(result: { stdout: string; stderr: string }): string {
  return (
    result.stderr.trim() ||
    result.stdout.trim() ||
    "no diagnostic output"
  )
    .replace(/\s+/g, " ")
    .slice(0, 500);
}

export function isLegacyManagedPiEnvironment(
  env: NodeJS.ProcessEnv = process.env,
): boolean {
  return Boolean(env.MILLSTRAND_RUN_ID?.trim());
}

export function getNativeIdentityInputs(
  pi: Pick<ExtensionAPI, "getFlag">,
  env: NodeJS.ProcessEnv = process.env,
): Pick<ResolveOptions, "identity" | "parentIdentity" | "workspace"> {
  return {
    identity: optionalString(
      pi.getFlag(MILLSTRAND_IDENTITY_FLAG),
      `--${MILLSTRAND_IDENTITY_FLAG}`,
    ),
    parentIdentity: optionalString(
      env[MILLSTRAND_PARENT_IDENTITY_ENV],
      MILLSTRAND_PARENT_IDENTITY_ENV,
    ),
    workspace:
      optionalString(
        pi.getFlag(MILLSTRAND_WORKSPACE_FLAG),
        `--${MILLSTRAND_WORKSPACE_FLAG}`,
      ) ??
      optionalString(env[MILLSTRAND_WORKSPACE_ENV], MILLSTRAND_WORKSPACE_ENV) ??
      optionalString(env.MILLSTRAND_WORKSPACE, "MILLSTRAND_WORKSPACE"),
  };
}

export async function resolveNativeIdentity(
  exec: Exec,
  options: ResolveOptions,
): Promise<NativeIdentityResult> {
  const workspace = options.workspace
    ? resolve(options.cwd, options.workspace)
    : undefined;
  const args = ["--cwd", options.cwd];
  if (workspace) args.push("--workspace", workspace);
  args.push("identity", "startup");
  if (options.identity) args.push("--identity", options.identity);
  if (options.parentIdentity)
    args.push("--parent-identity", options.parentIdentity);
  if (options.model) args.push("--model", options.model);
  if (options.thinkingLevel)
    args.push("--thinking-level", options.thinkingLevel);
  args.push("pi", options.nativeSessionId);

  const result = await exec("strand", args, {
    cwd: options.cwd,
    signal: options.signal,
    timeout: 10_000,
  });
  if (result.code !== 0) {
    throw new Error(
      `Strand identity startup failed (exit ${result.code}): ${diagnostic(result)}`,
    );
  }
  const response = parseStartupResponse(result.stdout);
  return {
    identity: response.identity,
    strandId: response["strand-id"],
    result: response.result,
    instruction: response.instruction,
    nativeSessionId: options.nativeSessionId,
    ...(workspace ? { workspace } : {}),
  };
}

export function formatNativeIdentityState(
  state: NativeIdentityState,
  cwd: string,
): string {
  const details =
    state.status === "bound"
      ? {
          nativeSessionId: state.nativeSessionId,
          identity: state.identity,
          strandId: state.strandId,
          result: state.result,
          workspace: state.workspace ?? null,
          instruction: state.instruction,
        }
      : state.status === "pending"
        ? {}
        : state.status === "suppressed"
          ? { nativeSessionId: state.nativeSessionId, reason: state.reason }
          : { nativeSessionId: state.nativeSessionId, error: state.error };
  return JSON.stringify({ status: state.status, cwd, ...details }, null, 2);
}

export function nativeIdentityModel(
  ctx: Pick<ExtensionContext, "model">,
): string | undefined {
  return ctx.model?.id;
}
