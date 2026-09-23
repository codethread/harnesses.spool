import { describe, expect, it } from "vitest";
import {
  buildMillstrandChildEnvironment,
  parseMillstrandIdentityContext,
} from "./context.js";

describe("parseMillstrandIdentityContext", () => {
  it("accepts reset and validates session-scoped identity messages", () => {
    expect(parseMillstrandIdentityContext(null)).toBeNull();
    expect(
      parseMillstrandIdentityContext({
        identity: "native-parent",
        instruction: "parent instruction",
        nativeSessionId: "parent-session",
        workspace: "/native/world",
      }),
    ).toEqual({
      identity: "native-parent",
      instruction: "parent instruction",
      nativeSessionId: "parent-session",
      workspace: "/native/world",
    });
    expect(() =>
      parseMillstrandIdentityContext({ identity: "invented-without-session" }),
    ).toThrow("instruction must be a non-empty string");
  });
});

describe("buildMillstrandChildEnvironment", () => {
  it("scrubs inherited ownership/bootstrap state and passes separate parent attribution", () => {
    const child = buildMillstrandChildEnvironment(
      {
        PATH: "/bin",
        MILLSTRAND_AGENT_ID: "legacy-parent",
        MILLSTRAND_RUN_ID: "run-1",
        MILLSTRAND_WORKSPACE: "/managed/world",
        MILLSTRAND_BOOTSTRAP_V1: "bootstrap-token",
        MILLSTRAND_MANAGED_BOOTSTRAP: "managed-bootstrap",
        MILLSTRAND_MANAGED_GUIDANCE: "managed-guidance",
        MILLSTRAND_BOOTSTRAP_ATTEMPT: "attempt-token",
        MILLSTRAND_RESERVATION_ID: "reservation-token",
        MILLSTRAND_IDENTITY_TRANSPORT: "native-v1",
      },
      {
        identity: "native-parent",
        instruction: "parent instruction",
        nativeSessionId: "native-parent-session",
        workspace: "/native/world",
      },
    );

    expect(child).toMatchObject({
      PATH: "/bin",
      PI_SUBAGENT: "1",
      MILLSTRAND_PI_PARENT_IDENTITY: "native-parent",
    });
    for (const name of [
      "MILLSTRAND_AGENT_ID",
      "MILLSTRAND_RUN_ID",
      "MILLSTRAND_WORKSPACE",
      "MILLSTRAND_PI_WORKSPACE",
      "MILLSTRAND_BOOTSTRAP_V1",
      "MILLSTRAND_MANAGED_BOOTSTRAP",
      "MILLSTRAND_MANAGED_GUIDANCE",
      "MILLSTRAND_BOOTSTRAP_ATTEMPT",
      "MILLSTRAND_RESERVATION_ID",
      "MILLSTRAND_IDENTITY_TRANSPORT",
    ]) {
      expect(child).not.toHaveProperty(name);
    }
  });

  it("never infers native parent attribution from inherited ownership", () => {
    const child = buildMillstrandChildEnvironment(
      {
        MILLSTRAND_AGENT_ID: "legacy-parent",
        MILLSTRAND_RUN_ID: "legacy-run",
        MILLSTRAND_WORKSPACE: "/legacy/world",
      },
      null,
    );

    expect(child.MILLSTRAND_PI_PARENT_IDENTITY).toBeUndefined();
    expect(child.MILLSTRAND_PI_WORKSPACE).toBeUndefined();
    expect(child.MILLSTRAND_AGENT_ID).toBeUndefined();
    expect(child.MILLSTRAND_RUN_ID).toBeUndefined();
    expect(child.MILLSTRAND_WORKSPACE).toBeUndefined();
  });

  it("does not turn a bare ambient identity into parent provenance", () => {
    const child = buildMillstrandChildEnvironment(
      {
        MILLSTRAND_AGENT_ID: "ambient-not-managed",
      },
      null,
    );

    expect(child.MILLSTRAND_PI_PARENT_IDENTITY).toBeUndefined();
    expect(child.MILLSTRAND_AGENT_ID).toBeUndefined();
  });
});
