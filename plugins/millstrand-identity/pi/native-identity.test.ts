import { describe, expect, it, vi } from "vitest";
import {
  getNativeIdentityInputs,
  isLegacyManagedPiEnvironment,
  resolveNativeIdentity,
} from "./native-identity.js";

describe("resolveNativeIdentity", () => {
  it("binds the actual Pi session with a cwd-scoped absolute workspace route", async () => {
    const exec = vi.fn(async () => ({
      stdout: JSON.stringify({
        operation: "identity startup",
        identity: "warm-silver-lemur",
        "strand-id": "abc12",
        result: "minted",
        instruction:
          "Your Millstrand identity is warm-silver-lemur. Use it as `--owner warm-silver-lemur` for `kanban claim` and `--by-identity warm-silver-lemur` for Kanban notes, workflow mutations, and agent operations. Keep `--identity` and `--parent-identity` for native-session references. Inspect live help; never pass an unsupported flag or invent another identity.",
      }),
      stderr: "",
      code: 0,
      killed: false,
    }));

    const result = await resolveNativeIdentity(exec as any, {
      cwd: "/repo/worktree",
      nativeSessionId: "pi-session-1",
      workspace: "./world/.millstrand",
      identity: "warm-silver-lemur",
      parentIdentity: "calm-green-otter",
      model: "gpt-5",
      thinkingLevel: "high",
    });

    expect(exec).toHaveBeenCalledWith(
      "strand",
      [
        "--cwd",
        "/repo/worktree",
        "--workspace",
        "/repo/worktree/world/.millstrand",
        "identity",
        "startup",
        "--identity",
        "warm-silver-lemur",
        "--parent-identity",
        "calm-green-otter",
        "--model",
        "gpt-5",
        "--thinking-level",
        "high",
        "pi",
        "pi-session-1",
      ],
      expect.objectContaining({ cwd: "/repo/worktree", timeout: 10_000 }),
    );
    expect(result).toMatchObject({
      identity: "warm-silver-lemur",
      strandId: "abc12",
      result: "minted",
      nativeSessionId: "pi-session-1",
      workspace: "/repo/worktree/world/.millstrand",
    });
  });

  it("surfaces unavailable Strand and malformed responses without fabricating identity", async () => {
    const unavailable = vi.fn(async () => ({
      stdout: "",
      stderr: "connection refused",
      code: 1,
      killed: false,
    }));
    await expect(
      resolveNativeIdentity(unavailable as any, {
        cwd: "/repo",
        nativeSessionId: "session-1",
      }),
    ).rejects.toThrow(
      "Strand identity startup failed (exit 1): connection refused",
    );

    const wrongOperation = vi.fn(async () => ({
      stdout: JSON.stringify({
        operation: "identity reserve",
        identity: "wrong-operation",
        "strand-id": "abc12",
        result: "minted",
        instruction: "wrong operation instruction",
      }),
      stderr: "",
      code: 0,
      killed: false,
    }));
    await expect(
      resolveNativeIdentity(wrongOperation as any, {
        cwd: "/repo",
        nativeSessionId: "session-1",
      }),
    ).rejects.toThrow("unexpected identity startup operation");

    const malformed = vi.fn(async () => ({
      stdout:
        '{"operation":"identity startup","identity":"invented-without-contract"}',
      stderr: "",
      code: 0,
      killed: false,
    }));
    await expect(
      resolveNativeIdentity(malformed as any, {
        cwd: "/repo",
        nativeSessionId: "session-1",
      }),
    ).rejects.toThrow("incomplete identity startup response");
  });
});

describe("native identity inputs", () => {
  it("uses explicit host settings and child parent metadata without adopting ambient ownership", () => {
    const getFlag = vi.fn((name: string) =>
      name === "millstrand-identity"
        ? "existing-friendly-name"
        : name === "millstrand-workspace"
          ? "/explicit/world"
          : undefined,
    );
    expect(
      getNativeIdentityInputs({ getFlag } as any, {
        MILLSTRAND_AGENT_ID: "ambient-owner-is-not-a-supply",
        MILLSTRAND_PI_PARENT_IDENTITY: "parent-friendly-name",
      }),
    ).toEqual({
      identity: "existing-friendly-name",
      parentIdentity: "parent-friendly-name",
      workspace: "/explicit/world",
    });
  });

  it("suppresses only legacy managed runs", () => {
    expect(isLegacyManagedPiEnvironment({ MILLSTRAND_RUN_ID: "run-1" })).toBe(
      true,
    );
    expect(
      isLegacyManagedPiEnvironment({ MILLSTRAND_AGENT_ID: "ambient-parent" }),
    ).toBe(false);
    expect(isLegacyManagedPiEnvironment({})).toBe(false);
  });
});
