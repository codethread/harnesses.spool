/** Prompt-owner rendering/receipt utilities. Pi startup no longer selects or fetches managed guidance. */
import { spawn } from "node:child_process";
import { parseStrictJson } from "./strict-json.js";
import { wrapSystemReminder } from "./xml.js";

export const MANAGED_BOOTSTRAP_ENV = "MILLSTRAND_MANAGED_BOOTSTRAP";
export const MANAGED_GUIDANCE_ENV = "MILLSTRAND_MANAGED_GUIDANCE";
export const DEBUG_MANAGED_GUIDANCE_FLAG = "debug-managed-guidance";

const METADATA_MAX_BYTES = 64 * 1024;
const BUNDLE_MAX_BYTES = 1024 * 1024;
const PI_CONTEXT_MAX_BYTES = 65_536;
const STRAND_STDERR_MAX_BYTES = 64 * 1024;
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
  | {
      kind: "native-v1";
      metadata: ManagedGuidanceMetadata;
      bootstrap: ManagedBootstrap;
    };

export type ManagedGuidanceStage =
  "preflight" | "startup" | "validation" | "rendering" | "handoff";

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
