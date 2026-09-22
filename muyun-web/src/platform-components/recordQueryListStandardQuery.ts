import type { QueryOperator } from '@muyun/web-contracts';
import type {
  RecordQueryListFilterField,
  RecordQueryListStandardQuery,
} from './recordQueryListQueryController';
import { isValueLessQueryOperator } from './queryCriteriaDraft';

/** Validate the entire replacement before the page commits any query state. */
export function parseRecordQueryListStandardQuery(
  input: unknown,
  fields: readonly RecordQueryListFilterField[],
): RecordQueryListStandardQuery {
  if (
    !object(input) ||
    Object.keys(input).some((key) => !['conditions', 'sorts'].includes(key)) ||
    !Array.isArray(input.conditions) ||
    !Array.isArray(input.sorts) ||
    input.conditions.length > 20 ||
    input.sorts.length > 5
  ) {
    throw new Error('Query requires conditions (up to 20) and sorts (up to 5)');
  }
  const conditions = input.conditions.map((item) => {
    if (
      !object(item) ||
      Object.keys(item).some((key) => !['fieldName', 'operator', 'values', 'kind'].includes(key))
    )
      throw new Error('Invalid query condition');
    const field = fields.find((field) => field.name === item.fieldName);
    const operator = item.operator as QueryOperator;
    if (
      !field ||
      !field.operators.includes(operator) ||
      !Array.isArray(item.values) ||
      (item.kind !== undefined && item.kind !== 'CONDITION')
    )
      throw new Error('Query field or operator is unavailable');
    const values: unknown[] = item.values;
    const collection = ['IN', 'NOT_IN', 'CONTAINS_ANY', 'CONTAINS_ALL'].includes(operator);
    const validCount = isValueLessQueryOperator(operator)
      ? values.length === 0
      : operator === 'BETWEEN'
        ? values.length === 2
        : collection
          ? values.length > 0 && values.length <= 100
          : values.length === 1;
    if (!validCount || values.some((value) => !validValue(value, field)))
      throw new Error(`Invalid query values for ${field.name}`);
    return { kind: 'CONDITION' as const, fieldName: field.name, operator, values: [...values] };
  });
  const names = new Set<string>();
  const sorts = input.sorts.map((item) => {
    if (
      !object(item) ||
      Object.keys(item).some((key) => !['field', 'desc'].includes(key)) ||
      typeof item.field !== 'string' ||
      typeof item.desc !== 'boolean' ||
      !fields.some((field) => field.name === item.field && field.sortable) ||
      names.has(item.field)
    )
      throw new Error('Query sort field is unavailable or repeated');
    names.add(item.field);
    return { field: item.field, desc: item.desc };
  });
  return { conditions, sorts };
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function validValue(value: unknown, field: RecordQueryListFilterField): boolean {
  if (field.options) return field.options.some((option) => option === value);
  switch (field.valueType) {
    case 'BOOLEAN':
      return typeof value === 'boolean';
    case 'INTEGER':
    case 'LONG':
      return typeof value === 'number' && Number.isSafeInteger(value);
    case 'DECIMAL':
      return typeof value === 'number' && Number.isFinite(value);
    case 'DATE':
      return (
        typeof value === 'string' &&
        /^\d{4}-\d{2}-\d{2}$/.test(value) &&
        !Number.isNaN(Date.parse(value)) &&
        new Date(value).toISOString().slice(0, 10) === value
      );
    case 'INSTANT':
      return (
        typeof value === 'string' &&
        /^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
        !Number.isNaN(Date.parse(value))
      );
    case 'STRING':
    case 'TEXT':
      return typeof value === 'string' && value.length <= 500;
    default:
      return false;
  }
}
