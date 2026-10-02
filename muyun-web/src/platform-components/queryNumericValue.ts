/** Query wire values preserve decimal/64-bit precision; never coerce text through Number. */
export function queryNumericValueSchema(type: string): Record<string, unknown> {
  const integer = type === 'INTEGER' || type === 'LONG';
  return {
    anyOf: [
      {
        type: 'string',
        maxLength: 500,
        pattern: integer ? '^[+-]?[0-9]+$' : '^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$',
      },
      integer
        ? {
            type: 'integer',
            minimum: type === 'INTEGER' ? -2147483648 : Number.MIN_SAFE_INTEGER,
            maximum: type === 'INTEGER' ? 2147483647 : Number.MAX_SAFE_INTEGER,
          }
        : { type: 'number' },
    ],
    description: 'Use a numeric string to preserve exact decimal or 64-bit integer precision.',
  };
}

export function validQueryNumericValue(value: unknown, type: string): boolean {
  if (typeof value === 'number')
    return type === 'DECIMAL'
      ? Number.isFinite(value)
      : Number.isSafeInteger(value) && (type !== 'INTEGER' || (value >= -2147483648 && value <= 2147483647));
  if (typeof value !== 'string' || value.length > 500) return false;
  const text = value.trim();
  if (type === 'DECIMAL') return /^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$/.test(text);
  if (!/^[+-]?[0-9]+$/.test(text)) return false;
  const integer = BigInt(text);
  return type === 'INTEGER'
    ? integer >= -2147483648n && integer <= 2147483647n
    : integer >= -9223372036854775808n && integer <= 9223372036854775807n;
}
