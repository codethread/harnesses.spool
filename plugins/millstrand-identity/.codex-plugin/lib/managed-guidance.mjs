#!/usr/bin/env node
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { lstatSync, readFileSync, readdirSync } from "node:fs";
import { isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const METADATA_MAX_BYTES = 64 * 1024;
export const BUNDLE_MAX_BYTES = 1024 * 1024;
export const CODEX_CONTEXT_MAX_BYTES = 3072;
const STDERR_MAX_BYTES = 64 * 1024;
const GUIDANCE_SCHEMA = "millstrand.agent-guidance-bootstrap/v1";
const BOOTSTRAP_SCHEMA = "millstrand.agent-managed-bootstrap/v1";
const BUNDLE_SCHEMA = "millstrand.agent-guidance-bundle/v1";
const RECEIPT_SCHEMA = "millstrand.agent-guidance-receipt/v1";
const RECEIPT_RESULT_SCHEMA = "millstrand.agent-guidance-receipt-result/v1";
const SHA256 = /^[a-f0-9]{64}$/;

function invalid(message) {
  throw new Error(`Invalid JSON: ${message}`);
}

function assertUnicode(value) {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) invalid("unpaired high surrogate");
      index += 1;
    } else if (code >= 0xdc00 && code <= 0xdfff)
      invalid("unpaired low surrogate");
  }
}

export function parseStrictJson(text, maxBytes) {
  if (Buffer.byteLength(text, "utf8") > maxBytes)
    invalid(`input exceeds ${maxBytes} bytes`);
  if (text.includes("\ufffd")) invalid("input is not valid UTF-8");
  assertUnicode(text);
  let position = 0;
  const whitespace = () => {
    while (/[\t\n\r ]/.test(text[position] ?? "")) position += 1;
  };
  const string = () => {
    if (text[position] !== '"') invalid(`expected string at byte ${position}`);
    const start = position++;
    while (position < text.length) {
      const character = text[position];
      if (character === '"') {
        position += 1;
        let parsed;
        try {
          parsed = JSON.parse(text.slice(start, position));
        } catch {
          invalid(`malformed string at byte ${start}`);
        }
        assertUnicode(parsed);
        return parsed;
      }
      if (character === "\\") position += 2;
      else {
        if ((character?.charCodeAt(0) ?? 0) < 0x20)
          invalid(`control character at byte ${position}`);
        position += 1;
      }
    }
    invalid(`unterminated string at byte ${start}`);
  };
  const value = () => {
    whitespace();
    const character = text[position];
    if (character === '"') return string();
    if (character === "{") {
      position += 1;
      whitespace();
      const result = Object.create(null);
      const keys = new Set();
      if (text[position] === "}") {
        position += 1;
        return result;
      }
      for (;;) {
        whitespace();
        const key = string();
        if (keys.has(key))
          invalid(`duplicate object key ${JSON.stringify(key)}`);
        keys.add(key);
        whitespace();
        if (text[position++] !== ":")
          invalid(`expected ':' at byte ${position - 1}`);
        result[key] = value();
        whitespace();
        if (text[position] === "}") {
          position += 1;
          return result;
        }
        if (text[position++] !== ",")
          invalid(`expected ',' at byte ${position - 1}`);
      }
    }
    if (character === "[") {
      position += 1;
      whitespace();
      const result = [];
      if (text[position] === "]") {
        position += 1;
        return result;
      }
      for (;;) {
        result.push(value());
        whitespace();
        if (text[position] === "]") {
          position += 1;
          return result;
        }
        if (text[position++] !== ",")
          invalid(`expected ',' at byte ${position - 1}`);
      }
    }
    for (const [literal, parsed] of [
      ["true", true],
      ["false", false],
      ["null", null],
    ]) {
      if (text.startsWith(literal, position)) {
        position += literal.length;
        return parsed;
      }
    }
    const match = text
      .slice(position)
      .match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
    if (!match) invalid(`unexpected token at byte ${position}`);
    position += match[0].length;
    const parsed = Number(match[0]);
    if (!Number.isFinite(parsed)) invalid("non-finite number");
    return parsed;
  };
  whitespace();
  const result = value();
  whitespace();
  if (position !== text.length) invalid(`excess data at byte ${position}`);
  return result;
}

export function canonicalJson(value) {
  if (value === null || typeof value === "boolean" || typeof value === "number")
    return JSON.stringify(value);
  if (typeof value === "string") {
    assertUnicode(value);
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(",")}}`;
  }
  throw new Error("Value is outside the JSON domain.");
}

export function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

export function sha256CanonicalJson(value) {
  return sha256(canonicalJson(value));
}

export function hashFile(path) {
  return sha256(readFileSync(path));
}

export function hashDirectory(
  root,
  { maximumFiles = 20_000, maximumBytes = 128 * 1024 * 1024 } = {},
) {
  const entries = [];
  let bytes = 0;
  const visit = (directory) => {
    for (const name of readdirSync(directory).sort()) {
      const path = join(directory, name);
      const stats = lstatSync(path, { throwIfNoEntry: true });
      if (stats.isSymbolicLink())
        throw new Error(`closure contains a symbolic link: ${path}`);
      if (stats.isDirectory()) visit(path);
      else if (stats.isFile()) {
        bytes += stats.size;
        if (entries.length >= maximumFiles || bytes > maximumBytes)
          throw new Error("closure exceeds its inspection bound");
        entries.push([relative(root, path), hashFile(path)]);
      }
    }
  };
  visit(root);
  return sha256CanonicalJson(entries);
}

function object(value, label) {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    throw new Error(`${label} must be one JSON object.`);
  return value;
}
function closed(value, allowed, required = allowed) {
  const keys = Object.keys(value);
  const allowedSet = new Set(allowed);
  if (
    keys.some((key) => !allowedSet.has(key)) ||
    required.some((key) => !Object.hasOwn(value, key))
  ) {
    throw new Error(
      `closed v1 schema key mismatch (actual: ${keys.sort().join(", ")}).`,
    );
  }
}
function nonblank(value, label) {
  if (typeof value !== "string" || !value.trim())
    throw new Error(`${label} must be nonblank.`);
  return value;
}
function digest(value, label) {
  const result = nonblank(value, label);
  if (!SHA256.test(result))
    throw new Error(`${label} must be a lowercase SHA-256 digest.`);
  return result;
}
function positive(value, label) {
  if (!Number.isSafeInteger(value) || value <= 0)
    throw new Error(`${label} must be a positive integral value.`);
  return value;
}
function absolute(value, label) {
  const result = nonblank(value, label);
  if (!isAbsolute(result) || resolve(result) !== result)
    throw new Error(`${label} must be canonical absolute.`);
  return result;
}
function canonicalIdentity(identity) {
  return `Your Millstrand identity is ${identity}. Use ${identity} for identity-bearing operations; pass \`--by-identity ${identity}\` explicitly. Do not invent another identity.`;
}

export function parseGuidance(raw, harness = "codex") {
  const value = object(
    parseStrictJson(raw, METADATA_MAX_BYTES),
    "managed guidance",
  );
  if (value.transport === "legacy") {
    closed(value, ["schema", "transport"]);
    if (value.schema !== GUIDANCE_SCHEMA)
      throw new Error("unsupported managed guidance schema");
    return { transport: "legacy" };
  }
  closed(value, [
    "schema",
    "transport",
    "run-id",
    "attempt",
    "invocation",
    "harness",
    "bundle-sha256",
    "capability-sha256",
  ]);
  if (
    value.schema !== GUIDANCE_SCHEMA ||
    value.transport !== "native-v1" ||
    value.harness !== harness
  ) {
    throw new Error(
      "unsupported managed guidance schema, transport, or provider",
    );
  }
  return {
    schema: GUIDANCE_SCHEMA,
    transport: "native-v1",
    "run-id": nonblank(value["run-id"], "run-id"),
    attempt: positive(value.attempt, "attempt"),
    invocation: nonblank(value.invocation, "invocation"),
    harness,
    "bundle-sha256": digest(value["bundle-sha256"], "bundle-sha256"),
    "capability-sha256": digest(
      value["capability-sha256"],
      "capability-sha256",
    ),
  };
}

function parseBootstrap(raw) {
  const value = object(
    parseStrictJson(raw, METADATA_MAX_BYTES),
    "managed bootstrap",
  );
  const allowed = [
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
  ];
  closed(value, allowed);
  if (
    value.schema !== BOOTSTRAP_SCHEMA ||
    value.harness !== "codex" ||
    value.scope !== "root"
  )
    throw new Error("managed bootstrap schema, provider, or scope is invalid");
  const bootstrap = {
    schema: BOOTSTRAP_SCHEMA,
    "run-id": nonblank(value["run-id"], "bootstrap run-id"),
    harness: "codex",
    identity: nonblank(value.identity, "bootstrap identity"),
    "reservation-id": nonblank(value["reservation-id"], "reservation-id"),
    cwd: absolute(value.cwd, "bootstrap cwd"),
    workspace: absolute(value.workspace, "bootstrap workspace"),
    attempt: positive(value.attempt, "bootstrap attempt"),
    invocation: nonblank(value.invocation, "bootstrap invocation"),
    scope: "root",
    "expected-native-session-id": nonblank(
      value["expected-native-session-id"],
      "expected-native-session-id",
    ),
  };
  return bootstrap;
}

class RouteFenceError extends Error {}

function validateBootstrapRoute(bootstrap, cwd) {
  if (bootstrap.cwd !== resolve(cwd))
    throw new RouteFenceError("managed bootstrap cwd fence mismatch");
}

function validateBootstrapFences(bootstrap, guidance, nativeSessionId) {
  for (const key of ["run-id", "attempt", "invocation"])
    if (bootstrap[key] !== guidance[key])
      throw new Error(`managed bootstrap ${key} fence mismatch`);
  if (bootstrap["expected-native-session-id"] !== nativeSessionId)
    throw new Error("managed bootstrap native session fence mismatch");
}

function scrubbedEnvironment() {
  const env = { ...process.env };
  for (const name of Object.keys(env)) {
    if (
      name === "MILLSTRAND_MANAGED_BOOTSTRAP" ||
      name === "MILLSTRAND_MANAGED_GUIDANCE" ||
      name === "MILLSTRAND_AGENT_ID" ||
      name === "MILLSTRAND_RUN_ID" ||
      name === "MILLSTRAND_WORKSPACE" ||
      name === "MILLSTRAND_RESERVATION_ID" ||
      name === "MILLSTRAND_IDENTITY_TRANSPORT" ||
      name.startsWith("MILLSTRAND_BOOTSTRAP_") ||
      name.endsWith("_RESERVATION_ID") ||
      name.endsWith("_IDENTITY_TRANSPORT")
    )
      delete env[name];
  }
  return env;
}

async function runStrand(args, cwd) {
  const executable =
    process.env.MILLSTRAND_CODEX_STRAND_BIN?.trim() || "strand";
  return await new Promise((resolvePromise, reject) => {
    const child = spawn(executable, args, {
      cwd,
      env: scrubbedEnvironment(),
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = Buffer.alloc(0);
    let stderr = Buffer.alloc(0);
    let done = false;
    const fail = (error) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      child.kill("SIGKILL");
      reject(error);
    };
    const append = (current, chunk, maximum, label) => {
      if (current.length + chunk.length > maximum) {
        fail(new Error(`Strand ${label} exceeded ${maximum} bytes`));
        return current;
      }
      return Buffer.concat([current, chunk]);
    };
    child.stdout.on("data", (chunk) => {
      stdout = append(stdout, chunk, BUNDLE_MAX_BYTES, "stdout");
    });
    child.stderr.on("data", (chunk) => {
      stderr = append(stderr, chunk, STDERR_MAX_BYTES, "stderr");
    });
    child.on("error", fail);
    child.on("close", (code) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try {
        resolvePromise({
          code: code ?? 1,
          stdout: new TextDecoder("utf-8", { fatal: true }).decode(stdout),
          stderr: new TextDecoder("utf-8", { fatal: true }).decode(stderr),
        });
      } catch (error) {
        reject(error);
      }
    });
    const timer = setTimeout(
      () => fail(new Error("Strand request exceeded 3 seconds")),
      3000,
    );
  });
}

function commandFailure(result, operation) {
  if (result.code === 0) return;
  const diagnostic = (result.stderr || result.stdout || "no diagnostic output")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 500);
  throw new Error(`${operation} failed (exit ${result.code}): ${diagnostic}`);
}

function parseBundle(stdout, guidance, bootstrap, nativeSessionId) {
  const value = object(
    parseStrictJson(stdout, BUNDLE_MAX_BYTES),
    "guidance bundle",
  );
  closed(value, [
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
  if (
    value.schema !== BUNDLE_SCHEMA ||
    value.operation !== "agent startup" ||
    value.harness !== "codex" ||
    value.transport !== "native-v1"
  )
    throw new Error(
      "guidance bundle schema, operation, provider, or transport is invalid",
    );
  const context = object(value.context, "managed context");
  closed(context, [
    "schema",
    "identity-instruction",
    "appended-system-prompts",
  ]);
  if (
    context.schema !== "millstrand.agent-managed-context/v1" ||
    !Array.isArray(context["appended-system-prompts"])
  )
    throw new Error("managed context schema or appends are invalid");
  for (const [index, append] of context["appended-system-prompts"].entries())
    if (typeof append !== "string" || !append.trim())
      throw new Error(
        `managed context append ${index} is blank or not a string`,
      );
  const bundle = {
    ...value,
    "run-id": nonblank(value["run-id"], "bundle run-id"),
    attempt: positive(value.attempt, "bundle attempt"),
    invocation: nonblank(value.invocation, "bundle invocation"),
    "native-session-id": nonblank(
      value["native-session-id"],
      "native-session-id",
    ),
    identity: nonblank(value.identity, "identity"),
    "strand-id": nonblank(value["strand-id"], "strand-id"),
    workspace: absolute(value.workspace, "workspace"),
    "bundle-sha256": digest(value["bundle-sha256"], "bundle-sha256"),
    "capability-sha256": digest(
      value["capability-sha256"],
      "capability-sha256",
    ),
    context,
  };
  for (const key of [
    "run-id",
    "attempt",
    "invocation",
    "harness",
    "bundle-sha256",
    "capability-sha256",
  ])
    if (bundle[key] !== guidance[key])
      throw new Error(`guidance bundle ${key} fence mismatch`);
  if (bundle.workspace !== bootstrap.workspace)
    throw new RouteFenceError("guidance bundle workspace fence mismatch");
  if (
    bundle["native-session-id"] !== nativeSessionId ||
    bundle.identity !== bootstrap.identity
  )
    throw new Error(
      "guidance bundle native session or identity fence mismatch",
    );
  if (context["identity-instruction"] !== canonicalIdentity(bundle.identity))
    throw new Error("guidance identity instruction is not canonical");
  if (
    sha256CanonicalJson([bundle["run-id"], bundle.workspace, context]) !==
    bundle["bundle-sha256"]
  )
    throw new Error("guidance bundle digest mismatch");
  return bundle;
}

function receiptBase(guidance, nativeSessionId) {
  return {
    schema: RECEIPT_SCHEMA,
    "run-id": guidance["run-id"],
    attempt: guidance.attempt,
    invocation: guidance.invocation,
    harness: "codex",
    "native-session-id": nativeSessionId,
    transport: "native-v1",
    "bundle-sha256": guidance["bundle-sha256"],
    "capability-sha256": guidance["capability-sha256"],
  };
}

function parseReceipt(stdout, guidance, nativeSessionId, state) {
  const value = object(
    parseStrictJson(stdout, METADATA_MAX_BYTES),
    "receipt result",
  );
  closed(value, [
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
  if (
    value.schema !== RECEIPT_RESULT_SCHEMA ||
    !["recorded", "replayed", "ignored"].includes(value.result) ||
    value.state !== state
  )
    throw new Error("receipt result schema, result, or state is invalid");
  for (const [key, expected] of Object.entries(
    receiptBase(guidance, nativeSessionId),
  ))
    if (key !== "schema" && value[key] !== expected)
      throw new Error(`receipt result ${key} fence mismatch`);
  if (value.result === "ignored")
    throw new Error("current receipt was ignored");
}

async function sendReceipt(
  kind,
  guidance,
  bootstrap,
  nativeSessionId,
  details = {},
) {
  const receipt = { ...receiptBase(guidance, nativeSessionId), ...details };
  const result = await runStrand(
    [
      "--workspace",
      bootstrap.workspace,
      "--cwd",
      bootstrap.cwd,
      "--timeout",
      "3s",
      "agent",
      "guidance",
      kind,
      "--receipt",
      JSON.stringify(receipt),
    ],
    bootstrap.cwd,
  );
  commandFailure(result, `guidance ${kind}`);
  parseReceipt(
    result.stdout,
    guidance,
    nativeSessionId,
    kind === "acknowledge" ? "acknowledged" : "failed",
  );
}

function stop(message) {
  const diagnostic = message.replace(/\s+/g, " ").trim().slice(0, 500);
  process.stdout.write(
    `${JSON.stringify({ continue: false, stopReason: diagnostic, systemMessage: `Millstrand managed guidance failed: ${diagnostic}` })}\n`,
  );
}

async function recordRuntimeFailure(nativeSessionId, cwd, code, diagnostic) {
  try {
    const guidance = parseGuidance(
      process.env.MILLSTRAND_MANAGED_GUIDANCE ?? "",
      "codex",
    );
    if (guidance.transport !== "native-v1")
      throw new Error("runtime failure was not fenced by native-v1 metadata");
    const bootstrap = parseBootstrap(
      process.env.MILLSTRAND_MANAGED_BOOTSTRAP ?? "",
    );
    validateBootstrapRoute(bootstrap, cwd);
    validateBootstrapFences(bootstrap, guidance, nativeSessionId);
    await sendReceipt("fail", guidance, bootstrap, nativeSessionId, {
      outcome: "failed",
      stage: "preflight",
      code,
      diagnostic: diagnostic.replace(/\s+/g, " ").slice(0, 500),
    });
    stop(diagnostic);
  } catch (error) {
    stop(
      `${diagnostic}; failure receipt was not recorded: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
}

async function handoff(nativeSessionId, cwd, eventName) {
  let guidance;
  let bootstrap;
  let receiptRouteTrusted = false;
  let stage = "startup";
  try {
    guidance = parseGuidance(
      process.env.MILLSTRAND_MANAGED_GUIDANCE ?? "",
      "codex",
    );
    if (guidance.transport !== "native-v1")
      throw new Error("native handoff was invoked without native-v1 selection");
    bootstrap = parseBootstrap(process.env.MILLSTRAND_MANAGED_BOOTSTRAP ?? "");
    stage = "validation";
    validateBootstrapRoute(bootstrap, cwd);
    receiptRouteTrusted = true;
    validateBootstrapFences(bootstrap, guidance, nativeSessionId);
    stage = "startup";
    const startup = await runStrand(
      [
        "--workspace",
        bootstrap.workspace,
        "--cwd",
        bootstrap.cwd,
        "--timeout",
        "3s",
        "agent",
        "startup",
        "codex",
        nativeSessionId,
        "--scope",
        "root",
        "--bootstrap",
        JSON.stringify(bootstrap),
        "--guidance",
        JSON.stringify(guidance),
      ],
      bootstrap.cwd,
    );
    commandFailure(startup, "managed guidance startup");
    stage = "validation";
    const bundle = parseBundle(
      startup.stdout,
      guidance,
      bootstrap,
      nativeSessionId,
    );
    stage = "rendering";
    const footer = `Current Millstrand run: ${bundle["run-id"]}. Pass --workspace ${JSON.stringify(bundle.workspace)} on Strand commands. This is the current managed guidance; earlier run guidance is historical.`;
    const context = [
      bundle.context["identity-instruction"],
      ...bundle.context["appended-system-prompts"],
      footer,
    ].join("\n\n");
    const bytes = Buffer.byteLength(context, "utf8");
    if (bytes > CODEX_CONTEXT_MAX_BYTES)
      throw new Error(
        `managed guidance contribution exceeds ${CODEX_CONTEXT_MAX_BYTES} bytes (${bytes})`,
      );
    stage = "handoff";
    await sendReceipt("acknowledge", guidance, bootstrap, nativeSessionId, {
      outcome: "adapter-handoff",
    });
    process.stdout.write(
      `${JSON.stringify({ hookSpecificOutput: { hookEventName: eventName, additionalContext: context } })}\n`,
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (error instanceof RouteFenceError) receiptRouteTrusted = false;
    if (
      guidance?.transport === "native-v1" &&
      bootstrap &&
      receiptRouteTrusted
    ) {
      try {
        await sendReceipt("fail", guidance, bootstrap, nativeSessionId, {
          outcome: "failed",
          stage,
          code: `${stage}-failed`,
          diagnostic: message.replace(/\s+/g, " ").slice(0, 500),
        });
      } catch (failureError) {
        stop(
          `${message}; failure receipt was not recorded: ${failureError instanceof Error ? failureError.message : String(failureError)}`,
        );
        return;
      }
    }
    stop(message);
  }
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const command = process.argv[2];
  if (command === "classify") {
    try {
      const guidance = parseGuidance(
        process.env.MILLSTRAND_MANAGED_GUIDANCE ?? "",
        "codex",
      );
      process.stdout.write(`${guidance.transport}\n`);
    } catch (error) {
      process.stderr.write(
        `${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 2;
    }
  } else if (command === "handoff") {
    await handoff(
      nonblank(process.argv[3], "native session ID"),
      absolute(process.argv[4], "cwd"),
      nonblank(process.argv[5], "event name"),
    );
  } else if (command === "fail") {
    await recordRuntimeFailure(
      nonblank(process.argv[3], "native session ID"),
      absolute(process.argv[4], "cwd"),
      nonblank(process.argv[5], "failure code"),
      nonblank(process.argv[6], "failure diagnostic"),
    );
  } else {
    process.stderr.write(
      "usage: managed-guidance.mjs classify | handoff SESSION CWD EVENT | fail SESSION CWD CODE DIAGNOSTIC\n",
    );
    process.exitCode = 64;
  }
}
