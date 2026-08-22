import type { CurrentUser, MenuRecord, WebTreeNode } from '@muyun/web-contracts';
import type { MenuClient, SessionClient } from './index';

export function createMockSessionClient(currentUser: CurrentUser = mockCurrentUser): SessionClient {
  return {
    current: async () => currentUser,
  };
}

export function createMockMenuClient(records: WebTreeNode<MenuRecord>[] = mockMenuTree): MenuClient {
  return {
    mine: async () => ({ records }),
  };
}

export const mockCurrentUser: CurrentUser = {
  userId: 'user-1',
  username: 'alice',
  tenantId: 'tenant-a',
  organizationId: 'org-1',
  system: false,
};

type MockMenuNode = WebTreeNode<MenuRecord>;
const MOCK_ROOT_COUNT = 5;
const MOCK_CHILD_COUNT = 4;

function mockMenuId(path: number[]) {
  return `menu-${path.join('-')}`;
}

function createMockMenuNode(path: number[], maxLevel: number): MockMenuNode {
  const id = mockMenuId(path);
  const level = path.length;
  const leaf = level === maxLevel;
  const carriesModule = leaf || path.at(-1)! % 2 === 1;
  const parentPath = path.slice(0, -1);

  return {
    record: {
      id,
      schemeId: 'default',
      parentId: parentPath.length === 0 ? 'root' : mockMenuId(parentPath),
      title: `菜单 ${path.join('-')}`,
      enabled: true,
      sortOrder: path.at(-1)! * 10,
      ...(carriesModule
        ? { entryType: 'module' as const, openMode: 'tab' as const, moduleAlias: 'platform.application' }
        : {}),
    },
    children: leaf
      ? []
      : Array.from({ length: MOCK_CHILD_COUNT }, (_, index) =>
          createMockMenuNode([...path, index + 1], maxLevel),
        ),
  };
}

export const mockMenuTree: MockMenuNode[] = Array.from({ length: MOCK_ROOT_COUNT }, (_, index) => {
  const rootNumber = index + 1;
  return createMockMenuNode([rootNumber], rootNumber === 1 ? 5 : 4);
});
