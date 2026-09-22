import { mkdirSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getNativeIdentityInputs,
  hasMillstrandProject,
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

describe("hasMillstrandProject", () => {
  const roots: string[] = [];
  const temporaryDirectory = () => {
    const root = mkdtempSync(join(tmpdir(), "millstrand-project-"));
    roots.push(root);
    return root;
  };

  afterEach(() => {
    for (const root of roots.splice(0))
      rmSync(root, { recursive: true, force: true });
  });

  it("accepts a project that carries its workspace at the session root without consulting Git", async () => {
    const project = temporaryDirectory();
    mkdirSync(join(project, ".millstrand"));
    const exec = vi.fn();

    await expect(hasMillstrandProject(exec as any, project)).resolves.toBe(
      true,
    );
    expect(exec).not.toHaveBeenCalled();
  });

  it("accepts a session directory inside the canonical Git project root", async () => {
    const project = temporaryDirectory();
    mkdirSync(join(project, ".millstrand"));
    const nested = join(project, "nested", "cwd");
    mkdirSync(nested, { recursive: true });
    const exec = vi.fn(async () => ({
      stdout: `${join(project, ".git")}\n`,
      stderr: "",
      code: 0,
      killed: false,
    }));

    await expect(hasMillstrandProject(exec as any, nested)).resolves.toBe(true);
    expect(exec).toHaveBeenCalledWith(
      "git",
      ["rev-parse", "--path-format=absolute", "--git-common-dir"],
      expect.objectContaining({ cwd: nested, timeout: 5_000 }),
    );
  });

  it("rejects unrelated, unsupported, and unresolvable projects", async () => {
    const project = temporaryDirectory();
    const unrelated = vi.fn(async () => ({
      stdout: `${join(project, ".git")}\n`,
      stderr: "",
      code: 0,
      killed: false,
    }));
    await expect(hasMillstrandProject(unrelated as any, project)).resolves.toBe(
      false,
    );

    mkdirSync(join(project, ".millstrand"));
    mkdirSync(join(project, "nested"), { recursive: true });
    const unsupportedLayout = vi.fn(async () => ({
      stdout: `${join(project, "bare.git")}\n`,
      stderr: "",
      code: 0,
      killed: false,
    }));
    await expect(
      hasMillstrandProject(unsupportedLayout as any, join(project, "nested")),
    ).resolves.toBe(false);

    const unavailable = vi.fn(async () => {
      throw new Error("spawn git ENOENT");
    });
    await expect(
      hasMillstrandProject(unavailable as any, join(project, "nested")),
    ).resolves.toBe(false);

    const failed = vi.fn(async () => ({
      stdout: "",
      stderr: "not a git repository",
      code: 128,
      killed: false,
    }));
    await expect(
      hasMillstrandProject(failed as any, join(project, "nested")),
    ).resolves.toBe(false);
  });
});
