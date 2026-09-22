import type {
  ExtensionAPI,
  ExtensionContext,
} from "@earendil-works/pi-coding-agent";
import {
  MILLSTRAND_GUIDANCE_CONTEXT_EVENT,
  MILLSTRAND_GUIDANCE_FAILURE_EVENT,
  MILLSTRAND_IDENTITY_CONTEXT_EVENT,
  MILLSTRAND_IDENTITY_STATE_EVENT,
  type ActiveMillstrandIdentity,
  type MillstrandGuidanceContext,
  type MillstrandIdentityState,
} from "./context.js";
import {
  failManagedGuidance,
  fetchManagedGuidance,
  ManagedGuidanceAdapterError,
  stageManagedPiGuidanceSelection,
  type ManagedPiSelection,
} from "./managed-guidance.js";
import {
  DEBUG_MILLSTRAND_IDENTITY_FLAG,
  formatNativeIdentityState,
  getNativeIdentityInputs,
  hasMillstrandProject,
  MILLSTRAND_IDENTITY_FLAG,
  MILLSTRAND_WORKSPACE_FLAG,
  nativeIdentityModel,
  resolveNativeIdentity,
} from "./native-identity.js";

function notify(
  ctx: Pick<ExtensionContext, "hasUI" | "ui">,
  message: string,
  level: "warning" | "error",
) {
  if (ctx.hasUI) ctx.ui.notify(message, level);
}

function diagnostic(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

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

/**
 * Create the data lifecycle used by a standalone or composed Pi extension.
 *
 * The lifecycle emits plain identity and guidance data. It never renders a
 * prompt, statusline, debug panel, or other presentation.
 */
export function createMillstrandIdentityLifecycle(
  pi: ExtensionAPI,
): MillstrandIdentityLifecycle {
  let identityState: MillstrandIdentityState = { status: "pending" };
  let guidanceContext: MillstrandGuidanceContext = {
    selection: { kind: "unmanaged" },
    bundle: null,
  };
  let managedTurnBlocked = false;

  const publishIdentity = (identity: ActiveMillstrandIdentity | null) => {
    pi.events.emit(MILLSTRAND_IDENTITY_CONTEXT_EVENT, identity);
  };
  const publishState = (state: MillstrandIdentityState) => {
    identityState = state;
    pi.events.emit(MILLSTRAND_IDENTITY_STATE_EVENT, state);
  };
  const publishGuidance = (context: MillstrandGuidanceContext | null) => {
    guidanceContext = context ?? {
      selection: { kind: "unmanaged" },
      bundle: null,
    };
    pi.events.emit(MILLSTRAND_GUIDANCE_CONTEXT_EVENT, context);
  };
  const reportGuidanceFailure = (message: string) => {
    managedTurnBlocked = true;
    pi.events.emit(MILLSTRAND_GUIDANCE_FAILURE_EVENT, { message });
  };

  pi.events.on(MILLSTRAND_GUIDANCE_FAILURE_EVENT, () => {
    managedTurnBlocked = true;
  });

  return {
    get identityState() {
      return identityState;
    },
    get guidanceContext() {
      return guidanceContext;
    },
    registerFlags() {
      pi.registerFlag(MILLSTRAND_IDENTITY_FLAG, {
        description:
          "Assert an existing Millstrand identity for this exact native Pi session",
        type: "string",
      });
      pi.registerFlag(MILLSTRAND_WORKSPACE_FLAG, {
        description:
          "Use an explicit Millstrand workspace for native Pi identity resolution",
        type: "string",
      });
      pi.registerFlag(DEBUG_MILLSTRAND_IDENTITY_FLAG, {
        description:
          "Resolve and print native Pi Millstrand identity data, then exit",
        type: "boolean",
        default: false,
      });
    },
    async sessionStart(ctx) {
      const nativeSessionId = ctx.sessionManager.getSessionId();
      const nativeInputs = getNativeIdentityInputs(pi);
      let managedSelection: ManagedPiSelection = { kind: "unmanaged" };
      managedTurnBlocked = false;
      publishIdentity(null);
      publishGuidance(null);
      publishState({ status: "pending" });

      try {
        const staged = stageManagedPiGuidanceSelection(
          nativeSessionId,
          ctx.cwd,
        );
        managedSelection = staged.selection;
        if (staged.kind === "rejected") {
          managedTurnBlocked = true;
          if (staged.receiptRouteTrusted) {
            try {
              await failManagedGuidance(
                staged.selection,
                nativeSessionId,
                "validation",
                "selection-fence-mismatch",
                staged.message,
                undefined,
                process.env,
                ctx.signal,
              );
            } catch (failureError) {
              throw new Error(
                `${staged.message}; failure receipt was not recorded: ${diagnostic(failureError)}`,
              );
            }
          }
          throw new Error(staged.message);
        }
      } catch (error) {
        const message = diagnostic(error);
        managedTurnBlocked = true;
        notify(ctx, `[millstrand-guidance] ${message}`, "error");
        process.stderr.write(`[millstrand-guidance] ${message}\n`);
        throw error;
      }

      if (
        managedSelection.kind === "unmanaged" &&
        !nativeInputs.workspace &&
        !(await hasMillstrandProject(pi.exec, ctx.cwd, ctx.signal))
      ) {
        // A project without a Millstrand workspace stays a plain native
        // session: nothing is resolved, fetched, or injected.
        publishState({
          status: "suppressed",
          reason: "the project has no Millstrand workspace at its root",
          nativeSessionId,
        });
        if (pi.getFlag(DEBUG_MILLSTRAND_IDENTITY_FLAG) === true) {
          process.stdout.write(
            `${formatNativeIdentityState(identityState, ctx.cwd)}\n`,
          );
          process.exit(0);
        }
        return;
      }

      if (managedSelection.kind === "native-v1") {
        publishState({
          status: "suppressed",
          reason: "managed native-v1 owns identity and frozen guidance",
          nativeSessionId,
        });
        try {
          const bundle = await fetchManagedGuidance(
            managedSelection,
            nativeSessionId,
            undefined,
            process.env,
            ctx.signal,
          );
          publishIdentity({
            identity: bundle.identity,
            instruction: bundle.context["identity-instruction"],
            nativeSessionId,
            workspace: bundle.workspace,
          });
          publishGuidance({ selection: managedSelection, bundle });
        } catch (error) {
          managedTurnBlocked = true;
          const message = diagnostic(error);
          const stage =
            error instanceof ManagedGuidanceAdapterError
              ? error.stage
              : "startup";
          if (
            !(error instanceof ManagedGuidanceAdapterError) ||
            error.receiptRouteTrusted
          ) {
            try {
              await failManagedGuidance(
                managedSelection,
                nativeSessionId,
                stage,
                `${stage}-failed`,
                message,
                undefined,
                process.env,
                ctx.signal,
              );
            } catch (failureError) {
              throw new Error(
                `${message}; failure receipt was not recorded: ${diagnostic(failureError)}`,
              );
            }
          }
          notify(ctx, `[millstrand-guidance] ${message}`, "error");
          process.stderr.write(`[millstrand-guidance] ${message}\n`);
          throw error;
        }
      } else if (managedSelection.kind === "legacy") {
        publishState({
          status: "suppressed",
          reason: "legacy managed run uses its existing prompt transport",
          nativeSessionId,
        });
        publishGuidance({ selection: managedSelection, bundle: null });
      } else {
        try {
          const resolved = await resolveNativeIdentity(pi.exec, {
            cwd: ctx.cwd,
            nativeSessionId,
            model: nativeIdentityModel(ctx),
            thinkingLevel: ctx.thinkingLevel,
            signal: ctx.signal,
            ...nativeInputs,
          });
          publishState({ status: "bound", ...resolved });
          publishIdentity(resolved);
          publishGuidance({ selection: managedSelection, bundle: null });
        } catch (error) {
          const message = diagnostic(error);
          publishState({ status: "error", error: message, nativeSessionId });
          notify(ctx, `[millstrand-identity] ${message}`, "error");
          process.stderr.write(`[millstrand-identity] ${message}\n`);
        }
      }

      if (pi.getFlag(DEBUG_MILLSTRAND_IDENTITY_FLAG) === true) {
        process.stdout.write(
          `${formatNativeIdentityState(identityState, ctx.cwd)}\n`,
        );
        process.exit(identityState.status === "error" ? 1 : 0);
      }
    },
    sessionShutdown() {
      publishIdentity(null);
      publishGuidance(null);
    },
    input() {
      if (managedTurnBlocked) return { action: "handled" };
    },
    beforeProviderRequest(event, ctx) {
      if (!managedTurnBlocked) return;
      ctx.abort();
      return event.payload;
    },
    reportGuidanceFailure,
  };
}

/** Register the standalone Pi extension around the reusable data lifecycle. */
export default function millstrandIdentityExtension(pi: ExtensionAPI) {
  const lifecycle = createMillstrandIdentityLifecycle(pi);
  lifecycle.registerFlags();
  pi.on("input", () => lifecycle.input());
  pi.on("before_provider_request", (event, ctx) =>
    lifecycle.beforeProviderRequest(event, ctx),
  );
  pi.on("session_shutdown", () => lifecycle.sessionShutdown());
  pi.on("session_start", (_event, ctx) => lifecycle.sessionStart(ctx));
}

export * from "./context.js";
export * from "./managed-guidance.js";
export * from "./native-identity.js";
