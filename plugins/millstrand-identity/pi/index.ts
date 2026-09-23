import type {
  ExtensionAPI,
  ExtensionContext,
} from "@earendil-works/pi-coding-agent";
import {
  MILLSTRAND_GUIDANCE_CONTEXT_EVENT,
  MILLSTRAND_GUIDANCE_FAILURE_EVENT,
  MILLSTRAND_IDENTITY_CONTEXT_EVENT,
  MILLSTRAND_IDENTITY_STATE_EVENT,
  type MillstrandGuidanceContext,
  type MillstrandIdentityState,
} from "./context.js";
import {
  DEBUG_MILLSTRAND_IDENTITY_FLAG,
  formatNativeIdentityState,
  getNativeIdentityInputs,
  nativeIdentityModel,
  resolveNativeIdentity,
} from "./native-identity.js";

export type MillstrandIdentityLifecycle = {
  readonly identityState: MillstrandIdentityState;
  readonly guidanceContext: MillstrandGuidanceContext;
  registerFlags(): void;
  sessionStart(ctx: ExtensionContext): Promise<void>;
  sessionShutdown(): void;
  input(): { action: "handled" } | undefined;
  beforeProviderRequest(
    event: { payload: unknown },
    ctx: { abort(): void },
  ): unknown;
  reportGuidanceFailure(message: string): void;
};

/** Data lifecycle for the consumer-owned prompt renderer. No task guidance transport. */
export function createMillstrandIdentityLifecycle(
  pi: ExtensionAPI,
): MillstrandIdentityLifecycle {
  let identityState: MillstrandIdentityState = { status: "pending" };
  const guidanceContext: MillstrandGuidanceContext = {
    selection: { kind: "unmanaged" },
    bundle: null,
  };
  let blocked = true;
  let managedSessionId: string | undefined;
  const publishState = (state: MillstrandIdentityState) => {
    identityState = state;
    pi.events.emit(MILLSTRAND_IDENTITY_STATE_EVENT, state);
  };
  pi.events.on(MILLSTRAND_GUIDANCE_FAILURE_EVENT, () => {
    blocked = true;
  });
  return {
    get identityState() {
      return identityState;
    },
    get guidanceContext() {
      return guidanceContext;
    },
    registerFlags() {
      pi.registerFlag(DEBUG_MILLSTRAND_IDENTITY_FLAG, {
        description:
          "Resolve and print native Pi identity and registration, then exit",
        type: "boolean",
        default: false,
      });
    },
    async sessionStart(ctx) {
      const nativeSessionId = ctx.sessionManager.getSessionId();
      const previous = identityState.status === "bound" ? identityState : null;
      blocked = true;
      pi.events.emit(MILLSTRAND_IDENTITY_CONTEXT_EVENT, null);
      pi.events.emit(MILLSTRAND_GUIDANCE_CONTEXT_EVENT, guidanceContext);
      publishState({ status: "pending" });
      try {
        const resolved = await resolveNativeIdentity(pi.exec, {
          cwd: ctx.cwd,
          nativeSessionId,
          parentSessionFile: ctx.sessionManager.getHeader?.()?.parentSession,
          model: nativeIdentityModel(ctx),
          thinkingLevel: ctx.thinkingLevel,
          signal: ctx.signal,
          inputs: () => {
            const inputs = getNativeIdentityInputs(pi);
            if (inputs.runId && !managedSessionId)
              managedSessionId = nativeSessionId;
            if (managedSessionId && managedSessionId !== nativeSessionId)
              inputs.runId = undefined;
            // A fresh session gets attribution, never parent ownership.
            if (previous && previous.nativeSessionId !== nativeSessionId)
              inputs.parentIdentity = previous.identity;
            return inputs;
          },
        });
        if (resolved) {
          publishState({ status: "bound", ...resolved });
          pi.events.emit(MILLSTRAND_IDENTITY_CONTEXT_EVENT, resolved);
        } else {
          publishState({
            status: "suppressed",
            reason: "outside a Millstrand project",
            nativeSessionId,
          });
        }
        blocked = false;
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        publishState({ status: "error", error: message, nativeSessionId });
        if (ctx.hasUI)
          ctx.ui.notify(`[millstrand-identity] ${message}`, "error");
        process.stderr.write(`[millstrand-identity] ${message}\n`);
        throw error;
      }
      if (pi.getFlag(DEBUG_MILLSTRAND_IDENTITY_FLAG) === true) {
        process.stdout.write(
          `${formatNativeIdentityState(identityState, ctx.cwd)}\n`,
        );
        process.exit(0);
      }
    },
    sessionShutdown() {
      blocked = true;
      pi.events.emit(MILLSTRAND_IDENTITY_CONTEXT_EVENT, null);
      pi.events.emit(MILLSTRAND_GUIDANCE_CONTEXT_EVENT, null);
    },
    input() {
      if (blocked) return { action: "handled" };
    },
    beforeProviderRequest(event, ctx) {
      if (blocked) {
        ctx.abort();
        return event.payload;
      }
    },
    reportGuidanceFailure(message) {
      blocked = true;
      pi.events.emit(MILLSTRAND_GUIDANCE_FAILURE_EVENT, { message });
    },
  };
}

/** Standalone adapter; composed prompt owners use the lifecycle instead. */
export default function millstrandIdentityExtension(pi: ExtensionAPI) {
  const lifecycle = createMillstrandIdentityLifecycle(pi);
  lifecycle.registerFlags();
  pi.on("input", () => lifecycle.input());
  pi.on("before_provider_request", (event, ctx) =>
    lifecycle.beforeProviderRequest(event, ctx),
  );
  pi.on("session_shutdown", () => lifecycle.sessionShutdown());
  pi.on("session_start", (_event, ctx) => lifecycle.sessionStart(ctx));
  pi.on("before_agent_start", (event) => {
    const state = lifecycle.identityState;
    if (state.status === "bound")
      return { systemPrompt: `${event.systemPrompt}\n\n${state.instruction}` };
  });
}
export * from "./context.js";
export * from "./native-identity.js";
