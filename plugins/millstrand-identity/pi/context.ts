import type {
  ManagedGuidanceBundle,
  ManagedPiSelection,
} from "./managed-guidance.js";
export const MILLSTRAND_IDENTITY_CONTEXT_EVENT =
  "codethread:millstrand-identity-context:v1";
export const MILLSTRAND_IDENTITY_STATE_EVENT = "millstrand:identity-state:v1";
export const MILLSTRAND_GUIDANCE_CONTEXT_EVENT =
  "millstrand:guidance-context:v1";
export const MILLSTRAND_GUIDANCE_FAILURE_EVENT =
  "millstrand:guidance-failure:v1";
export const MILLSTRAND_PARENT_IDENTITY_ENV = "MILLSTRAND_PI_PARENT_IDENTITY";
export const MILLSTRAND_WORKSPACE_ENV = "MILLSTRAND_PI_WORKSPACE";

export type ActiveMillstrandIdentity = {
  identity: string;
  instruction: string;
  nativeSessionId: string;
  workspace?: string;
};

export type MillstrandGuidanceContext = {
  selection: ManagedPiSelection;
  bundle: ManagedGuidanceBundle | null;
};

export type MillstrandIdentityState =
  | { status: "pending" }
  | ({
      status: "bound";
      strandId: string;
      result: "minted" | "recovered" | "attached";
    } & ActiveMillstrandIdentity)
  | { status: "suppressed"; reason: string; nativeSessionId: string }
  | { status: "error"; error: string; nativeSessionId: string };

function requiredString(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(
      `Millstrand identity context ${field} must be a non-empty string.`,
    );
  }
  return value.trim();
}

export function parseMillstrandIdentityContext(
  value: unknown,
): ActiveMillstrandIdentity | null {
  if (value === null) return null;
  if (typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Millstrand identity context must be an object or null.");
  }
  const context = value as Record<string, unknown>;
  const workspace = context.workspace;
  return {
    identity: requiredString(context.identity, "identity"),
    instruction: requiredString(context.instruction, "instruction"),
    nativeSessionId: requiredString(context.nativeSessionId, "nativeSessionId"),
    ...(workspace === undefined
      ? {}
      : { workspace: requiredString(workspace, "workspace") }),
  };
}

function isBootstrapOwnershipKey(name: string): boolean {
  if (!name.startsWith("MILLSTRAND_")) return false;
  return (
    name === "MILLSTRAND_AGENT_ID" ||
    name === "MILLSTRAND_RUN_ID" ||
    name === "MILLSTRAND_INVOCATION" ||
    name === "MILLSTRAND_RESERVATION_ID" ||
    name === "MILLSTRAND_MANAGED_BOOTSTRAP" ||
    name === "MILLSTRAND_MANAGED_GUIDANCE" ||
    name === "MILLSTRAND_BOOTSTRAP_V1" ||
    name === "MILLSTRAND_IDENTITY_TRANSPORT" ||
    name.startsWith("MILLSTRAND_BOOTSTRAP_") ||
    name.endsWith("_RESERVATION_ID") ||
    name.endsWith("_IDENTITY_TRANSPORT")
  );
}

/**
 * Build the environment for a native Pi child session.
 *
 * Parent ownership and managed bootstrap state are never inherited by the child.
 * The explicitly supplied current identity is carried only as provenance input
 * for the child's own `identity startup` call.
 */
export function buildMillstrandChildEnvironment(
  env: NodeJS.ProcessEnv,
  currentIdentity: ActiveMillstrandIdentity | null,
): NodeJS.ProcessEnv {
  const childEnv = { ...env };
  const parentIdentity = currentIdentity?.identity;

  for (const name of Object.keys(childEnv)) {
    if (isBootstrapOwnershipKey(name)) delete childEnv[name];
  }
  delete childEnv.MILLSTRAND_WORKSPACE;
  delete childEnv[MILLSTRAND_PARENT_IDENTITY_ENV];
  delete childEnv[MILLSTRAND_WORKSPACE_ENV];

  if (parentIdentity) childEnv[MILLSTRAND_PARENT_IDENTITY_ENV] = parentIdentity;
  childEnv.PI_SUBAGENT = "1";
  return childEnv;
}
