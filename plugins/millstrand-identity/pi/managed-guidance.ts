import { spawn } from "node:child_process";
import { isAbsolute, resolve } from "node:path";
import { parseStrictJson, sha256CanonicalJson } from "./strict-json.js";
import { wrapSystemReminder } from "./xml.js";

export const MANAGED_BOOTSTRAP_ENV = "MILLSTRAND_MANAGED_BOOTSTRAP";
export const MANAGED_GUIDANCE_ENV = "MILLSTRAND_MANAGED_GUIDANCE";
export const DEBUG_MANAGED_GUIDANCE_FLAG = "debug-managed-guidance";

const METADATA_MAX_BYTES = 64 * 1024;
const BUNDLE_MAX_BYTES = 1024 * 1024;
const PI_CONTEXT_MAX_BYTES = 65_536;
const STRAND_STDERR_MAX_BYTES = 64 * 1024;
const SHA256 = /^[a-f0-9]{64}$/;
const GUIDANCE_SCHEMA = "millstrand.agent-guidance-bootstrap/v1";
const BOOTSTRAP_SCHEMA = "millstrand.agent-managed-bootstrap/v1";
const BUNDLE_SCHEMA = "millstrand.agent-guidance-bundle/v1";
const RECEIPT_SCHEMA = "millstrand.agent-guidance-receipt/v1";
const RECEIPT_RESULT_SCHEMA = "millstrand.agent-guidance-receipt-result/v1";

export type ManagedGuidanceMetadata = {
  schema: typeof GUIDANCE_SCHEMA;
  transport: "native-v1";
  "run-id": string;
  attempt: number;
  invocation: string;
  harness: "pi";
  "bundle-sha256": string;
  "capability-sha256": string;
};

export type ManagedBootstrap = {
  schema: typeof BOOTSTRAP_SCHEMA;
  "run-id": string;
  harness: "pi";
  identity: string;
  "reservation-id": string;
  cwd: string;
  workspace: string;
  attempt: number;
  invocation: string;
  scope: "root";
  "expected-native-session-id": string;
};

export type ManagedGuidanceBundle = {
  schema: typeof BUNDLE_SCHEMA;
  operation: "agent startup";
  "run-id": string;
  attempt: number;
  invocation: string;
  harness: "pi";
  "native-session-id": string;
  identity: string;
  "strand-id": string;
  workspace: string;
  transport: "native-v1";
  "bundle-sha256": string;
  "capability-sha256": string;
  context: {
    schema: "millstrand.agent-managed-context/v1";
    "identity-instruction": string;
    "appended-system-prompts": string[];
  };
};

export type ManagedPiSelection =
  | { kind: "unmanaged" }
  | { kind: "legacy"; reason: string }
  | {
      kind: "native-v1";
      metadata: ManagedGuidanceMetadata;
      bootstrap: ManagedBootstrap;
    };

type NativeManagedPiSelection = Extract<
  ManagedPiSelection,
  { kind: "native-v1" }
>;
export type StagedManagedPiSelection =
  | { kind: "selected"; selection: ManagedPiSelection }
  | {
      kind: "rejected";
      selection: NativeManagedPiSelection;
      message: string;
      receiptRouteTrusted: boolean;
    };

export type ManagedGuidanceStage =
  "preflight" | "startup" | "validation" | "rendering" | "handoff";

export class ManagedGuidanceAdapterError extends Error {
  readonly stage: ManagedGuidanceStage;
  readonly receiptRouteTrusted: boolean;

  constructor(
    stage: ManagedGuidanceStage,
    message: string,
    receiptRouteTrusted = true,
  ) {
    super(message);
    this.name = "ManagedGuidanceAdapterError";
    this.stage = stage;
    this.receiptRouteTrusted = receiptRouteTrusted;
  }
}

class ManagedGuidanceRouteFenceError extends Error {}

function object(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`${label} must be one JSON object.`);
  }
  return value as Record<string, unknown>;
}

function closedKeys(
  value: Record<string, unknown>,
  allowed: string[],
  required = allowed,
) {
  const actual = Object.keys(value).sort();
  const allowedSet = new Set(allowed);
  if (
    actual.some((key) => !allowedSet.has(key)) ||
    required.some((key) => !Object.hasOwn(value, key))
  ) {
    throw new Error(
      `response keys do not match the closed v1 schema (actual: ${actual.join(", ")}).`,
    );
  }
}

function string(value: unknown, label: string): string {
  if (typeof value !== "string" || !value.trim())
    throw new Error(`${label} must be nonblank.`);
  return value;
}

function digest(value: unknown, label: string): string {
  const result = string(value, label);
  if (!SHA256.test(result))
    throw new Error(`${label} must be a lowercase SHA-256 digest.`);
  return result;
}

function positiveInteger(value: unknown, label: string): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) {
    throw new Error(`${label} must be a positive integral value.`);
  }
  return value as number;
}

function absolutePath(value: unknown, label: string): string {
  const path = string(value, label);
  if (!isAbsolute(path) || resolve(path) !== path)
    throw new Error(`${label} must be canonical absolute.`);
  return path;
}

function parseMetadata(
  raw: string,
): { transport: "legacy" } | ManagedGuidanceMetadata {
  const value = object(
    parseStrictJson(raw, METADATA_MAX_BYTES),
    MANAGED_GUIDANCE_ENV,
  );
  if (value.transport === "legacy") {
    closedKeys(value, ["schema", "transport"]);
    if (value.schema !== GUIDANCE_SCHEMA)
      throw new Error("managed guidance schema is unsupported.");
    return { transport: "legacy" };
  }
  closedKeys(value, [
    "schema",
    "transport",
    "run-id",
    "attempt",
    "invocation",
    "harness",
    "bundle-sha256",
    "capability-sha256",
  ]);
  if (value.schema !== GUIDANCE_SCHEMA || value.transport !== "native-v1") {
    throw new Error("managed guidance schema or transport is unsupported.");
  }
  if (value.harness !== "pi")
    throw new Error("managed guidance provider is not pi.");
  return {
    schema: GUIDANCE_SCHEMA,
    transport: "native-v1",
    "run-id": string(value["run-id"], "run-id"),
    attempt: positiveInteger(value.attempt, "attempt"),
    invocation: string(value.invocation, "invocation"),
    harness: "pi",
    "bundle-sha256": digest(value["bundle-sha256"], "bundle-sha256"),
    "capability-sha256": digest(
      value["capability-sha256"],
      "capability-sha256",
    ),
  };
}

function parseBootstrap(raw: string): ManagedBootstrap {
  const value = object(
    parseStrictJson(raw, METADATA_MAX_BYTES),
    MANAGED_BOOTSTRAP_ENV,
  );
  closedKeys(value, [
    "schema",
    "run-id",
    "harness",
    "identity",
    "reservation-id",
    "cwd",
    "workspace",
    "attempt",
    "invocation",
    "scope",
    "expected-native-session-id",
  ]);
  if (value.schema !== BOOTSTRAP_SCHEMA)
    throw new Error("managed bootstrap schema is unsupported.");
  if (value.harness !== "pi" || value.scope !== "root") {
    throw new Error("managed bootstrap provider or scope is invalid.");
  }
  const bootstrap: ManagedBootstrap = {
    schema: BOOTSTRAP_SCHEMA,
    "run-id": string(value["run-id"], "bootstrap run-id"),
    harness: "pi",
    identity: string(value.identity, "bootstrap identity"),
    "reservation-id": string(value["reservation-id"], "reservation-id"),
    cwd: absolutePath(value.cwd, "bootstrap cwd"),
    workspace: absolutePath(value.workspace, "bootstrap workspace"),
    attempt: positiveInteger(value.attempt, "bootstrap attempt"),
    invocation: string(value.invocation, "bootstrap invocation"),
    scope: "root",
    "expected-native-session-id": string(
      value["expected-native-session-id"],
      "expected-native-session-id",
    ),
  };
  return bootstrap;
}

export function stageManagedPiGuidanceSelection(
  nativeSessionId: string,
  cwd: string,
  env: NodeJS.ProcessEnv = process.env,
): StagedManagedPiSelection {
  if (env.PI_SUBAGENT?.trim() === "1") {
    return { kind: "selected", selection: { kind: "unmanaged" } };
  }
  const rawGuidance = env[MANAGED_GUIDANCE_ENV];
  if (rawGuidance === undefined) {
    return {
      kind: "selected",
      selection: env.MILLSTRAND_RUN_ID?.trim()
        ? {
            kind: "legacy",
            reason: "managed metadata has no guidance document",
          }
        : { kind: "unmanaged" },
    };
  }
  const metadata = parseMetadata(rawGuidance);
  if (metadata.transport === "legacy") {
    return {
      kind: "selected",
      selection: { kind: "legacy", reason: "legacy selected" },
    };
  }
  const rawBootstrap = env[MANAGED_BOOTSTRAP_ENV];
  if (rawBootstrap === undefined)
    throw new Error(`${MANAGED_BOOTSTRAP_ENV} is required for native-v1.`);
  const bootstrap = parseBootstrap(rawBootstrap);
  const selection: NativeManagedPiSelection = {
    kind: "native-v1",
    metadata,
    bootstrap,
  };
  const receiptRouteTrusted = bootstrap.cwd === resolve(cwd);
  for (const key of ["run-id", "attempt", "invocation"] as const) {
    if (bootstrap[key] !== metadata[key]) {
      return {
        kind: "rejected",
        selection,
        message: `managed bootstrap ${key} fence mismatch.`,
        receiptRouteTrusted,
      };
    }
  }
  if (bootstrap["expected-native-session-id"] !== nativeSessionId) {
    return {
      kind: "rejected",
      selection,
      message: "managed bootstrap native session fence mismatch.",
      receiptRouteTrusted,
    };
  }
  if (bootstrap.cwd !== resolve(cwd)) {
    return {
      kind: "rejected",
      selection,
      message: "managed bootstrap cwd fence mismatch.",
      receiptRouteTrusted: false,
    };
  }
  return { kind: "selected", selection };
}

export function selectManagedPiGuidance(
  nativeSessionId: string,
  cwd: string,
  env: NodeJS.ProcessEnv = process.env,
): ManagedPiSelection {
  const staged = stageManagedPiGuidanceSelection(nativeSessionId, cwd, env);
  if (staged.kind === "rejected") throw new Error(staged.message);
  return staged.selection;
}

function parseBundle(
  stdout: string,
  selection: Extract<ManagedPiSelection, { kind: "native-v1" }>,
  nativeSessionId: string,
): ManagedGuidanceBundle {
  const value = object(
    parseStrictJson(stdout, BUNDLE_MAX_BYTES),
    "guidance bundle",
  );
  closedKeys(value, [
    "schema",
    "operation",
    "run-id",
    "attempt",
    "invocation",
    "harness",
    "native-session-id",
    "identity",
    "strand-id",
    "workspace",
    "transport",
    "bundle-sha256",
    "capability-sha256",
    "context",
  ]);
  if (value.schema !== BUNDLE_SCHEMA || value.operation !== "agent startup") {
    throw new Error("guidance bundle schema or operation is invalid.");
  }
  if (value.harness !== "pi" || value.transport !== "native-v1") {
    throw new Error("guidance bundle provider or transport is invalid.");
  }
  const contextValue = object(value.context, "guidance context");
  closedKeys(contextValue, [
    "schema",
    "identity-instruction",
    "appended-system-prompts",
  ]);
  if (contextValue.schema !== "millstrand.agent-managed-context/v1") {
    throw new Error("managed context schema is invalid.");
  }
  if (!Array.isArray(contextValue["appended-system-prompts"])) {
    throw new Error("managed context appends must be a vector.");
  }
  const appends = contextValue["appended-system-prompts"].map(
    (entry, index) => {
      if (typeof entry !== "string" || !entry.trim()) {
        throw new Error(
          `managed context append ${index} must be a nonblank string.`,
        );
      }
      return entry;
    },
  );
  const bundle: ManagedGuidanceBundle = {
    schema: BUNDLE_SCHEMA,
    operation: "agent startup",
    "run-id": string(value["run-id"], "bundle run-id"),
    attempt: positiveInteger(value.attempt, "bundle attempt"),
    invocation: string(value.invocation, "bundle invocation"),
    harness: "pi",
    "native-session-id": string(
      value["native-session-id"],
      "native-session-id",
    ),
    identity: string(value.identity, "bundle identity"),
    "strand-id": string(value["strand-id"], "strand-id"),
    workspace: absolutePath(value.workspace, "bundle workspace"),
    transport: "native-v1",
    "bundle-sha256": digest(value["bundle-sha256"], "bundle-sha256"),
    "capability-sha256": digest(
      value["capability-sha256"],
      "capability-sha256",
    ),
    context: {
      schema: "millstrand.agent-managed-context/v1",
      "identity-instruction": string(
        contextValue["identity-instruction"],
        "identity-instruction",
      ),
      "appended-system-prompts": appends,
    },
  };
  const expected = selection.metadata;
  for (const key of [
    "run-id",
    "attempt",
    "invocation",
    "harness",
    "bundle-sha256",
    "capability-sha256",
  ] as const) {
    if (bundle[key] !== expected[key])
      throw new Error(`guidance bundle ${key} fence mismatch.`);
  }
  if (bundle["native-session-id"] !== nativeSessionId) {
    throw new Error("guidance bundle native session fence mismatch.");
  }
  if (bundle.workspace !== selection.bootstrap.workspace) {
    throw new ManagedGuidanceRouteFenceError(
      "guidance bundle workspace fence mismatch.",
    );
  }
  if (bundle.identity !== selection.bootstrap.identity) {
    throw new Error("guidance bundle identity fence mismatch.");
  }
  const calculated = sha256CanonicalJson([
    bundle["run-id"],
    bundle.workspace,
    bundle.context,
  ]);
  if (calculated !== bundle["bundle-sha256"])
    throw new Error("guidance bundle digest mismatch.");
  return bundle;
}

function childEnvironment(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const child = { ...env };
  for (const name of Object.keys(child)) {
    if (
      name === MANAGED_BOOTSTRAP_ENV ||
      name === MANAGED_GUIDANCE_ENV ||
      name === "MILLSTRAND_AGENT_ID" ||
      name === "MILLSTRAND_RUN_ID" ||
      name === "MILLSTRAND_WORKSPACE" ||
      name === "MILLSTRAND_RESERVATION_ID" ||
      name === "MILLSTRAND_IDENTITY_TRANSPORT" ||
      name.startsWith("MILLSTRAND_BOOTSTRAP_") ||
      name.endsWith("_RESERVATION_ID") ||
      name.endsWith("_IDENTITY_TRANSPORT")
    ) {
      delete child[name];
    }
  }
  return child;
}

export type BoundedCommand = (
  args: string[],
  options: { cwd: string; env: NodeJS.ProcessEnv; signal?: AbortSignal },
) => Promise<{ stdout: string; stderr: string; code: number }>;

export const runBoundedStrand: BoundedCommand = (args, options) =>
  new Promise((resolvePromise, reject) => {
    if (options.signal?.aborted) {
      reject(new Error("Strand request was aborted."));
      return;
    }
    const executable = options.env.MILLSTRAND_PI_STRAND_BIN?.trim() || "strand";
    const child = spawn(executable, args, {
      cwd: options.cwd,
      env: childEnvironment(options.env),
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout: Buffer = Buffer.alloc(0);
    let stderr: Buffer = Buffer.alloc(0);
    let settled = false;
    const finishError = (error: Error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", abort);
      child.kill("SIGKILL");
      reject(error);
    };
    const append = (
      current: Buffer,
      chunk: Buffer,
      maximum: number,
      label: string,
    ) => {
      if (current.length + chunk.length > maximum) {
        finishError(new Error(`Strand ${label} exceeded ${maximum} bytes.`));
        return current;
      }
      return Buffer.concat([current, chunk]);
    };
    child.stdout.on("data", (chunk: Buffer) => {
      stdout = append(stdout, chunk, BUNDLE_MAX_BYTES, "stdout");
    });
    child.stderr.on("data", (chunk: Buffer) => {
      stderr = append(stderr, chunk, STRAND_STDERR_MAX_BYTES, "stderr");
    });
    child.on("error", finishError);
    child.on("close", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", abort);
      try {
        resolvePromise({
          stdout: new TextDecoder("utf-8", { fatal: true }).decode(stdout),
          stderr: new TextDecoder("utf-8", { fatal: true }).decode(stderr),
          code: code ?? 1,
        });
      } catch (error) {
        reject(error);
      }
    });
    const abort = () => finishError(new Error("Strand request was aborted."));
    const timer = setTimeout(
      () => finishError(new Error("Strand request exceeded 3 seconds.")),
      3_000,
    );
    options.signal?.addEventListener("abort", abort, { once: true });
  });

function strandBase(
  selection: Extract<ManagedPiSelection, { kind: "native-v1" }>,
): string[] {
  return [
    "--workspace",
    selection.bootstrap.workspace,
    "--cwd",
    selection.bootstrap.cwd,
    "--timeout",
    "3s",
  ];
}

function commandFailure(
  result: { stdout: string; stderr: string; code: number },
  operation: string,
) {
  if (result.code === 0) return;
  const diagnostic = (result.stderr || result.stdout || "no diagnostic output")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 500);
  throw new Error(`${operation} failed (exit ${result.code}): ${diagnostic}`);
}

function receiptBase(
  selection: Extract<ManagedPiSelection, { kind: "native-v1" }>,
  nativeSessionId: string,
) {
  return {
    schema: RECEIPT_SCHEMA,
    "run-id": selection.metadata["run-id"],
    attempt: selection.metadata.attempt,
    invocation: selection.metadata.invocation,
    harness: "pi",
    "native-session-id": nativeSessionId,
    transport: "native-v1",
    "bundle-sha256": selection.metadata["bundle-sha256"],
    "capability-sha256": selection.metadata["capability-sha256"],
  };
}

function validateReceiptResult(
  stdout: string,
  selection: Extract<ManagedPiSelection, { kind: "native-v1" }>,
  nativeSessionId: string,
) {
  const value = object(
    parseStrictJson(stdout, METADATA_MAX_BYTES),
    "guidance receipt result",
  );
  closedKeys(value, [
    "schema",
    "result",
    "state",
    "run-id",
    "attempt",
    "invocation",
    "harness",
    "native-session-id",
    "transport",
    "bundle-sha256",
    "capability-sha256",
  ]);
  if (value.schema !== RECEIPT_RESULT_SCHEMA)
    throw new Error("receipt result schema is invalid.");
  if (!["recorded", "replayed", "ignored"].includes(String(value.result))) {
    throw new Error("receipt result is invalid.");
  }
  if (
    !["pending", "fetched", "acknowledged", "failed"].includes(
      String(value.state),
    )
  ) {
    throw new Error("receipt state is invalid.");
  }
  const expected = receiptBase(selection, nativeSessionId);
  for (const [key, expectedValue] of Object.entries(expected)) {
    if (key === "schema") continue;
    if (value[key] !== expectedValue)
      throw new Error(`receipt result ${key} fence mismatch.`);
  }
  return value;
}

export async function fetchManagedGuidance(
  selection: Extract<ManagedPiSelection, { kind: "native-v1" }>,
  nativeSessionId: string,
  command: BoundedCommand = runBoundedStrand,
  env: NodeJS.ProcessEnv = process.env,
  signal?: AbortSignal,
): Promise<ManagedGuidanceBundle> {
  const args = [
    ...strandBase(selection),
    "agent",
    "startup",
    "pi",
    nativeSessionId,
    "--scope",
    "root",
    "--bootstrap",
    JSON.stringify(selection.bootstrap),
    "--guidance",
    JSON.stringify(selection.metadata),
  ];
  let result: Awaited<ReturnType<BoundedCommand>>;
  try {
    result = await command(args, { cwd: selection.bootstrap.cwd, env, signal });
    commandFailure(result, "Strand managed guidance startup");
  } catch (error) {
    throw new ManagedGuidanceAdapterError(
      "startup",
      error instanceof Error ? error.message : String(error),
    );
  }
  try {
    return parseBundle(result.stdout, selection, nativeSessionId);
  } catch (error) {
    throw new ManagedGuidanceAdapterError(
      "validation",
      error instanceof Error ? error.message : String(error),
      !(error instanceof ManagedGuidanceRouteFenceError),
    );
  }
}

export async function acknowledgeManagedGuidance(
  selection: Extract<ManagedPiSelection, { kind: "native-v1" }>,
  nativeSessionId: string,
  command: BoundedCommand = runBoundedStrand,
  env: NodeJS.ProcessEnv = process.env,
  signal?: AbortSignal,
) {
  const receipt = {
    ...receiptBase(selection, nativeSessionId),
    outcome: "adapter-handoff",
  };
  const result = await command(
    [
      ...strandBase(selection),
      "agent",
      "guidance",
      "acknowledge",
      "--receipt",
      JSON.stringify(receipt),
    ],
    { cwd: selection.bootstrap.cwd, env, signal },
  );
  commandFailure(result, "Strand managed guidance acknowledgement");
  const response = validateReceiptResult(
    result.stdout,
    selection,
    nativeSessionId,
  );
  if (response.result === "ignored" || response.state !== "acknowledged") {
    throw new Error(
      "Strand did not accept the current adapter-handoff receipt.",
    );
  }
}

export async function failManagedGuidance(
  selection: Extract<ManagedPiSelection, { kind: "native-v1" }>,
  nativeSessionId: string,
  stage: ManagedGuidanceStage,
  code: string,
  diagnostic: string,
  command: BoundedCommand = runBoundedStrand,
  env: NodeJS.ProcessEnv = process.env,
  signal?: AbortSignal,
) {
  const receipt = {
    ...receiptBase(selection, nativeSessionId),
    outcome: "failed",
    stage,
    code,
    diagnostic: diagnostic.replace(/\s+/g, " ").trim().slice(0, 500),
  };
  const result = await command(
    [
      ...strandBase(selection),
      "agent",
      "guidance",
      "fail",
      "--receipt",
      JSON.stringify(receipt),
    ],
    { cwd: selection.bootstrap.cwd, env, signal },
  );
  commandFailure(result, "Strand managed guidance failure recording");
  const response = validateReceiptResult(
    result.stdout,
    selection,
    nativeSessionId,
  );
  if (response.result === "ignored" || response.state !== "failed") {
    throw new Error(
      "Strand did not record the current managed guidance failure.",
    );
  }
}

export function renderManagedGuidance(bundle: ManagedGuidanceBundle): string {
  const footer = `Current Millstrand run: ${bundle["run-id"]}. Pass --workspace ${JSON.stringify(bundle.workspace)} on Strand commands. This is the current managed guidance; earlier run guidance is historical.`;
  const text = [
    bundle.context["identity-instruction"],
    ...bundle.context["appended-system-prompts"],
    footer,
  ].join("\n\n");
  const contribution = wrapSystemReminder("millstrand-managed-guidance", text);
  const bytes = Buffer.byteLength(contribution, "utf8");
  if (bytes > PI_CONTEXT_MAX_BYTES) {
    throw new Error(
      `managed guidance contribution exceeds ${PI_CONTEXT_MAX_BYTES} bytes (${bytes}).`,
    );
  }
  return contribution;
}

export function formatManagedGuidanceDebug(
  selection: ManagedPiSelection,
  bundle: ManagedGuidanceBundle | null,
  error?: string,
): string {
  return JSON.stringify(
    {
      selection: selection.kind,
      ...(selection.kind === "native-v1"
        ? {
            "run-id": selection.metadata["run-id"],
            attempt: selection.metadata.attempt,
            invocation: selection.metadata.invocation,
            "bundle-sha256": selection.metadata["bundle-sha256"],
            "capability-sha256": selection.metadata["capability-sha256"],
          }
        : {}),
      fetched: bundle !== null,
      ...(bundle
        ? { identity: bundle.identity, workspace: bundle.workspace }
        : {}),
      ...(error ? { error } : {}),
    },
    null,
    2,
  );
}
