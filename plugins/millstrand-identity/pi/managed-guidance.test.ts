import { describe, expect, it, vi } from "vitest";
import { sha256CanonicalJson } from "./strict-json.js";
import {
  acknowledgeManagedGuidance,
  failManagedGuidance,
  fetchManagedGuidance,
  renderManagedGuidance,
  selectManagedPiGuidance,
  stageManagedPiGuidanceSelection,
  type BoundedCommand,
  type ManagedGuidanceBundle,
} from "./managed-guidance.js";

const cwd = "/repo";
const workspace = "/world/.millstrand";
const identity = "coral-lucid-bison";
const instruction =
  "Your Millstrand identity is coral-lucid-bison. Use coral-lucid-bison for identity-bearing operations; pass `--by-identity coral-lucid-bison` explicitly. Do not invent another identity.";

function fixture(
  overrides: {
    runId?: string;
    attempt?: number;
    invocation?: string;
    session?: string;
    appends?: string[];
  } = {},
) {
  const runId = overrides.runId ?? "run-1";
  const attempt = overrides.attempt ?? 1;
  const invocation = overrides.invocation ?? "invocation-1";
  const session = overrides.session ?? "pi-session-1";
  const context = {
    schema: "millstrand.agent-managed-context/v1" as const,
    "identity-instruction": instruction,
    "appended-system-prompts": overrides.appends ?? [
      "first frozen",
      "same",
      "same",
    ],
  };
  const bundleDigest = sha256CanonicalJson([runId, workspace, context]);
  const capabilityDigest = "a".repeat(64);
  const metadata = {
    schema: "millstrand.agent-guidance-bootstrap/v1" as const,
    transport: "native-v1" as const,
    "run-id": runId,
    attempt,
    invocation,
    harness: "pi" as const,
    "bundle-sha256": bundleDigest,
    "capability-sha256": capabilityDigest,
  };
  const bootstrap = {
    schema: "millstrand.agent-managed-bootstrap/v1" as const,
    "run-id": runId,
    harness: "pi" as const,
    identity,
    "reservation-id": "reservation-1",
    cwd,
    workspace,
    attempt,
    invocation,
    scope: "root" as const,
    "expected-native-session-id": session,
  };
  const bundle: ManagedGuidanceBundle = {
    schema: "millstrand.agent-guidance-bundle/v1",
    operation: "agent startup",
    "run-id": runId,
    attempt,
    invocation,
    harness: "pi",
    "native-session-id": session,
    identity,
    "strand-id": "identity-strand",
    workspace,
    transport: "native-v1",
    "bundle-sha256": bundleDigest,
    "capability-sha256": capabilityDigest,
    context,
  };
  const env = {
    MILLSTRAND_MANAGED_GUIDANCE: JSON.stringify(metadata),
    MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify(bootstrap),
  };
  const selection = selectManagedPiGuidance(session, cwd, env);
  if (selection.kind !== "native-v1")
    throw new Error("fixture did not select native-v1");
  return {
    runId,
    attempt,
    invocation,
    session,
    metadata,
    bootstrap,
    bundle,
    env,
    selection,
  };
}

function receiptResult(
  f: ReturnType<typeof fixture>,
  state: "acknowledged" | "failed",
  result = "recorded",
) {
  return {
    schema: "millstrand.agent-guidance-receipt-result/v1",
    result,
    state,
    "run-id": f.runId,
    attempt: f.attempt,
    invocation: f.invocation,
    harness: "pi",
    "native-session-id": f.session,
    transport: "native-v1",
    "bundle-sha256": f.metadata["bundle-sha256"],
    "capability-sha256": f.metadata["capability-sha256"],
  };
}

describe("managed Pi guidance selection", () => {
  it("preserves unmanaged identity and old-spool legacy suppression when guidance is absent", () => {
    expect(selectManagedPiGuidance("session", cwd, {})).toEqual({
      kind: "unmanaged",
    });
    expect(
      selectManagedPiGuidance("session", cwd, {
        MILLSTRAND_AGENT_ID: "ambient-parent",
      }),
    ).toEqual({ kind: "unmanaged" });
    expect(
      selectManagedPiGuidance("session", cwd, { MILLSTRAND_RUN_ID: "old-run" }),
    ).toEqual({
      kind: "legacy",
      reason: "managed metadata has no guidance document",
    });
  });

  it("accepts only the closed explicit legacy shape", () => {
    expect(
      selectManagedPiGuidance("session", cwd, {
        MILLSTRAND_MANAGED_GUIDANCE: JSON.stringify({
          schema: "millstrand.agent-guidance-bootstrap/v1",
          transport: "legacy",
        }),
      }),
    ).toEqual({ kind: "legacy", reason: "legacy selected" });
    expect(() =>
      selectManagedPiGuidance("session", cwd, {
        MILLSTRAND_MANAGED_GUIDANCE:
          '{"schema":"millstrand.agent-guidance-bootstrap/v1","transport":"legacy","transport":"native-v1"}',
      }),
    ).toThrow("duplicate object key");
  });

  it("rejects required fields supplied only through a __proto__ value", () => {
    const f = fixture();
    expect(() =>
      selectManagedPiGuidance(f.session, cwd, {
        MILLSTRAND_MANAGED_GUIDANCE: `{"__proto__":${JSON.stringify(f.metadata)}}`,
        MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify(f.bootstrap),
      }),
    ).toThrow("response keys do not match the closed v1 schema");
  });

  it("rejects missing, changed, oversized, provider, session, and fence metadata", () => {
    const f = fixture();
    const cases: Array<[string, NodeJS.ProcessEnv]> = [
      [
        "missing bootstrap",
        { MILLSTRAND_MANAGED_GUIDANCE: JSON.stringify(f.metadata) },
      ],
      [
        "provider",
        {
          ...f.env,
          MILLSTRAND_MANAGED_GUIDANCE: JSON.stringify({
            ...f.metadata,
            harness: "codex",
          }),
        },
      ],
      [
        "session",
        {
          ...f.env,
          MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify({
            ...f.bootstrap,
            "expected-native-session-id": "other",
          }),
        },
      ],
      [
        "attempt",
        {
          ...f.env,
          MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify({
            ...f.bootstrap,
            attempt: 2,
          }),
        },
      ],
      [
        "oversized",
        { MILLSTRAND_MANAGED_GUIDANCE: `{"x":"${"x".repeat(70_000)}"}` },
      ],
    ];
    for (const [, env] of cases) {
      expect(() => selectManagedPiGuidance(f.session, cwd, env)).toThrow();
    }
  });

  it("retains both validated documents when cross-document or host fences reject selection", () => {
    const f = fixture();
    for (const scenario of [
      { key: "run-id", value: "other-run", message: "run-id fence mismatch" },
      { key: "attempt", value: 2, message: "attempt fence mismatch" },
      {
        key: "invocation",
        value: "other-invocation",
        message: "invocation fence mismatch",
      },
      {
        key: "expected-native-session-id",
        value: "other-session",
        message: "native session fence mismatch",
      },
    ] as const) {
      const bootstrap = { ...f.bootstrap, [scenario.key]: scenario.value };
      const staged = stageManagedPiGuidanceSelection(f.session, cwd, {
        ...f.env,
        MILLSTRAND_MANAGED_BOOTSTRAP: JSON.stringify(bootstrap),
      });
      expect(staged).toMatchObject({
        kind: "rejected",
        selection: {
          kind: "native-v1",
          metadata: f.metadata,
          bootstrap,
        },
        message: expect.stringContaining(scenario.message),
      });
    }
    const cwdStage = stageManagedPiGuidanceSelection(
      f.session,
      "/other-repo",
      f.env,
    );
    expect(cwdStage).toMatchObject({
      kind: "rejected",
      selection: { metadata: f.metadata, bootstrap: f.bootstrap },
      message: expect.stringContaining("cwd fence mismatch"),
    });
  });

  it("never lets a Pi child consume inherited root guidance", () => {
    const f = fixture();
    expect(
      selectManagedPiGuidance(f.session, cwd, { ...f.env, PI_SUBAGENT: "1" }),
    ).toEqual({
      kind: "unmanaged",
    });
  });
});

describe("managed Pi bundle and receipts", () => {
  it("requests every launch fence and preserves fresh/resume/retry identity", async () => {
    for (const f of [
      fixture({ runId: "fresh", attempt: 1, invocation: "fresh-invocation" }),
      fixture({ runId: "resume", attempt: 1, invocation: "resume-invocation" }),
      fixture({ runId: "fresh", attempt: 2, invocation: "retry-invocation" }),
    ]) {
      const command = vi.fn(async (..._args: Parameters<BoundedCommand>) => ({
        stdout: JSON.stringify(f.bundle),
        stderr: "",
        code: 0,
      }));
      await expect(
        fetchManagedGuidance(f.selection, f.session, command, f.env),
      ).resolves.toEqual(f.bundle);
      const args = command.mock.calls[0]?.[0] as string[];
      expect(args).toContain(f.session);
      expect(args).toContain("--guidance");
      expect(args).toContain("--bootstrap");
      expect(JSON.parse(args[args.indexOf("--guidance") + 1])).toMatchObject({
        "run-id": f.runId,
        attempt: f.attempt,
        invocation: f.invocation,
      });
    }
  });

  it("rejects malformed, duplicate-key, oversized, digest, session, capability and invocation responses", async () => {
    const f = fixture();
    const changed = [
      "{not json}",
      JSON.stringify(f.bundle).replace(/}$/, ',"run-id":"duplicate"}'),
      JSON.stringify({ ...f.bundle, "native-session-id": "other" }),
      JSON.stringify({ ...f.bundle, invocation: "other" }),
      JSON.stringify({ ...f.bundle, "capability-sha256": "b".repeat(64) }),
      JSON.stringify({ ...f.bundle, "bundle-sha256": "b".repeat(64) }),
      JSON.stringify({ ...f.bundle, excess: true }),
      `{"padding":"${"x".repeat(1024 * 1024)}"}`,
    ];
    for (const stdout of changed) {
      const command: BoundedCommand = async () => ({
        stdout,
        stderr: "",
        code: 0,
      });
      await expect(
        fetchManagedGuidance(f.selection, f.session, command, f.env),
      ).rejects.toThrow();
    }
  });

  it("renders identity first, preserves duplicate positions and appends one current-run footer", () => {
    const f = fixture({ appends: ["first\n", "same", "same"] });
    const contribution = renderManagedGuidance(f.bundle);
    expect(contribution.indexOf(instruction)).toBeLessThan(
      contribution.indexOf("first\n"),
    );
    expect(contribution.match(/same/g)).toHaveLength(2);
    expect(contribution.match(/Current Millstrand run:/g)).toHaveLength(1);
    expect(contribution).toContain(
      `Pass --workspace ${JSON.stringify(workspace)} on Strand commands.`,
    );
  });

  it("rejects rendered contributions above the Pi profile bound without truncation", () => {
    const f = fixture({ appends: ["x".repeat(66_000)] });
    expect(() => renderManagedGuidance(f.bundle)).toThrow(
      "exceeds 65536 bytes",
    );
  });

  it("accepts acknowledgement replay, rejects ignored receipts, and records fenced failure", async () => {
    const f = fixture();
    const replay: BoundedCommand = async () => ({
      stdout: JSON.stringify(receiptResult(f, "acknowledged", "replayed")),
      stderr: "",
      code: 0,
    });
    await expect(
      acknowledgeManagedGuidance(f.selection, f.session, replay, f.env),
    ).resolves.toBeUndefined();

    const ignored: BoundedCommand = async () => ({
      stdout: JSON.stringify(receiptResult(f, "acknowledged", "ignored")),
      stderr: "",
      code: 0,
    });
    await expect(
      acknowledgeManagedGuidance(f.selection, f.session, ignored, f.env),
    ).rejects.toThrow("did not accept");

    const failure = vi.fn(async (..._args: Parameters<BoundedCommand>) => ({
      stdout: JSON.stringify(receiptResult(f, "failed")),
      stderr: "",
      code: 0,
    }));
    await failManagedGuidance(
      f.selection,
      f.session,
      "rendering",
      "too-large",
      "diagnostic",
      failure,
      f.env,
    );
    const receipt = JSON.parse(
      (failure.mock.calls[0]?.[0] as string[]).at(-1) ?? "null",
    );
    expect(receipt).toMatchObject({
      outcome: "failed",
      stage: "rendering",
      code: "too-large",
      "run-id": f.runId,
      attempt: f.attempt,
      invocation: f.invocation,
    });
  });
});
