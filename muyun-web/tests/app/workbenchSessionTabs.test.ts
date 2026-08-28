import { afterEach, expect, it } from 'vitest';
import { restoreWorkbenchSessionTabs, saveWorkbenchSessionTabs } from '@/app/workbenchSessionTabs';

const values = new Map<string, string>();
const storage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => values.set(key, value),
  removeItem: (key: string) => values.delete(key),
};

afterEach(() => {
  values.clear();
});

it('restores the current browser session tabs only for the same user', () => {
  saveWorkbenchSessionTabs(
    {
      userId: 'user-a',
      tabs: [
        {
          key: 'menu:iam.user',
          title: '用户管理',
          target: { menuId: 'iam.user', menuType: 'module', moduleAlias: 'iam.user', openMode: 'tab' },
        },
      ],
      activeTabKey: 'menu:iam.user',
    },
    storage,
  );

  expect(restoreWorkbenchSessionTabs('user-a', storage)).toMatchObject({
    userId: 'user-a',
    activeTabKey: 'menu:iam.user',
    tabs: [{ key: 'menu:iam.user' }],
  });
  expect(restoreWorkbenchSessionTabs('user-b', storage)).toBeUndefined();
});

it('discards malformed browser session snapshots', () => {
  storage.setItem('muyun.workbench.session-tabs.v1', '{not-json');

  expect(restoreWorkbenchSessionTabs('user-a', storage)).toBeUndefined();
  expect(storage.getItem('muyun.workbench.session-tabs.v1')).toBeNull();
});

it('filters structurally invalid tab entries before startup restoration', () => {
  storage.setItem(
    'muyun.workbench.session-tabs.v1',
    JSON.stringify({
      version: 1,
      userId: 'user-a',
      tabs: [{ key: { invalid: true }, title: '损坏' }],
    }),
  );

  expect(restoreWorkbenchSessionTabs('user-a', storage)?.tabs).toEqual([]);
});
