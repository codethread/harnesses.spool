function escapeXml(
  value: string,
  options: { escapeSingleQuote: boolean },
): string {
  const escaped = value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
  return options.escapeSingleQuote ? escaped.replace(/'/g, "&apos;") : escaped;
}

export function escapeXmlAttribute(value: string): string {
  return escapeXml(value, { escapeSingleQuote: false });
}

export function escapeXmlText(value: string): string {
  return escapeXml(value, { escapeSingleQuote: true });
}

export function formatXmlAttributes(
  attributes: Record<string, string | undefined>,
): string {
  return Object.entries(attributes)
    .filter(
      (entry): entry is [string, string] =>
        entry[1] !== undefined && entry[1].length > 0,
    )
    .map(([key, value]) => `${key}="${escapeXmlText(value)}"`)
    .join(" ");
}

export function formatXmlElement(
  tagName: string,
  attributes: Record<string, string | undefined>,
  options: { indent?: string; selfClosing?: boolean } = {},
): string {
  const indent = options.indent ?? "";
  const attrs = formatXmlAttributes(attributes);
  if (options.selfClosing ?? true) {
    return attrs.length > 0
      ? `${indent}<${tagName} ${attrs} />`
      : `${indent}<${tagName} />`;
  }
  return attrs.length > 0
    ? `${indent}<${tagName} ${attrs}>`
    : `${indent}<${tagName}>`;
}

export function formatXmlTextElement(
  tagName: string,
  text: string,
  options: { indent?: string; multiline?: boolean } = {},
): string {
  const indent = options.indent ?? "";
  if (!options.multiline)
    return `${indent}<${tagName}>${escapeXmlText(text)}</${tagName}>`;
  const content = text
    .split("\n")
    .map((line) => `${indent}  ${escapeXmlText(line)}`)
    .join("\n");
  return `${indent}<${tagName}>\n${content}\n${indent}</${tagName}>`;
}

export function wrapXmlTag(
  tagName: string,
  content: string,
  attributes?: Record<string, string>,
): string {
  const trimmedContent = content.trim();
  if (!trimmedContent) return "";
  const renderedAttributes = attributes
    ? Object.entries(attributes)
        .map(([key, value]) => ` ${key}="${escapeXmlAttribute(value)}"`)
        .join("")
    : "";
  return `<${tagName}${renderedAttributes}>\n${trimmedContent}\n</${tagName}>`;
}

export function wrapSystemReminder(type: string, content: string): string {
  return wrapXmlTag("system-reminder", content, { type });
}
