import type { PageHostType } from '@muyun/web-contracts';

/**
 * Restored workbench state can still carry the pre-source-neutral host value.
 * Keep the historical spelling isolated here so new routing code has one name.
 */
export function isLegacyModulePageHostType(hostType: PageHostType): hostType is 'dynamic-module-host' {
  return hostType === 'dynamic-module-host';
}
