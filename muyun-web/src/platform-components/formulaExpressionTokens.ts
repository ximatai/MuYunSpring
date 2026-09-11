import type { UiTokenInputToken } from '@muyun/vue-ui-antdv';

export interface FormulaExpressionField {
  name: string;
  label: string;
  valueType?: string;
  available?: boolean;
  unavailableReason?: string;
}

export interface FormulaExpressionFunction {
  name: string;
  title?: string;
  description?: string;
}

/**
 * Produces decoration ranges only. The expression itself remains untouched and is
 * deliberately not parsed: unfinished syntax and unknown text stay editable.
 */
export function formulaExpressionTokens(
  expression: string,
  fields: readonly FormulaExpressionField[],
  functions: readonly FormulaExpressionFunction[] = [],
): UiTokenInputToken[] {
  const fieldByName = new Map(fields.map((field) => [field.name, field]));
  const functionByName = new Map(functions.map((definition) => [definition.name, definition]));
  const tokens: UiTokenInputToken[] = [];

  for (let index = 0; index < expression.length; ) {
    const current = expression[index]!;
    if (current === '"' || current === "'") {
      index = quotedEnd(expression, index, current);
      continue;
    }
    if (current === '{') {
      const close = expression.indexOf('}', index + 1);
      if (close >= 0) {
        const name = expression.slice(index + 1, close);
        const field = fieldByName.get(name);
        if (field) {
          tokens.push({
            start: index,
            end: close + 1,
            source: expression.slice(index, close + 1),
            label: field.label,
            title: field.unavailableReason
              ? `${field.label}：${field.unavailableReason}`
              : `${field.label}（${field.name}）`,
          });
        }
        index = close + 1;
        continue;
      }
    }
    if (isIdentifierStart(current)) {
      let end = index + 1;
      while (end < expression.length && isIdentifierPart(expression[end]!)) end += 1;
      const definition = functionByName.get(expression.slice(index, end));
      let cursor = end;
      while (cursor < expression.length && /\s/.test(expression[cursor]!)) cursor += 1;
      if (definition && expression[cursor] === '(') {
        tokens.push({
          start: index,
          end,
          source: expression.slice(index, end),
          label: definition.title || definition.name,
          title: definition.description || definition.name,
        });
      }
      index = end;
      continue;
    }
    index += 1;
  }
  return tokens;
}

function quotedEnd(expression: string, start: number, quote: string) {
  for (let index = start + 1; index < expression.length; index += 1) {
    // Keep the same lexical boundary as FormulaTokenizer: only the immediately
    // preceding slash matters. This does not decode or normalise the source.
    if (expression[index] === quote && expression[index - 1] !== '\\') return index + 1;
  }
  return expression.length;
}

function isIdentifierStart(value: string) {
  return /[A-Za-z_]/.test(value);
}

function isIdentifierPart(value: string) {
  return /[A-Za-z0-9_]/.test(value);
}
