import { createMockMenuClient, createMockSessionClient } from '@/web-core/mock';
import {
  platformAdminModuleRoutes,
  platformAdminDynamicModuleRoutes,
  platformAdminRouteLayouts,
  platformAdminRoutePrefixes,
} from '../platform-admin-runtime/platformAdminRoutes';
import { loadWorkbenchStartupState } from './workbenchStartup';

export function loadDevWorkbenchStartupState() {
  return loadWorkbenchStartupState(
    {
      sessionClient: createMockSessionClient(),
      menuClient: createMockMenuClient(),
    },
    {
      businessModuleRoutes: platformAdminModuleRoutes,
      dynamicModuleRoutes: platformAdminDynamicModuleRoutes,
      businessRouteLayouts: platformAdminRouteLayouts,
      businessRoutePrefixes: platformAdminRoutePrefixes,
    },
  );
}
