import type { ModulePageNavigatorEnhancement } from '@muyun/dynamic-page-runtime';

/** Platform defaults are a real configuration range, represented by no tenant selection. */
export const passwordSecurityNavigation: ModulePageNavigatorEnhancement = {
  emptySelectionLabel: ({ currentUser, levelKey, language }) =>
    currentUser?.system && levelKey === 'tenant'
      ? language.t('platform.password-security.platform-default-range', '平台默认')
      : undefined,
};
