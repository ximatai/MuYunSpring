import type { PageHostType } from '@muyun/web-contracts';
import { isLegacyModulePageHostType } from './legacyPageHostTypeCompatibility';

export type PageHostComponentName =
  'PlatformRouteHost' | 'BusinessRouteHost' | 'ModulePageHost' | 'ExternalPageHost';

export function resolvePageHostComponentName(hostType: PageHostType): PageHostComponentName {
  switch (hostType) {
    case 'platform-route-host':
      return 'PlatformRouteHost';
    case 'business-route-host':
      return 'BusinessRouteHost';
    case 'module-page-host':
      return 'ModulePageHost';
    case 'external-page-host':
      return 'ExternalPageHost';
    default:
      if (isLegacyModulePageHostType(hostType)) return 'ModulePageHost';
      return exhaustiveHostType(hostType);
  }
}

function exhaustiveHostType(hostType: never): never {
  throw new Error(`Unsupported page host type: ${hostType}`);
}
