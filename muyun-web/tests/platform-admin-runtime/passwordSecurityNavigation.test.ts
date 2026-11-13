import { expect, it } from 'vitest';
import { passwordSecurityNavigation } from '@/platform-admin-runtime/passwordSecurityNavigation';
import { passwordSecurityPageEnhancement } from '@/platform-admin-runtime/passwordSecurityPageEnhancement';
import type { CurrentUser } from '@muyun/web-contracts';
import { platformLanguageContext } from '@muyun/web-core';

it('offers the platform default range only to system users on the tenant navigator', () => {
  const label = passwordSecurityNavigation.emptySelectionLabel!;
  const context = { levelKey: 'tenant', selectedNavigatorRecords: {}, language: platformLanguageContext };
  expect(label({ ...context, currentUser: { system: true } as CurrentUser })).toBe('平台默认');
  expect(
    label({ ...context, currentUser: { system: false, tenantId: 'tenant-a' } as CurrentUser }),
  ).toBeUndefined();
  expect(label(context)).toBeUndefined();
  expect(
    label({ ...context, levelKey: 'another', currentUser: { system: true } as CurrentUser }),
  ).toBeUndefined();
});

it('uses the explicit platform configuration label only on the security policy page', () => {
  const context = {
    levelKey: 'tenant',
    selectedNavigatorRecords: {},
    language: platformLanguageContext,
    currentUser: { system: true } as CurrentUser,
  };
  expect(passwordSecurityPageEnhancement.navigator?.emptySelectionLabel?.(context)).toBe('平台配置');
  expect(passwordSecurityNavigation.emptySelectionLabel?.(context)).toBe('平台默认');
  expect(passwordSecurityPageEnhancement.navigator?.retainSelectedRecord?.('tenant')).toBe(true);
});
