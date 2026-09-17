import { createHash } from "node:crypto";

function invalid(message: string): never {
  throw new Error(`Invalid JSON: ${message}`);
}

function assertValidUnicode(value: string) {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) invalid("unpaired high surrogate");
      index += 1;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      invalid("unpaired low surrogate");
    }
  }
}

/** Parse exactly one bounded JSON value while rejecting duplicate object keys. */
export function parseStrictJson(text: string, maxBytes: number): unknown {
  if (Buffer.byteLength(text, "utf8") > maxBytes)
    invalid(`input exceeds ${maxBytes} bytes`);
  if (text.includes("\ufffd")) invalid("input is not valid UTF-8");
  assertValidUnicode(text);
  let position = 0;

  const whitespace = () => {
    while (/[\t\n\r ]/.test(text[position] ?? "")) position += 1;
  };
  const string = (): string => {
    if (text[position] !== '"') invalid(`expected string at byte ${position}`);
    const start = position;
    position += 1;
    while (position < text.length) {
      const character = text[position];
      if (character === '"') {
        position += 1;
        let value: unknown;
        try {
          value = JSON.parse(text.slice(start, position));
        } catch {
          invalid(`malformed string at byte ${start}`);
        }
        assertValidUnicode(value as string);
        return value as string;
      }
      if (character === "\\") {
        position += 2;
        continue;
      }
      if ((character?.charCodeAt(0) ?? 0) < 0x20)
        invalid(`control character at byte ${position}`);
      position += 1;
    }
    invalid(`unterminated string at byte ${start}`);
  };
  const value = (): unknown => {
    whitespace();
    const character = text[position];
    if (character === '"') return string();
    if (character === "{") {
      position += 1;
      whitespace();
      const object = Object.create(null) as Record<string, unknown>;
      const keys = new Set<string>();
      if (text[position] === "}") {
        position += 1;
        return object;
      }
      for (;;) {
        whitespace();
        const key = string();
        if (keys.has(key))
          invalid(`duplicate object key ${JSON.stringify(key)}`);
        keys.add(key);
        whitespace();
        if (text[position] !== ":") invalid(`expected ':' at byte ${position}`);
        position += 1;
        object[key] = value();
        whitespace();
        if (text[position] === "}") {
          position += 1;
          return object;
        }
        if (text[position] !== ",") invalid(`expected ',' at byte ${position}`);
        position += 1;
      }
    }
    if (character === "[") {
      position += 1;
      whitespace();
      const array: unknown[] = [];
      if (text[position] === "]") {
        position += 1;
        return array;
      }
      for (;;) {
        array.push(value());
        whitespace();
        if (text[position] === "]") {
          position += 1;
          return array;
        }
        if (text[position] !== ",") invalid(`expected ',' at byte ${position}`);
        position += 1;
      }
    }
    for (const [literal, parsed] of [
      ["true", true],
      ["false", false],
      ["null", null],
    ] as const) {
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
    const number = Number(match[0]);
    if (!Number.isFinite(number)) invalid("non-finite number");
    return number;
  };

  whitespace();
  const parsed = value();
  whitespace();
  if (position !== text.length) invalid(`excess data at byte ${position}`);
  return parsed;
}

/** RFC 8785-compatible canonicalization for the JSON domain used by guidance v1. */
export function canonicalJson(value: unknown): string {
  if (
    value === null ||
    typeof value === "boolean" ||
    typeof value === "number"
  ) {
    const encoded = JSON.stringify(value);
    if (encoded === undefined)
      throw new Error("Value is outside the JSON domain.");
    return encoded;
  }
  if (typeof value === "string") {
    assertValidUnicode(value);
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map(
        (key) =>
          `${JSON.stringify(key)}:${canonicalJson((value as Record<string, unknown>)[key])}`,
      )
      .join(",")}}`;
  }
  throw new Error("Value is outside the JSON domain.");
}

export function sha256CanonicalJson(value: unknown): string {
  return createHash("sha256")
    .update(canonicalJson(value), "utf8")
    .digest("hex");
}
