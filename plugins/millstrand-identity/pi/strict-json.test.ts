import { describe, expect, it } from "vitest";
import { canonicalJson, parseStrictJson } from "./strict-json.js";

describe("strict JSON prototype safety", () => {
  it("retains root and nested __proto__ keys without changing prototypes", () => {
    const parsed = parseStrictJson(
      '{"__proto__":{"polluted":true},"nested":{"__proto__":{"deep":true}}}',
      1024,
    ) as Record<string, unknown>;
    const rootPrototypeValue = parsed.__proto__ as Record<string, unknown>;
    const nested = parsed.nested as Record<string, unknown>;
    const nestedPrototypeValue = nested.__proto__ as Record<string, unknown>;

    expect(Object.getPrototypeOf(parsed)).toBeNull();
    expect(Object.getPrototypeOf(rootPrototypeValue)).toBeNull();
    expect(Object.getPrototypeOf(nested)).toBeNull();
    expect(Object.getPrototypeOf(nestedPrototypeValue)).toBeNull();
    expect(Object.hasOwn(parsed, "__proto__")).toBe(true);
    expect(Object.hasOwn(nested, "__proto__")).toBe(true);
    expect(({} as { polluted?: boolean }).polluted).toBeUndefined();
    expect(canonicalJson(parsed)).toBe(
      '{"__proto__":{"polluted":true},"nested":{"__proto__":{"deep":true}}}',
    );
  });

  it("rejects duplicate keys whose spelling uses an escape", () => {
    expect(() => parseStrictJson('{"key":1,"\\u006bey":2}', 1024)).toThrow(
      'duplicate object key "key"',
    );
  });
});
