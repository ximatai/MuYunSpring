import type { ModulePageEnhancement } from '@muyun/dynamic-page-runtime';

/** Tenant execution scope is supplied by the standard host; the domain owns uniqueness. */
export const tenantCurrencySettingPageEnhancement: ModulePageEnhancement = {
  id: 'platform-tenant-currency-setting',
  target: { moduleAlias: 'platform.tenant_currency_setting' },
  list: { singleRecord: true },
  standardActions: { disabled: ['delete'] },
};
