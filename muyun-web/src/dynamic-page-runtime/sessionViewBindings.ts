import { computed, type WritableComputedRef } from 'vue';

/** Keep the renderer instance while replacing the disposed business session behind its bindings. */
export function sessionViewBindings<T extends object>(current: () => T) {
  return Object.fromEntries(
    Object.keys(current()).map((name) => {
      const key = name as keyof T;
      return [
        key,
        computed({
          get: () => current()[key],
          set: (value) => {
            current()[key] = value;
          },
        }),
      ];
    }),
  ) as { [K in keyof T]: WritableComputedRef<T[K]> };
}
