/**
 * Encodes partition values without collapsing distinct persisted values such as null and "".
 * JSON is used instead of a delimiter so values cannot collide through delimiter characters.
 */
export function sortPartitionKey(values: readonly unknown[]): string {
  return JSON.stringify(
    values.map((value) => ({
      type: value === null ? 'null' : value === undefined ? 'undefined' : typeof value,
      value: value === undefined ? null : value,
    })),
  );
}
