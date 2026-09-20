#!/usr/bin/env node
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import {
  cpSync,
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  readlinkSync,
  realpathSync,
  rmSync,
  lstatSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  canonicalJson,
  hashFile,
  parseGuidance,
  parseStrictJson,
  sha256CanonicalJson,
} from "../lib/managed-guidance.mjs";

const runnerPath = fileURLToPath(import.meta.url);
const conformanceRoot = dirname(runnerPath);
const pluginRoot = resolve(conformanceRoot, "../..");
const payloadRoot = join(conformanceRoot, "payloads");
const identityHook = join(pluginRoot, ".codex-plugin/hooks/identity.sh");
const fakeStrand = join(conformanceRoot, "fake-strand.sh");
const fakeGuidanceStrand = join(conformanceRoot, "fake-guidance-strand.mjs");
const managedGuidancePreflight = resolve(
  pluginRoot,
  "../../scripts/managed-guidance-preflight.mjs",
);
const expectedCodexVersion = "codex-cli 0.154.0";
const schemaRoot = join(conformanceRoot, "schemas");
const inputSchemas = {
  SessionStart: JSON.parse(
    readFileSync(
      join(schemaRoot, "session-start.command.input.schema.json"),
      "utf8",
    ),
  ),
  SubagentStart: JSON.parse(
    readFileSync(
      join(schemaRoot, "subagent-start.command.input.schema.json"),
      "utf8",
    ),
  ),
};
const outputSchemas = {
  SessionStart: JSON.parse(
    readFileSync(
      join(schemaRoot, "session-start.command.output.schema.json"),
      "utf8",
    ),
  ),
  SubagentStart: JSON.parse(
    readFileSync(
      join(schemaRoot, "subagent-start.command.output.schema.json"),
      "utf8",
    ),
  ),
};
const temporaryDirectories = [];
const activeChildren = new Set();
let environmentRoot;

function temporaryDirectory(prefix) {
  const directory = mkdtempSync(join(tmpdir(), prefix));
  temporaryDirectories.push(directory);
  return directory;
}

async function waitForFile(path, timeout = 2_000) {
  const deadline = Date.now() + timeout;
  while (!existsSync(path)) {
    assert.ok(Date.now() < deadline, `timed out waiting for ${path}`);
    await new Promise((resolvePromise) => setImmediate(resolvePromise));
  }
}

function trackChild(child) {
  activeChildren.add(child);
  child.once("close", () => activeChildren.delete(child));
  return child;
}

function processExists(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error.code === "ESRCH") return false;
    throw error;
  }
}

async function waitForProcessExit(pid, timeout = 2_000) {
  const deadline = Date.now() + timeout;
  while (processExists(pid) && Date.now() < deadline) {
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 10));
  }
}

function terminateProcessTree(child) {
  if (child.pid === undefined) return;
  try {
    process.kill(
      process.platform === "win32" ? child.pid : -child.pid,
      "SIGKILL",
    );
  } catch (error) {
    if (error.code !== "ESRCH") throw error;
  }
}

function cleanup() {
  for (const child of activeChildren) terminateProcessTree(child);
  activeChildren.clear();
  while (temporaryDirectories.length > 0) {
    rmSync(temporaryDirectories.pop(), {
      recursive: true,
      force: true,
      maxRetries: 5,
      retryDelay: 20,
    });
  }
}

for (const signal of ["SIGINT", "SIGTERM"]) {
  const handler = () => {
    process.off(signal, handler);
    try {
      cleanup();
    } finally {
      process.kill(process.pid, signal);
    }
  };
  process.on(signal, handler);
}

function run(
  command,
  args,
  {
    input = "",
    env = process.env,
    timeout = 10_000,
    onSpawn,
    cwd = env.HOME,
  } = {},
) {
  return new Promise((resolvePromise, reject) => {
    const child = trackChild(
      spawn(command, args, {
        detached: process.platform !== "win32",
        env,
        cwd,
        stdio: ["pipe", "pipe", "pipe"],
      }),
    );
    onSpawn?.(child);
    let stdout = "";
    let stderr = "";
    let terminalError;
    let terminalErrorSource;
    const recordTerminalError = (error, source) => {
      if (terminalError) return;
      terminalError = error;
      terminalErrorSource = source;
    };
    const stopWithError = (error) => {
      recordTerminalError(error, "execution");
      terminateProcessTree(child);
    };
    const timer = setTimeout(() => {
      stopWithError(new Error(`${command} timed out after ${timeout}ms`));
    }, timeout);

    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
      if (stdout.length > 1_000_000) {
        stopWithError(new Error(`${command} stdout was unbounded`));
      }
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
      if (stderr.length > 1_000_000) {
        stopWithError(new Error(`${command} stderr was unbounded`));
      }
    });
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.stdin.on("error", (error) => {
      recordTerminalError(error, "stdin");
    });
    child.on("close", (code, signal) => {
      clearTimeout(timer);
      if (terminalErrorSource === "stdin") {
        const invocation = JSON.stringify([command, ...args]);
        const outcome = `code ${String(code)}, signal ${String(signal)}`;
        reject(
          new Error(
            `${invocation} stdin failed: ${terminalError.message}; child outcome: ${outcome}; stderr: ${stderr || "<empty>"}`,
            { cause: terminalError },
          ),
        );
      } else if (terminalError) reject(terminalError);
      else resolvePromise({ code, signal, stdout, stderr });
    });
    child.stdin.end(input);
  });
}

async function checkEarlyStdinCloseReporting() {
  const diagnostic = "fixture closed stdin before consuming its payload";
  const closeStdin = [
    "process.stdin.destroy();",
    `process.stderr.write(${JSON.stringify(`${diagnostic}\n`)});`,
    "setTimeout(() => process.exit(23), 20);",
  ].join("");
  await assert.rejects(
    run(process.execPath, ["-e", closeStdin], {
      input: "x".repeat(1_000_000),
    }),
    (error) => {
      assert.match(error.message, /stdin failed: write EPIPE/);
      assert.match(error.message, /child outcome: code 23, signal null/);
      assert.match(error.message, new RegExp(diagnostic));
      assert.match(error.message, /process\.stdin\.destroy/);
      return true;
    },
  );
}

function parseJsonLines(text, label) {
  return text
    .trim()
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch (error) {
        throw new Error(`${label} contains invalid JSON: ${error.message}`);
      }
    });
}

function parseSingleJsonLine(text, label) {
  const lines = parseJsonLines(text, label);
  assert.equal(lines.length, 1, `${label} must emit exactly one JSON line`);
  return lines[0];
}

function assertMatchesSchema(value, schema, rootSchema, label) {
  if (schema.$ref) {
    const definitionName = schema.$ref.replace("#/definitions/", "");
    assertMatchesSchema(
      value,
      rootSchema.definitions[definitionName],
      rootSchema,
      label,
    );
    return;
  }
  if (schema.allOf) {
    for (const member of schema.allOf)
      assertMatchesSchema(value, member, rootSchema, label);
    return;
  }
  if (schema.const !== undefined) assert.equal(value, schema.const, label);
  if (schema.enum)
    assert.ok(schema.enum.includes(value), `${label} is outside its enum`);
  if (Array.isArray(schema.type)) {
    const matches = schema.type.some((type) =>
      type === "null" ? value === null : typeof value === type,
    );
    assert.ok(matches, `${label} has the wrong type`);
  } else if (schema.type === "object") {
    assert.ok(
      value !== null && typeof value === "object" && !Array.isArray(value),
      label,
    );
    for (const required of schema.required ?? []) {
      assert.ok(required in value, `${label}.${required} is required`);
    }
    if (schema.additionalProperties === false) {
      for (const key of Object.keys(value)) {
        assert.ok(
          key in schema.properties,
          `${label}.${key} is not in the pinned schema`,
        );
      }
    }
    for (const [key, member] of Object.entries(schema.properties ?? {})) {
      if (key in value)
        assertMatchesSchema(value[key], member, rootSchema, `${label}.${key}`);
    }
  } else if (schema.type) {
    assert.equal(typeof value, schema.type, label);
  }
}

function assertHookOutput(output, eventName) {
  assertMatchesSchema(
    output,
    outputSchemas[eventName],
    outputSchemas[eventName],
    `${eventName} output`,
  );
  assert.deepEqual(Object.keys(output), ["hookSpecificOutput"]);
  assert.equal(output.hookSpecificOutput.hookEventName, eventName);
  assert.equal(typeof output.hookSpecificOutput.additionalContext, "string");
  assert.ok(output.hookSpecificOutput.additionalContext.length > 0);
}

function fixtureEnvironment(extra = {}) {
  // Only PATH comes from the caller: no credentials, shared workspace, shell
  // startup scripts, or ambient fake modes may influence a disposable replay.
  environmentRoot ??= temporaryDirectory("codex-hook-environment-");
  const env = {
    PATH: process.env.PATH,
    LANG: "C.UTF-8",
    HOME: join(environmentRoot, "home"),
    CODEX_HOME: join(environmentRoot, "codex"),
    XDG_CONFIG_HOME: join(environmentRoot, "config"),
    XDG_STATE_HOME: join(environmentRoot, "state"),
    XDG_CACHE_HOME: join(environmentRoot, "cache"),
    XDG_RUNTIME_DIR: join(environmentRoot, "runtime"),
    TMPDIR: join(environmentRoot, "tmp"),
    PLUGIN_ROOT: pluginRoot,
    ...extra,
  };
  for (const name of [
    "HOME",
    "CODEX_HOME",
    "XDG_CONFIG_HOME",
    "XDG_STATE_HOME",
    "XDG_CACHE_HOME",
    "XDG_RUNTIME_DIR",
    "TMPDIR",
  ]) {
    mkdirSync(env[name], { recursive: true });
  }
  return env;
}

function checkStrictJsonRegression() {
  const parsed = parseStrictJson(
    '{"__proto__":{"polluted":true},"nested":{"__proto__":{"deep":true}}}',
    1024,
  );
  assert.equal(Object.getPrototypeOf(parsed), null);
  assert.equal(Object.getPrototypeOf(parsed.__proto__), null);
  assert.equal(Object.getPrototypeOf(parsed.nested), null);
  assert.equal(Object.getPrototypeOf(parsed.nested.__proto__), null);
  assert.equal(Object.hasOwn(parsed, "__proto__"), true);
  assert.equal(Object.hasOwn(parsed.nested, "__proto__"), true);
  assert.equal({}.polluted, undefined);
  assert.equal(
    canonicalJson(parsed),
    '{"__proto__":{"polluted":true},"nested":{"__proto__":{"deep":true}}}',
  );
  assert.throws(
    () => parseStrictJson('{"key":1,"\\u006bey":2}', 1024),
    /duplicate object key "key"/,
  );
  const inheritedGuidance = {
    schema: "millstrand.agent-guidance-bootstrap/v1",
    transport: "native-v1",
    "run-id": "inherited-run",
    attempt: 1,
    invocation: "inherited-invocation",
    harness: "codex",
    "bundle-sha256": "a".repeat(64),
    "capability-sha256": "b".repeat(64),
  };
  assert.throws(
    () => parseGuidance(`{"__proto__":${JSON.stringify(inheritedGuidance)}}`),
    /closed v1 schema key mismatch/,
  );
}

async function checkHostKillRecovery(payloadText) {
  const directory = temporaryDirectory("codex-hook-sigkill-recovery-");
  const stateDirectory = join(directory, "state");
  const gate = join(directory, "gate");
  const ready = join(directory, "ready");
  const fakePidFile = join(directory, "fake.pid");
  const logPath = join(directory, "fake-strand.jsonl");
  mkdirSync(stateDirectory);
  const fifo = await run("mkfifo", [gate], { env: fixtureEnvironment() });
  assert.equal(fifo.code, 0, fifo.stderr);

  const environment = fixtureEnvironment({
    XDG_STATE_HOME: stateDirectory,
    TMPDIR: directory,
    MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
    FAKE_STRAND_LOG: logPath,
  });
  const child = trackChild(
    spawn("bash", [identityHook, "--configured-source"], {
      detached: true,
      env: {
        ...environment,
        FAKE_STRAND_MODE: "hold",
        FAKE_STRAND_GATE: gate,
        FAKE_STRAND_READY: ready,
        FAKE_STRAND_PID_FILE: fakePidFile,
      },
      stdio: ["pipe", "pipe", "pipe"],
    }),
  );
  child.stdin.end(payloadText);
  await waitForFile(ready);
  await waitForFile(fakePidFile);
  const fakePid = Number.parseInt(readFileSync(fakePidFile, "utf8"), 10);
  assert.equal(processExists(fakePid), true);
  const closed = new Promise((resolvePromise) =>
    child.once("close", (code, closeSignal) =>
      resolvePromise({ code, closeSignal }),
    ),
  );
  process.kill(-child.pid, "SIGKILL");
  assert.deepEqual(await closed, { code: null, closeSignal: "SIGKILL" });
  await waitForProcessExit(fakePid);
  assert.equal(
    processExists(fakePid),
    false,
    "host SIGKILL must terminate the Strand child",
  );

  const recovered = await run("bash", [identityHook, "--configured-source"], {
    input: payloadText,
    env: environment,
  });
  assert.equal(recovered.code, 0, recovered.stderr);
  assertHookOutput(
    parseSingleJsonLine(recovered.stdout, "post-SIGKILL recovery response"),
    "SessionStart",
  );
  assert.equal(
    parseJsonLines(readFileSync(logPath, "utf8"), "post-SIGKILL fake calls")
      .length,
    2,
    "a healthy replay must reach Strand after the abandoned OS lock is released",
  );
}

function managedGuidanceEnvironment(
  payload,
  runId = "managed-current",
  attempt = 1,
) {
  const identity = "fixture-managed-identity";
  const workspace = join(payload.cwd, ".millstrand");
  const context = {
    schema: "millstrand.agent-managed-context/v1",
    "identity-instruction": `Your Millstrand identity is ${identity}. Use it as \`--owner ${identity}\` for \`kanban claim\` and \`--by-identity ${identity}\` for Kanban notes, workflow mutations, and agent operations. Keep \`--identity\` and \`--parent-identity\` for native-session references. Inspect live help; never pass an unsupported flag or invent another identity.`,
    "appended-system-prompts": [
      "first frozen contribution",
      "intentionally repeated",
      "intentionally repeated",
    ],
  };
  const invocation = `invocation-${runId}-${attempt}`;
  const guidance = {
    schema: "millstrand.agent-guidance-bootstrap/v1",
    transport: "native-v1",
    "run-id": runId,
    attempt,
    invocation,
    harness: "codex",
    "bundle-sha256": sha256CanonicalJson([runId, workspace, context]),
    "capability-sha256": "a".repeat(64),
  };
  const bootstrap = {
    schema: "millstrand.agent-managed-bootstrap/v1",
    "run-id": runId,
    harness: "codex",
    identity,
    "reservation-id": `reservation-${runId}`,
    cwd: payload.cwd,
    workspace,
    attempt,
    invocation,
    scope: "root",
    "expected-native-session-id": payload.session_id,
  };
  return {
    MILLSTRAND_AGENT_ID: identity,
    MILLSTRAND_RUN_ID: runId,
    MILLSTRAND_MANAGED_GUIDANCE: JSON.stringify(guidance),
    MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify(bootstrap),
  };
}

async function checkManagedGuidanceReplay() {
  for (const [index, fileName] of [
    "session-start-startup.json",
    "session-start-resume.json",
    "session-start-clear.json",
    "session-start-compact.json",
  ].entries()) {
    const sourcePayload = JSON.parse(
      readFileSync(join(payloadRoot, fileName), "utf8"),
    );
    const directory = temporaryDirectory("codex-managed-guidance-");
    const payload = { ...sourcePayload, cwd: join(directory, "host-cwd") };
    mkdirSync(join(payload.cwd, ".millstrand"), { recursive: true });
    const payloadText = JSON.stringify(payload);
    const logPath = join(directory, "calls.jsonl");
    const runId = `current-${index}`;
    const environment = fixtureEnvironment({
      ...managedGuidanceEnvironment(payload, runId),
      MILLSTRAND_CODEX_STRAND_BIN: fakeGuidanceStrand,
      FAKE_GUIDANCE_LOG: logPath,
      TMPDIR: directory,
    });
    const result = await run("bash", [identityHook, "--configured-source"], {
      input: payloadText,
      env: environment,
    });
    assert.equal(result.code, 0, result.stderr);
    const output = parseSingleJsonLine(
      result.stdout,
      `${fileName} managed output`,
    );
    assertHookOutput(output, "SessionStart");
    const context = output.hookSpecificOutput.additionalContext;
    assert.ok(
      context.startsWith(
        "Your Millstrand identity is fixture-managed-identity.",
      ),
    );
    assert.equal(context.match(/first frozen contribution/g)?.length, 1);
    assert.equal(context.match(/intentionally repeated/g)?.length, 2);
    assert.equal(context.match(/Current Millstrand run:/g)?.length, 1);
    assert.match(context, new RegExp(`Current Millstrand run: ${runId}\\.`));
    const calls = parseJsonLines(
      readFileSync(logPath, "utf8"),
      `${fileName} managed calls`,
    );
    assert.equal(
      calls.length,
      2,
      "each reconstruction must fetch and acknowledge",
    );
    assert.deepEqual(
      calls.map((call) => call.operation.slice(0, 3)),
      [
        ["agent", "startup", "codex"],
        ["agent", "guidance", "acknowledge"],
      ],
    );
    assert.deepEqual(
      calls.flatMap((call) => call.managedNames),
      [],
      "Strand children must receive scrubbed ownership",
    );

    const replay = await run("bash", [identityHook, "--configured-source"], {
      input: payloadText,
      env: { ...environment, FAKE_GUIDANCE_MODE: "ack-replay" },
    });
    assertHookOutput(
      parseSingleJsonLine(replay.stdout, `${fileName} replay output`),
      "SessionStart",
    );
    assert.equal(
      parseJsonLines(readFileSync(logPath, "utf8"), `${fileName} replay calls`)
        .length,
      4,
      "receipt replay must not suppress reconstruction",
    );
  }

  const sourcePayload = JSON.parse(
    readFileSync(join(payloadRoot, "session-start-startup.json"), "utf8"),
  );
  const sourceProbeRoot = temporaryDirectory("codex-managed-source-probe-");
  const sourceProbePlugin = join(sourceProbeRoot, "harness");
  cpSync(pluginRoot, sourceProbePlugin, { recursive: true });
  writeFileSync(
    join(sourceProbePlugin, ".codex-plugin/hooks/identity-sources.sh"),
    "#!/usr/bin/env bash\nprintf '%s\\n' '{\"configured\":\"invalid\"}'\n",
  );
  const sourceProbePayload = {
    ...sourcePayload,
    cwd: join(sourceProbeRoot, "host-cwd"),
  };
  mkdirSync(join(sourceProbePayload.cwd, ".millstrand"), { recursive: true });
  const sourceProbeLog = join(sourceProbeRoot, "guidance-calls.jsonl");
  const sourceProbeManagedEnvironment = managedGuidanceEnvironment(
    sourceProbePayload,
    "managed-source-probe",
  );
  const sourceProbeGuidance = JSON.parse(
    sourceProbeManagedEnvironment.MILLSTRAND_MANAGED_GUIDANCE,
  );
  const sourceProbeResult = await run(
    "bash",
    [join(sourceProbePlugin, ".codex-plugin/hooks/identity.sh")],
    {
      input: JSON.stringify(sourceProbePayload),
      env: fixtureEnvironment({
        ...sourceProbeManagedEnvironment,
        MILLSTRAND_FOO_RESERVATION_ID: "inherited-suffixed-reservation",
        MILLSTRAND_FOO_IDENTITY_TRANSPORT: "inherited-suffixed-transport",
        MILLSTRAND_CODEX_STRAND_BIN: fakeGuidanceStrand,
        FAKE_GUIDANCE_LOG: sourceProbeLog,
        TMPDIR: sourceProbeRoot,
      }),
    },
  );
  assert.equal(sourceProbeResult.code, 0, sourceProbeResult.stderr);
  const sourceProbeOutput = parseSingleJsonLine(
    sourceProbeResult.stdout,
    "managed malformed source probe",
  );
  assert.equal(sourceProbeOutput.continue, false);
  assert.equal("hookSpecificOutput" in sourceProbeOutput, false);
  const sourceProbeCalls = parseJsonLines(
    readFileSync(sourceProbeLog, "utf8"),
    "managed malformed source probe calls",
  );
  assert.equal(sourceProbeCalls.length, 1);
  assert.deepEqual(sourceProbeCalls[0].operation.slice(0, 3), [
    "agent",
    "guidance",
    "fail",
  ]);
  const sourceProbeReceiptIndex =
    sourceProbeCalls[0].operation.indexOf("--receipt");
  assert.notEqual(sourceProbeReceiptIndex, -1);
  const sourceProbeReceipt = JSON.parse(
    sourceProbeCalls[0].operation[sourceProbeReceiptIndex + 1],
  );
  assert.deepEqual(sourceProbeReceipt, {
    schema: "millstrand.agent-guidance-receipt/v1",
    "run-id": sourceProbeGuidance["run-id"],
    attempt: sourceProbeGuidance.attempt,
    invocation: sourceProbeGuidance.invocation,
    harness: "codex",
    "native-session-id": sourceProbePayload.session_id,
    transport: "native-v1",
    "bundle-sha256": sourceProbeGuidance["bundle-sha256"],
    "capability-sha256": sourceProbeGuidance["capability-sha256"],
    outcome: "failed",
    stage: "preflight",
    code: "unverifiable-profile",
    diagnostic:
      "Millstrand identity startup received an invalid configured-source result.",
  });
  assert.deepEqual(sourceProbeCalls[0].managedNames, []);

  for (const mismatch of [
    "run-id",
    "attempt",
    "invocation",
    "session",
    "cwd",
  ]) {
    const mismatchLog = join(sourceProbeRoot, `${mismatch}-fence-calls.jsonl`);
    const mismatchEnvironment = managedGuidanceEnvironment(
      sourceProbePayload,
      `managed-source-probe-${mismatch}`,
    );
    const bootstrap = JSON.parse(
      mismatchEnvironment.MILLSTRAND_MANAGED_BOOTSTRAP,
    );
    if (mismatch === "run-id") bootstrap["run-id"] = "mismatched-run";
    else if (mismatch === "attempt") bootstrap.attempt += 1;
    else if (mismatch === "invocation")
      bootstrap.invocation = "mismatched-invocation";
    else if (mismatch === "session")
      bootstrap["expected-native-session-id"] = "mismatched-session";
    else bootstrap.cwd = join(sourceProbeRoot, "mismatched-cwd");
    const mismatchResult = await run(
      "bash",
      [join(sourceProbePlugin, ".codex-plugin/hooks/identity.sh")],
      {
        input: JSON.stringify(sourceProbePayload),
        env: fixtureEnvironment({
          ...mismatchEnvironment,
          MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify(bootstrap),
          MILLSTRAND_CODEX_STRAND_BIN: fakeGuidanceStrand,
          FAKE_GUIDANCE_LOG: mismatchLog,
          TMPDIR: sourceProbeRoot,
        }),
      },
    );
    assert.equal(mismatchResult.code, 0, mismatchResult.stderr);
    assert.ok(Buffer.byteLength(mismatchResult.stdout) < 1024);
    const mismatchOutput = parseSingleJsonLine(
      mismatchResult.stdout,
      `${mismatch} source-probe fence mismatch`,
    );
    assert.equal(mismatchOutput.continue, false);
    assert.equal("hookSpecificOutput" in mismatchOutput, false);
    assert.match(
      mismatchOutput.systemMessage,
      /failure receipt was not recorded/,
    );
    assert.match(
      mismatchOutput.systemMessage,
      new RegExp(
        `managed bootstrap ${mismatch === "session" ? "native session" : mismatch} fence mismatch`,
      ),
    );
    assert.equal(
      existsSync(mismatchLog),
      false,
      "fence mismatch must not contact Strand",
    );
  }

  const lockSetupRoot = temporaryDirectory("codex-managed-lock-setup-");
  const lockSetupPayload = {
    ...sourcePayload,
    cwd: join(lockSetupRoot, "host-cwd"),
  };
  mkdirSync(join(lockSetupPayload.cwd, ".millstrand"), { recursive: true });
  const blockedRuntimePath = join(lockSetupRoot, "runtime-is-a-file");
  writeFileSync(blockedRuntimePath, "not a directory\n");
  const lockSetupLog = join(lockSetupRoot, "guidance-calls.jsonl");
  const lockSetupEnvironment = fixtureEnvironment({
    ...managedGuidanceEnvironment(lockSetupPayload, "managed-lock-setup"),
    MILLSTRAND_CODEX_STRAND_BIN: fakeGuidanceStrand,
    FAKE_GUIDANCE_LOG: lockSetupLog,
    TMPDIR: lockSetupRoot,
  });
  lockSetupEnvironment.XDG_RUNTIME_DIR = blockedRuntimePath;
  const lockSetupResult = await run(
    "bash",
    [identityHook, "--configured-source"],
    {
      input: JSON.stringify(lockSetupPayload),
      env: lockSetupEnvironment,
    },
  );
  assert.equal(lockSetupResult.code, 0, lockSetupResult.stderr);
  const lockSetupOutput = parseSingleJsonLine(
    lockSetupResult.stdout,
    "managed lock setup failure",
  );
  assert.equal(lockSetupOutput.continue, false);
  assert.equal("hookSpecificOutput" in lockSetupOutput, false);
  assert.match(lockSetupOutput.systemMessage, /duplicate-injector protection/);
  const lockSetupCalls = parseJsonLines(
    readFileSync(lockSetupLog, "utf8"),
    "managed lock setup calls",
  );
  assert.equal(lockSetupCalls.length, 1);
  assert.deepEqual(lockSetupCalls[0].operation.slice(0, 3), [
    "agent",
    "guidance",
    "fail",
  ]);
  const lockSetupReceiptIndex =
    lockSetupCalls[0].operation.indexOf("--receipt");
  assert.notEqual(lockSetupReceiptIndex, -1);
  assert.deepEqual(
    JSON.parse(lockSetupCalls[0].operation[lockSetupReceiptIndex + 1]),
    {
      schema: "millstrand.agent-guidance-receipt/v1",
      "run-id": "managed-lock-setup",
      attempt: 1,
      invocation: "invocation-managed-lock-setup-1",
      harness: "codex",
      "native-session-id": lockSetupPayload.session_id,
      transport: "native-v1",
      "bundle-sha256": JSON.parse(
        lockSetupEnvironment.MILLSTRAND_MANAGED_GUIDANCE,
      )["bundle-sha256"],
      "capability-sha256": "a".repeat(64),
      outcome: "failed",
      stage: "preflight",
      code: "probe-failed",
      diagnostic:
        "Millstrand identity startup could not establish duplicate-injector protection.",
    },
  );
  assert.deepEqual(lockSetupCalls[0].managedNames, []);

  const childRoot = temporaryDirectory("codex-managed-child-");
  const childSourcePayload = JSON.parse(
    readFileSync(join(payloadRoot, "subagent-start.json"), "utf8"),
  );
  const childPayload = {
    ...childSourcePayload,
    cwd: join(childRoot, "host-cwd"),
  };
  mkdirSync(join(childPayload.cwd, ".millstrand"), { recursive: true });
  const childLog = join(childRoot, "identity-calls.jsonl");
  const childResult = await run("bash", [identityHook, "--configured-source"], {
    input: JSON.stringify(childPayload),
    env: fixtureEnvironment({
      ...managedGuidanceEnvironment(childPayload, "managed-root-for-child"),
      MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
      FAKE_STRAND_LOG: childLog,
      TMPDIR: childRoot,
    }),
  });
  assert.equal(childResult.code, 0, childResult.stderr);
  const childOutput = parseSingleJsonLine(
    childResult.stdout,
    "managed SubagentStart output",
  );
  assertHookOutput(childOutput, "SubagentStart");
  assert.match(
    childOutput.hookSpecificOutput.additionalContext,
    /fixture-child-identity/,
  );
  assert.doesNotMatch(
    childOutput.hookSpecificOutput.additionalContext,
    /fixture-managed-identity|first frozen contribution|Current Millstrand run/,
  );
  const childCalls = parseJsonLines(
    readFileSync(childLog, "utf8"),
    "managed child calls",
  );
  assert.equal(childCalls.length, 2);
  assert.deepEqual(
    childCalls.map((call) => call.managed_environment_present),
    [false, false],
  );

  for (const mismatch of ["run-id", "attempt", "invocation", "session"]) {
    const mismatchRoot = temporaryDirectory(`codex-managed-${mismatch}-fence-`);
    const mismatchPayload = {
      ...sourcePayload,
      cwd: join(mismatchRoot, "host-cwd"),
    };
    mkdirSync(join(mismatchPayload.cwd, ".millstrand"), { recursive: true });
    const mismatchLog = join(mismatchRoot, "guidance-calls.jsonl");
    const mismatchEnvironment = managedGuidanceEnvironment(
      mismatchPayload,
      `managed-${mismatch}-fence`,
    );
    const guidance = JSON.parse(
      mismatchEnvironment.MILLSTRAND_MANAGED_GUIDANCE,
    );
    const bootstrap = JSON.parse(
      mismatchEnvironment.MILLSTRAND_MANAGED_BOOTSTRAP,
    );
    if (mismatch === "run-id") bootstrap["run-id"] = "mismatched-run";
    else if (mismatch === "attempt") bootstrap.attempt = 2;
    else if (mismatch === "invocation")
      bootstrap.invocation = "mismatched-invocation";
    else if (mismatch === "session")
      bootstrap["expected-native-session-id"] = "mismatched-session";
    const mismatchResult = await run(
      "bash",
      [identityHook, "--configured-source"],
      {
        input: JSON.stringify(mismatchPayload),
        env: fixtureEnvironment({
          ...mismatchEnvironment,
          MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify(bootstrap),
          MILLSTRAND_CODEX_STRAND_BIN: fakeGuidanceStrand,
          FAKE_GUIDANCE_LOG: mismatchLog,
          ...(mismatch === "invocation"
            ? { FAKE_GUIDANCE_MODE: "ack-ignored" }
            : {}),
          TMPDIR: mismatchRoot,
        }),
      },
    );
    assert.equal(mismatchResult.code, 0, mismatchResult.stderr);
    const mismatchOutput = parseSingleJsonLine(
      mismatchResult.stdout,
      `${mismatch} managed fence failure`,
    );
    assert.equal(mismatchOutput.continue, false);
    assert.equal("hookSpecificOutput" in mismatchOutput, false);
    const mismatchCalls = parseJsonLines(
      readFileSync(mismatchLog, "utf8"),
      `${mismatch} managed fence calls`,
    );
    assert.equal(mismatchCalls.length, 1);
    assert.deepEqual(mismatchCalls[0].operation.slice(0, 3), [
      "agent",
      "guidance",
      "fail",
    ]);
    assert.equal(mismatchCalls[0].workspace, bootstrap.workspace);
    assert.equal(mismatchCalls[0].cwd, bootstrap.cwd);
    assert.deepEqual(mismatchCalls[0].managedNames, []);
    const receiptIndex = mismatchCalls[0].operation.indexOf("--receipt");
    assert.notEqual(receiptIndex, -1);
    const receipt = JSON.parse(mismatchCalls[0].operation[receiptIndex + 1]);
    assert.deepEqual(receipt, {
      schema: "millstrand.agent-guidance-receipt/v1",
      "run-id": guidance["run-id"],
      attempt: guidance.attempt,
      invocation: guidance.invocation,
      harness: "codex",
      "native-session-id": mismatchPayload.session_id,
      transport: "native-v1",
      "bundle-sha256": guidance["bundle-sha256"],
      "capability-sha256": guidance["capability-sha256"],
      outcome: "failed",
      stage: "validation",
      code: "validation-failed",
      diagnostic: `managed bootstrap ${mismatch === "session" ? "native session" : mismatch} fence mismatch`,
    });
  }

  const rejectedRouteRoot = temporaryDirectory(
    "codex-managed-cwd-route-fence-",
  );
  const rejectedRoutePayload = {
    ...sourcePayload,
    cwd: join(rejectedRouteRoot, "host-cwd"),
  };
  mkdirSync(join(rejectedRoutePayload.cwd, ".millstrand"), { recursive: true });
  const rejectedRouteCwd = join(rejectedRouteRoot, "rejected-cwd");
  mkdirSync(rejectedRouteCwd);
  const rejectedRouteLog = join(rejectedRouteRoot, "guidance-calls.jsonl");
  const rejectedRouteEnvironment = managedGuidanceEnvironment(
    rejectedRoutePayload,
    "managed-cwd-route-fence",
  );
  const rejectedRouteBootstrap = JSON.parse(
    rejectedRouteEnvironment.MILLSTRAND_MANAGED_BOOTSTRAP,
  );
  rejectedRouteBootstrap.cwd = rejectedRouteCwd;
  const rejectedRouteResult = await run(
    "bash",
    [identityHook, "--configured-source"],
    {
      input: JSON.stringify(rejectedRoutePayload),
      env: fixtureEnvironment({
        ...rejectedRouteEnvironment,
        MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify(rejectedRouteBootstrap),
        MILLSTRAND_CODEX_STRAND_BIN: fakeGuidanceStrand,
        FAKE_GUIDANCE_LOG: rejectedRouteLog,
        TMPDIR: rejectedRouteRoot,
      }),
    },
  );
  assert.equal(rejectedRouteResult.code, 0, rejectedRouteResult.stderr);
  const rejectedRouteOutput = parseSingleJsonLine(
    rejectedRouteResult.stdout,
    "rejected managed cwd route",
  );
  assert.equal(rejectedRouteOutput.continue, false);
  assert.match(rejectedRouteOutput.systemMessage, /cwd fence mismatch/);
  assert.equal(
    existsSync(rejectedRouteLog),
    false,
    "rejected bootstrap route must not contact Strand",
  );

  const workspaceFenceRoot = temporaryDirectory(
    "codex-managed-workspace-route-fence-",
  );
  const workspaceFencePayload = {
    ...sourcePayload,
    cwd: join(workspaceFenceRoot, "host-cwd"),
  };
  mkdirSync(join(workspaceFencePayload.cwd, ".millstrand"), {
    recursive: true,
  });
  const workspaceFenceLog = join(workspaceFenceRoot, "guidance-calls.jsonl");
  const workspaceFenceEnvironment = managedGuidanceEnvironment(
    workspaceFencePayload,
    "managed-workspace-route-fence",
  );
  const workspaceFenceResult = await run(
    "bash",
    [identityHook, "--configured-source"],
    {
      input: JSON.stringify(workspaceFencePayload),
      env: fixtureEnvironment({
        ...workspaceFenceEnvironment,
        MILLSTRAND_CODEX_STRAND_BIN: fakeGuidanceStrand,
        FAKE_GUIDANCE_LOG: workspaceFenceLog,
        FAKE_GUIDANCE_MODE: "workspace-mismatch",
        TMPDIR: workspaceFenceRoot,
      }),
    },
  );
  assert.equal(workspaceFenceResult.code, 0, workspaceFenceResult.stderr);
  const workspaceFenceOutput = parseSingleJsonLine(
    workspaceFenceResult.stdout,
    "managed workspace route fence",
  );
  assert.equal(workspaceFenceOutput.continue, false);
  assert.match(workspaceFenceOutput.systemMessage, /workspace fence mismatch/);
  const workspaceFenceCalls = parseJsonLines(
    readFileSync(workspaceFenceLog, "utf8"),
    "managed workspace route calls",
  );
  assert.deepEqual(
    workspaceFenceCalls.map((call) => call.operation.slice(0, 3)),
    [["agent", "startup", "codex"]],
    "workspace route mismatch must not route a failure receipt",
  );

  const missingSessionRoot = temporaryDirectory(
    "codex-managed-missing-session-fence-",
  );
  const missingSessionPayload = {
    ...sourcePayload,
    cwd: join(missingSessionRoot, "host-cwd"),
  };
  mkdirSync(join(missingSessionPayload.cwd, ".millstrand"), {
    recursive: true,
  });
  const missingSessionLog = join(missingSessionRoot, "guidance-calls.jsonl");
  const missingSessionEnvironment = managedGuidanceEnvironment(
    missingSessionPayload,
    "managed-missing-session-fence",
  );
  const missingSessionBootstrap = JSON.parse(
    missingSessionEnvironment.MILLSTRAND_MANAGED_BOOTSTRAP,
  );
  delete missingSessionBootstrap["expected-native-session-id"];
  const missingSessionResult = await run(
    "bash",
    [identityHook, "--configured-source"],
    {
      input: JSON.stringify(missingSessionPayload),
      env: fixtureEnvironment({
        ...missingSessionEnvironment,
        MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify(missingSessionBootstrap),
        MILLSTRAND_CODEX_STRAND_BIN: fakeGuidanceStrand,
        FAKE_GUIDANCE_LOG: missingSessionLog,
        TMPDIR: missingSessionRoot,
      }),
    },
  );
  assert.equal(missingSessionResult.code, 0, missingSessionResult.stderr);
  assert.ok(Buffer.byteLength(missingSessionResult.stdout) < 1024);
  const missingSessionOutput = parseSingleJsonLine(
    missingSessionResult.stdout,
    "missing managed native session fence",
  );
  assert.equal(missingSessionOutput.continue, false);
  assert.equal("hookSpecificOutput" in missingSessionOutput, false);
  assert.match(
    missingSessionOutput.systemMessage,
    /closed v1 schema key mismatch/,
  );
  assert.equal(
    existsSync(missingSessionLog),
    false,
    "invalid bootstrap must not contact Strand",
  );

  const failureRoot = temporaryDirectory("codex-managed-failure-host-");
  const payload = { ...sourcePayload, cwd: join(failureRoot, "host-cwd") };
  mkdirSync(join(payload.cwd, ".millstrand"), { recursive: true });
  const payloadText = JSON.stringify(payload);
  for (const mode of [
    "malformed",
    "duplicate-key",
    "digest-mismatch",
    "session-mismatch",
    "flood",
    "ack-ignored",
    "exit",
  ]) {
    const directory = temporaryDirectory("codex-managed-failure-");
    const logPath = join(directory, "guidance-calls.jsonl");
    const managedEnvironment = managedGuidanceEnvironment(payload);
    const guidance = JSON.parse(managedEnvironment.MILLSTRAND_MANAGED_GUIDANCE);
    const result = await run("bash", [identityHook, "--configured-source"], {
      input: payloadText,
      env: fixtureEnvironment({
        ...managedEnvironment,
        MILLSTRAND_CODEX_STRAND_BIN: fakeGuidanceStrand,
        FAKE_GUIDANCE_LOG: logPath,
        FAKE_GUIDANCE_MODE: mode,
        TMPDIR: directory,
      }),
    });
    assert.equal(result.code, 0, result.stderr);
    assert.ok(
      Buffer.byteLength(result.stdout) < 1024,
      `${mode} failure output must be bounded`,
    );
    const output = parseSingleJsonLine(
      result.stdout,
      `${mode} managed failure`,
    );
    assert.equal(output.continue, false);
    assert.match(output.systemMessage, /managed guidance failed/i);
    assert.equal("hookSpecificOutput" in output, false);

    const calls = parseJsonLines(
      readFileSync(logPath, "utf8"),
      `${mode} failure calls`,
    );
    assert.deepEqual(calls[0].operation.slice(0, 3), [
      "agent",
      "startup",
      "codex",
    ]);
    assert.deepEqual(calls.at(-1).operation.slice(0, 3), [
      "agent",
      "guidance",
      "fail",
    ]);
    const receiptFlag = calls.at(-1).operation.indexOf("--receipt");
    assert.notEqual(
      receiptFlag,
      -1,
      `${mode} failure call must carry a receipt`,
    );
    const receipt = JSON.parse(calls.at(-1).operation[receiptFlag + 1]);
    assert.deepEqual(
      {
        schema: receipt.schema,
        runId: receipt["run-id"],
        attempt: receipt.attempt,
        invocation: receipt.invocation,
        harness: receipt.harness,
        nativeSessionId: receipt["native-session-id"],
        transport: receipt.transport,
        bundleSha256: receipt["bundle-sha256"],
        capabilitySha256: receipt["capability-sha256"],
        outcome: receipt.outcome,
      },
      {
        schema: "millstrand.agent-guidance-receipt/v1",
        runId: "managed-current",
        attempt: 1,
        invocation: "invocation-managed-current-1",
        harness: "codex",
        nativeSessionId: payload.session_id,
        transport: "native-v1",
        bundleSha256: guidance["bundle-sha256"],
        capabilitySha256: "a".repeat(64),
        outcome: "failed",
      },
    );
    assert.ok(
      ["startup", "validation", "rendering", "handoff"].includes(receipt.stage),
    );
    assert.equal(receipt.code, `${receipt.stage}-failed`);
    assert.equal(typeof receipt.diagnostic, "string");
    assert.ok(
      receipt.diagnostic.length > 0 && receipt.diagnostic.length <= 500,
    );
    assert.deepEqual(
      calls.flatMap((call) => call.managedNames),
      [],
      `${mode} failure receipt child must receive scrubbed ownership`,
    );
  }

  const malformedMetadata = await run(
    "bash",
    [identityHook, "--configured-source"],
    {
      input: payloadText,
      env: fixtureEnvironment({
        MILLSTRAND_AGENT_ID: "managed",
        MILLSTRAND_RUN_ID: "run",
        MILLSTRAND_MANAGED_GUIDANCE:
          '{"schema":"millstrand.agent-guidance-bootstrap/v1","transport":"legacy","transport":"native-v1"}',
      }),
    },
  );
  assert.equal(
    parseSingleJsonLine(malformedMetadata.stdout, "malformed metadata")
      .continue,
    false,
  );

  const legacy = await run("bash", [identityHook, "--configured-source"], {
    input: payloadText,
    env: fixtureEnvironment({
      MILLSTRAND_AGENT_ID: "managed",
      MILLSTRAND_RUN_ID: "run",
      MILLSTRAND_MANAGED_GUIDANCE: JSON.stringify({
        schema: "millstrand.agent-guidance-bootstrap/v1",
        transport: "legacy",
      }),
    }),
  });
  assert.equal(
    legacy.stdout,
    "",
    "explicit legacy must preserve the existing managed transport",
  );
}

async function checkPayloadReplay() {
  const cases = [
    ["session-start-startup.json", "SessionStart", "startup"],
    ["session-start-resume.json", "SessionStart", "resume"],
    ["session-start-clear.json", "SessionStart", "clear"],
    ["session-start-compact.json", "SessionStart", "compact"],
    ["subagent-start.json", "SubagentStart", null],
  ];

  for (const [fileName, eventName, source] of cases) {
    const payloadText = readFileSync(join(payloadRoot, fileName), "utf8");
    const payload = JSON.parse(payloadText);
    assertMatchesSchema(
      payload,
      inputSchemas[eventName],
      inputSchemas[eventName],
      fileName,
    );
    assert.equal(payload.hook_event_name, eventName);
    assert.equal(typeof payload.session_id, "string");
    assert.equal(typeof payload.cwd, "string");
    if (source === null) {
      assert.equal(typeof payload.turn_id, "string");
      assert.equal(typeof payload.agent_id, "string");
      assert.equal(typeof payload.agent_type, "string");
      assert.equal("source" in payload, false);
    } else {
      assert.equal(payload.source, source);
      assert.equal("agent_id" in payload, false);
    }

    const logDirectory = temporaryDirectory("codex-hook-replay-");
    const logPath = join(logDirectory, "fake-strand.jsonl");
    const inheritedChildHints =
      eventName === "SubagentStart"
        ? {
            MILLSTRAND_AGENT_ID: "inherited-parent-must-not-be-used",
            MILLSTRAND_RUN_ID: "inherited-run",
            MILLSTRAND_BOOTSTRAP_V1: "inherited-bootstrap",
            MILLSTRAND_BOOTSTRAP_FUTURE_V9: "inherited-future-bootstrap",
            MILLSTRAND_RESERVATION_ID: "inherited-reservation",
            MILLSTRAND_FOO_RESERVATION_ID: "inherited-suffixed-reservation",
            MILLSTRAND_FOO_IDENTITY_TRANSPORT: "inherited-suffixed-transport",
          }
        : {};
    const result = await run("bash", [identityHook, "--configured-source"], {
      input: payloadText,
      env: fixtureEnvironment({
        MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
        FAKE_STRAND_LOG: logPath,
        FAKE_STRAND_RESULT: source === "resume" ? "recovered" : "minted",
        TMPDIR: logDirectory,
        ...inheritedChildHints,
      }),
    });
    assert.equal(result.code, 0, result.stderr);
    assert.equal(result.stderr, "");
    const output = parseSingleJsonLine(result.stdout, fileName);
    assertHookOutput(output, eventName);
    assert.deepEqual(
      readdirSync(logDirectory),
      ["fake-strand.jsonl"],
      "hook must clean bounded response files",
    );

    const fakeCalls = parseJsonLines(
      readFileSync(logPath, "utf8"),
      `${fileName} fake calls`,
    );
    assert.equal(fakeCalls.length, eventName === "SubagentStart" ? 2 : 1);
    for (const fakeCall of fakeCalls) {
      assert.equal(fakeCall.cwd, payload.cwd);
      assert.equal(fakeCall.timeout, "3s");
      assert.equal(fakeCall.workspace, "");
      assert.equal(fakeCall.model, payload.model);
      assert.equal(fakeCall.managed_environment_present, false);
    }

    if (eventName === "SubagentStart") {
      const [parentCall, childCall] = fakeCalls;
      assert.equal(parentCall.native_session_id, payload.session_id);
      assert.equal(parentCall.parent_identity, "");
      assert.equal(
        childCall.native_session_id,
        `codex-child:v1:${Buffer.from(payload.session_id).toString("base64url")}:${Buffer.from(payload.agent_id).toString("base64url")}`,
      );
      assert.equal(childCall.parent_identity, "fixture-root-identity");
      assert.match(
        output.hookSpecificOutput.additionalContext,
        /fixture-child-identity/,
      );
      assert.doesNotMatch(
        output.hookSpecificOutput.additionalContext,
        /fixture-root-identity/,
      );
    } else {
      assert.equal(fakeCalls[0].native_session_id, payload.session_id);
      assert.match(
        output.hookSpecificOutput.additionalContext,
        /fixture-root-identity/,
      );
    }

    assert.match(
      output.hookSpecificOutput.additionalContext,
      /Run Strand from the Codex session working directory/,
    );
    if (source === "startup") assert.match(payload.cwd, /linked-worktree/);
  }

  const payloadText = readFileSync(
    join(payloadRoot, "session-start-startup.json"),
    "utf8",
  );
  const explicitDirectory = temporaryDirectory(
    "codex-hook-explicit-workspace-",
  );
  const explicitLog = join(explicitDirectory, "fake-strand.jsonl");
  const explicitWorkspace = "/configured workspace/.millstrand";
  const explicit = await run("bash", [identityHook, "--configured-source"], {
    input: payloadText,
    env: fixtureEnvironment({
      MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
      MILLSTRAND_CODEX_WORKSPACE: explicitWorkspace,
      FAKE_STRAND_LOG: explicitLog,
      TMPDIR: explicitDirectory,
    }),
  });
  assert.equal(explicit.code, 0, explicit.stderr);
  const explicitOutput = parseSingleJsonLine(
    explicit.stdout,
    "explicit workspace response",
  );
  assertHookOutput(explicitOutput, "SessionStart");
  assert.equal(
    parseSingleJsonLine(readFileSync(explicitLog, "utf8"), "explicit call")
      .workspace,
    explicitWorkspace,
  );
  assert.match(
    explicitOutput.hookSpecificOutput.additionalContext,
    /configured workspace/,
  );
  assert.match(
    explicitOutput.hookSpecificOutput.additionalContext,
    /`--workspace`/,
  );

  const legacyDirectory = temporaryDirectory("codex-hook-legacy-");
  const legacyLog = join(legacyDirectory, "fake-strand.jsonl");
  const legacy = await run("bash", [identityHook, "--configured-source"], {
    input: payloadText,
    env: fixtureEnvironment({
      MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
      MILLSTRAND_AGENT_ID: "legacy-identity",
      MILLSTRAND_RUN_ID: "legacy-run",
      MILLSTRAND_WORKSPACE: "/legacy/.millstrand",
      FAKE_STRAND_LOG: legacyLog,
      TMPDIR: legacyDirectory,
    }),
  });
  assert.equal(legacy.code, 0, legacy.stderr);
  assert.equal(
    legacy.stdout,
    "",
    "legacy managed roots must not inject context",
  );
  assert.equal(
    existsSync(legacyLog),
    false,
    "legacy managed roots must not mint identity",
  );

  const duplicateDirectory = temporaryDirectory("codex-hook-duplicate-");
  const duplicateGate = join(duplicateDirectory, "gate");
  const duplicateReady = join(duplicateDirectory, "ready");
  const duplicateLog = join(duplicateDirectory, "fake-strand.jsonl");
  const fifo = await run("mkfifo", [duplicateGate], {
    env: fixtureEnvironment(),
  });
  assert.equal(fifo.code, 0, fifo.stderr);
  const duplicateEnvironment = fixtureEnvironment({
    MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
    FAKE_STRAND_LOG: duplicateLog,
    TMPDIR: duplicateDirectory,
  });
  const firstInjector = run("bash", [identityHook, "--configured-source"], {
    input: payloadText,
    env: {
      ...duplicateEnvironment,
      FAKE_STRAND_MODE: "hold",
      FAKE_STRAND_GATE: duplicateGate,
      FAKE_STRAND_READY: duplicateReady,
    },
  });
  await waitForFile(duplicateReady);
  const secondInjector = await run(
    "bash",
    [identityHook, "--configured-source"],
    {
      input: payloadText,
      env: duplicateEnvironment,
    },
  );
  assert.match(
    parseSingleJsonLine(secondInjector.stdout, "duplicate injector response")
      .systemMessage,
    /Duplicate Millstrand identity injector/,
  );
  assert.equal(
    parseJsonLines(readFileSync(duplicateLog, "utf8"), "duplicate fake calls")
      .length,
    1,
  );
  writeFileSync(duplicateGate, "release\n");
  const firstInjectorResult = await firstInjector;
  assert.equal(firstInjectorResult.code, 0, firstInjectorResult.stderr);
  assertHookOutput(
    parseSingleJsonLine(
      firstInjectorResult.stdout,
      "first duplicate injector response",
    ),
    "SessionStart",
  );

  for (const mode of [
    "failure",
    "no-workspace",
    "invalid-binding",
    "flood",
    "oversized",
    "invalid-json",
    "missing-context",
    "empty-context",
    "multiple-responses",
  ]) {
    const failureDirectory = temporaryDirectory("codex-hook-failure-");
    const result = await run("bash", [identityHook, "--configured-source"], {
      input: payloadText,
      env: fixtureEnvironment({
        MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
        FAKE_STRAND_MODE: mode,
        TMPDIR: failureDirectory,
      }),
    });
    assert.equal(result.code, 0, result.stderr);
    assert.equal(result.stderr, "");
    assert.deepEqual(
      readdirSync(failureDirectory),
      [],
      `${mode} must clean response files`,
    );
    assert.ok(
      Buffer.byteLength(result.stdout) < 512,
      `${mode} output was not bounded`,
    );
    const output = parseSingleJsonLine(result.stdout, `${mode} response`);
    assertMatchesSchema(
      output,
      outputSchemas.SessionStart,
      outputSchemas.SessionStart,
      `${mode} output`,
    );
    assert.deepEqual(Object.keys(output), ["continue", "systemMessage"]);
    assert.equal(output.continue, true);
    assert.match(
      output.systemMessage,
      /unbound|required context was not injected/,
    );
  }

  const missingStrand = await run(
    "bash",
    [identityHook, "--configured-source"],
    {
      input: payloadText,
      env: fixtureEnvironment({
        MILLSTRAND_CODEX_STRAND_BIN: "/missing/strand",
      }),
    },
  );
  assert.match(
    parseSingleJsonLine(missingStrand.stdout, "missing Strand response")
      .systemMessage,
    /cannot execute Strand/,
  );

  const malformed = await run("bash", [identityHook, "--configured-source"], {
    input: '{"hook_event_name":"SessionStart","session_id":""}',
    env: fixtureEnvironment({ MILLSTRAND_CODEX_STRAND_BIN: fakeStrand }),
  });
  assert.match(
    parseSingleJsonLine(malformed.stdout, "malformed payload response")
      .systemMessage,
    /invalid SessionStart payload/,
  );

  if (process.platform !== "win32") await checkHostKillRecovery(payloadText);

  const timeoutDirectory = temporaryDirectory("codex-hook-timeout-");
  await assert.rejects(
    run("bash", [identityHook, "--configured-source"], {
      input: payloadText,
      timeout: 250,
      env: fixtureEnvironment({
        MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
        FAKE_STRAND_MODE: "hang",
        TMPDIR: timeoutDirectory,
      }),
    }),
    /timed out/,
  );
  rmSync(timeoutDirectory, { recursive: true, force: true });
  assert.equal(
    existsSync(timeoutDirectory),
    false,
    "forced timeout artifacts must be removable",
  );
}

function writeConfig(codexHome, enabled, hooksEnabled = true) {
  writeFileSync(
    join(codexHome, "config.toml"),
    `[features]\nplugins = true\nremote_plugin = false\nhooks = ${hooksEnabled}\n\n[plugins."millstrand-identity@harnesses"]\nenabled = ${enabled}\n`,
  );
}

function createCodexWorld({
  enabled = true,
  hooksEnabled = true,
  defaultCodexHome = false,
} = {}) {
  const root = temporaryDirectory("codex-hook-world-");
  const home = join(root, "home");
  const codexHome = defaultCodexHome ? join(home, ".codex") : root;
  const installedPlugin = join(
    codexHome,
    "plugins/cache/harnesses/millstrand-identity/local",
  );
  mkdirSync(codexHome, { recursive: true });
  mkdirSync(home, { recursive: true });
  mkdirSync(dirname(installedPlugin), { recursive: true });
  cpSync(pluginRoot, installedPlugin, { recursive: true });
  writeConfig(codexHome, enabled, hooksEnabled);
  const cwd = join(root, "project");
  mkdirSync(cwd);
  return { codexHome, home, installedPlugin, cwd };
}

async function listHooks(world, cwd = world.cwd, configOverrides = []) {
  return new Promise((resolvePromise, reject) => {
    const child = trackChild(
      spawn(
        "codex",
        [
          ...configOverrides.flatMap((override) => ["-c", override]),
          "app-server",
          "--stdio",
        ],
        {
          detached: process.platform !== "win32",
          env: fixtureEnvironment({
            CODEX_HOME: world.codexHome,
            HOME: world.home,
            CODEX_APP_SERVER_DISABLE_MANAGED_CONFIG: "1",
          }),
          cwd: world.cwd,
          stdio: ["pipe", "pipe", "pipe"],
        },
      ),
    );
    let stdout = "";
    let stderr = "";
    let response;
    let terminalError;
    let initialized = false;
    let outputBytes = 0;
    const stopWithError = (error) => {
      if (terminalError) return;
      terminalError = error;
      terminateProcessTree(child);
    };
    const timer = setTimeout(() => {
      stopWithError(
        new Error(`codex app-server hooks/list timed out; stderr: ${stderr}`),
      );
    }, 15_000);

    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
      outputBytes += Buffer.byteLength(chunk);
      if (outputBytes > 1_000_000) {
        stopWithError(new Error("codex app-server output was unbounded"));
      }
    });
    child.stdout.on("data", (chunk) => {
      if (terminalError) return;
      stdout += chunk;
      outputBytes += Buffer.byteLength(chunk);
      if (outputBytes > 1_000_000) {
        stopWithError(new Error("codex app-server output was unbounded"));
        return;
      }
      try {
        for (;;) {
          const newline = stdout.indexOf("\n");
          if (newline < 0) break;
          const line = stdout.slice(0, newline);
          stdout = stdout.slice(newline + 1);
          if (line.length === 0) continue;
          const message = JSON.parse(line);
          if (message.id === 1 && !initialized) {
            initialized = true;
            child.stdin.write(`${JSON.stringify({ method: "initialized" })}\n`);
            child.stdin.write(
              `${JSON.stringify({ id: 2, method: "hooks/list", params: { cwds: [cwd] } })}\n`,
            );
          }
          if (message.id === 2) {
            if (message.error) {
              stopWithError(
                new Error(
                  `hooks/list failed: ${JSON.stringify(message.error)}`,
                ),
              );
              return;
            }
            response = message.result;
            child.stdin.end();
          }
        }
      } catch (error) {
        stopWithError(error);
      }
    });
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (terminalError) {
        reject(terminalError);
        return;
      }
      if (response === undefined) {
        reject(new Error(`codex app-server exited ${code}; stderr: ${stderr}`));
        return;
      }
      if (code !== 0) {
        reject(new Error(`codex app-server exited ${code}; stderr: ${stderr}`));
        return;
      }
      resolvePromise(response);
    });

    child.stdin.write(
      `${JSON.stringify({
        id: 1,
        method: "initialize",
        params: {
          clientInfo: { name: "codex-hook-conformance", version: "1" },
          capabilities: { experimentalApi: true },
        },
      })}\n`,
    );
  });
}

function onlyEntry(response) {
  assert.equal(response.data.length, 1);
  return response.data[0];
}

function managedIdentityHooks(entry) {
  return entry.hooks.filter(
    (hook) =>
      ["sessionStart", "subagentStart"].includes(hook.eventName) &&
      hook.command.includes("/.codex-plugin/hooks/identity.sh"),
  );
}

function trustHooks(world, hooks) {
  const configPath = join(world.codexHome, "config.toml");
  writeFileSync(
    configPath,
    `${readFileSync(configPath, "utf8")}\n${hooks
      .map(
        (hook) =>
          `[hooks.state."${hook.key}"]\ntrusted_hash = "${hook.currentHash}"\n`,
      )
      .join("\n")}`,
  );
}

function mutateInstalledIdentityHook(world, eventName, mutate) {
  const manifestPath = join(
    world.installedPlugin,
    ".codex-plugin/hooks/hooks.json",
  );
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  const hook = manifest.hooks[eventName]
    .flatMap((registration) => registration.hooks)
    .find((candidate) =>
      candidate.command.includes("/.codex-plugin/hooks/identity.sh"),
    );
  assert.ok(hook, `${eventName} identity hook fixture must exist`);
  mutate(hook);
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, "\t")}\n`);
}

async function startFixtureProvider() {
  const requests = [];
  const server = createServer((request, response) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1_000_000) request.destroy();
    });
    request.on("end", () => {
      requests.push(JSON.parse(body));
      const output = {
        id: "msg_fixture",
        type: "message",
        role: "assistant",
        content: [{ type: "output_text", text: "ok" }],
      };
      const events = [
        { type: "response.created", response: { id: "resp_fixture" } },
        { type: "response.output_item.done", output_index: 0, item: output },
        {
          type: "response.completed",
          response: {
            id: "resp_fixture",
            status: "completed",
            output: [output],
            usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2 },
          },
        },
      ];
      const eventStream = events
        .map((event) => `data: ${JSON.stringify(event)}\n\n`)
        .join("");
      response.writeHead(200, {
        "Content-Type": "text/event-stream",
        "Content-Length": Buffer.byteLength(eventStream),
      });
      response.end(eventStream);
    });
  });
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  return {
    port: server.address().port,
    requests,
    close: () => new Promise((resolvePromise) => server.close(resolvePromise)),
  };
}

function writeHostConfig(world, providerPort, pluginIds, hooksEnabled = true) {
  writeFileSync(
    join(world.codexHome, "config.toml"),
    `model = "gpt-5.4"\nmodel_provider = "fixture"\n\n[model_providers.fixture]\nname = "fixture"\nbase_url = "http://127.0.0.1:${providerPort}/v1"\nwire_api = "responses"\nrequires_openai_auth = false\n\n[features]\nplugins = true\nremote_plugin = false\nhooks = ${hooksEnabled}\n\n${pluginIds.map((id) => `[plugins."millstrand-identity@${id}"]\nenabled = true\n`).join("\n")}`,
  );
}

function identityMessages(requests) {
  return requests.flatMap((request) =>
    (request.input ?? []).filter(
      (item) =>
        item.role === "developer" &&
        JSON.stringify(item).includes("Your Millstrand identity is"),
    ),
  );
}

async function checkInvocationEnabledHooks() {
  const provider = await startFixtureProvider();
  try {
    const world = createCodexWorld();
    writeHostConfig(world, provider.port, ["harnesses"], false);
    const effectiveEntry = onlyEntry(
      await listHooks(world, world.cwd, ["features.hooks=true"]),
    );
    assert.equal(
      effectiveEntry.hooks.filter(
        (hook) =>
          hook.eventName === "sessionStart" &&
          hook.command.includes("/.codex-plugin/hooks/identity.sh"),
      ).length,
      1,
    );

    const logPath = join(world.codexHome, "invocation-enabled-strand.jsonl");
    const environment = fixtureEnvironment({
      CODEX_HOME: world.codexHome,
      HOME: world.home,
      CODEX_APP_SERVER_DISABLE_MANAGED_CONFIG: "1",
      MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
      FAKE_STRAND_LOG: logPath,
    });
    const commonArgs = [
      "--enable",
      "hooks",
      "exec",
      "--skip-git-repo-check",
      "--dangerously-bypass-hook-trust",
      "--json",
    ];
    const requestOffset = provider.requests.length;
    const fresh = await run("codex", [...commonArgs, "Reply ok."], {
      env: environment,
      cwd: world.cwd,
      timeout: 45_000,
    });
    assert.equal(fresh.code, 0, fresh.stderr);
    const threadId = parseJsonLines(
      fresh.stdout,
      "invocation-enabled fresh events",
    ).find((event) => event.type === "thread.started")?.thread_id;
    assert.equal(typeof threadId, "string", fresh.stdout);

    const resumed = await run(
      "codex",
      [...commonArgs, "resume", threadId, "Reply ok again."],
      {
        env: environment,
        cwd: world.cwd,
        timeout: 45_000,
      },
    );
    assert.equal(resumed.code, 0, resumed.stderr);
    const calls = parseJsonLines(
      readFileSync(logPath, "utf8"),
      "invocation-enabled Strand calls",
    );
    assert.equal(
      calls.length,
      2,
      `${fresh.stdout}\n${fresh.stderr}\n${resumed.stderr}`,
    );
    assert.deepEqual(
      calls.map((call) => call.native_session_id),
      [threadId, threadId],
    );
    const modelRequests = provider.requests.slice(requestOffset);
    assert.equal(modelRequests.length, 2);
    assert.deepEqual(
      modelRequests.map((request) => identityMessages([request]).length),
      [1, 2],
      "resume must retain prior identity context and inject reconstructed context",
    );
  } finally {
    await provider.close();
  }
}

async function checkActualHostDuplicateSources() {
  const provider = await startFixtureProvider();
  try {
    for (const scenario of ["single", "two-packages", "inherited-user"]) {
      const world = createCodexWorld();
      const pluginIds = ["harnesses"];
      if (scenario === "two-packages") {
        const duplicatePlugin = join(
          world.codexHome,
          "plugins/cache/duplicate/millstrand-identity/local",
        );
        mkdirSync(dirname(duplicatePlugin), { recursive: true });
        cpSync(pluginRoot, duplicatePlugin, { recursive: true });
        const duplicateHooksPath = join(
          duplicatePlugin,
          ".codex-plugin/hooks/hooks.json",
        );
        const duplicateHooks = JSON.parse(
          readFileSync(duplicateHooksPath, "utf8"),
        );
        for (const group of duplicateHooks.hooks.SessionStart) {
          for (const hook of group.hooks) {
            if (hook.command.includes("identity.sh"))
              hook.command = `sleep 1; ${hook.command}`;
          }
        }
        writeFileSync(
          duplicateHooksPath,
          `${JSON.stringify(duplicateHooks, null, "\t")}\n`,
        );
        pluginIds.push("duplicate");
      }
      if (scenario === "inherited-user") {
        const command = `sleep 1; bash "${join(
          world.installedPlugin,
          ".codex-plugin/hooks/identity.sh",
        )}"`;
        writeFileSync(
          join(world.codexHome, "hooks.json"),
          `${JSON.stringify({ hooks: { SessionStart: [{ hooks: [{ type: "command", command }] }] } }, null, "\t")}\n`,
        );
      }
      writeHostConfig(world, provider.port, pluginIds);
      const installedIdentity = join(
        world.installedPlugin,
        ".codex-plugin/hooks/identity.sh",
      );

      const listed = onlyEntry(await listHooks(world));
      const configuredIdentityHooks = listed.hooks.filter(
        (hook) =>
          hook.eventName === "sessionStart" &&
          hook.command.includes("/.codex-plugin/hooks/identity.sh"),
      );
      assert.equal(
        configuredIdentityHooks.length,
        scenario === "single" ? 1 : 2,
      );
      if (scenario === "two-packages") {
        assert.deepEqual(
          configuredIdentityHooks.map((hook) => hook.pluginId).sort(),
          ["millstrand-identity@duplicate", "millstrand-identity@harnesses"],
        );
      }
      if (scenario === "inherited-user") {
        assert.deepEqual(
          configuredIdentityHooks.map((hook) => hook.source).sort(),
          ["plugin", "user"],
        );
      }

      const logPath = join(world.codexHome, `${scenario}-strand.jsonl`);
      const requestOffset = provider.requests.length;
      const environment = fixtureEnvironment({
        CODEX_HOME: world.codexHome,
        HOME: world.home,
        CODEX_APP_SERVER_DISABLE_MANAGED_CONFIG: "1",
        MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
        FAKE_STRAND_LOG: logPath,
        ...(scenario === "inherited-user"
          ? { PLUGIN_ROOT: world.installedPlugin }
          : {}),
      });
      if (scenario !== "single") {
        const diagnostic = await run("bash", [installedIdentity], {
          input: JSON.stringify({
            session_id: `${scenario}-diagnostic`,
            cwd: world.cwd,
            hook_event_name: "SessionStart",
            source: "startup",
            model: "gpt-5.4",
          }),
          env: environment,
          cwd: world.cwd,
        });
        assert.match(
          parseSingleJsonLine(
            diagnostic.stdout,
            `${scenario} duplicate diagnostic`,
          ).systemMessage,
          /Duplicate Millstrand identity injector configuration detected \(2 configured sources\)/,
        );
      }
      const result = await run(
        "codex",
        [
          "exec",
          "--skip-git-repo-check",
          "--dangerously-bypass-hook-trust",
          "--ephemeral",
          "--json",
          "Reply ok.",
        ],
        { env: environment, cwd: world.cwd, timeout: 45_000 },
      );
      assert.equal(result.code, 0, `${scenario}: ${result.stderr}`);
      const calls = existsSync(logPath)
        ? parseJsonLines(
            readFileSync(logPath, "utf8"),
            `${scenario} Strand calls`,
          )
        : [];
      const modelRequests = provider.requests.slice(requestOffset);
      assert.equal(
        modelRequests.length,
        1,
        `${scenario} must issue one model request`,
      );
      if (scenario === "single") {
        assert.equal(calls.length, 1, `${result.stdout}\n${result.stderr}`);
        assert.equal(identityMessages(modelRequests).length, 1);
      } else {
        assert.equal(calls.length, 0, `${scenario} must stop before Strand`);
        assert.equal(
          identityMessages(modelRequests).length,
          0,
          `${scenario} must not inject duplicate identity context`,
        );
      }
    }
  } finally {
    await provider.close();
  }
}

async function checkCliDiscovery() {
  const version = await run("codex", ["--version"], {
    env: fixtureEnvironment(),
  });
  assert.equal(version.code, 0, version.stderr);
  assert.equal(version.stdout.trim(), expectedCodexVersion);

  const manifest = JSON.parse(
    readFileSync(join(pluginRoot, ".codex-plugin/plugin.json"), "utf8"),
  );
  assert.equal(manifest.hooks, "./.codex-plugin/hooks/hooks.json");

  const active = createCodexWorld();
  const activeEntry = onlyEntry(await listHooks(active));
  assert.deepEqual(activeEntry.hooks.map((hook) => hook.eventName).sort(), [
    "sessionStart",
    "subagentStart",
  ]);
  const identityHooks = activeEntry.hooks.filter((hook) =>
    hook.command.endsWith('/.codex-plugin/hooks/identity.sh"'),
  );
  assert.equal(identityHooks.length, 2);
  assert.deepEqual(identityHooks.map((hook) => hook.eventName).sort(), [
    "sessionStart",
    "subagentStart",
  ]);
  for (const hook of identityHooks) {
    assert.equal(hook.source, "plugin");
    assert.equal(hook.pluginId, "millstrand-identity@harnesses");
    assert.equal(hook.trustStatus, "untrusted");
    assert.equal(hook.timeoutSec, 18);
    assert.equal(hook.additionalContextLimit, 4096);
    assert.match(hook.sourcePath, /\.codex-plugin\/hooks\/hooks\.json$/);
  }
  const sessionStart = identityHooks.find(
    (hook) => hook.eventName === "sessionStart",
  );

  writeFileSync(
    join(active.codexHome, "config.toml"),
    `${readFileSync(join(active.codexHome, "config.toml"), "utf8")}\n[hooks.state."${sessionStart.key}"]\ntrusted_hash = "${sessionStart.currentHash}"\n`,
  );
  const trustedEntry = onlyEntry(await listHooks(active));
  assert.equal(
    trustedEntry.hooks.find((hook) => hook.key === sessionStart.key)
      .trustStatus,
    "trusted",
  );

  const disabledFeature = createCodexWorld({ hooksEnabled: false });
  assert.deepEqual(onlyEntry(await listHooks(disabledFeature)).hooks, []);

  const disabledPlugin = createCodexWorld({ enabled: false });
  assert.deepEqual(onlyEntry(await listHooks(disabledPlugin)).hooks, []);

  const missing = createCodexWorld();
  const missingManifestPath = join(
    missing.installedPlugin,
    ".codex-plugin/plugin.json",
  );
  const missingManifest = JSON.parse(readFileSync(missingManifestPath, "utf8"));
  missingManifest.hooks = "./.codex-plugin/hooks/missing.json";
  writeFileSync(
    missingManifestPath,
    `${JSON.stringify(missingManifest, null, "\t")}\n`,
  );
  const missingEntry = onlyEntry(await listHooks(missing));
  assert.deepEqual(missingEntry.hooks, []);
  assert.equal(missingEntry.warnings.length, 1);
  assert.match(
    missingEntry.warnings[0],
    /failed to read plugin hooks config .*missing\.json/,
  );

  const duplicate = createCodexWorld();
  const command = `bash "${join(duplicate.installedPlugin, ".codex-plugin/hooks/identity.sh")}"`;
  writeFileSync(
    join(duplicate.codexHome, "hooks.json"),
    `${JSON.stringify(
      {
        hooks: {
          SessionStart: [
            {
              hooks: [
                {
                  type: "command",
                  command,
                  additionalContextLimit: 1024,
                },
              ],
            },
          ],
        },
      },
      null,
      "\t",
    )}\n`,
  );
  const duplicateEntry = onlyEntry(await listHooks(duplicate));
  const duplicateSessionHooks = duplicateEntry.hooks.filter(
    (hook) =>
      hook.eventName === "sessionStart" &&
      hook.command.endsWith('/.codex-plugin/hooks/identity.sh"'),
  );
  assert.equal(
    duplicateSessionHooks.length,
    2,
    "duplicate injectors must be diagnosed before startup",
  );
  assert.deepEqual(duplicateSessionHooks.map((hook) => hook.source).sort(), [
    "plugin",
    "user",
  ]);
  assert.equal(
    duplicateSessionHooks.find((hook) => hook.source === "user")
      .additionalContextLimit,
    1024,
  );

  const linkedEntry = onlyEntry(
    await listHooks(active, "/workspace/project-linked-worktree"),
  );
  assert.equal(linkedEntry.cwd, "/workspace/project-linked-worktree");
}

function snapshotFilesystemTree(root) {
  const entries = [];
  const visit = (directory, prefix = "") => {
    for (const name of readdirSync(directory).sort()) {
      const path = join(directory, name);
      const relativePath = prefix ? join(prefix, name) : name;
      const stats = lstatSync(path);
      if (stats.isSymbolicLink())
        entries.push([relativePath, "link", readlinkSync(path)]);
      else if (stats.isDirectory()) {
        entries.push([relativePath, "directory"]);
        visit(path, relativePath);
      } else if (stats.isFile())
        entries.push([relativePath, "file", hashFile(path)]);
      else entries.push([relativePath, "other", stats.mode]);
    }
  };
  visit(root);
  return entries;
}

async function runPreflight(
  world,
  extraArgv = [],
  { omitCodexHome = false } = {},
) {
  const executable = realpathSync(
    (
      await run("which", ["codex"], { env: fixtureEnvironment() })
    ).stdout.trim(),
  );
  const workspace = join(world.cwd, ".millstrand");
  mkdirSync(workspace, { recursive: true });
  const env = fixtureEnvironment({
    CODEX_HOME: world.codexHome,
    HOME: world.home,
    CODEX_APP_SERVER_DISABLE_MANAGED_CONFIG: "1",
  });
  if (omitCodexHome) delete env.CODEX_HOME;
  const request = {
    schema: "millstrand.agent-guidance-preflight/v1",
    harness: "codex",
    executable,
    mode: "headless",
    cwd: realpathSync(world.cwd),
    workspace: realpathSync(workspace),
    env,
    "extra-argv": extraArgv,
    resumes: false,
    model: "gpt-5.4",
    effort: "low",
  };
  const inspectedPaths = [world.codexHome, world.cwd, workspace];
  const before = inspectedPaths.map(snapshotFilesystemTree);
  const result = await run("node", [managedGuidancePreflight], {
    input: JSON.stringify(request),
    env: fixtureEnvironment(),
    cwd: world.cwd,
    timeout: 20_000,
  });
  assert.equal(result.code, 0, result.stderr);
  assert.deepEqual(
    inspectedPaths.map(snapshotFilesystemTree),
    before,
    "Codex preflight must not write to CODEX_HOME, cwd, or workspace",
  );
  return parseSingleJsonLine(result.stdout, "managed guidance preflight");
}

async function checkManagedGuidancePreflight() {
  const defaultHome = createCodexWorld({ defaultCodexHome: true });
  writeFileSync(
    join(defaultHome.codexHome, "config.toml"),
    `developer_instructions = "competing instructions"\n${readFileSync(join(defaultHome.codexHome, "config.toml"), "utf8")}`,
  );
  const defaultHomeResult = await runPreflight(defaultHome, [], {
    omitCodexHome: true,
  });
  assert.equal(
    defaultHomeResult.code,
    "unverifiable-profile",
    JSON.stringify(defaultHomeResult),
  );
  assert.match(
    defaultHomeResult.diagnostic,
    /competing Codex instruction configuration/,
  );

  const untrusted = createCodexWorld();
  const untrustedResult = await runPreflight(untrusted);
  assert.equal(
    untrustedResult.code,
    "untrusted-hook",
    JSON.stringify(untrustedResult),
  );

  const entry = onlyEntry(await listHooks(untrusted));
  const identities = managedIdentityHooks(entry);
  assert.deepEqual(identities.map((hook) => hook.eventName).sort(), [
    "sessionStart",
    "subagentStart",
  ]);
  trustHooks(untrusted, identities);
  const capable = await runPreflight(untrusted);
  assert.equal(capable.result, "capable");
  assert.equal(capable.capability.harness, "codex");
  assert.equal(capable.capability["host-version"], expectedCodexVersion);
  assert.equal(capable.capability["max-context-bytes"], 3072);
  assert.deepEqual(Object.keys(capable.capability["hook-fact"]), [
    "sessionStart",
    "subagentStart",
  ]);
  for (const [eventName, hookFact] of Object.entries(
    capable.capability["hook-fact"],
  )) {
    assert.equal(hookFact.eventName, eventName);
    assert.equal(hookFact.source, "plugin");
    assert.equal(hookFact.pluginId, "millstrand-identity@harnesses");
    assert.equal(hookFact.trustStatus, "trusted");
    assert.equal(hookFact.timeoutSec, 18);
    assert.equal(hookFact.additionalContextLimit, 4096);
    assert.equal(
      hookFact.command,
      `bash "${realpathSync(join(untrusted.installedPlugin, ".codex-plugin/hooks/identity.sh"))}"`,
    );
  }

  const missingSubagent = createCodexWorld();
  const missingSubagentManifestPath = join(
    missingSubagent.installedPlugin,
    ".codex-plugin/hooks/hooks.json",
  );
  const missingSubagentManifest = JSON.parse(
    readFileSync(missingSubagentManifestPath, "utf8"),
  );
  delete missingSubagentManifest.hooks.SubagentStart;
  writeFileSync(
    missingSubagentManifestPath,
    `${JSON.stringify(missingSubagentManifest, null, "\t")}\n`,
  );
  const missingSubagentResult = await runPreflight(missingSubagent);
  assert.equal(missingSubagentResult.code, "missing-hook");
  assert.match(missingSubagentResult.diagnostic, /subagentStart/);

  const untrustedSubagent = createCodexWorld();
  const untrustedSubagentHooks = managedIdentityHooks(
    onlyEntry(await listHooks(untrustedSubagent)),
  );
  trustHooks(
    untrustedSubagent,
    untrustedSubagentHooks.filter((hook) => hook.eventName === "sessionStart"),
  );
  const untrustedSubagentResult = await runPreflight(untrustedSubagent);
  assert.equal(untrustedSubagentResult.code, "untrusted-hook");
  assert.match(untrustedSubagentResult.diagnostic, /subagentStart/);

  const changedSubagent = createCodexWorld();
  mutateInstalledIdentityHook(changedSubagent, "SubagentStart", (hook) => {
    hook.command = `echo changed; ${hook.command}`;
  });
  trustHooks(
    changedSubagent,
    managedIdentityHooks(onlyEntry(await listHooks(changedSubagent))),
  );
  const changedSubagentResult = await runPreflight(changedSubagent);
  assert.equal(changedSubagentResult.code, "changed-hook");
  assert.match(
    changedSubagentResult.diagnostic,
    /subagentStart command or limits/,
  );

  const missingLimits = createCodexWorld();
  mutateInstalledIdentityHook(missingLimits, "SubagentStart", (hook) => {
    delete hook.timeout;
    delete hook.additionalContextLimit;
  });
  trustHooks(
    missingLimits,
    managedIdentityHooks(onlyEntry(await listHooks(missingLimits))),
  );
  const missingLimitsResult = await runPreflight(missingLimits);
  assert.equal(missingLimitsResult.code, "changed-hook");
  assert.match(
    missingLimitsResult.diagnostic,
    /subagentStart command or limits/,
  );

  const alteredLimits = createCodexWorld();
  mutateInstalledIdentityHook(alteredLimits, "SessionStart", (hook) => {
    hook.timeout = 19;
    hook.additionalContextLimit = 4097;
  });
  trustHooks(
    alteredLimits,
    managedIdentityHooks(onlyEntry(await listHooks(alteredLimits))),
  );
  const alteredLimitsResult = await runPreflight(alteredLimits);
  assert.equal(alteredLimitsResult.code, "changed-hook");
  assert.match(
    alteredLimitsResult.diagnostic,
    /sessionStart command or limits/,
  );

  const duplicateSubagent = createCodexWorld();
  const duplicateSubagentCommand = `bash "${join(duplicateSubagent.installedPlugin, ".codex-plugin/hooks/identity.sh")}"`;
  writeFileSync(
    join(duplicateSubagent.codexHome, "hooks.json"),
    JSON.stringify({
      hooks: {
        SubagentStart: [
          { hooks: [{ type: "command", command: duplicateSubagentCommand }] },
        ],
      },
    }),
  );
  const duplicateSubagentResult = await runPreflight(duplicateSubagent);
  assert.equal(duplicateSubagentResult.code, "duplicate-injector");
  assert.match(duplicateSubagentResult.diagnostic, /subagentStart/);

  const unsupportedArgumentResult = await runPreflight(untrusted, [
    "--unreviewed-option",
    "value",
  ]);
  assert.equal(unsupportedArgumentResult.code, "unverifiable-profile");
  assert.match(
    unsupportedArgumentResult.diagnostic,
    /unsupported Codex extra argument/,
  );
  for (const attachedProfile of ["--profile=unreviewed", "-punreviewed"]) {
    assert.equal(
      (await runPreflight(untrusted, [attachedProfile])).code,
      "unverifiable-profile",
    );
  }
  for (const splitProfile of [
    ["--profile", "unreviewed"],
    ["-p", "unreviewed"],
  ]) {
    assert.equal(
      (await runPreflight(untrusted, splitProfile)).code,
      "unverifiable-profile",
    );
  }
  const splitEnable = await runPreflight(untrusted, ["--enable", "hooks"]);
  assert.equal(splitEnable.result, "capable");
  assert.notEqual(
    splitEnable.capability["launch-profile-sha256"],
    capable.capability["launch-profile-sha256"],
    "split feature selectors must be represented in capability evidence",
  );
  assert.equal(
    (await runPreflight(untrusted, ["--disable", "hooks"])).code,
    "missing-hook",
    "a split disabling selector must reach the effective hook probe",
  );
  const attachedEnable = await runPreflight(untrusted, ["--enable=hooks"]);
  assert.equal(attachedEnable.result, "capable");
  assert.notEqual(
    attachedEnable.capability["launch-profile-sha256"],
    capable.capability["launch-profile-sha256"],
    "attached feature selectors must be represented in capability evidence",
  );
  assert.notEqual(
    (await runPreflight(untrusted, ["--disable=hooks"])).result,
    "capable",
    "an attached disabling selector must reach the effective hook probe",
  );

  const changed = createCodexWorld();
  trustHooks(
    changed,
    managedIdentityHooks(onlyEntry(await listHooks(changed))),
  );
  writeFileSync(
    join(changed.installedPlugin, ".codex-plugin/lib/managed-guidance.mjs"),
    `${readFileSync(join(changed.installedPlugin, ".codex-plugin/lib/managed-guidance.mjs"), "utf8")}\n// changed\n`,
  );
  assert.equal((await runPreflight(changed)).code, "changed-hook");

  const duplicate = createCodexWorld();
  const duplicateCommand = `bash "${join(duplicate.installedPlugin, ".codex-plugin/hooks/identity.sh")}"`;
  writeFileSync(
    join(duplicate.codexHome, "hooks.json"),
    JSON.stringify({
      hooks: {
        SessionStart: [
          { hooks: [{ type: "command", command: duplicateCommand }] },
        ],
      },
    }),
  );
  assert.equal((await runPreflight(duplicate)).code, "duplicate-injector");

  const missing = createCodexWorld({ enabled: false });
  assert.equal((await runPreflight(missing)).code, "missing-hook");

  const mismatch = createCodexWorld();
  assert.equal(
    (await runPreflight(mismatch, ["-c", 'developer_instructions="hostile"']))
      .code,
    "unverifiable-profile",
  );

  const duplicateKeyRequest = JSON.stringify({
    schema: "millstrand.agent-guidance-preflight/v1",
    harness: "codex",
    executable: "/missing",
    mode: "headless",
    cwd: "/tmp",
    workspace: "/tmp",
    env: {},
    "extra-argv": [],
    resumes: false,
  }).replace(/}$/, ',"harness":"pi"}');
  const malformed = await run("node", [managedGuidancePreflight], {
    input: duplicateKeyRequest,
    env: fixtureEnvironment(),
  });
  assert.equal(
    parseSingleJsonLine(malformed.stdout, "duplicate-key preflight").code,
    "unverifiable-profile",
  );
}

async function holdInterruptProbe() {
  const directory = temporaryDirectory("codex-hook-interrupt-");
  writeFileSync(join(directory, "artifact"), "must be removed\n");
  const payloadText = readFileSync(
    join(payloadRoot, "session-start-startup.json"),
    "utf8",
  );
  await run("bash", [identityHook, "--configured-source"], {
    input: payloadText,
    timeout: 120_000,
    env: fixtureEnvironment({
      MILLSTRAND_CODEX_STRAND_BIN: fakeStrand,
      FAKE_STRAND_MODE: "hang",
      TMPDIR: directory,
    }),
    onSpawn(child) {
      process.stdout.write(
        `${JSON.stringify({ childPid: child.pid, directory })}\n`,
      );
    },
  });
}

try {
  if (process.argv[2] === "--interrupt-probe") {
    await holdInterruptProbe();
  } else {
    checkStrictJsonRegression();
    await checkEarlyStdinCloseReporting();
    await checkPayloadReplay();
    await checkManagedGuidanceReplay();
    await checkCliDiscovery();
    await checkManagedGuidancePreflight();
    await checkInvocationEnabledHooks();
    await checkActualHostDuplicateSources();
    console.log(
      `Codex hook conformance passed (${expectedCodexVersion}; CLI-only, local provider).`,
    );
  }
} finally {
  cleanup();
}
