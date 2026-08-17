import { expect, it, vi } from 'vitest';
import type { UserPreferenceStore } from '@muyun/web-core';
import {
  lockedTabPreferenceKey,
  normalizeLockedTabs,
  restoreLockedTabPreference,
  saveLockedTabPreference,
} from '@/app/lockedTabPreference';

function preferenceStore(): UserPreferenceStore {
  return { get: vi.fn(), restore: vi.fn(), set: vi.fn(), remove: vi.fn() };
}

const lockedTabs = [
  {
    instanceKey: 'ROUTE:platform.application',
    key: 'ROUTE:platform.application',
    title: '应用管理',
    fullPath: '/platform/application?InstanceKey=ROUTE%3Aplatform.application',
    pageDescriptor: {
      pageType: 'platform-route' as const,
      openMode: 'workbench-route' as const,
      hostType: 'platform-route-host' as const,
      target: { route: '/platform/application' },
      tabPolicy: { identity: 'by-target' as const },
    },
  },
];

it('restores normalized locked tabs from the account backend without a device-local fallback', async () => {
  const store = preferenceStore();
  vi.mocked(store.restore).mockResolvedValue([...lockedTabs, { key: 'invalid', title: '无描述' }]);

  await expect(restoreLockedTabPreference(store)).resolves.toEqual(lockedTabs);
  expect(store.restore).toHaveBeenCalledWith(lockedTabPreferenceKey, [], { persistence: 'backend' });
  expect(store.set).not.toHaveBeenCalled();
});

it('stores the complete locked tab descriptors in backend preferences', async () => {
  const store = preferenceStore();
  await saveLockedTabPreference(store, lockedTabs);

  expect(store.set).toHaveBeenCalledWith(lockedTabPreferenceKey, lockedTabs, {
    persistence: 'backend',
  });
  expect(normalizeLockedTabs([lockedTabs[0], lockedTabs[0]])).toEqual(lockedTabs);
  expect(normalizeLockedTabs([{ key: 'broken', title: '损坏', pageDescriptor: {} }])).toEqual([]);
  expect(
    normalizeLockedTabs([
      {
        key: 'missing-route-target',
        title: '损坏路由',
        pageDescriptor: {
          pageType: 'platform-route',
          openMode: 'workbench-route',
          hostType: 'platform-route-host',
          target: {},
          tabPolicy: { identity: 'by-target' },
        },
      },
    ]),
  ).toEqual([]);
});
