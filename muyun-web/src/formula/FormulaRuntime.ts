import type { FormulaNode, FormulaProgram } from '@muyun/web-contracts';

export type FormulaRecord = Record<string, unknown>;
export type FormulaComputePatch = Readonly<Record<string, FormulaValue>>;
export interface FormulaComputeResult {
  readonly patch: FormulaComputePatch;
  readonly changedFields: readonly string[];
}

/**
 * Browser-local executor for server-issued FormulaProgram values.
 *
 * It deliberately accepts no source expression. Unknown program versions, profiles and node shapes fail closed.
 */
export class FormulaRuntime {
  evaluateWebUi(program: FormulaProgram | undefined, record: FormulaRecord): boolean {
    if (!program || program.schemaVersion !== 1 || program.profile !== 'WEB_UI' || !program.root)
      return false;
    return this.evaluateNode(program.root, record, 1, { count: 0 }) === true;
  }

  /**
   * Evaluates one server-issued FORM_COMPUTE assignment against a draft without mutating it.
   * Applying the returned patch, ordering rules, and user-value ownership remain form-runtime concerns.
   */
  evaluateFormCompute(program: FormulaProgram | undefined, draft: FormulaRecord): FormulaComputeResult {
    if (!program || program.schemaVersion !== 1 || program.profile !== 'FORM_COMPUTE' || !program.root)
      return EMPTY_COMPUTE_RESULT;
    const budget: FormulaBudget = { count: 0 };
    const root = program.root;
    if (root.kind !== 'ASSIGN' || root.operator !== '=' || root.arguments.length !== 2)
      return EMPTY_COMPUTE_RESULT;
    const [target, expression] = root.arguments;
    if (!isDirectField(target)) return EMPTY_COMPUTE_RESULT;
    const value = this.evaluateComputeNode(expression, draft, 2, budget);
    if (value === INVALID_FORMULA_VALUE) return EMPTY_COMPUTE_RESULT;
    const field = target.field;
    if (equalsFormulaLoose(draft[field], value)) return EMPTY_COMPUTE_RESULT;
    return Object.freeze({
      patch: Object.freeze({ [field]: value }),
      changedFields: Object.freeze([field]),
    });
  }

  private evaluateNode(
    node: FormulaNode,
    record: FormulaRecord,
    depth: number,
    budget: FormulaBudget,
  ): FormulaValue | undefined {
    if (++budget.count > 64 || depth > 12 || !Array.isArray(node.arguments) || !isKnownKind(node.kind))
      return undefined;
    if (node.kind === 'VALUE') {
      return node.arguments.length === 0 && isFormulaValue(node.value) ? node.value : undefined;
    }
    if (node.kind === 'FIELD') {
      return node.arguments.length === 0 &&
        typeof node.field === 'string' &&
        /^[A-Za-z][A-Za-z0-9_]*$/.test(node.field)
        ? asFormulaValue(record[node.field])
        : undefined;
    }
    if (node.kind === 'UNARY') {
      return node.operator === '!' && node.arguments.length === 1
        ? !this.toBoolean(this.evaluateNode(node.arguments[0], record, depth + 1, budget))
        : undefined;
    }
    if (node.kind === 'BINARY') {
      if (node.arguments.length !== 2 || !['&&', '||', '==', '!='].includes(node.operator ?? ''))
        return undefined;
      const left = this.evaluateNode(node.arguments[0], record, depth + 1, budget);
      if (node.operator === '&&')
        return (
          this.toBoolean(left) &&
          this.toBoolean(this.evaluateNode(node.arguments[1], record, depth + 1, budget))
        );
      if (node.operator === '||')
        return (
          this.toBoolean(left) ||
          this.toBoolean(this.evaluateNode(node.arguments[1], record, depth + 1, budget))
        );
      const right = this.evaluateNode(node.arguments[1], record, depth + 1, budget);
      return node.operator === '==' ? equalsFormulaLoose(left, right) : !equalsFormulaLoose(left, right);
    }
    if (node.kind === 'FUNCTION') {
      if (node.operator === 'PRESENT' && node.arguments.length === 1) {
        const value = this.evaluateNode(node.arguments[0], record, depth + 1, budget);
        return value !== null && value !== undefined && value !== '';
      }
      if (node.operator === 'ISNULL' && node.arguments.length === 1) {
        const value = this.evaluateNode(node.arguments[0], record, depth + 1, budget);
        return value === null || value === undefined || value === '';
      }
      if (node.operator === 'IN' && node.arguments.length >= 2 && node.arguments.length <= 21) {
        const value = this.evaluateNode(node.arguments[0], record, depth + 1, budget);
        return (
          node.arguments.slice(1).every((argument) => argument.kind === 'VALUE') &&
          node.arguments
            .slice(1)
            .some((argument) =>
              equalsFormulaLoose(value, this.evaluateNode(argument, record, depth + 1, budget)),
            )
        );
      }
    }
    return undefined;
  }

  private toBoolean(value: FormulaValue | undefined): boolean {
    if (value == null) return false;
    if (typeof value === 'boolean') return value;
    if (typeof value === 'number') return value !== 0;
    const text = String(value).trim();
    if (!text || text.toLowerCase() === 'false') return false;
    if (text.toLowerCase() === 'true') return true;
    const number = parseJavaDouble(text);
    return number === undefined ? true : number !== 0;
  }

  private evaluateComputeNode(
    node: FormulaNode,
    record: FormulaRecord,
    depth: number,
    budget: FormulaBudget,
  ): FormulaValue | typeof INVALID_FORMULA_VALUE {
    if (++budget.count > 128 || depth > 16 || !Array.isArray(node.arguments) || !isKnownKind(node.kind))
      return INVALID_FORMULA_VALUE;
    if (node.kind === 'VALUE') {
      return node.arguments.length === 0 && isFormulaValue(node.value) ? node.value : INVALID_FORMULA_VALUE;
    }
    if (node.kind === 'FIELD') {
      return isDirectField(node) ? asFormulaValue(record[node.field]) : INVALID_FORMULA_VALUE;
    }
    if (
      node.kind === 'UNARY' &&
      node.arguments.length === 1 &&
      ['!', '+', '-'].includes(node.operator ?? '')
    ) {
      const value = this.evaluateComputeNode(node.arguments[0], record, depth + 1, budget);
      if (value === INVALID_FORMULA_VALUE) return value;
      if (node.operator === '!') return !this.toBoolean(value);
      return node.operator === '+' ? toFormulaNumber(value) : -toFormulaNumber(value);
    }
    if (node.kind === 'BINARY' && node.arguments.length === 2 && isComputeBinaryOperator(node.operator)) {
      const left = this.evaluateComputeNode(node.arguments[0], record, depth + 1, budget);
      if (left === INVALID_FORMULA_VALUE) return left;
      if (node.operator === '&&' && !this.toBoolean(left)) return false;
      if (node.operator === '||' && this.toBoolean(left)) return true;
      const right = this.evaluateComputeNode(node.arguments[1], record, depth + 1, budget);
      if (right === INVALID_FORMULA_VALUE) return right;
      switch (node.operator) {
        case '+':
          return typeof left === 'string' || typeof right === 'string'
            ? `${left == null ? '' : left}${right == null ? '' : right}`
            : toFormulaNumber(left) + toFormulaNumber(right);
        case '-':
          return toFormulaNumber(left) - toFormulaNumber(right);
        case '*':
          return toFormulaNumber(left) * toFormulaNumber(right);
        case '/': {
          const divisor = toFormulaNumber(right);
          return divisor === 0 ? 0 : toFormulaNumber(left) / divisor;
        }
        case '%': {
          const divisor = toFormulaNumber(right);
          return divisor === 0 ? 0 : toFormulaNumber(left) % divisor;
        }
        case '>':
          return compareFormulaValues(left, right) > 0;
        case '<':
          return compareFormulaValues(left, right) < 0;
        case '>=':
          return compareFormulaValues(left, right) >= 0;
        case '<=':
          return compareFormulaValues(left, right) <= 0;
        case '==':
          return equalsFormulaLoose(left, right);
        case '!=':
          return !equalsFormulaLoose(left, right);
        case '&&':
          return this.toBoolean(left) && this.toBoolean(right);
        case '||':
          return this.toBoolean(left) || this.toBoolean(right);
      }
    }
    if (node.kind === 'FUNCTION') {
      if ((node.operator === 'PRESENT' || node.operator === 'ISNULL') && node.arguments.length === 1) {
        const value = this.evaluateComputeNode(node.arguments[0], record, depth + 1, budget);
        if (value === INVALID_FORMULA_VALUE) return value;
        const present = value !== null && value !== undefined && value !== '';
        return node.operator === 'PRESENT' ? present : !present;
      }
      if (node.operator === 'IN' && node.arguments.length >= 2 && node.arguments.length <= 21) {
        const value = this.evaluateComputeNode(node.arguments[0], record, depth + 1, budget);
        if (value === INVALID_FORMULA_VALUE) return value;
        for (const argument of node.arguments.slice(1)) {
          if (argument.kind !== 'VALUE') return INVALID_FORMULA_VALUE;
          const candidate = this.evaluateComputeNode(argument, record, depth + 1, budget);
          if (candidate === INVALID_FORMULA_VALUE) return candidate;
          if (equalsFormulaLoose(value, candidate)) return true;
        }
        return false;
      }
    }
    return INVALID_FORMULA_VALUE;
  }
}

type FormulaValue = string | number | boolean | null | undefined;
type FormulaBudget = { count: number };
const INVALID_FORMULA_VALUE = Symbol('invalid formula value');
const EMPTY_COMPUTE_RESULT: FormulaComputeResult = Object.freeze({
  patch: Object.freeze({}),
  changedFields: Object.freeze([]),
});

function isKnownKind(kind: string): kind is FormulaNode['kind'] {
  return ['VALUE', 'FIELD', 'UNARY', 'BINARY', 'FUNCTION', 'ASSIGN'].includes(kind);
}

function isFormulaValue(value: unknown): value is FormulaValue {
  return (
    value === null ||
    value === undefined ||
    typeof value === 'string' ||
    (typeof value === 'number' && Number.isFinite(value)) ||
    typeof value === 'boolean'
  );
}

function asFormulaValue(value: unknown): FormulaValue | undefined {
  return isFormulaValue(value) ? value : undefined;
}

function isDirectField(node: FormulaNode): node is FormulaNode & { field: string } {
  return (
    node.kind === 'FIELD' &&
    node.arguments.length === 0 &&
    typeof node.field === 'string' &&
    /^[A-Za-z][A-Za-z0-9_]*$/.test(node.field)
  );
}

function isComputeBinaryOperator(
  operator: string | undefined,
): operator is '+' | '-' | '*' | '/' | '%' | '>' | '<' | '>=' | '<=' | '==' | '!=' | '&&' | '||' {
  return ['+', '-', '*', '/', '%', '>', '<', '>=', '<=', '==', '!=', '&&', '||'].includes(operator ?? '');
}

function toFormulaNumber(value: FormulaValue): number {
  if (value == null) return 0;
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0;
  const numeric = parseJavaDouble(String(value));
  return numeric !== undefined && Number.isFinite(numeric) ? numeric : 0;
}

function compareFormulaValues(left: FormulaValue, right: FormulaValue): number {
  if (left == null && right == null) return 0;
  if (left == null) return -1;
  if (right == null) return 1;
  const leftInstant = utcSecondMillis(left);
  const rightInstant = utcSecondMillis(right);
  if (leftInstant != null && rightInstant != null) return leftInstant - rightInstant;
  if (isFormulaNumberLike(left) || isFormulaNumberLike(right))
    return toFormulaNumber(left) - toFormulaNumber(right);
  const leftText = String(left);
  const rightText = String(right);
  return leftText === rightText ? 0 : leftText < rightText ? -1 : 1;
}

/** Mirrors FormulaEngine.equalsLoose for the primitive values admitted by the WEB_UI profile. */
export function equalsFormulaLoose(left: unknown, right: unknown): boolean {
  if (left === right || (left == null && right == null)) return true;
  if (left == null || right == null) return false;
  const leftInstant = utcSecondMillis(left);
  const rightInstant = utcSecondMillis(right);
  if (leftInstant != null && rightInstant != null) return leftInstant === rightInstant;
  if (isFormulaNumberLike(left) || isFormulaNumberLike(right))
    return formulaNumber(left) === formulaNumber(right);
  return String(left) === String(right);
}

function isFormulaNumberLike(value: unknown): boolean {
  return typeof value === 'number' || (value != null && parseJavaDouble(String(value)) !== undefined);
}

function formulaNumber(value: unknown): number {
  const numeric = typeof value === 'number' ? value : parseJavaDouble(String(value));
  return numeric !== undefined && Number.isFinite(numeric) ? numeric : 0;
}

/**
 * Mirrors the accepted primitive grammar of Java Double.parseDouble for wire values. JavaScript
 * Number is deliberately not used here: it accepts values such as `0x10` which Java rejects.
 */
function parseJavaDouble(input: string): number | undefined {
  const text = input.trim();
  if (!text) return undefined;
  if (/^[+-]?NaN$/.test(text)) return Number.NaN;
  if (/^[+-]?Infinity$/.test(text))
    return text.startsWith('-') ? Number.NEGATIVE_INFINITY : Number.POSITIVE_INFINITY;
  const decimal = /^[+-]?(?:(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)[dDfF]?$/;
  if (decimal.test(text)) {
    const numeric = Number(text.replace(/[dDfF]$/, ''));
    return Number.isNaN(numeric) ? undefined : numeric;
  }
  const hexadecimal =
    /^([+-]?)(?:0[xX])([0-9a-fA-F]+(?:\.[0-9a-fA-F]*)?|\.[0-9a-fA-F]+)[pP]([+-]?\d+)[dDfF]?$/;
  const match = hexadecimal.exec(text);
  if (!match) return undefined;
  const [integer = '', fraction = ''] = match[2].split('.');
  const whole = integer ? Number.parseInt(integer, 16) : 0;
  const decimalPart = fraction
    ? [...fraction].reduce((total, digit, index) => total + Number.parseInt(digit, 16) / 16 ** (index + 1), 0)
    : 0;
  const numeric = (whole + decimalPart) * 2 ** Number(match[3]);
  return match[1] === '-' ? -numeric : numeric;
}

function utcSecondMillis(value: unknown): number | undefined {
  if (typeof value !== 'string') return undefined;
  const text = value.trim();
  const parts =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})Z$/.exec(text) ??
    /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(text);
  if (!parts) return undefined;
  const millis = Date.UTC(
    Number(parts[1]),
    Number(parts[2]) - 1,
    Number(parts[3]),
    Number(parts[4]),
    Number(parts[5]),
    Number(parts[6]),
  );
  const date = new Date(millis);
  return date.getUTCFullYear() === Number(parts[1]) &&
    date.getUTCMonth() === Number(parts[2]) - 1 &&
    date.getUTCDate() === Number(parts[3]) &&
    date.getUTCHours() === Number(parts[4]) &&
    date.getUTCMinutes() === Number(parts[5]) &&
    date.getUTCSeconds() === Number(parts[6])
    ? millis
    : undefined;
}
