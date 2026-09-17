#!/usr/bin/env node
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const conformanceRoot = dirname(fileURLToPath(import.meta.url));
const runnerPath = join(conformanceRoot, "run.mjs");

function processGroupExists(processGroupId) {
  try {
    process.kill(-processGroupId, 0);
    return true;
  } catch (error) {
    if (error.code === "ESRCH") return false;
    throw error;
  }
}

async function waitForProcessGroupExit(processGroupId) {
  const deadline = Date.now() + 2_000;
  while (processGroupExists(processGroupId) && Date.now() < deadline) {
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 20));
  }
  assert.equal(
    processGroupExists(processGroupId),
    false,
    `process group ${processGroupId} leaked`,
  );
}

function cleanupProbe(child, processGroupId, root) {
  if (processGroupId !== undefined && processGroupExists(processGroupId)) {
    process.kill(-processGroupId, "SIGKILL");
  }
  try {
    process.kill(-child.pid, "SIGKILL");
  } catch (error) {
    if (error.code !== "ESRCH") throw error;
  }
  rmSync(root, { recursive: true, force: true });
}

function waitForProbe(child, stderr) {
  return new Promise((resolvePromise, reject) => {
    let stdout = "";
    const timer = setTimeout(() => {
      reject(
        new Error(
          `interrupt probe did not become ready; stderr: ${stderr.value}`,
        ),
      );
    }, 5_000);
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
      const newline = stdout.indexOf("\n");
      if (newline < 0) return;
      clearTimeout(timer);
      try {
        resolvePromise(JSON.parse(stdout.slice(0, newline)));
      } catch (error) {
        reject(error);
      }
    });
    child.once("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.once("close", (code, signal) => {
      clearTimeout(timer);
      reject(
        new Error(
          `interrupt probe exited before ready (code ${code}, signal ${signal}); stderr: ${stderr.value}`,
        ),
      );
    });
  });
}

async function checkSignal(signal) {
  const root = mkdtempSync(
    join(tmpdir(), `codex-hook-${signal.toLowerCase()}-`),
  );
  const stderr = { value: "" };
  const child = spawn(process.execPath, [runnerPath, "--interrupt-probe"], {
    detached: true,
    env: { ...process.env, TMPDIR: root },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let processGroupId;
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => {
    stderr.value += chunk;
  });

  try {
    const probe = await waitForProbe(child, stderr);
    processGroupId = probe.childPid;
    assert.equal(
      processGroupExists(processGroupId),
      true,
      "probe child group must be running",
    );
    const closed = new Promise((resolvePromise) => {
      child.once("close", (code, closeSignal) =>
        resolvePromise({ code, closeSignal }),
      );
    });
    child.kill(signal);
    const result = await closed;
    assert.deepEqual(result, { code: null, closeSignal: signal });
    await waitForProcessGroupExit(processGroupId);
    assert.deepEqual(
      readdirSync(root),
      [],
      `${signal} must remove disposable directories`,
    );
  } finally {
    cleanupProbe(child, processGroupId, root);
  }
}

if (process.platform === "win32") {
  console.log(
    "Codex hook interruption cleanup skipped: process groups require POSIX.",
  );
} else {
  await checkSignal("SIGINT");
  await checkSignal("SIGTERM");
  console.log(
    "Codex hook interruption cleanup passed (SIGINT, SIGTERM; disposable CLI-only).",
  );
}
