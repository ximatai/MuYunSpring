export interface LoginTenantDefaults {
  tenantId: string;
  tenantLocked: boolean;
}

export function resolveLoginTenantDefaults(
  envTenantId?: string | null,
  search = currentSearch(),
  pathname = currentPathname(),
  workbenchBase = import.meta.env.BASE_URL,
): LoginTenantDefaults {
  const urlTenantId = tenantIdFromSearch(search, pathname === workbenchRootPath(workbenchBase));
  if (urlTenantId) {
    return {
      tenantId: urlTenantId,
      tenantLocked: true,
    };
  }
  return {
    tenantId: normalizeInitialValue(envTenantId),
    tenantLocked: false,
  };
}

export function normalizeInitialValue(value: string | null | undefined) {
  return value?.trim() ?? '';
}

function workbenchRootPath(base: string | undefined): string {
  const normalized = base?.trim() || '/';
  const path = `/${normalized.replace(/^\/+|\/+$/g, '')}`.replace(/^\/\/+/g, '/') || '/';
  return path === '/' ? path : `${path}/`;
}

function tenantIdFromSearch(search: string, allowGenericTenantParameter: boolean) {
  const params = new URLSearchParams(search.startsWith('?') ? search : `?${search}`);
  const workbenchTenantId = normalizeInitialValue(params.get('_muyunTenantId'));
  if (workbenchTenantId || !allowGenericTenantParameter) {
    return workbenchTenantId;
  }
  return normalizeInitialValue(params.get('tenantId')) || normalizeInitialValue(params.get('tenant'));
}

function currentSearch() {
  return typeof window === 'undefined' ? '' : window.location.search;
}

function currentPathname() {
  return typeof window === 'undefined' ? '/' : window.location.pathname;
}
