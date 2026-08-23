import { describe, expect, it } from 'vitest';
import { shouldHideSingleResultNavigator } from '@/dynamic-page-runtime/navigatorVisibility';
import type { ResolvedPageNavigatorLevelDescriptor } from '@muyun/web-contracts';

describe('shouldHideSingleResultNavigator', () => {
  const tenantNavigator = {
    key: 'tenant',
    kind: 'MICRO_LIST',
    sourceModuleAlias: 'iam.tenant',
    title: '租户',
    searchPlaceholder: '搜索租户',
    sourceScope: 'CURRENT_TENANT',
    singleResultPolicy: 'AUTO_SELECT_AND_HIDE',
  } satisfies ResolvedPageNavigatorLevelDescriptor;

  it('keeps the authorized tenant selector visible for a system user', () => {
    expect(shouldHideSingleResultNavigator(tenantNavigator, true, undefined)).toBe(false);
  });

  it('hides a tenant selector fixed by a tenant user session', () => {
    expect(shouldHideSingleResultNavigator(tenantNavigator, true, 'tenant-a')).toBe(true);
  });

  it('retains the configured single-result behavior for other navigators', () => {
    expect(
      shouldHideSingleResultNavigator({ ...tenantNavigator, sourceScope: 'NONE' }, true, undefined),
    ).toBe(true);
  });
});
