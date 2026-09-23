import { createHash } from "node:crypto";
import { lstatSync, readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";

export const METADATA_MAX_BYTES = 64 * 1024;

function invalid(message) {
  throw new Error(`Invalid JSON: ${message}`);
}

function assertUnicode(value) {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) invalid("unpaired high surrogate");
      index += 1;
    } else if (code >= 0xdc00 && code <= 0xdfff)
      invalid("unpaired low surrogate");
  }
}

export function parseStrictJson(text, maxBytes) {
  if (Buffer.byteLength(text, "utf8") > maxBytes)
    invalid(`input exceeds ${maxBytes} bytes`);
  if (text.includes("\ufffd")) invalid("input is not valid UTF-8");
  assertUnicode(text);
  let position = 0;
  const whitespace = () => {
    while (/[\t\n\r ]/.test(text[position] ?? "")) position += 1;
  };
  const string = () => {
    if (text[position] !== '"') invalid(`expected string at byte ${position}`);
    const start = position++;
    while (position < text.length) {
      const character = text[position];
      if (character === '"') {
        position += 1;
        let parsed;
        try {
          parsed = JSON.parse(text.slice(start, position));
        } catch {
          invalid(`malformed string at byte ${start}`);
        }
        assertUnicode(parsed);
        return parsed;
      }
      if (character === "\\") position += 2;
      else {
        if ((character?.charCodeAt(0) ?? 0) < 0x20)
          invalid(`control character at byte ${position}`);
        position += 1;
      }
    }
    invalid(`unterminated string at byte ${start}`);
  };
  const value = () => {
    whitespace();
    const character = text[position];
    if (character === '"') return string();
    if (character === "{") {
      position += 1;
      whitespace();
      const result = Object.create(null);
      const keys = new Set();
      if (text[position] === "}") {
        position += 1;
        return result;
      }
      for (;;) {
        whitespace();
        const key = string();
        if (keys.has(key))
          invalid(`duplicate object key ${JSON.stringify(key)}`);
        keys.add(key);
        whitespace();
        if (text[position++] !== ":")
          invalid(`expected ':' at byte ${position - 1}`);
        result[key] = value();
        whitespace();
        if (text[position] === "}") {
          position += 1;
          return result;
        }
        if (text[position++] !== ",")
          invalid(`expected ',' at byte ${position - 1}`);
      }
    }
    if (character === "[") {
      position += 1;
      whitespace();
      const result = [];
      if (text[position] === "]") {
        position += 1;
        return result;
      }
      for (;;) {
        result.push(value());
        whitespace();
        if (text[position] === "]") {
          position += 1;
          return result;
        }
        if (text[position++] !== ",")
          invalid(`expected ',' at byte ${position - 1}`);
      }
    }
    for (const [literal, parsed] of [
      ["true", true],
      ["false", false],
      ["null", null],
    ]) {
      if (text.startsWith(literal, position)) {
        position += literal.length;
        return parsed;
      }
    }
    const match = text
      .slice(position)
      .match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
    if (!match) invalid(`unexpected token at byte ${position}`);
    position += match[0].length;
    const parsed = Number(match[0]);
    if (!Number.isFinite(parsed)) invalid("non-finite number");
    return parsed;
  };
  whitespace();
  const result = value();
  whitespace();
  if (position !== text.length) invalid(`excess data at byte ${position}`);
  return result;
}

export function canonicalJson(value) {
  if (value === null || typeof value === "boolean" || typeof value === "number")
    return JSON.stringify(value);
  if (typeof value === "string") {
    assertUnicode(value);
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(",")}}`;
  }
  throw new Error("Value is outside the JSON domain.");
}

export function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

export function sha256CanonicalJson(value) {
  return sha256(canonicalJson(value));
}

export function hashFile(path) {
  return sha256(readFileSync(path));
}

export function hashDirectory(
  root,
  { maximumFiles = 20_000, maximumBytes = 128 * 1024 * 1024 } = {},
) {
  const entries = [];
  let bytes = 0;
  const visit = (directory) => {
    for (const name of readdirSync(directory).sort()) {
      const path = join(directory, name);
      const stats = lstatSync(path, { throwIfNoEntry: true });
      if (stats.isSymbolicLink())
        throw new Error(`closure contains a symbolic link: ${path}`);
      if (stats.isDirectory()) visit(path);
      else if (stats.isFile()) {
        bytes += stats.size;
        if (entries.length >= maximumFiles || bytes > maximumBytes)
          throw new Error("closure exceeds its inspection bound");
        entries.push([relative(root, path), hashFile(path)]);
      }
    }
  };
  visit(root);
  return sha256CanonicalJson(entries);
}
