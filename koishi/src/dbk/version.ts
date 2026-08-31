import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const FALLBACK = "0.0.0-dev";

function hereDir(): string {
  try {
    return dirname(fileURLToPath(import.meta.url));
  } catch {
    return process.cwd();
  }
}

function packageDir(): string {
  const fromHere = join(hereDir(), "..", "..");
  if (existsSync(join(fromHere, "package.json"))) return fromHere;
  const fromCwd = join(process.cwd(), "koishi");
  if (existsSync(join(fromCwd, "package.json"))) return fromCwd;
  return process.cwd();
}

function isProductRepo(root: string): boolean {
  return (
    existsSync(join(root, "pnpm-workspace.yaml")) &&
    existsSync(join(root, "jvm", "build.gradle.kts")) &&
    existsSync(join(root, "koishi", "package.json"))
  );
}

function gitDescribe(cwd: string): string | undefined {
  try {
    const described = execFileSync(
      "git",
      ["describe", "--tags", "--always", "--abbrev=7", "--dirty"],
      { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] },
    ).trim();
    return described || undefined;
  } catch {
    return undefined;
  }
}

function packageVersion(dir: string): string {
  try {
    const pkg = JSON.parse(readFileSync(join(dir, "package.json"), "utf8")) as {
      version?: string;
    };
    return pkg.version?.trim() || FALLBACK;
  } catch {
    return FALLBACK;
  }
}

function resolveGatewayVersion(): string {
  const pkg = packageDir();
  const repoRoot = join(pkg, "..");
  if (isProductRepo(repoRoot)) {
    return gitDescribe(repoRoot) ?? packageVersion(pkg);
  }
  return packageVersion(pkg);
}

export const GATEWAY_VERSION = resolveGatewayVersion();
