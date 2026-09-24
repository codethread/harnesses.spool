import { execFileSync, spawnSync } from "node:child_process";
import {
  chmodSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { afterEach, expect, it } from "vitest";

const roots: string[] = [];
afterEach(() =>
  roots
    .splice(0)
    .forEach((root) => rmSync(root, { recursive: true, force: true })),
);
function fixture(project = true, git = true) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "pi-native-host-")));
  roots.push(root);
  if (git) execFileSync("git", ["init", "-q", root]);
  if (project) mkdirSync(join(root, ".millstrand"));
  mkdirSync(join(root, "bin"));
  const strand = join(root, "bin", "strand");
  writeFileSync(
    strand,
    `#!${process.execPath}\nconst a=process.argv.slice(2);const s=a[a.indexOf('pi')+1];\nrequire('node:fs').appendFileSync(${JSON.stringify(join(root, "calls"))},JSON.stringify(a)+'\\n');\nconsole.log(JSON.stringify({operation:'agent native-startup',identity:'native-fixture',instruction:'Your Millstrand identity is native-fixture.', 'strand-id':'identity-fixture','run-id':'run-fixture',result:'recovered','native-session-id':s}));\n`,
  );
  chmodSync(strand, 0o700);
  const env = {
    ...process.env,
    PI_CODING_AGENT_DIR: join(root, "agent"),
    PI_OFFLINE: "1",
    PATH: `${join(root, "bin")}:${process.env.PATH}`,
  };
  for (const key of Object.keys(env))
    if (key.startsWith("MILLSTRAND_")) delete (env as NodeJS.ProcessEnv)[key];
  return { root, env };
}
function host(
  root: string,
  env: NodeJS.ProcessEnv,
  extension: string,
  args: string[],
) {
  const result = spawnSync(
    process.execPath,
    [
      resolve("node_modules/@earendil-works/pi-coding-agent/dist/cli.js"),
      "--no-extensions",
      "--extension",
      extension,
      "--no-skills",
      "--no-prompt-templates",
      "--no-themes",
      "--no-context-files",
      "--offline",
      "--provider",
      "openai",
      "--model",
      "gpt-5",
      "--api-key",
      "no-model-fixture",
      "--print",
      ...args,
    ],
    {
      cwd: root,
      env,
      encoding: "utf8",
      timeout: 30_000,
      maxBuffer: 2 * 1024 * 1024,
    },
  );
  expect(result.error).toBeUndefined();
  expect(result.status, result.stderr).toBe(0);
  return result.stdout + result.stderr;
}
it("loads only the candidate extension in a real no-model Pi host", () => {
  const { root, env } = fixture();
  const extension = resolve("plugins/millstrand-identity/pi/index.ts");
  const session = "4a053dc9-aa09-4c8d-855b-b38a7ca9278a";
  for (let n = 0; n < 2; n++) {
    const output = host(root, env, extension, [
      "--session-id",
      session,
      "--thinking",
      "high",
      "--debug-millstrand-identity",
    ]);
    expect(output).toContain('"status": "bound"');
    expect(output).toContain(session);
  }
  const calls = readFileSync(join(root, "calls"), "utf8")
    .trim()
    .split("\n")
    .map((line) => JSON.parse(line) as string[]);
  expect(calls).toHaveLength(2);
  for (const call of calls) {
    expect(call).toContain(session);
    expect(call).toContain("openai/gpt-5");
    expect(call).toContain("high");
    expect(call).not.toContain("--run-id");
  }
}, 60_000);
it("real fork supplies its native parent header", () => {
  const { root, env } = fixture();
  const parent = join(root, "parent.jsonl");
  writeFileSync(
    parent,
    `${JSON.stringify({ type: "session", version: 3, id: "parent-native-id", timestamp: new Date().toISOString(), cwd: root })}\n`,
  );
  const output = host(
    root,
    env,
    resolve("plugins/millstrand-identity/pi/index.ts"),
    ["--fork", parent, "--debug-millstrand-identity", "no model work"],
  );
  expect(output).toContain('"status": "bound"');
  const call = JSON.parse(readFileSync(join(root, "calls"), "utf8").trim());
  expect(call).toContain("parent-native-id");
  expect(call).toContain("--parent-native-session-id");
}, 30_000);

it.each([
  { project: false, git: true },
  { project: false, git: false },
  { project: true, git: false },
])(
  "stays inactive with project=$project git=$git",
  ({ project, git }) => {
    const { root, env } = fixture(project, git);
    // Inactive projects ignore malformed inherited ownership, not just valid hints.
    Object.assign(env, {
      MILLSTRAND_RUN_ID: " ",
      MILLSTRAND_PI_PARENT_IDENTITY: " ",
      MILLSTRAND_WORKSPACE: "/not-this-project/.millstrand",
    });
    const output = host(
      root,
      env,
      resolve("plugins/millstrand-identity/pi/index.ts"),
      ["--debug-millstrand-identity"],
    );
    expect(output).toContain('"status": "suppressed"');
    expect(() => readFileSync(join(root, "calls"))).toThrow();
  },
  30_000,
);

it("routes nested and linked-worktree sessions to the canonical Git workspace", () => {
  const { root, env } = fixture();
  execFileSync("git", [
    "-C",
    root,
    "-c",
    "user.name=Fixture",
    "-c",
    "user.email=fixture@example.test",
    "commit",
    "-q",
    "--allow-empty",
    "-m",
    "fixture",
  ]);
  const linked = join(root, "linked");
  execFileSync("git", [
    "-C",
    root,
    "worktree",
    "add",
    "-q",
    "--detach",
    linked,
  ]);
  mkdirSync(join(linked, ".millstrand"));
  const nested = join(root, "nested");
  mkdirSync(nested);
  for (const cwd of [nested, linked]) {
    const output = host(
      cwd,
      env,
      resolve("plugins/millstrand-identity/pi/index.ts"),
      ["--debug-millstrand-identity"],
    );
    expect(output).toContain('"status": "bound"');
  }
  const calls = readFileSync(join(root, "calls"), "utf8")
    .trim()
    .split("\n")
    .map((line) => JSON.parse(line) as string[]);
  expect(calls).toHaveLength(2);
  for (const [index, call] of calls.entries()) {
    expect(call[call.indexOf("--workspace") + 1]).toBe(
      join(root, ".millstrand"),
    );
    expect(call[call.indexOf("--cwd") + 1]).toBe([nested, linked][index]);
  }
}, 60_000);

// The prompt owner is consumer source, not a dependency or an installed-package edit.
it.skipIf(!process.env.PI_PROMPT_OWNER_SOURCE)(
  "composes the candidate lifecycle with the actual consumer prompt owner",
  () => {
    const source = resolve(process.env.PI_PROMPT_OWNER_SOURCE!);
    const { root, env } = fixture();
    const adapter = resolve("plugins/millstrand-identity/pi/index.ts");
    const consumer = readFileSync(source, "utf8")
      .replace(
        /from "@codethread\/harnesses\/pi\/millstrand-identity"/g,
        `from ${JSON.stringify(adapter)}`,
      )
      .replace(/from "(\.\.?\/[^"\n]+)"/g, (_match, relative: string) => {
        const target =
          relative === "./native-identity.js" ||
          relative === "./managed-guidance.js"
            ? resolve(
                "plugins/millstrand-identity/pi",
                relative.replace(/\.js$/, ".ts"),
              )
            : resolve(dirname(source), relative.replace(/\.js$/, ".ts"));
        return `from ${JSON.stringify(target)}`;
      });
    const composed = join(root, "prompt-owner.ts");
    writeFileSync(composed, consumer);
    const output = host(root, env, composed, [
      "--debug-prompt",
      "--append-system-prompt",
      "ordinary role",
      "--append-system-prompt",
      "user append",
      "render without model",
    ]);
    expect(
      output.match(/Your Millstrand identity is native-fixture\./g),
    ).toHaveLength(1);
    expect(output.indexOf("ordinary role")).toBeLessThan(
      output.indexOf("user append"),
    );
    expect(output).toContain("user append");
  },
  30_000,
);
