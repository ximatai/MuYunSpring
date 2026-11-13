import type { ModulePageNavigatorEnhancement } from './modulePageEnhancements';

export interface TenantScopeNavigatorOptions {
  /** Must match the descriptor tenant navigator and its tenantId bindings. */
  levelKey?: string;
  /** Only enable when the domain supports records with no tenant ownership. Defaults to true. */
  platformLevel?: boolean;
  title?: string;
  titleKey?: string;
  platformLevelLabel?: string;
  platformLevelLabelKey?: string;
}

/** Standard tenant scope presentation; server permissions and scope enforcement remain authoritative. */
export function createTenantScopeNavigator(
  options: TenantScopeNavigatorOptions = {},
): ModulePageNavigatorEnhancement {
  const levelKey = options.levelKey ?? 'tenant';
  return {
    title: (context) =>
      context.levelKey === levelKey
        ? options.titleKey
          ? context.language.t(options.titleKey, options.title ?? '管理范围')
          : (options.title ?? context.language.t('platform.navigator.management-scope', '管理范围'))
        : undefined,
    emptySelectionLabel: ({ currentUser, levelKey: key, language }) =>
      options.platformLevel !== false &&
      currentUser?.system === true &&
      !currentUser.tenantId &&
      key === levelKey
        ? options.platformLevelLabelKey
          ? language.t(options.platformLevelLabelKey, options.platformLevelLabel ?? '平台级')
          : (options.platformLevelLabel ?? language.t('platform.navigator.platform-level', '平台级'))
        : undefined,
    retainSelectedRecord: (key) => key === levelKey,
    emptyListScope: ({ currentUser, selectedNavigatorRecords }) =>
      options.platformLevel !== false &&
      currentUser?.system === true &&
      !currentUser.tenantId &&
      !selectedNavigatorRecords[levelKey]
        ? [{ fieldName: 'tenantId', operator: 'NULL', values: [] }]
        : undefined,
  };
}
