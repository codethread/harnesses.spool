import { spawn } from "node:child_process";
import {
  chmodSync,
  mkdtempSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const preflight = join(root, "scripts/managed-guidance-preflight.mjs");
const executable = realpathSync(
  join(
    root,
    "node_modules/@codethread/pi-coding-agent-test/dist/bundle/cli.js",
  ),
);
const owner = realpathSync(
  join(root, "plugins/millstrand-identity/pi/index.ts"),
);
const temporaryDirectories: string[] = [];

function temporaryDirectory(): string {
  const path = mkdtempSync(join(tmpdir(), "pi-guidance-preflight-"));
  temporaryDirectories.push(path);
  return path;
}

function files(path: string): string[] {
  return readdirSync(path, { recursive: true }).map(String).sort();
}

async function runCommand(
  command: string,
  args: string[],
  cwd = root,
): Promise<void> {
  await new Promise<void>((resolvePromise, reject) => {
    const child = spawn(command, args, {
      cwd,
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stderr = "";
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => (stderr += chunk));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) resolvePromise();
      else reject(new Error(`${command} exited ${code}: ${stderr}`));
    });
  });
}

async function invokeRaw(
  input: string,
  preflightEntrypoint = preflight,
): Promise<any> {
  return await new Promise((resolvePromise, reject) => {
    const child = spawn(process.execPath, [preflightEntrypoint], {
      cwd: root,
      env: { PATH: process.env.PATH },
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => (stdout += chunk));
    child.stderr.on("data", (chunk) => (stderr += chunk));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code !== 0) reject(new Error(`preflight exited ${code}: ${stderr}`));
      else resolvePromise(JSON.parse(stdout));
    });
    child.stdin.end(input);
  });
}

async function invoke(
  request: Record<string, unknown>,
  preflightEntrypoint = preflight,
): Promise<any> {
  return await invokeRaw(JSON.stringify(request), preflightEntrypoint);
}

function world(settings?: Record<string, unknown>) {
  const base = temporaryDirectory();
  const cwd = join(base, "project");
  const workspace = join(base, "world/.millstrand");
  const agentDir = join(base, "agent");
  mkdirSync(join(cwd, ".pi"), { recursive: true });
  mkdirSync(workspace, { recursive: true });
  mkdirSync(agentDir, { recursive: true });
  if (settings)
    writeFileSync(join(cwd, ".pi/settings.json"), JSON.stringify(settings));
  return {
    base,
    cwd: realpathSync(cwd),
    workspace: realpathSync(workspace),
    agentDir: realpathSync(agentDir),
  };
}

function request(
  fixture: ReturnType<typeof world>,
  extraArgv: string[] = [],
): Record<string, unknown> {
  return {
    schema: "millstrand.agent-guidance-preflight/v1",
    harness: "pi",
    executable,
    mode: "headless",
    cwd: fixture.cwd,
    workspace: fixture.workspace,
    env: {
      PATH: process.env.PATH ?? "",
      HOME: fixture.base,
      PI_CODING_AGENT_DIR: fixture.agentDir,
      MILLSTRAND_MANAGED_GUIDANCE: "must be scrubbed",
      MILLSTRAND_MANAGED_BOOTSTRAP: "must be scrubbed",
    },
    "extra-argv": extraArgv,
    resumes: false,
    model: "gpt-5.4",
    effort: "low",
  };
}

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    rmSync(directory, { recursive: true, force: true });
  }
});

describe("managed guidance Pi preflight", () => {
  it("rejects identity data without an accepted managed renderer and makes no writes", async () => {
    const fixture = world({ packages: [root] });
    const before = files(fixture.base);
    expect(await invoke(request(fixture))).toMatchObject({
      schema: "millstrand.agent-guidance-preflight/v1",
      result: "legacy-required",
      code: "missing-hook",
      diagnostic: expect.stringContaining(
        "no managed-guidance renderer is accepted",
      ),
    });
    expect(files(fixture.base)).toEqual(before);
  });

  it("runs the no-model preflight from a pnpm-packed installation", async () => {
    const packDirectory = temporaryDirectory();
    await runCommand("pnpm", ["pack", "--pack-destination", packDirectory]);
    const archiveName = readdirSync(packDirectory).find((name) =>
      name.endsWith(".tgz"),
    );
    expect(archiveName).toBeDefined();
    const extractionDirectory = temporaryDirectory();
    await runCommand("tar", [
      "-xzf",
      join(packDirectory, archiveName!),
      "-C",
      extractionDirectory,
    ]);
    const packedRoot = realpathSync(join(extractionDirectory, "package"));
    const packedPreflight = join(
      packedRoot,
      "scripts/managed-guidance-preflight.mjs",
    );
    const fixture = world({ packages: [packedRoot] });

    const result = await invoke(request(fixture), packedPreflight);

    expect(result, JSON.stringify(result)).toMatchObject({
      result: "legacy-required",
      code: "missing-hook",
      diagnostic: expect.stringContaining(
        "no managed-guidance renderer is accepted",
      ),
    });
  });

  it("honors CLI extension selectors without enabling or inferring native", async () => {
    const fixture = world();
    const result = await invoke(
      request(fixture, ["--no-extensions", "--extension", owner]),
    );
    expect(result).toMatchObject({
      result: "legacy-required",
      code: "missing-hook",
    });
  });

  it("treats -ne as --no-extensions before applying explicit extension selectors", async () => {
    const disabled = world({ packages: [root] });
    expect(await invoke(request(disabled, ["-ne"]))).toMatchObject({
      result: "legacy-required",
      code: "missing-hook",
    });

    const explicit = world({ packages: [root] });
    const result = await invoke(request(explicit, ["-ne", "-e", owner]));
    expect(result).toMatchObject({
      result: "legacy-required",
      code: "missing-hook",
    });
  });

  it("preserves global and project extension scopes with their own path bases", async () => {
    const fixture = world({ extensions: [owner] });
    const globalExtension = join(fixture.agentDir, "global-extension.ts");
    writeFileSync(
      globalExtension,
      "export default function globalExtension() {}\n",
    );
    writeFileSync(
      join(fixture.agentDir, "settings.json"),
      JSON.stringify({ extensions: ["./global-extension.ts"] }),
    );

    expect(await invoke(request(fixture))).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining(globalExtension),
    });
  });

  it("preserves global and project package scopes with their own path bases", async () => {
    const fixture = world({ packages: [root] });
    const globalPackage = join(fixture.agentDir, "global-package");
    mkdirSync(globalPackage);
    writeFileSync(
      join(globalPackage, "package.json"),
      JSON.stringify({ pi: { extensions: ["./global-extension.ts"] } }),
    );
    writeFileSync(
      join(globalPackage, "global-extension.ts"),
      "export default function globalExtension() {}\n",
    );
    writeFileSync(
      join(fixture.agentDir, "settings.json"),
      JSON.stringify({ packages: ["./global-package"] }),
    );

    expect(await invoke(request(fixture))).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining(
        join(globalPackage, "global-extension.ts"),
      ),
    });
  });

  it("rejects an overlapping project autoload:false delta instead of hiding global extensions", async () => {
    const fixture = world();
    const sharedPackage = join(fixture.agentDir, "shared-package");
    mkdirSync(sharedPackage);
    writeFileSync(
      join(sharedPackage, "package.json"),
      JSON.stringify({ pi: { extensions: ["./global-extension.ts"] } }),
    );
    writeFileSync(
      join(sharedPackage, "global-extension.ts"),
      "export default function globalExtension() {}\n",
    );
    writeFileSync(
      join(fixture.agentDir, "settings.json"),
      JSON.stringify({ packages: [sharedPackage] }),
    );
    writeFileSync(
      join(fixture.cwd, ".pi/settings.json"),
      JSON.stringify({
        packages: [{ source: sharedPackage, autoload: false }],
        extensions: [owner],
      }),
    );

    expect(await invoke(request(fixture))).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining(
        "overlapping global/project package with project autoload:false",
      ),
    });
  });

  it("rejects symlink entries that pinned Pi extension discovery would follow", async () => {
    const fixture = world({ packages: [root] });
    const external = join(fixture.base, "external-extension.ts");
    const extensionsDirectory = join(fixture.cwd, ".pi/extensions");
    writeFileSync(external, "export default function externalExtension() {}\n");
    mkdirSync(extensionsDirectory);
    symlinkSync(external, join(extensionsDirectory, "linked-extension.ts"));

    expect(await invoke(request(fixture))).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining(
        "extension discovery contains a symbolic link",
      ),
    });
  });

  it("treats package source leading signs as literal path characters", async () => {
    const leadingPlus = world({ packages: [`+${root}`] });
    expect(await invoke(request(leadingPlus))).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining("local package has no package.json"),
    });

    const leadingMinus = world({ packages: [root, `-${root}`] });
    expect(await invoke(request(leadingMinus))).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining("local package has no package.json"),
    });
  });

  it("rejects attached --extension syntax that pinned Pi ignores", async () => {
    const fixture = world();
    expect(
      await invoke(
        request(fixture, ["--no-extensions", `--extension=${owner}`]),
      ),
    ).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining(
        "attached Pi --extension syntax is not supported",
      ),
    });
  });

  it("rejects missing and extensions outside the canonical reviewed profile", async () => {
    const missing = world();
    expect((await invoke(request(missing))).code).toBe("missing-hook");

    const missingExplicit = world({ packages: [root] });
    expect(
      await invoke(
        request(missingExplicit, [
          "--extension",
          join(missingExplicit.base, "missing.ts"),
        ]),
      ),
    ).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining(
        "explicit extension path does not exist",
      ),
    });

    const competing = world({ packages: [root] });
    const computedOwner = join(competing.base, "computed-owner.ts");
    writeFileSync(
      computedOwner,
      "const event = ['before', 'agent', 'start'].join('_');\nexport default function x(pi) { pi.on(event, () => ({ systemPrompt: 'x' })); }\n",
    );
    expect(
      await invoke(request(competing, ["--extension", computedOwner])),
    ).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining(
        "outside the reviewed Harnesses package profile",
      ),
    });

    const benign = world({ packages: [root] });
    const unknownBenign = join(benign.base, "unknown-benign.ts");
    writeFileSync(
      unknownBenign,
      "export default function x(pi) { pi.registerCommand('benign', { handler() {} }); }\n",
    );
    expect(
      await invoke(request(benign, ["--extension", unknownBenign])),
    ).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining(
        "outside the reviewed Harnesses package profile",
      ),
    });

    const changed = world();
    const changedOwner = join(changed.base, "changed-owner.ts");
    writeFileSync(
      changedOwner,
      "export default function x(pi) { pi.on('before_agent_start', () => ({ systemPrompt: 'x' })); }\n",
    );
    expect(
      (
        await invoke(
          request(changed, ["--no-extensions", "--extension", changedOwner]),
        )
      ).code,
    ).toBe("unverifiable-profile");

    const promptCompetition = world({ packages: [root] });
    expect(
      (
        await invoke(
          request(promptCompetition, ["--append-system-prompt=hostile"]),
        )
      ).code,
    ).toBe("unverifiable-profile");
  });

  it("rejects malformed, inherited, and duplicate-key request fields", async () => {
    const fixture = world({ packages: [root] });
    const validRequest = request(fixture);
    const inherited = await invokeRaw(
      `{"__proto__":${JSON.stringify(validRequest)}}`,
    );
    expect(inherited).toMatchObject({
      result: "legacy-required",
      code: "unverifiable-profile",
      diagnostic: expect.stringContaining(
        "request keys do not match the closed v1 schema",
      ),
    });

    const encoded = JSON.stringify(validRequest).replace(
      /}$/,
      ',"harness":"codex"}',
    );
    const response = await new Promise<string>((resolvePromise, reject) => {
      const child = spawn(process.execPath, [preflight], {
        cwd: root,
        stdio: ["pipe", "pipe", "pipe"],
      });
      let stdout = "";
      child.stdout.setEncoding("utf8");
      child.stdout.on("data", (chunk) => (stdout += chunk));
      child.on("error", reject);
      child.on("close", () => resolvePromise(stdout));
      child.stdin.end(encoded);
    });
    expect(response.trim().split("\n")).toHaveLength(1);
    expect(Buffer.byteLength(response)).toBeLessThan(1024);
    expect(JSON.parse(response).code).toBe("unverifiable-profile");
  });
});

function processExists(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ESRCH") return false;
    throw error;
  }
}

describe("managed guidance Codex preflight", () => {
  it("awaits bounded exact-child shutdown when hooks/list ignores SIGTERM", async () => {
    const fixture = world();
    const codexHome = join(fixture.base, "codex-home");
    const fakeExecutable = join(fixture.base, "fake-codex.mjs");
    const pidFile = join(fixture.base, "app-server.pid");
    mkdirSync(codexHome);
    writeFileSync(
      fakeExecutable,
      `#!/usr/bin/env node
import { writeFileSync } from "node:fs";
if (process.argv.includes("--version")) {
	process.stdout.write("codex-cli 0.154.0\\n");
	process.exit(0);
}
writeFileSync(process.env.FAKE_CODEX_PID_FILE, String(process.pid));
process.on("SIGTERM", () => {});
const originalParent = process.ppid;
setInterval(() => {
	if (process.ppid !== originalParent) process.exit(0);
}, 25);
let input = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
	input += chunk;
	for (;;) {
		const newline = input.indexOf("\\n");
		if (newline < 0) break;
		const line = input.slice(0, newline);
		input = input.slice(newline + 1);
		if (!line) continue;
		const message = JSON.parse(line);
		if (message.id === 1) {
			process.stdout.write(JSON.stringify({ id: 1, result: {} }) + "\\n");
		} else if (message.id === 2) {
			process.stdout.write(JSON.stringify({
				id: 2,
				result: { data: [{ cwd: message.params.cwds[0], hooks: [], warnings: [] }] },
			}) + "\\n");
		}
	}
});
`,
    );
    chmodSync(fakeExecutable, 0o755);
    const result = await invoke({
      schema: "millstrand.agent-guidance-preflight/v1",
      harness: "codex",
      executable: fakeExecutable,
      mode: "headless",
      cwd: fixture.cwd,
      workspace: fixture.workspace,
      env: {
        PATH: process.env.PATH ?? "",
        HOME: fixture.base,
        CODEX_HOME: codexHome,
        FAKE_CODEX_PID_FILE: pidFile,
      },
      "extra-argv": [],
      resumes: false,
    });

    expect(result).toMatchObject({
      result: "legacy-required",
      code: "missing-hook",
    });
    const appServerPid = Number.parseInt(readFileSync(pidFile, "utf8"), 10);
    expect(processExists(appServerPid)).toBe(false);
  });
});
