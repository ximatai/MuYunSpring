/**
 * Reconciles a controlled selection with a freshly loaded collection.
 *
 * Platform components never infer a business fallback; callers supply it when their domain has
 * one.  The shared rule is simply that a still-present stable key keeps focus after refresh.
 */
export function reconcileSelectedKey<Key>(
  previous: Key | undefined,
  available: Iterable<Key>,
  fallback?: Key,
): Key | undefined {
  const keys = new Set(available);
  return previous !== undefined && keys.has(previous) ? previous : fallback;
}

/** Keeps only selected keys that are still present in the refreshed result set. */
export function reconcileSelectedKeys<Key>(previous: Iterable<Key>, available: Iterable<Key>): Key[] {
  const keys = new Set(available);
  return [...previous].filter((key) => keys.has(key));
}
