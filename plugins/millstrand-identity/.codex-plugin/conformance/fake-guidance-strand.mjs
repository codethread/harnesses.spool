#!/usr/bin/env node
import { appendFileSync } from "node:fs";
import { sha256CanonicalJson } from "../lib/managed-guidance.mjs";

const args = process.argv.slice(2);
function take(flag) {
  const index = args.indexOf(flag);
  if (index < 0 || index + 1 >= args.length) throw new Error(`missing ${flag}`);
  return args[index + 1];
}
const workspace = take("--workspace");
const cwd = take("--cwd");
if (take("--timeout") !== "3s") throw new Error("timeout mismatch");
const operationIndex = args.indexOf("agent");
const operation = args.slice(operationIndex);
const mode = process.env.FAKE_GUIDANCE_MODE ?? "success";
const managedNames = Object.keys(process.env).filter(
  (name) =>
    /^MILLSTRAND_(?:MANAGED_|AGENT_ID|RUN_ID|WORKSPACE|BOOTSTRAP_)/.test(
      name,
    ) ||
    name.endsWith("_RESERVATION_ID") ||
    name.endsWith("_IDENTITY_TRANSPORT"),
);
if (process.env.FAKE_GUIDANCE_LOG) {
  appendFileSync(
    process.env.FAKE_GUIDANCE_LOG,
    `${JSON.stringify({ workspace, cwd, operation, managedNames })}\n`,
  );
}

if (mode === "exit") {
  process.stderr.write("fixture unavailable\n");
  process.exit(70);
}
if (mode === "flood") {
  process.stdout.write("x".repeat(1024 * 1024 + 1));
  process.exit(0);
}
if (mode === "malformed") {
  process.stdout.write("{not json}\n");
  process.exit(0);
}

if (operation[1] === "startup") {
  const harness = operation[2];
  const session = operation[3];
  const guidance = JSON.parse(take("--guidance"));
  const bootstrap = JSON.parse(take("--bootstrap"));
  const context = {
    schema: "millstrand.agent-managed-context/v1",
    "identity-instruction": `Your Millstrand identity is ${bootstrap.identity}. Use it as \`--owner ${bootstrap.identity}\` for \`kanban claim\` and \`--by-identity ${bootstrap.identity}\` for Kanban notes, workflow mutations, and agent operations. Keep \`--identity\` and \`--parent-identity\` for native-session references. Inspect live help; never pass an unsupported flag or invent another identity.`,
    "appended-system-prompts": [
      "first frozen contribution",
      "intentionally repeated",
      "intentionally repeated",
    ],
  };
  const digest = sha256CanonicalJson([guidance["run-id"], workspace, context]);
  const responseWorkspace =
    mode === "workspace-mismatch" ? `${workspace}/other` : workspace;
  const response = {
    schema: "millstrand.agent-guidance-bundle/v1",
    operation: "agent startup",
    "run-id": guidance["run-id"],
    attempt: guidance.attempt,
    invocation: guidance.invocation,
    harness,
    "native-session-id": session,
    identity: bootstrap.identity,
    "strand-id": "fixture-identity-strand",
    workspace: responseWorkspace,
    transport: "native-v1",
    "bundle-sha256": mode === "digest-mismatch" ? "b".repeat(64) : digest,
    "capability-sha256": guidance["capability-sha256"],
    context,
  };
  if (mode === "session-mismatch")
    response["native-session-id"] = "wrong-session";
  if (mode === "duplicate-key") {
    const encoded = JSON.stringify(response);
    process.stdout.write(`${encoded.slice(0, -1)},"attempt":99}\n`);
  } else {
    process.stdout.write(`${JSON.stringify(response)}\n`);
  }
  process.exit(0);
}

if (operation[1] === "guidance") {
  const kind = operation[2];
  const receipt = JSON.parse(take("--receipt"));
  const response = {
    schema: "millstrand.agent-guidance-receipt-result/v1",
    result:
      mode === "ack-replay"
        ? "replayed"
        : mode === "ack-ignored"
          ? "ignored"
          : "recorded",
    state: kind === "acknowledge" ? "acknowledged" : "failed",
    "run-id": receipt["run-id"],
    attempt: receipt.attempt,
    invocation: receipt.invocation,
    harness: receipt.harness,
    "native-session-id": receipt["native-session-id"],
    transport: receipt.transport,
    "bundle-sha256": receipt["bundle-sha256"],
    "capability-sha256": receipt["capability-sha256"],
  };
  process.stdout.write(`${JSON.stringify(response)}\n`);
  process.exit(0);
}
throw new Error(`unexpected operation: ${operation.join(" ")}`);
