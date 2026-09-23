import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, realpathSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getNativeIdentityInputs,
  projectWorkspace,
  resolveNativeIdentity,
} from "./native-identity.js";

const roots: string[] = [];
function project() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "pi-native-")));
  roots.push(root);
  execFileSync("git", ["init", "-q", root]);
  mkdirSync(join(root, ".millstrand"));
  return root;
}
afterEach(() =>
  roots
    .splice(0)
    .forEach((root) => rmSync(root, { recursive: true, force: true })),
);
const ok = (stdout: string) => ({ stdout, stderr: "", code: 0, killed: false });

describe("native identity boundary", () => {
  it("derives canonical routing, records actual host data and only minimal correlation", async () => {
    const root = project();
    const exec = vi.fn(async (command: string) =>
      command === "git"
        ? ok(join(root, ".git"))
        : ok(
            JSON.stringify({
              operation: "agent native-startup",
              identity: "native-parent",
              "strand-id": "i1",
              "run-id": "r1",
              instruction: "Your Millstrand identity is native-parent.",
              result: "minted",
            }),
          ),
    );
    const result = await resolveNativeIdentity(exec as any, {
      cwd: root,
      nativeSessionId: "actual-session",
      runId: "r1",
      model: "provider/model",
      thinkingLevel: "high",
    });
    expect(result).toMatchObject({
      identity: "native-parent",
      runId: "r1",
      nativeSessionId: "actual-session",
      workspace: join(root, ".millstrand"),
    });
    expect(exec.mock.calls[1]).toEqual([
      "strand",
      [
        "--workspace",
        join(root, ".millstrand"),
        "--cwd",
        root,
        "agent",
        "native-startup",
        "pi",
        "actual-session",
        "--run-id",
        "r1",
        "--model",
        "provider/model",
        "--thinking-level",
        "high",
      ],
      expect.objectContaining({ cwd: root }),
    ]);
  });
  it("does nothing outside the launch project even with inherited ownership", async () => {
    const exec = vi.fn(async () => ({ ...ok(""), code: 128 }));
    const inputs = vi.fn(() => {
      throw new Error("invalid inherited correlation");
    });
    expect(
      await resolveNativeIdentity(exec as any, {
        cwd: "/tmp",
        nativeSessionId: "s",
        runId: "parent",
        inputs,
      }),
    ).toBeNull();
    expect(inputs).not.toHaveBeenCalled();
    expect(exec).toHaveBeenCalledTimes(1);
    expect(
      getNativeIdentityInputs({ getFlag: () => false } as any, {
        PI_SUBAGENT: "1",
        MILLSTRAND_RUN_ID: "parent",
        MILLSTRAND_PI_PARENT_IDENTITY: "native-parent",
        MILLSTRAND_AGENT_ID: "wrong",
        MILLSTRAND_WORKSPACE: "/wrong",
      }),
    ).toEqual({ runId: undefined, parentIdentity: "native-parent" });
  });
  it("still rejects malformed inherited correlation inside an admitted project", async () => {
    const root = project();
    const exec = vi.fn(async () => ok(join(root, ".git")));
    await expect(
      resolveNativeIdentity(exec as any, {
        cwd: root,
        nativeSessionId: "s",
        inputs: () =>
          getNativeIdentityInputs({ getFlag: () => false } as any, {
            MILLSTRAND_RUN_ID: " ",
          }),
      }),
    ).rejects.toThrow("MILLSTRAND_RUN_ID must be a non-empty string");
    expect(exec).toHaveBeenCalledTimes(1);
  });
  it("fails visibly on actual startup failure", async () => {
    const root = project();
    const exec = vi.fn(async (command: string) =>
      command === "git"
        ? ok(join(root, ".git"))
        : { ...ok(""), code: 1, stderr: "unavailable" },
    );
    await expect(
      resolveNativeIdentity(exec as any, { cwd: root, nativeSessionId: "s" }),
    ).rejects.toThrow("unavailable");
  });
  it("routes a linked worktree to its canonical project's workspace", async () => {
    const root = project();
    const exec = vi.fn(async () => ok(join(root, ".git")));
    expect(
      await projectWorkspace(exec as any, join(root, "worktrees", "feature")),
    ).toBe(join(root, ".millstrand"));
  });
});
