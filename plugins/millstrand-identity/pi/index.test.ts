import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  MILLSTRAND_GUIDANCE_CONTEXT_EVENT,
  MILLSTRAND_IDENTITY_CONTEXT_EVENT,
  MILLSTRAND_IDENTITY_STATE_EVENT,
} from "./context.js";

const resolvedIdentity = {
  identity: "warm-silver-lemur",
  strandId: "identity-1",
  result: "minted" as const,
  instruction: "Your Millstrand identity is warm-silver-lemur.",
  nativeSessionId: "session-1",
  workspace: "/world/.millstrand",
};

vi.mock("./managed-guidance.js", () => ({
  failManagedGuidance: vi.fn(),
  fetchManagedGuidance: vi.fn(),
  ManagedGuidanceAdapterError: class ManagedGuidanceAdapterError extends Error {
    stage = "startup";
    receiptRouteTrusted = true;
  },
  stageManagedPiGuidanceSelection: vi.fn(() => ({
    kind: "selected",
    selection: { kind: "unmanaged" },
  })),
}));

vi.mock("./native-identity.js", () => ({
  DEBUG_MILLSTRAND_IDENTITY_FLAG: "debug-millstrand-identity",
  MILLSTRAND_IDENTITY_FLAG: "millstrand-identity",
  MILLSTRAND_WORKSPACE_FLAG: "millstrand-workspace",
  formatNativeIdentityState: vi.fn(() => "{}"),
  getNativeIdentityInputs: vi.fn(() => ({})),
  nativeIdentityModel: vi.fn(() => "gpt"),
  resolveNativeIdentity: vi.fn(async () => resolvedIdentity),
}));

import millstrandIdentityExtension from "./index.js";

type Handler = (...args: any[]) => any;

describe("Millstrand Pi identity data extension", () => {
  beforeEach(() => vi.clearAllMocks());

  it("publishes resolved identity and guidance data without owning prompt rendering", async () => {
    const handlers = new Map<string, Handler>();
    const eventHandlers = new Map<string, Handler>();
    const emitted: Array<[string, unknown]> = [];
    const pi = {
      exec: vi.fn(),
      getFlag: vi.fn(() => false),
      registerFlag: vi.fn(),
      on: vi.fn((name: string, handler: Handler) =>
        handlers.set(name, handler),
      ),
      events: {
        on: vi.fn((name: string, handler: Handler) =>
          eventHandlers.set(name, handler),
        ),
        emit: vi.fn((name: string, value: unknown) =>
          emitted.push([name, value]),
        ),
      },
    };
    millstrandIdentityExtension(pi as any);

    expect(handlers.has("before_agent_start")).toBe(false);
    await handlers.get("session_start")?.(
      {},
      {
        cwd: "/repo",
        hasUI: false,
        model: { id: "gpt" },
        thinkingLevel: "high",
        sessionManager: { getSessionId: () => "session-1" },
        signal: new AbortController().signal,
      },
    );

    expect(emitted).toContainEqual([
      MILLSTRAND_IDENTITY_CONTEXT_EVENT,
      resolvedIdentity,
    ]);
    expect(emitted).toContainEqual([
      MILLSTRAND_IDENTITY_STATE_EVENT,
      { status: "bound", ...resolvedIdentity },
    ]);
    expect(emitted).toContainEqual([
      MILLSTRAND_GUIDANCE_CONTEXT_EVENT,
      { selection: { kind: "unmanaged" }, bundle: null },
    ]);
  });
});
