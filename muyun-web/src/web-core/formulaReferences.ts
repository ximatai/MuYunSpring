/** Reads only real field tokens; references inside string literals are formula text, not dependencies. */
export function referencedFormulaFields(expression: string): string[] {
  const fields = new Set<string>();
  let quote: '"' | "'" | undefined;
  for (let index = 0; index < expression.length; index += 1) {
    const character = expression[index]!;
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = undefined;
      continue;
    }
    if (character === '"' || character === "'") {
      quote = character;
      continue;
    }
    if (character !== '{') continue;
    const closing = expression.indexOf('}', index + 1);
    if (closing < 0) continue;
    const candidate = expression.slice(index + 1, closing);
    if (/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*$/.test(candidate)) {
      fields.add(candidate);
    }
    index = closing;
  }
  return [...fields];
}
