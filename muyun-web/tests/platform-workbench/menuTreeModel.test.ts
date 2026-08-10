import { assert, it } from 'vitest';
import {
  buildWorkbenchMegaMenuModel,
  createWorkbenchMenuNodes,
  filterWorkbenchMenuNodes,
  findWorkbenchMenuPath,
  firstDeepRootIdOf,
} from '@/platform-workbench/menuTreeModel.ts';
import type { MenuTreeNode } from '@/web-contracts/index.ts';

const menus: MenuTreeNode[] = [
  {
    record: {
      id: 'platform',
      schemeId: 'default',
      title: '平台管理',
    },
    children: [
      {
        record: {
          id: 'config',
          schemeId: 'default',
          title: '平台配置',
        },
        children: [
          {
            record: {
              id: 'dictionary',
              schemeId: 'default',
              title: '字典管理',
              openMode: 'tab',
              moduleAlias: 'platform.dictionary_category',
            },
            children: [
              {
                record: {
                  id: 'dictionary-items',
                  schemeId: 'default',
                  title: '字典项',
                  openMode: 'tab',
                  moduleAlias: 'platform.dictionary_item',
                  enabled: false,
                },
                children: [],
              },
            ],
          },
        ],
      },
    ],
  },
];

it('createWorkbenchMenuNodes annotates navigable state without changing tree shape', () => {
  const [root] = createWorkbenchMenuNodes(menus);
  const dictionary = root.children[0].children[0];
  const dictionaryItems = dictionary.children[0];

  assert.equal(root.navigable, false);
  assert.equal(root.hasChildren, true);
  assert.equal(dictionary.navigable, true);
  assert.equal(dictionary.target?.menuType, 'module');
  assert.equal(dictionaryItems.navigable, false);
  assert.equal(dictionaryItems.target, undefined);
});

it('filterWorkbenchMenuNodes keeps matching descendants and their ancestors', () => {
  const filtered = filterWorkbenchMenuNodes(createWorkbenchMenuNodes(menus), 'dictionary');

  assert.deepEqual(
    findWorkbenchMenuPath(filtered, 'dictionary').map((node) => node.record.id),
    ['platform', 'config', 'dictionary'],
  );
});

it('filterWorkbenchMenuNodes keeps navigable descendants when a container matches', () => {
  const filtered = filterWorkbenchMenuNodes(createWorkbenchMenuNodes(menus), '平台配置');
  const config = findWorkbenchMenuPath(filtered, 'config').at(-1);

  assert.deepEqual(
    config?.children.map((node) => node.record.id),
    ['dictionary'],
  );
  assert.deepEqual(
    findWorkbenchMenuPath(filtered, 'dictionary').map((node) => node.record.id),
    ['platform', 'config', 'dictionary'],
  );
  assert.deepEqual(findWorkbenchMenuPath(filtered, 'dictionary-items'), []);
});

it('buildWorkbenchMegaMenuModel exposes groups and active deep tree root', () => {
  const [root] = createWorkbenchMenuNodes(menus);
  const activeDeepRootId = firstDeepRootIdOf(root);
  const model = buildWorkbenchMegaMenuModel(root, activeDeepRootId);

  assert.equal(activeDeepRootId, 'dictionary');
  assert.deepEqual(
    model.groups.map((node) => node.record.id),
    ['config'],
  );
  assert.equal(model.activeDeepRoot?.record.id, 'dictionary');
});

it('buildWorkbenchMegaMenuModel packs ordered groups into height-aware columns', () => {
  const [root] = createWorkbenchMenuNodes([
    {
      record: {
        id: 'root',
        schemeId: 'default',
        title: '平台管理',
      },
      children: [
        menuGroup('long', '平台配置与低代码运维', ['应用管理', '模块管理', '元数据管理', '低代码治理']),
        menuGroup('identity', '组织与权限', ['租户管理']),
        menuGroup('support', '业务支撑', ['编码规则']),
        menuGroup('operation', '平台运行运维', ['工作流运维']),
      ],
    },
  ]);
  const model = buildWorkbenchMegaMenuModel(root, undefined, 3);

  assert.deepEqual(
    model.columns.map((column) => column.map((group) => group.record.id)),
    [['long'], ['identity'], ['support', 'operation']],
  );
  assert.deepEqual(
    model.columns.flat().map((group) => group.record.id),
    ['long', 'identity', 'support', 'operation'],
  );
});

it('buildWorkbenchMegaMenuModel caps column count by group count', () => {
  const [root] = createWorkbenchMenuNodes([
    {
      record: {
        id: 'root',
        schemeId: 'default',
        title: '平台管理',
      },
      children: [
        menuGroup('config', '平台配置', ['应用管理']),
        menuGroup('identity', '组织与权限', ['用户管理']),
      ],
    },
  ]);
  const model = buildWorkbenchMegaMenuModel(root, undefined, 4);

  assert.equal(model.columns.length, 2);
});

function menuGroup(id: string, title: string, children: string[]): MenuTreeNode {
  return {
    record: {
      id,
      schemeId: 'default',
      title,
    },
    children: children.map((childTitle, index) => ({
      record: {
        id: `${id}-${index}`,
        schemeId: 'default',
        title: childTitle,
        openMode: 'tab',
        moduleAlias: `platform.${id}_${index}`,
      },
      children: [],
    })),
  };
}
