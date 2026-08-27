import type { FormulaNode, FormulaProgram, ViewFieldValueType } from '@muyun/web-contracts';

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
    if (!isValidWebUiRoot(program.root)) return false;
    return this.evaluateNode(program.root, record, 1, { count: 0 }) === true;
  }

  /** Evaluates a server-issued PAGE_TEXT program against a host-whitelisted display context. */
  evaluatePageText(program: FormulaProgram | undefined, context: FormulaRecord): string | undefined {
    if (!program || program.schemaVersion !== 1 || program.profile !== 'PAGE_TEXT' || !program.root)
      return undefined;
    if (!isValidPageTextNode(program.root, 1, { count: 0 })) return undefined;
    const value = this.evaluatePageTextNode(program.root, context, 1, { count: 0 });
    return typeof value === 'string' ? value : undefined;
  }

  /**
   * Evaluates one server-issued FORM_COMPUTE assignment against a draft without mutating it.
   * Applying the returned patch, ordering rules, and user-value ownership remain form-runtime concerns.
   */
  evaluateFormCompute(
    program: FormulaProgram | undefined,
    draft: FormulaRecord,
    targetValueType?: ViewFieldValueType,
  ): FormulaComputeResult {
    if (!program || program.schemaVersion !== 1 || program.profile !== 'FORM_COMPUTE' || !program.root)
      return EMPTY_COMPUTE_RESULT;
    if (!targetValueType || targetValueType === 'JSON') return EMPTY_COMPUTE_RESULT;
    const budget: FormulaBudget = { count: 0 };
    const root = program.root;
    if (root.kind !== 'ASSIGN' || root.operator !== '=' || root.arguments.length !== 2)
      return EMPTY_COMPUTE_RESULT;
    const [target, expression] = root.arguments;
    if (!isDirectField(target)) return EMPTY_COMPUTE_RESULT;
    const evaluated = this.evaluateComputeNode(expression, draft, 2, budget);
    if (evaluated === INVALID_FORMULA_VALUE) return EMPTY_COMPUTE_RESULT;
    const value = normalizeComputeWriteValue(evaluated, targetValueType);
    if (value === INVALID_FORMULA_VALUE) return EMPTY_COMPUTE_RESULT;
    const field = target.field;
    if (Object.is(draft[field], value) || (draft[field] == null && value == null))
      return EMPTY_COMPUTE_RESULT;
    return Object.freeze({
      patch: Object.freeze({ [field]: value }),
      changedFields: Object.freeze([field]),
    });
  }

  /** Evaluates a row-to-row assignment in the changed row's scope without mutating any drafts. */
  evaluateRelationFormCompute(
    program: FormulaProgram | undefined,
    draft: FormulaRecord,
    targetField: string,
    targetValueType?: ViewFieldValueType,
  ): FormulaComputeResult {
    if (!program || program.schemaVersion !== 1 || program.profile !== 'FORM_COMPUTE' || !program.root)
      return EMPTY_COMPUTE_RESULT;
    if (!targetValueType || targetValueType === 'JSON') return EMPTY_COMPUTE_RESULT;
    const root = program.root;
    if (
      root.kind !== 'ASSIGN' ||
      root.operator !== '=' ||
      root.arguments.length < 2 ||
      root.arguments.length > 3
    )
      return EMPTY_COMPUTE_RESULT;
    const [target, expression, condition] = root.arguments;
    if (target.kind !== 'OTHERS' || target.field == null || target.field.split('.').at(-1) !== targetField)
      return EMPTY_COMPUTE_RESULT;
    const budget: FormulaBudget = { count: 0 };
    if (condition) {
      const applies = this.evaluateComputeNode(condition, draft, 2, budget);
      if (applies === INVALID_FORMULA_VALUE || !this.toBoolean(applies)) return EMPTY_COMPUTE_RESULT;
    }
    const evaluated = this.evaluateComputeNode(expression, draft, 2, budget);
    if (evaluated === INVALID_FORMULA_VALUE || evaluated === undefined) return EMPTY_COMPUTE_RESULT;
    const value = normalizeComputeWriteValue(evaluated, targetValueType);
    if (value === INVALID_FORMULA_VALUE) return EMPTY_COMPUTE_RESULT;
    return Object.freeze({
      patch: Object.freeze({ [targetField]: value }),
      changedFields: Object.freeze([targetField]),
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
      return node.arguments.length === 0 && typeof node.field === 'string' && isWebUiFieldName(node.field)
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

  private evaluatePageTextNode(
    node: FormulaNode,
    context: FormulaRecord,
    depth: number,
    budget: FormulaBudget,
  ): string | undefined {
    if (++budget.count > 64 || depth > 12 || !Array.isArray(node.arguments)) return undefined;
    if (node.kind === 'VALUE') {
      return node.operator == null &&
        node.field == null &&
        node.arguments.length === 0 &&
        typeof node.value === 'string'
        ? node.value
        : undefined;
    }
    if (node.kind === 'FIELD') {
      if (
        node.operator != null ||
        node.value != null ||
        node.arguments.length !== 0 ||
        !isPageTextFieldName(node.field)
      )
        return undefined;
      const value = context[node.field];
      return value == null ? '' : typeof value === 'string' ? value : undefined;
    }
    if (
      node.kind !== 'BINARY' ||
      node.operator !== '+' ||
      node.field != null ||
      node.value != null ||
      node.arguments.length !== 2
    )
      return undefined;
    const left = this.evaluatePageTextNode(node.arguments[0], context, depth + 1, budget);
    const right = this.evaluatePageTextNode(node.arguments[1], context, depth + 1, budget);
    return left === undefined || right === undefined ? undefined : `${left}${right}`;
  }
}

type FormulaValue = string | number | boolean | null | undefined;
type FormulaBudget = { count: number };
const INVALID_FORMULA_VALUE = Symbol('invalid formula value');
const EMPTY_COMPUTE_RESULT: FormulaComputeResult = Object.freeze({
  patch: Object.freeze({}),
  changedFields: Object.freeze([]),
});

function isValidWebUiRoot(node: FormulaNode): boolean {
  return node.kind !== 'VALUE' && node.kind !== 'FIELD' && isValidWebUiNode(node, 1, { count: 0 });
}

function isValidPageTextNode(node: FormulaNode, depth: number, budget: FormulaBudget): boolean {
  if (++budget.count > 64 || depth > 12 || !Array.isArray(node.arguments)) return false;
  if (node.kind === 'VALUE')
    return (
      node.operator == null &&
      node.field == null &&
      node.arguments.length === 0 &&
      typeof node.value === 'string' &&
      node.value.length <= 128
    );
  if (node.kind === 'FIELD')
    return (
      node.operator == null &&
      node.value == null &&
      node.arguments.length === 0 &&
      isPageTextFieldName(node.field)
    );
  return (
    node.kind === 'BINARY' &&
    node.operator === '+' &&
    node.field == null &&
    node.value == null &&
    node.arguments.length === 2 &&
    isValidPageTextNode(node.arguments[0], depth + 1, budget) &&
    isValidPageTextNode(node.arguments[1], depth + 1, budget)
  );
}

function isPageTextFieldName(field: unknown): field is 'selection.label' | 'selection.secondaryLabel' {
  return field === 'selection.label' || field === 'selection.secondaryLabel';
}

function isValidWebUiNode(node: FormulaNode, depth: number, budget: FormulaBudget): boolean {
  if (++budget.count > 64 || depth > 12 || !Array.isArray(node.arguments)) return false;
  if (node.kind === 'FIELD')
    return (
      node.operator == null &&
      node.value == null &&
      node.arguments.length === 0 &&
      typeof node.field === 'string' &&
      isWebUiFieldName(node.field)
    );
  if (node.kind === 'VALUE')
    return (
      node.operator == null &&
      node.field == null &&
      node.arguments.length === 0 &&
      ((typeof node.value === 'string' && node.value.length <= 128) ||
        typeof node.value === 'boolean' ||
        (typeof node.value === 'number' && Number.isFinite(node.value)))
    );
  if (node.kind === 'UNARY')
    return (
      node.field == null &&
      node.value == null &&
      node.operator === '!' &&
      node.arguments.length === 1 &&
      isValidWebUiNode(node.arguments[0], depth + 1, budget)
    );
  if (node.kind === 'BINARY') {
    if (node.field != null || node.value != null || node.arguments.length !== 2) return false;
    if (node.operator === '&&' || node.operator === '||')
      return (
        isValidWebUiNode(node.arguments[0], depth + 1, budget) &&
        isValidWebUiNode(node.arguments[1], depth + 1, budget)
      );
    return (
      (node.operator === '==' || node.operator === '!=') &&
      isWebUiField(node.arguments[0], depth + 1, budget) &&
      isWebUiLiteral(node.arguments[1], depth + 1, budget)
    );
  }
  if (node.kind !== 'FUNCTION' || node.field != null || node.value != null) return false;
  if (node.operator === 'PRESENT' || node.operator === 'ISNULL')
    return node.arguments.length === 1 && isWebUiField(node.arguments[0], depth + 1, budget);
  if (node.operator !== 'IN' || node.arguments.length < 2 || node.arguments.length > 21) return false;
  if (!isWebUiField(node.arguments[0], depth + 1, budget)) return false;
  return node.arguments.slice(1).every((argument) => isWebUiLiteral(argument, depth + 1, budget));
}

function isWebUiField(node: FormulaNode, depth: number, budget: FormulaBudget): boolean {
  return (
    isValidWebUiNode(node, depth, budget) &&
    node.kind === 'FIELD' &&
    typeof node.field === 'string' &&
    isWebUiFieldName(node.field)
  );
}

function isWebUiLiteral(node: FormulaNode, depth: number, budget: FormulaBudget): boolean {
  return isValidWebUiNode(node, depth, budget) && node.kind === 'VALUE';
}

function normalizeComputeWriteValue(
  input: FormulaValue,
  targetValueType: ViewFieldValueType,
): FormulaValue | typeof INVALID_FORMULA_VALUE {
  const value = typeof input === 'number' && Number.isNaN(input) ? 0 : input;
  if (value == null) return null;
  switch (targetValueType) {
    case 'STRING':
    case 'TEXT':
      return typeof value === 'number' ? javaDoubleString(value) : String(value);
    case 'INTEGER': {
      const integer = integralFormulaValue(value);
      return integer !== undefined && integer >= -2_147_483_648 && integer <= 2_147_483_647
        ? integer
        : INVALID_FORMULA_VALUE;
    }
    case 'LONG': {
      const integer = integralFormulaValue(value);
      return integer !== undefined && Number.isSafeInteger(integer) ? integer : INVALID_FORMULA_VALUE;
    }
    case 'DECIMAL':
      if (typeof value === 'number') return Number.isFinite(value) ? value : INVALID_FORMULA_VALUE;
      return typeof value === 'string' && isJavaBigDecimal(value) ? value.trim() : INVALID_FORMULA_VALUE;
    case 'BOOLEAN':
      if (typeof value === 'boolean') return value;
      if (typeof value === 'number') return value !== 0;
      if (typeof value === 'string' && value.trim().toLowerCase() === 'true') return true;
      if (typeof value === 'string' && value.trim().toLowerCase() === 'false') return false;
      return INVALID_FORMULA_VALUE;
    case 'DATE':
      return typeof value === 'string' && isIsoDate(value.trim()) ? value.trim() : INVALID_FORMULA_VALUE;
    case 'TIMESTAMP':
    case 'ZONED_TIMESTAMP':
      return typeof value === 'string' && isJavaTimestamp(value.trim())
        ? value.trim()
        : INVALID_FORMULA_VALUE;
    case 'JSON':
      return INVALID_FORMULA_VALUE;
  }
}

function integralFormulaValue(value: FormulaValue): number | undefined {
  if (typeof value === 'number')
    return Number.isSafeInteger(value) && Number.isFinite(value) ? value : undefined;
  if (typeof value !== 'string' || !/^[+-]?\d+$/.test(value.trim())) return undefined;
  const numeric = Number(value.trim());
  return Number.isSafeInteger(numeric) ? numeric : undefined;
}

function isJavaBigDecimal(value: string): boolean {
  return /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim());
}

function isIsoDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12) return false;
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day >= 1 && day <= daysInMonth[month - 1];
}

function isJavaTimestamp(value: string): boolean {
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})?$/.exec(value);
  if (!match || !isIsoDate(match[1])) return false;
  const hour = Number(match[2]);
  const minute = Number(match[3]);
  const second = Number(match[4]);
  if (hour > 23 || minute > 59 || second > 59) return false;
  const offset = match[5];
  if (offset && offset !== 'Z') {
    const [offsetHour, offsetMinute] = offset.slice(1).split(':').map(Number);
    if (offsetHour > 18 || offsetMinute > 59 || (offsetHour === 18 && offsetMinute !== 0)) return false;
  }
  return true;
}

function javaDoubleString(value: number): string {
  if (Number.isNaN(value)) return 'NaN';
  if (value === Number.POSITIVE_INFINITY) return 'Infinity';
  if (value === Number.NEGATIVE_INFINITY) return '-Infinity';
  if (Object.is(value, -0)) return '-0.0';
  const absolute = Math.abs(value);
  if (absolute === 0) return '0.0';
  if (absolute >= 1e-3 && absolute < 1e7) {
    const plain = String(value);
    return Number.isInteger(value) ? `${plain}.0` : plain;
  }
  const [rawMantissa, rawExponent] = value.toExponential().split('e');
  const mantissa = rawMantissa.includes('.') ? rawMantissa : `${rawMantissa}.0`;
  return `${mantissa}E${Number(rawExponent)}`;
}

function isKnownKind(kind: string): kind is FormulaNode['kind'] {
  return ['VALUE', 'FIELD', 'OTHERS', 'UNARY', 'BINARY', 'FUNCTION', 'ASSIGN'].includes(kind);
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

/** WEB_UI formulas may read descriptor-authorized reference selection context through dot paths. */
function isWebUiFieldName(field: string) {
  return /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*$/.test(field);
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
