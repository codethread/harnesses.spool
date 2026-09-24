import { createReadStream, realpathSync, statSync } from "node:fs";
import { createInterface } from "node:readline";
import { basename, dirname, join } from "node:path";
import type {
  ExtensionAPI,
  ExtensionContext,
} from "@earendil-works/pi-coding-agent";
import {
  MILLSTRAND_PARENT_IDENTITY_ENV,
  type ActiveMillstrandIdentity,
  type MillstrandIdentityState,
} from "./context.js";

export const DEBUG_MILLSTRAND_IDENTITY_FLAG = "debug-millstrand-identity";
export type NativeIdentityState = MillstrandIdentityState;
export type NativeIdentityResult = ActiveMillstrandIdentity & {
  strandId: string;
  runId: string;
  result: "minted" | "recovered" | "attached";
};
type Exec = ExtensionAPI["exec"];
type ResolveOptions = {
  cwd: string;
  nativeSessionId: string;
  model?: string;
  thinkingLevel?: string;
  parentIdentity?: string;
  parentSessionFile?: string;
  runId?: string;
  inputs?: () => Pick<ResolveOptions, "runId" | "parentIdentity">;
  signal?: AbortSignal;
};

function optionalString(value: unknown, label: string): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`${label} must be a non-empty string.`);
  }
  return value.trim();
}

/** Ignore inherited routing and derive the canonical workspace from this project. */
export async function projectWorkspace(
  exec: Exec,
  cwd: string,
  signal?: AbortSignal,
): Promise<string | null> {
  const git = await exec(
    "git",
    ["rev-parse", "--path-format=absolute", "--git-common-dir"],
    {
      cwd,
      signal,
      timeout: 10_000,
    },
  );
  if (git.code !== 0) return null;
  const common = git.stdout.trim();
  if (basename(common) !== ".git") return null;
  const workspace = join(dirname(common), ".millstrand");
  try {
    return statSync(workspace).isDirectory() ? realpathSync(workspace) : null;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  }
}

/** Only a run reference and resolved parent attribution cross the process boundary. */
export function getNativeIdentityInputs(
  _pi: Pick<ExtensionAPI, "getFlag">,
  env: NodeJS.ProcessEnv = process.env,
): Pick<ResolveOptions, "runId" | "parentIdentity"> {
  return {
    runId:
      env.PI_SUBAGENT === "1"
        ? undefined
        : optionalString(env.MILLSTRAND_RUN_ID, "MILLSTRAND_RUN_ID"),
    parentIdentity: optionalString(
      env[MILLSTRAND_PARENT_IDENTITY_ENV],
      MILLSTRAND_PARENT_IDENTITY_ENV,
    ),
  };
}

/** Await identity and run registration before allowing model work. */
export async function resolveNativeIdentity(
  exec: Exec,
  options: ResolveOptions,
): Promise<NativeIdentityResult | null> {
  const workspace = await projectWorkspace(exec, options.cwd, options.signal);
  if (!workspace) return null;
  // Inactive projects must not parse inherited managed configuration.
  options = { ...options, ...options.inputs?.() };
  let parentNativeSessionId: string | undefined;
  if (options.parentSessionFile) {
    const stream = createReadStream(options.parentSessionFile, {
      encoding: "utf8",
    });
    const lines = createInterface({ input: stream });
    try {
      for await (const line of lines) {
        const header = JSON.parse(line);
        if (
          header.type !== "session" ||
          typeof header.id !== "string" ||
          !header.id
        ) {
          throw new Error("Invalid native parent session header.");
        }
        parentNativeSessionId = header.id;
        break;
      }
      if (!parentNativeSessionId)
        throw new Error("Missing native parent session header.");
    } finally {
      lines.close();
      stream.destroy();
    }
  }
  const args = [
    "--workspace",
    workspace,
    "--cwd",
    options.cwd,
    "agent",
    "native-startup",
    "pi",
    options.nativeSessionId,
  ];
  if (options.runId) args.push("--run-id", options.runId);
  if (parentNativeSessionId)
    args.push("--parent-native-session-id", parentNativeSessionId);
  if (options.parentIdentity)
    args.push("--parent-identity", options.parentIdentity);
  if (options.model) args.push("--model", options.model);
  if (options.thinkingLevel)
    args.push("--thinking-level", options.thinkingLevel);
  const response = await exec("strand", args, {
    cwd: options.cwd,
    signal: options.signal,
    timeout: 10_000,
  });
  if (response.code !== 0) {
    throw new Error(
      `Strand native startup failed (exit ${response.code}): ${response.stderr.trim() || response.stdout.trim()}`,
    );
  }
  const value: unknown = JSON.parse(response.stdout);
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("Invalid native startup response.");
  const data = value as Record<string, unknown>;
  const identity = optionalString(data.identity, "identity");
  const strandId = optionalString(data["strand-id"], "strand-id");
  const runId = optionalString(data["run-id"], "run-id");
  const instruction = optionalString(data.instruction, "instruction");
  if (
    data.operation !== "agent native-startup" ||
    !identity ||
    !strandId ||
    !runId ||
    !instruction ||
    !["minted", "recovered", "attached"].includes(String(data.result))
  ) {
    throw new Error("Incomplete native startup response.");
  }
  return {
    identity,
    strandId,
    runId,
    instruction,
    result: data.result as NativeIdentityResult["result"],
    nativeSessionId: options.nativeSessionId,
    workspace,
  };
}

export function formatNativeIdentityState(
  state: NativeIdentityState,
  cwd: string,
): string {
  return JSON.stringify({ ...state, cwd }, null, 2);
}

export function nativeIdentityModel(
  ctx: Pick<ExtensionContext, "model">,
): string | undefined {
  return ctx.model ? `${ctx.model.provider}/${ctx.model.id}` : undefined;
}
