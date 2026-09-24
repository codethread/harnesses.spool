import { beforeEach, describe, expect, it, vi } from "vitest";
const resolved = {
  identity: "native-parent",
  strandId: "i1",
  runId: "r1",
  result: "minted",
  instruction: "Your Millstrand identity is native-parent.",
  nativeSessionId: "s1",
  workspace: "/world/.millstrand",
};
vi.mock("./native-identity.js", () => ({
  DEBUG_MILLSTRAND_IDENTITY_FLAG: "debug-millstrand-identity",
  formatNativeIdentityState: vi.fn(),
  getNativeIdentityInputs: vi.fn(() => ({ runId: "managed" })),
  nativeIdentityModel: vi.fn(() => "provider/model"),
  resolveNativeIdentity: vi.fn(async () => resolved),
}));
import extension, { createMillstrandIdentityLifecycle } from "./index.js";
import { resolveNativeIdentity } from "./native-identity.js";
function fixture() {
  const handlers = new Map<string, (...args: any[]) => any>();
  const pi = {
    exec: vi.fn(),
    getFlag: () => false,
    registerFlag: vi.fn(),
    on: (name: string, handler: (...args: any[]) => any) =>
      handlers.set(name, handler),
    events: { on: vi.fn(), emit: vi.fn() },
  };
  const ctx = {
    cwd: "/repo",
    hasUI: false,
    thinkingLevel: "high",
    sessionManager: { getSessionId: () => "s1" },
  };
  return { pi, ctx, handlers };
}
describe("native identity lifecycle", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(resolveNativeIdentity).mockImplementation(
      async (_exec, options) => {
        options.inputs?.();
        return resolved as any;
      },
    );
  });
  it("awaits startup and contributes exactly once on each reconstructed standalone prompt", async () => {
    const { pi, ctx, handlers } = fixture();
    extension(pi as any);
    expect(handlers.get("input")?.()).toEqual({ action: "handled" });
    for (let n = 0; n < 3; n++) {
      await handlers.get("session_start")?.({}, ctx);
      const value = await handlers.get("before_agent_start")?.(
        { systemPrompt: "ordinary policy\nuser append" },
        ctx,
      );
      expect(value.systemPrompt).toBe(
        `ordinary policy\nuser append\n\n${resolved.instruction}`,
      );
      expect(handlers.get("input")?.()).toBeUndefined();
    }
  });
  it("leaves inactive sessions usable without adding identity context", async () => {
    const { pi, ctx, handlers } = fixture();
    vi.mocked(resolveNativeIdentity).mockResolvedValueOnce(null);
    extension(pi as any);
    await handlers.get("session_start")?.({}, ctx);
    expect(handlers.get("input")?.()).toBeUndefined();
    const abort = vi.fn();
    const payload = { messages: [] };
    expect(
      handlers.get("before_provider_request")?.({ payload }, { abort }),
    ).toBeUndefined();
    expect(abort).not.toHaveBeenCalled();
    expect(
      handlers.get("before_agent_start")?.({ systemPrompt: "ordinary policy" }),
    ).toBeUndefined();
  });
  it("composes with a prompt owner without installing another renderer", async () => {
    const { pi, ctx, handlers } = fixture();
    const lifecycle = createMillstrandIdentityLifecycle(pi as any);
    await lifecycle.sessionStart(ctx as any);
    expect(handlers.has("before_agent_start")).toBe(false);
    expect(lifecycle.guidanceContext).toEqual({
      selection: { kind: "unmanaged" },
      bundle: null,
    });
    expect(lifecycle.identityState).toMatchObject({
      status: "bound",
      instruction: resolved.instruction,
    });
  });
  it("blocks input and requests after registration failure", async () => {
    const { pi, ctx } = fixture();
    const lifecycle = createMillstrandIdentityLifecycle(pi as any);
    vi.mocked(resolveNativeIdentity).mockRejectedValueOnce(
      new Error("startup failed"),
    );
    await expect(lifecycle.sessionStart(ctx as any)).rejects.toThrow(
      "startup failed",
    );
    expect(lifecycle.input()).toEqual({ action: "handled" });
    const abort = vi.fn();
    lifecycle.beforeProviderRequest({ payload: {} }, { abort });
    expect(abort).toHaveBeenCalledOnce();
  });
  it("does not reuse managed correlation after native session changes or reloads", async () => {
    const { pi, ctx } = fixture();
    const lifecycle = createMillstrandIdentityLifecycle(pi as any);
    await lifecycle.sessionStart(ctx as any);
    ctx.sessionManager.getSessionId = () => "child";
    const inputs: unknown[] = [];
    vi.mocked(resolveNativeIdentity).mockImplementation(
      async (_exec, options) => {
        inputs.push(options.inputs?.());
        return {
          ...resolved,
          nativeSessionId: "child",
          identity: "native-child",
        } as any;
      },
    );
    await lifecycle.sessionStart(ctx as any);
    expect(inputs[0]).toEqual({
      runId: undefined,
      parentIdentity: "native-parent",
    });
    await lifecycle.sessionStart(ctx as any);
    expect(inputs[1]).toEqual({ runId: undefined });
  });
});
