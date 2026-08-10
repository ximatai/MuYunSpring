/**
 * Application-local registry for business-owned refresh triggers.
 * It deliberately knows nothing about realtime transports or business events.
 */
export interface ModulePageListRefreshRegistry {
  register(moduleAlias: string, refresh: () => void): () => void;
  refresh(moduleAlias: string): boolean;
}

export function createModulePageListRefreshRegistry(): ModulePageListRefreshRegistry {
  const refreshers = new Map<string, Set<() => void>>();

  function register(moduleAlias: string, refresh: () => void) {
    const normalized = requireModuleAlias(moduleAlias);
    const handlers = refreshers.get(normalized) ?? new Set<() => void>();
    handlers.add(refresh);
    refreshers.set(normalized, handlers);
    return () => {
      handlers.delete(refresh);
      if (handlers.size === 0) {
        refreshers.delete(normalized);
      }
    };
  }

  function refresh(moduleAlias: string) {
    const handlers = refreshers.get(requireModuleAlias(moduleAlias));
    if (!handlers || handlers.size === 0) {
      return false;
    }
    for (const handler of [...handlers]) {
      handler();
    }
    return true;
  }

  return { register, refresh };
}

/** Shared application facade for business-owned triggers. */
export const modulePageListRefreshRegistry = createModulePageListRefreshRegistry();

export function refreshModulePageList(moduleAlias: string) {
  return modulePageListRefreshRegistry.refresh(moduleAlias);
}

function requireModuleAlias(moduleAlias: string) {
  const normalized = moduleAlias?.trim();
  if (!normalized) {
    throw new Error('Module page list refresh requires a moduleAlias');
  }
  return normalized;
}
