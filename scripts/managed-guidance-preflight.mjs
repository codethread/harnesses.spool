#!/usr/bin/env node
import { spawn } from "node:child_process";
import {
  cpSync,
  existsSync,
  lstatSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync,
  statSync,
} from "node:fs";
import { tmpdir } from "node:os";
import {
  dirname,
  extname,
  isAbsolute,
  join,
  relative,
  resolve,
} from "node:path";
import { fileURLToPath } from "node:url";
import {
  METADATA_MAX_BYTES,
  canonicalJson,
  hashFile,
  parseStrictJson,
  sha256CanonicalJson,
} from "./strict-json.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const packageRoot = resolve(dirname(scriptPath), "..");
const codexPluginRoot = join(packageRoot, "plugins/millstrand-identity");
const piOwner = join(packageRoot, "plugins/millstrand-identity/pi/index.ts");
const RESPONSE_MAX_BYTES = 1024 * 1024;
const STDERR_MAX_BYTES = 64 * 1024;
const REQUEST_SCHEMA = "millstrand.agent-guidance-preflight/v1";
const CAPABILITY_SCHEMA = "millstrand.agent-guidance-capability/v1";
const failureCodes = new Set([
  "unsupported-host",
  "missing-hook",
  "changed-hook",
  "untrusted-hook",
  "duplicate-injector",
  "unverifiable-profile",
  "probe-failed",
]);

function fail(code, diagnostic) {
  if (!failureCodes.has(code))
    throw new Error(`internal unsupported failure code: ${code}`);
  return {
    schema: REQUEST_SCHEMA,
    result: "legacy-required",
    code,
    diagnostic: String(diagnostic).replace(/\s+/g, " ").trim().slice(0, 500),
  };
}

function object(value, label) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`${label} must be one JSON object`);
  }
  return value;
}

function closed(value, allowed, required) {
  const allowedSet = new Set(allowed);
  const keys = Object.keys(value);
  if (
    keys.some((key) => !allowedSet.has(key)) ||
    required.some((key) => !Object.hasOwn(value, key))
  ) {
    throw new Error(
      `request keys do not match the closed v1 schema (actual: ${keys.sort().join(", ")})`,
    );
  }
}

function nonblank(value, label) {
  if (typeof value !== "string" || !value.trim())
    throw new Error(`${label} must be nonblank`);
  return value;
}

function canonicalPath(value, label, kind = "any") {
  const path = nonblank(value, label);
  if (!isAbsolute(path) || resolve(path) !== path || !existsSync(path)) {
    throw new Error(`${label} must be an existing canonical absolute path`);
  }
  const actual = realpathSync(path);
  if (actual !== path) throw new Error(`${label} must not contain symlinks`);
  const stats = statSync(path);
  if (kind === "file" && !stats.isFile())
    throw new Error(`${label} must be a file`);
  if (kind === "directory" && !stats.isDirectory())
    throw new Error(`${label} must be a directory`);
  return path;
}

function executablePath(value) {
  const path = nonblank(value, "executable");
  if (!isAbsolute(path) || !existsSync(path) || !statSync(path).isFile()) {
    throw new Error("executable must be an existing absolute file path");
  }
  return path;
}

function parseRequest(raw) {
  const value = object(
    parseStrictJson(raw, METADATA_MAX_BYTES),
    "preflight request",
  );
  const required = [
    "schema",
    "harness",
    "executable",
    "mode",
    "cwd",
    "workspace",
    "env",
    "extra-argv",
    "resumes",
  ];
  closed(
    value,
    [...required, "model", "effort", "native-session-id"],
    required,
  );
  if (value.schema !== REQUEST_SCHEMA)
    throw new Error("unsupported preflight request schema");
  if (!new Set(["codex", "pi"]).has(value.harness))
    throw new Error("unsupported harness");
  if (!new Set(["headless", "interactive"]).has(value.mode))
    throw new Error("unsupported launch mode");
  const env = object(value.env, "env");
  for (const [name, entry] of Object.entries(env)) {
    if (!name || typeof entry !== "string" || name.includes("="))
      throw new Error("env must contain string name/value pairs");
  }
  if (
    !Array.isArray(value["extra-argv"]) ||
    value["extra-argv"].some((entry) => typeof entry !== "string")
  ) {
    throw new Error("extra-argv must be a vector of strings");
  }
  if (typeof value.resumes !== "boolean")
    throw new Error("resumes must be boolean");
  for (const key of ["model", "effort", "native-session-id"]) {
    if (value[key] !== undefined) nonblank(value[key], key);
  }
  if (value.resumes && value["native-session-id"] === undefined) {
    throw new Error("native-session-id is required when resumes is true");
  }
  return {
    ...value,
    executable: executablePath(value.executable),
    cwd: canonicalPath(value.cwd, "cwd", "directory"),
    workspace: canonicalPath(value.workspace, "workspace", "directory"),
    env,
  };
}

function scrubPreflightEnvironment(env) {
  const result = { ...env };
  for (const name of Object.keys(result)) {
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
    ) {
      delete result[name];
    }
  }
  return result;
}

function terminateExactChild(child, label) {
  if (child.pid === undefined) return Promise.resolve();
  return new Promise((resolvePromise, reject) => {
    let settled = false;
    let forceTimer;
    let deadlineTimer;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(forceTimer);
      clearTimeout(deadlineTimer);
      child.off("close", closed);
      if (error) reject(error);
      else resolvePromise();
    };
    const closed = () => finish();
    child.once("close", closed);
    child.kill("SIGTERM");
    forceTimer = setTimeout(() => child.kill("SIGKILL"), 250);
    deadlineTimer = setTimeout(
      () =>
        finish(new Error(`${label} did not exit after exact-child SIGKILL`)),
      1_250,
    );
  });
}

function run(command, args, request, { input = "", timeout = 5_000 } = {}) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, {
      cwd: request.cwd,
      env: scrubPreflightEnvironment(request.env),
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = Buffer.alloc(0);
    let stderr = Buffer.alloc(0);
    let done = false;
    const rejectOnce = (error) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      child.kill("SIGKILL");
      reject(error);
    };
    const append = (current, chunk, maximum, label) => {
      if (current.length + chunk.length > maximum) {
        rejectOnce(new Error(`${label} exceeded ${maximum} bytes`));
        return current;
      }
      return Buffer.concat([current, chunk]);
    };
    child.stdout.on("data", (chunk) => {
      stdout = append(stdout, chunk, RESPONSE_MAX_BYTES, "stdout");
    });
    child.stderr.on("data", (chunk) => {
      stderr = append(stderr, chunk, STDERR_MAX_BYTES, "stderr");
    });
    child.stdin.on("error", (error) => {
      if (input.length === 0 && error.code === "EPIPE") return;
      rejectOnce(error);
    });
    child.on("error", rejectOnce);
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
      () => rejectOnce(new Error(`probe exceeded ${timeout}ms`)),
      timeout,
    );
    child.stdin.end(input);
  });
}

function parseSelectors(argv, harness) {
  const selectors = [];
  const extensionPaths = [];
  let noExtensions = false;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (
      harness === "pi" &&
      ["--append-system-prompt", "--system-prompt"].some(
        (flag) => argument === flag || argument.startsWith(`${flag}=`),
      )
    ) {
      throw new Error(`competing Pi prompt option ${argument.split("=")[0]}`);
    }
    if (
      harness === "codex" &&
      (argument === "-c" ||
        argument === "--config" ||
        argument.startsWith("-c=") ||
        (argument.startsWith("-c") && argument.length > 2) ||
        argument.startsWith("--config="))
    ) {
      const attached =
        argument === "-c" || argument === "--config"
          ? argv[++index]
          : argument.startsWith("-c=")
            ? argument.slice(3)
            : argument.startsWith("-c") && !argument.startsWith("--")
              ? argument.slice(2)
              : argument.slice(argument.indexOf("=") + 1);
      if (typeof attached !== "string")
        throw new Error("Codex config selector has no value");
      if (
        /(?:^|[.\s])(?:developer_instructions|instructions_file|base_instructions)\s*=/.test(
          attached.replace(/["']/g, ""),
        )
      ) {
        throw new Error("competing Codex instruction config");
      }
      selectors.push("-c", attached);
      continue;
    }
    if (
      harness === "codex" &&
      (argument === "--profile" ||
        argument === "-p" ||
        argument.startsWith("--profile=") ||
        (argument.startsWith("-p") && argument.length > 2))
    ) {
      throw new Error(
        "Codex profile selectors are not verifiable in native-v1",
      );
    }
    if (harness === "codex" && argument === "--dangerously-bypass-hook-trust") {
      throw new Error(
        "Codex hook trust bypass cannot establish native-v1 capability",
      );
    }
    if (harness === "codex" && ["--enable", "--disable"].includes(argument)) {
      const value = argv[++index];
      if (typeof value !== "string")
        throw new Error(`${argument} has no value`);
      selectors.push(argument, value);
      continue;
    }
    if (
      harness === "codex" &&
      (argument.startsWith("--enable=") || argument.startsWith("--disable="))
    ) {
      if (!argument.slice(argument.indexOf("=") + 1)) {
        throw new Error(
          `${argument.slice(0, argument.indexOf("="))} has no value`,
        );
      }
      selectors.push(argument);
      continue;
    }
    if (harness === "codex") {
      throw new Error(
        `unsupported Codex extra argument: ${argument || "<blank>"}`,
      );
    }
    if (harness === "pi" && ["-e", "--extension"].includes(argument)) {
      const value = argv[++index];
      if (typeof value !== "string")
        throw new Error(`${argument} has no value`);
      extensionPaths.push(value);
      continue;
    }
    if (harness === "pi" && argument.startsWith("--extension=")) {
      throw new Error(
        "attached Pi --extension syntax is not supported by 0.84.4",
      );
    }
    if (
      harness === "pi" &&
      (argument === "--no-extensions" || argument === "-ne")
    ) {
      noExtensions = true;
      continue;
    }
    if (
      /^(?:--model|--provider|--thinking|--session|--session-id|--fork|--mode|--name)$/.test(
        argument,
      )
    )
      index += 1;
  }
  return { selectors, extensionPaths, noExtensions };
}

async function versionOf(request) {
  let probeRoot;
  let probeRequest = request;
  if (request.harness === "codex") {
    probeRoot = mkdtempSync(join(tmpdir(), "millstrand-codex-version-"));
    const isolatedEnvironment = { ...request.env };
    for (const [environmentName, directoryName] of [
      ["CODEX_HOME", "codex-home"],
      ["HOME", "home"],
      ["XDG_CONFIG_HOME", "config"],
      ["XDG_STATE_HOME", "state"],
      ["XDG_CACHE_HOME", "cache"],
      ["XDG_RUNTIME_DIR", "runtime"],
      ["TMPDIR", "tmp"],
    ]) {
      const path = join(probeRoot, directoryName);
      mkdirSync(path);
      isolatedEnvironment[environmentName] = path;
    }
    probeRequest = { ...request, env: isolatedEnvironment };
  }
  try {
    const result = await run(request.executable, ["--version"], probeRequest);
    if (result.code !== 0)
      throw new Error(`host version probe exited ${result.code}`);
    return result.stdout.trim();
  } finally {
    if (probeRoot) rmSync(probeRoot, { recursive: true, force: true });
  }
}

function codexClosureHash(root = codexPluginRoot) {
  const paths = [
    ".codex-plugin/plugin.json",
    ".codex-plugin/hooks/hooks.json",
    ".codex-plugin/hooks/identity.sh",
    ".codex-plugin/hooks/identity-sources.sh",
  ];
  return sha256CanonicalJson(
    paths.map((path) => [path, hashFile(join(root, path))]),
  );
}

function replacePathStrings(value, from, to) {
  if (typeof value === "string") return value.split(from).join(to);
  if (Array.isArray(value))
    return value.map((member) => replacePathStrings(member, from, to));
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, member]) => [
        key,
        replacePathStrings(member, from, to),
      ]),
    );
  }
  return value;
}

function codexHome(request) {
  return request.env.CODEX_HOME
    ? nonblank(request.env.CODEX_HOME, "CODEX_HOME")
    : join(nonblank(request.env.HOME, "HOME"), ".codex");
}

async function listCodexHooks(request, selectors) {
  const sourceCodexHome = codexHome(request);
  if (
    !isAbsolute(sourceCodexHome) ||
    !existsSync(sourceCodexHome) ||
    !statSync(sourceCodexHome).isDirectory()
  ) {
    throw new Error("Codex home must be an existing absolute directory");
  }
  const probeRoot = mkdtempSync(join(tmpdir(), "millstrand-codex-preflight-"));
  const probeCodexHome = join(probeRoot, "codex-home");
  cpSync(sourceCodexHome, probeCodexHome, {
    recursive: true,
    verbatimSymlinks: true,
  });
  for (const name of ["config", "state", "cache", "runtime", "tmp"]) {
    mkdirSync(join(probeRoot, name));
  }
  const probeEnvironment = {
    ...scrubPreflightEnvironment(request.env),
    CODEX_HOME: probeCodexHome,
    XDG_CONFIG_HOME: join(probeRoot, "config"),
    XDG_STATE_HOME: join(probeRoot, "state"),
    XDG_CACHE_HOME: join(probeRoot, "cache"),
    XDG_RUNTIME_DIR: join(probeRoot, "runtime"),
    TMPDIR: join(probeRoot, "tmp"),
  };
  try {
    const result = await new Promise((resolvePromise, reject) => {
      const child = spawn(
        request.executable,
        [...selectors, "app-server", "--stdio"],
        {
          cwd: request.cwd,
          env: probeEnvironment,
          stdio: ["pipe", "pipe", "pipe"],
        },
      );
      let buffer = "";
      let bytes = 0;
      let initialized = false;
      let done = false;
      let stderr = "";
      const finishError = (error) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        child.stdin.destroy();
        void terminateExactChild(child, "Codex hooks/list probe").then(
          () => reject(error),
          (shutdownError) =>
            reject(
              new Error(
                `${error.message}; cleanup failed: ${shutdownError.message}`,
              ),
            ),
        );
      };
      const finishSuccess = (value) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        child.stdin.end();
        void terminateExactChild(child, "Codex hooks/list probe").then(
          () => resolvePromise(value),
          reject,
        );
      };
      child.stderr.setEncoding("utf8");
      child.stderr.on("data", (chunk) => {
        stderr += chunk;
        bytes += Buffer.byteLength(chunk);
        if (bytes > RESPONSE_MAX_BYTES)
          finishError(new Error("Codex probe output exceeded its bound"));
      });
      child.stdout.setEncoding("utf8");
      child.stdout.on("data", (chunk) => {
        buffer += chunk;
        bytes += Buffer.byteLength(chunk);
        if (bytes > RESPONSE_MAX_BYTES)
          return finishError(
            new Error("Codex probe output exceeded its bound"),
          );
        try {
          for (;;) {
            const newline = buffer.indexOf("\n");
            if (newline < 0) break;
            const line = buffer.slice(0, newline);
            buffer = buffer.slice(newline + 1);
            if (!line) continue;
            const message = object(
              parseStrictJson(line, RESPONSE_MAX_BYTES),
              "Codex app-server message",
            );
            if (message.id === 1 && !initialized) {
              if (message.error) throw new Error("Codex initialize failed");
              initialized = true;
              child.stdin.write(
                `${JSON.stringify({ method: "initialized" })}\n`,
              );
              child.stdin.write(
                `${JSON.stringify({ id: 2, method: "hooks/list", params: { cwds: [request.cwd] } })}\n`,
              );
            } else if (message.id === 2) {
              if (message.error) throw new Error("Codex hooks/list failed");
              finishSuccess(message.result);
            }
          }
        } catch (error) {
          finishError(error);
        }
      });
      child.on("error", finishError);
      child.on("close", (code) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        reject(
          new Error(`Codex app-server exited ${code}: ${stderr.slice(0, 300)}`),
        );
      });
      const timer = setTimeout(
        () => finishError(new Error("Codex hooks/list exceeded 5 seconds")),
        5_000,
      );
      child.stdin.write(
        `${JSON.stringify({ id: 1, method: "initialize", params: { clientInfo: { name: "millstrand-guidance-preflight", version: "1" }, capabilities: { experimentalApi: true } } })}\n`,
      );
    });
    return replacePathStrings(result, probeCodexHome, sourceCodexHome);
  } finally {
    rmSync(probeRoot, { recursive: true, force: true });
  }
}

function codexConfigEvidence(request) {
  const paths = [join(codexHome(request), "config.toml")];
  let directory = request.cwd;
  for (;;) {
    paths.push(join(directory, ".codex/config.toml"));
    const parent = dirname(directory);
    if (parent === directory) break;
    directory = parent;
  }
  const evidence = [];
  for (const path of [...new Set(paths)]) {
    if (!existsSync(path)) continue;
    const source = readFileSync(path, "utf8");
    if (/^[\t ]*["']?[^\n=]*instructions[^\n=]*["']?[\t ]*=/im.test(source)) {
      throw new Error(
        `competing Codex instruction configuration is present in ${path}`,
      );
    }
    evidence.push([path, hashFile(path)]);
  }
  return evidence;
}

async function preflightCodex(request) {
  const version = await versionOf(request);
  if (version !== "codex-cli 0.154.0")
    return fail(
      "unsupported-host",
      `Codex host must be exactly 0.154.0; observed ${version || "blank"}`,
    );
  let parsed;
  let configEvidence;
  try {
    parsed = parseSelectors(request["extra-argv"], "codex");
    configEvidence = codexConfigEvidence(request);
  } catch (error) {
    return fail("unverifiable-profile", error.message);
  }
  let listed;
  try {
    listed = object(
      await listCodexHooks(request, parsed.selectors),
      "hooks/list result",
    );
  } catch (error) {
    return fail("probe-failed", error.message);
  }
  if (!Array.isArray(listed.data) || listed.data.length !== 1)
    return fail(
      "unverifiable-profile",
      "hooks/list did not return exactly one cwd profile",
    );
  const profile = object(listed.data[0], "hooks/list profile");
  if (profile.cwd !== request.cwd || !Array.isArray(profile.hooks))
    return fail("unverifiable-profile", "hooks/list cwd/profile mismatch");
  if (Array.isArray(profile.warnings) && profile.warnings.length > 0)
    return fail(
      "unverifiable-profile",
      "Codex reported hook configuration warnings",
    );
  const requiredEvents = ["sessionStart", "subagentStart"];
  const injectorsByEvent = new Map(
    requiredEvents.map((eventName) => [
      eventName,
      profile.hooks.filter(
        (entry) =>
          entry?.eventName === eventName &&
          typeof entry.command === "string" &&
          entry.command.includes("/.codex-plugin/hooks/identity.sh"),
      ),
    ]),
  );
  for (const eventName of requiredEvents) {
    const injectors = injectorsByEvent.get(eventName);
    if (injectors.length === 0)
      return fail(
        "missing-hook",
        `no managed ${eventName} injector is effective`,
      );
    if (injectors.length !== 1)
      return fail(
        "duplicate-injector",
        `${injectors.length} managed ${eventName} injectors are effective`,
      );
  }
  const hookFacts = {};
  const installedPluginRoots = new Set();
  for (const eventName of requiredEvents) {
    const hook = object(
      injectorsByEvent.get(eventName)[0],
      `managed ${eventName} hook`,
    );
    if (hook.enabled !== true)
      return fail("missing-hook", `managed ${eventName} hook is disabled`);
    if (hook.trustStatus !== "trusted")
      return fail(
        "untrusted-hook",
        `managed ${eventName} hook is not trusted by Codex`,
      );
    if (
      hook.source !== "plugin" ||
      hook.pluginId !== "millstrand-identity@harnesses"
    )
      return fail(
        "untrusted-hook",
        `managed ${eventName} hook source/plugin registration is not approved`,
      );
    if (!String(hook.sourcePath).endsWith("/.codex-plugin/hooks/hooks.json"))
      return fail(
        "changed-hook",
        `managed ${eventName} hook manifest path is unexpected`,
      );
    const installedPluginRoot = resolve(dirname(hook.sourcePath), "../..");
    installedPluginRoots.add(installedPluginRoot);
    const expectedCommand = `bash "${join(installedPluginRoot, ".codex-plugin/hooks/identity.sh")}"`;
    if (
      hook.command !== expectedCommand ||
      hook.timeoutSec !== 18 ||
      hook.additionalContextLimit !== 4096
    ) {
      return fail(
        "changed-hook",
        `managed ${eventName} command or limits differ from the reviewed profile`,
      );
    }
    hookFacts[eventName] = Object.fromEntries(
      [
        "eventName",
        "key",
        "source",
        "sourcePath",
        "pluginId",
        "command",
        "enabled",
        "trustStatus",
        "currentHash",
        "timeoutSec",
        "additionalContextLimit",
      ].map((key) => [key, hook[key]]),
    );
  }
  if (installedPluginRoots.size !== 1)
    return fail(
      "untrusted-hook",
      "managed Codex hooks do not share one approved plugin root",
    );
  let adapterHash;
  try {
    const [installedPluginRoot] = installedPluginRoots;
    adapterHash = codexClosureHash(installedPluginRoot);
    if (adapterHash !== codexClosureHash()) {
      return fail(
        "changed-hook",
        "managed adapter file/dependency closure differs from the reviewed implementation",
      );
    }
  } catch (error) {
    return fail(
      "changed-hook",
      `managed adapter closure is incomplete: ${error.message}`,
    );
  }
  const executableHash = hashFile(request.executable);
  const launchProfileHash = sha256CanonicalJson({
    harness: "codex",
    mode: request.mode,
    cwd: request.cwd,
    workspace: request.workspace,
    executable: executableHash,
    selectors: parsed.selectors,
    configEvidence,
    resumes: request.resumes,
    model: request.model ?? null,
    effort: request.effort ?? null,
    nativeSessionId: request["native-session-id"] ?? null,
    hookFact: hookFacts,
  });
  return {
    schema: REQUEST_SCHEMA,
    result: "capable",
    capability: {
      schema: CAPABILITY_SCHEMA,
      harness: "codex",
      "adapter-contract": "native-v1",
      "adapter-sha256": adapterHash,
      "executable-sha256": executableHash,
      "host-version": version,
      "launch-profile-sha256": launchProfileHash,
      "max-context-bytes": 3072,
      "hook-fact": hookFacts,
    },
  };
}

function readSettings(path) {
  if (!existsSync(path)) return {};
  return object(
    parseStrictJson(readFileSync(path, "utf8"), METADATA_MAX_BYTES),
    `settings ${path}`,
  );
}

function expandHome(value, env) {
  if (value === "~") return env.HOME;
  if (value.startsWith("~/"))
    return env.HOME ? join(env.HOME, value.slice(2)) : undefined;
  return value;
}

function directExtensionEntries(path) {
  const manifestPath = join(path, "package.json");
  if (existsSync(manifestPath)) {
    const manifest = object(
      parseStrictJson(readFileSync(manifestPath, "utf8"), METADATA_MAX_BYTES),
      "Pi package manifest",
    );
    if (Array.isArray(manifest.pi?.extensions)) {
      return manifest.pi.extensions.map((entry) => resolve(path, entry));
    }
  }
  for (const name of ["index.ts", "index.js"]) {
    if (existsSync(join(path, name))) return [join(path, name)];
  }
  return null;
}

function extensionEntries(path) {
  if (!existsSync(path)) return [];
  const stats = statSync(path);
  if (!stats.isDirectory()) return [path];
  const direct = directExtensionEntries(path);
  if (direct) return direct;
  const entries = [];
  for (const item of readdirSync(path, { withFileTypes: true })) {
    const itemPath = join(path, item.name);
    if (item.isSymbolicLink()) {
      throw new Error(
        `extension discovery contains a symbolic link: ${itemPath}`,
      );
    }
    if (
      item.isFile() &&
      [".ts", ".js"].includes(extname(item.name)) &&
      !item.name.includes(".test.")
    ) {
      entries.push(itemPath);
    } else if (item.isDirectory()) {
      entries.push(...(directExtensionEntries(itemPath) ?? []));
    }
  }
  return entries;
}

function reviewedPiEntrypoints() {
  const declared = directExtensionEntries(packageRoot);
  if (!declared)
    throw new Error("reviewed Pi package has no extension declaration");
  return new Set(
    declared
      .flatMap(extensionEntries)
      .map((entrypoint) => realpathSync(entrypoint)),
  );
}

function resolveLocalSource(source, base, env) {
  const expanded = expandHome(source, env);
  if (
    !expanded ||
    (!source.startsWith(".") &&
      !source.startsWith("/") &&
      !source.startsWith("~"))
  )
    throw new Error(
      `non-local package/resource source is unverifiable without cache mutation: ${source}`,
    );
  return resolve(base, expanded);
}

function resolveLocalPackageSource(source, base, env) {
  const expanded = expandHome(source, env);
  if (
    !expanded ||
    (!source.startsWith(".") &&
      !source.startsWith("/") &&
      !source.startsWith("~") &&
      !source.startsWith("+") &&
      !source.startsWith("-"))
  ) {
    throw new Error(
      `non-local package/resource source is unverifiable without cache mutation: ${source}`,
    );
  }
  return resolve(base, expanded);
}

function packageExtensions(settings, settingsPath, env, seenRoots, scope) {
  if (settings.packages === undefined) return [];
  if (!Array.isArray(settings.packages))
    throw new Error("settings packages must be an array");
  const enabled = new Map();
  for (const entry of settings.packages) {
    const descriptor =
      typeof entry === "string"
        ? { source: entry }
        : object(entry, "package source");
    const source = nonblank(descriptor.source, "package source");
    const root = resolveLocalPackageSource(source, dirname(settingsPath), env);
    const previous = seenRoots.get(root);
    if (previous) {
      if (
        scope === "global" &&
        previous.scope === "project" &&
        previous.autoload === false
      ) {
        throw new Error(
          `overlapping global/project package with project autoload:false is unverifiable: ${root}`,
        );
      }
      continue;
    }
    if (!existsSync(join(root, "package.json")))
      throw new Error(`local package has no package.json: ${root}`);
    const manifest = object(
      parseStrictJson(
        readFileSync(join(root, "package.json"), "utf8"),
        METADATA_MAX_BYTES,
      ),
      "package manifest",
    );
    const declared =
      descriptor.autoload === false
        ? (descriptor.extensions ?? [])
        : (descriptor.extensions ?? manifest.pi?.extensions ?? []);
    if (
      !Array.isArray(declared) ||
      declared.some((value) => typeof value !== "string")
    )
      throw new Error("package extension declaration is invalid");
    enabled.set(
      root,
      declared.map((value) => resolve(root, value)),
    );
    seenRoots.set(root, { scope, autoload: descriptor.autoload });
  }
  return [...enabled.values()].flatMap((paths) =>
    paths.flatMap(extensionEntries),
  );
}

function importClosure(entrypoint) {
  const visited = new Set();
  const visit = (path) => {
    path = resolve(path);
    if (visited.has(path)) return;
    if (!existsSync(path) || !statSync(path).isFile())
      throw new Error(`extension closure file is missing: ${path}`);
    if (lstatSync(path).isSymbolicLink())
      throw new Error(`extension closure contains a symbolic link: ${path}`);
    visited.add(path);
    const source = readFileSync(path, "utf8");
    const imports = [
      ...source.matchAll(/(?:from\s*|import\s*(?:\(\s*)?)["'](\.[^"']+)["']/g),
    ].map((match) => match[1]);
    for (const specifier of imports) {
      const base = resolve(dirname(path), specifier.replace(/\.js$/, ""));
      const candidate = [
        `${base}.ts`,
        `${base}.js`,
        base,
        join(base, "index.ts"),
        join(base, "index.js"),
      ].find(
        (candidatePath) =>
          existsSync(candidatePath) && statSync(candidatePath).isFile(),
      );
      if (!candidate)
        throw new Error(
          `extension import is missing: ${specifier} from ${path}`,
        );
      visit(candidate);
    }
  };
  visit(entrypoint);
  return sha256CanonicalJson(
    [...visited]
      .sort()
      .map((path) => [relative(packageRoot, path), hashFile(path)]),
  );
}

function piProfile(request, parsed) {
  const agentDir = request.env.PI_CODING_AGENT_DIR
    ? resolve(request.env.PI_CODING_AGENT_DIR)
    : request.env.HOME
      ? join(request.env.HOME, ".pi/agent")
      : undefined;
  if (!agentDir)
    throw new Error(
      "Pi agent directory is unavailable from the effective environment",
    );
  for (const path of [
    join(request.cwd, ".pi/SYSTEM.md"),
    join(request.cwd, ".pi/APPEND_SYSTEM.md"),
    join(agentDir, "SYSTEM.md"),
    join(agentDir, "APPEND_SYSTEM.md"),
  ]) {
    if (existsSync(path))
      throw new Error(`competing Pi prompt source is present: ${path}`);
  }
  const globalPath = join(agentDir, "settings.json");
  const projectPath = join(request.cwd, ".pi/settings.json");
  const global = readSettings(globalPath);
  const project = readSettings(projectPath);
  let entries = [];
  if (!parsed.noExtensions) {
    const seenPackageRoots = new Map();
    entries.push(
      ...packageExtensions(
        project,
        projectPath,
        request.env,
        seenPackageRoots,
        "project",
      ),
    );
    entries.push(
      ...packageExtensions(
        global,
        globalPath,
        request.env,
        seenPackageRoots,
        "global",
      ),
    );
    for (const auto of [
      join(request.cwd, ".pi/extensions"),
      join(agentDir, "extensions"),
    ])
      entries.push(...extensionEntries(auto));
    for (const [settings, settingsPath] of [
      [project, projectPath],
      [global, globalPath],
    ]) {
      if (Array.isArray(settings.extensions)) {
        for (const entry of settings.extensions) {
          entries.push(
            ...extensionEntries(
              resolveLocalSource(entry, dirname(settingsPath), request.env),
            ),
          );
        }
      }
    }
  }
  for (const entry of parsed.extensionPaths) {
    const path = resolveLocalSource(entry, request.cwd, request.env);
    if (!existsSync(path))
      throw new Error(`explicit extension path does not exist: ${path}`);
    entries.push(...extensionEntries(path));
  }
  entries = [...new Set(entries.map((entry) => resolve(entry)))];
  const reviewedEntrypoints = reviewedPiEntrypoints();
  const canonicalEntries = entries.map((entrypoint) => ({
    entrypoint,
    canonical: realpathSync(entrypoint),
  }));
  const unreviewed = canonicalEntries.find(
    ({ canonical }) => !reviewedEntrypoints.has(canonical),
  );
  if (unreviewed) {
    throw Object.assign(
      new Error(
        `extension is outside the reviewed Harnesses package profile: ${unreviewed.entrypoint}`,
      ),
      { code: "unverifiable-profile" },
    );
  }
  const ownerPath = realpathSync(piOwner);
  const ownerCandidates = canonicalEntries.filter(
    ({ canonical }) => canonical === ownerPath,
  );
  if (ownerCandidates.length === 0)
    throw Object.assign(
      new Error("no owned Pi system-prompt renderer is effective"),
      {
        code: "missing-hook",
      },
    );
  if (ownerCandidates.length !== 1)
    throw Object.assign(
      new Error(
        `${ownerCandidates.length} Pi system-prompt owners are effective`,
      ),
      { code: "duplicate-injector" },
    );
  const extensions = entries.map((entrypoint) => ({
    entrypoint,
    "closure-sha256": importClosure(entrypoint),
  }));
  return { entries, extensions, owner: ownerCandidates[0].entrypoint };
}

async function preflightPi(request) {
  const version = await versionOf(request);
  if (version !== "0.84.4")
    return fail(
      "unsupported-host",
      `Pi host must be exactly 0.84.4; observed ${version || "blank"}`,
    );
  let parsed;
  try {
    parsed = parseSelectors(request["extra-argv"], "pi");
  } catch (error) {
    return fail("unverifiable-profile", error.message);
  }
  try {
    piProfile(request, parsed);
  } catch (error) {
    return fail(
      failureCodes.has(error.code) ? error.code : "unverifiable-profile",
      error.message,
    );
  }
  return fail(
    "missing-hook",
    "the reviewed Pi identity data lifecycle is present, but no managed-guidance renderer is accepted",
  );
}

let response;
try {
  let input = Buffer.alloc(0);
  for await (const chunk of process.stdin) {
    input = Buffer.concat([input, chunk]);
    if (input.length > METADATA_MAX_BYTES)
      throw new Error(`request exceeds ${METADATA_MAX_BYTES} bytes`);
  }
  const raw = new TextDecoder("utf-8", { fatal: true }).decode(input);
  const request = parseRequest(raw);
  response =
    request.harness === "codex"
      ? await preflightCodex(request)
      : await preflightPi(request);
} catch (error) {
  response = fail(
    "unverifiable-profile",
    error instanceof Error ? error.message : String(error),
  );
}
process.stdout.write(`${canonicalJson(response)}\n`);
