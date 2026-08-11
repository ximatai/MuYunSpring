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

export const mockMenuTree: WebTreeNode<MenuRecord>[] = [
  {
    record: {
      id: 'menu-platform-management',
      schemeId: 'default',
      parentId: 'root',
      title: '平台管理',
      enabled: true,
      sortOrder: 10,
    },
    children: [
      {
        record: {
          id: 'menu-platform-config',
          schemeId: 'default',
          parentId: 'menu-platform-management',
          title: '平台配置与低代码运维',
          enabled: true,
          sortOrder: 10,
        },
        children: [
          {
            record: {
              id: 'menu-platform-application',
              schemeId: 'default',
              parentId: 'menu-platform-config',
              title: '应用管理',
              openMode: 'tab',
              moduleAlias: 'platform.application',
              pageMode: 'LIST',
              enabled: true,
              sortOrder: 10,
            },
            children: [],
          },
          {
            record: {
              id: 'menu-platform-metadata',
              schemeId: 'default',
              parentId: 'menu-platform-config',
              title: '元数据管理',
              openMode: 'tab',
              route: '/platform/metadata',
              enabled: true,
              sortOrder: 30,
            },
            children: [
              {
                record: {
                  id: 'menu-platform-field-spec',
                  schemeId: 'default',
                  parentId: 'menu-platform-metadata',
                  title: '字段规格',
                  enabled: true,
                  sortOrder: 10,
                },
                children: [
                  {
                    record: {
                      id: 'menu-platform-field-validation',
                      schemeId: 'default',
                      parentId: 'menu-platform-field-spec',
                      title: '字段校验规则',
                      openMode: 'tab',
                      moduleAlias: 'platform.field_validation_rule',
                      pageMode: 'LIST',
                      enabled: true,
                      sortOrder: 10,
                    },
                    children: [],
                  },
                ],
              },
            ],
          },
          {
            record: {
              id: 'menu-platform-dictionary',
              schemeId: 'default',
              parentId: 'menu-platform-config',
              title: '字典管理',
              openMode: 'tab',
              moduleAlias: 'platform.dictionary_category',
              pageMode: 'LIST',
              enabled: true,
              sortOrder: 50,
            },
            children: [],
          },
        ],
      },
      {
        record: {
          id: 'menu-platform-identity',
          schemeId: 'default',
          parentId: 'menu-platform-management',
          title: '组织与权限',
          enabled: true,
          sortOrder: 20,
        },
        children: [
          {
            record: {
              id: 'menu-platform-employee',
              schemeId: 'default',
              parentId: 'menu-platform-identity',
              title: '职员管理',
              openMode: 'tab',
              moduleAlias: 'iam.employee',
              route: '/iam/employees',
              pageMode: 'LIST',
              enabled: true,
              sortOrder: 50,
            },
            children: [],
          },
          {
            record: {
              id: 'menu-platform-position',
              schemeId: 'default',
              parentId: 'menu-platform-identity',
              title: '岗位管理',
              openMode: 'tab',
              moduleAlias: 'iam.position_category',
              route: '/iam/positions',
              enabled: true,
              sortOrder: 60,
            },
            children: [],
          },
        ],
      },
    ],
  },
  {
    record: {
      id: 'menu-runtime',
      schemeId: 'default',
      parentId: 'root',
      title: '动态运行态',
      openMode: 'tab',
      moduleAlias: 'platform.runtime',
      pageMode: 'LIST',
      defaultUiConfigId: 'runtime-list-v1',
      enabled: true,
      sortOrder: 20,
    },
    children: [],
  },
  {
    record: {
      id: 'menu-identity',
      schemeId: 'default',
      parentId: 'root',
      title: '身份权限',
      openMode: 'tab',
      route: '/identity',
      enabled: true,
      sortOrder: 30,
    },
    children: [],
  },
  {
    record: {
      id: 'menu-crm-online',
      schemeId: 'default',
      parentId: 'root',
      title: 'CRM Online',
      openMode: 'tab',
      externalUrl: '/crm/customer/list',
      enabled: true,
      sortOrder: 40,
    },
    children: [],
  },
];
