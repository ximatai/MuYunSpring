import { describe, expect, it } from 'vitest';
import { createTenantScopeNavigator } from '@/dynamic-page-runtime/tenantScopeNavigator';
import type { CurrentUser } from '@muyun/web-contracts';
import { platformLanguageContext } from '@muyun/web-core';

const system = { system: true } as CurrentUser;
const tenant = { system: false, tenantId: 'tenant-a' } as CurrentUser;
const context = { levelKey: 'tenant', selectedNavigatorRecords: {}, language: platformLanguageContext };

describe('tenant scope navigation', () => {
  it('exposes an explicit platform range with null ownership instead of all tenants', () => {
    const navigation = createTenantScopeNavigator();
    expect(navigation.title?.(context)).toBe('管理范围');
    expect(navigation.emptySelectionLabel?.({ ...context, currentUser: system })).toBe('平台级');
    expect(navigation.emptyListScope?.({ ...context, currentUser: system })).toEqual([
      { fieldName: 'tenantId', operator: 'NULL', values: [] },
    ]);
    expect(
      navigation.emptyListScope?.({
        ...context,
        currentUser: system,
        selectedNavigatorRecords: { tenant: { id: 'tenant-a' } },
      }),
    ).toBeUndefined();
    expect(navigation.retainSelectedRecord?.('tenant')).toBe(true);
  });

  it('does not add a platform range for a fixed tenant or unrelated navigator', () => {
    const navigation = createTenantScopeNavigator();
    expect(navigation.emptySelectionLabel?.({ ...context, currentUser: tenant })).toBeUndefined();
    expect(navigation.emptyListScope?.({ ...context, currentUser: tenant })).toBeUndefined();
    expect(navigation.emptySelectionLabel?.(context)).toBeUndefined();
    expect(
      navigation.emptySelectionLabel?.({ ...context, currentUser: system, levelKey: 'department' }),
    ).toBeUndefined();
    expect(navigation.title?.({ ...context, levelKey: 'department' })).toBeUndefined();
    expect(navigation.retainSelectedRecord?.('department')).toBe(false);
  });

  it('supports tenant-only scopes and declarative label overrides', () => {
    const navigation = createTenantScopeNavigator({ platformLevel: false, title: '适用租户' });
    expect(navigation.title?.(context)).toBe('适用租户');
    expect(navigation.emptySelectionLabel?.({ ...context, currentUser: system })).toBeUndefined();
    expect(navigation.emptyListScope?.({ ...context, currentUser: system })).toBeUndefined();
    expect(
      createTenantScopeNavigator({ platformLevelLabel: '系统账号' }).emptySelectionLabel?.({
        ...context,
        currentUser: system,
      }),
    ).toBe('系统账号');
    const translated = createTenantScopeNavigator({
      titleKey: 'platform.action.confirm',
      title: 'Fallback',
      platformLevelLabelKey: 'platform.action.confirm',
    });
    expect(translated.title?.(context)).toBe('确定');
    expect(translated.emptySelectionLabel?.({ ...context, currentUser: system })).toBe('确定');
  });
});
