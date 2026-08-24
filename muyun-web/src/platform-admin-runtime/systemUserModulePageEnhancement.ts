import type { ModulePageEnhancement } from '@muyun/dynamic-page-runtime';

/**
 * Entry-only presentation rules for system accounts. Data scope and mutation safety remain on the
 * server-side IAM entry policy; this merely removes a standard action that this entry cannot use.
 */
export const systemUserModulePageEnhancement: ModulePageEnhancement = {
  id: 'iam-system-user-page-enhancement',
  target: { moduleAlias: 'iam.user', menuId: 'platform.menu.iam.system-user' },
  standardActions: {
    disabled: ['create'],
  },
  navigator: {
    hidden: true,
    bypassListScope: true,
  },
};
