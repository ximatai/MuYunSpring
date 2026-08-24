import { createMenuClient, createSessionClient } from '@muyun/web-core';
import type { WorkbenchStartupState } from '@muyun/web-contracts';
import { createBackendHttpClient } from '../platform-admin-runtime/backendHttp';
import {
  platformAdminModuleRoutes,
  platformAdminRouteLayouts,
  platformAdminRoutePrefixes,
} from '../platform-admin-runtime/platformAdminRoutes';
import { loadWorkbenchStartupState } from './workbenchStartup';

export async function loadAppWorkbenchStartupState(): Promise<WorkbenchStartupState> {
  if (usesMockStartup()) {
    if (!import.meta.env.DEV) {
      throw new Error('Mock workbench startup is only available in dev mode.');
    }

    // Let Vite resolve this relative import so the configured workbench base
    // (for example `/app/`) is preserved in development as well.
    const { loadDevWorkbenchStartupState } = await import('./devWorkbenchStartup');
    return loadDevWorkbenchStartupState();
  }

  const httpClient = createBackendHttpClient();
  return loadWorkbenchStartupState(
    {
      sessionClient: createSessionClient(httpClient),
      menuClient: createMenuClient(httpClient),
    },
    {
      businessModuleRoutes: platformAdminModuleRoutes,
      businessRouteLayouts: platformAdminRouteLayouts,
      businessRoutePrefixes: platformAdminRoutePrefixes,
    },
  );
}

export function usesMockStartup() {
  if (import.meta.env.VITE_MUYUN_USE_MOCK === 'false') {
    return false;
  }

  return import.meta.env.DEV || import.meta.env.VITE_MUYUN_USE_MOCK === 'true';
}
