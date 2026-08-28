import { afterEach, describe, expect, it } from 'vitest';
import {
  configureModulePageEnhancementContributions,
  configureModulePageEnhancements,
  createModulePageEnhancementRegistry,
  createReadonlyCardRecordSnapshot,
  modulePageWorkspaceViews,
  resolveModulePageEnhancement,
} from '@/dynamic-page-runtime/modulePageEnhancements.ts';
import { createWorkspaceViewDescriptor } from '@/platform-workbench/workspaceViews.ts';
import { platformModulePageEnhancement } from '@/platform-admin-runtime/platformModulePageEnhancement.ts';
import { passwordPolicyPageEnhancement } from '@/platform-admin-runtime/passwordPolicyPageEnhancement.ts';
import { tenantModulePageEnhancement } from '@/platform-admin-runtime/tenantModulePageEnhancement.ts';
import { userModulePageEnhancement } from '@/platform-admin-runtime/userModulePageEnhancement.ts';

describe('module page enhancements', () => {
  afterEach(() => {
    configureModulePageEnhancements([]);
    configureModulePageEnhancementContributions('platform-admin-runtime', [
      platformModulePageEnhancement,
      passwordPolicyPageEnhancement,
      tenantModulePageEnhancement,
      userModulePageEnhancement,
    ]);
  });

  it('merges a view-specific enhancement with its module-wide fallback', () => {
    const fallback = { id: 'customer-default', target: { moduleAlias: 'crm.customer' } };
    const viewSpecific = {
      id: 'customer-list',
      target: { moduleAlias: 'crm.customer', viewCode: 'default_list' },
    };
    const registry = createModulePageEnhancementRegistry([fallback, viewSpecific]);

    expect(registry.resolve('crm.customer', 'default_list')).toMatchObject({
      id: 'customer-default, customer-list',
      target: { moduleAlias: 'crm.customer', viewCode: 'default_list' },
    });
    expect(registry.resolve('crm.customer', 'other_list')).toMatchObject(fallback);
    expect(registry.resolve('crm.order')).toBeUndefined();
  });

  it('merges a menu-entry enhancement without leaking it to other module entries', () => {
    const fallback = { id: 'user-default', target: { moduleAlias: 'iam.user' } };
    const systemEntry = {
      id: 'system-user',
      target: { moduleAlias: 'iam.user', menuId: 'platform.menu.iam.system-user' },
      standardActions: { disabled: ['create'] },
      navigator: { hidden: true, bypassListScope: true },
    };
    const registry = createModulePageEnhancementRegistry([fallback, systemEntry]);

    expect(registry.resolve('iam.user', undefined, 'platform.menu.iam.system-user')).toMatchObject({
      id: 'user-default, system-user',
      standardActions: { disabled: ['create'] },
      navigator: { hidden: true, bypassListScope: true },
    });
    expect(registry.resolve('iam.user', undefined, 'platform.menu.module.iam.user')).toMatchObject(fallback);
  });

  it('composes independent target contributions and rejects duplicate contribution identities', () => {
    const registry = createModulePageEnhancementRegistry([
      {
        id: 'first',
        target: { moduleAlias: 'crm.customer' },
        detail: { actions: [{ key: 'timeline', title: '时间线', run: () => undefined }] },
      },
      {
        id: 'second',
        target: { moduleAlias: 'crm.customer' },
        detail: { actions: [{ key: 'conversation', title: '对话', run: () => undefined }] },
      },
    ]);
    expect(registry.resolve('crm.customer')?.detail?.actions?.map((action) => action.key)).toEqual([
      'timeline',
      'conversation',
    ]);

    expect(() =>
      createModulePageEnhancementRegistry([
        { id: 'duplicate-id', target: { moduleAlias: 'crm.customer' } },
        { id: 'duplicate-id', target: { moduleAlias: 'crm.order' } },
      ]),
    ).toThrow('重复的模块页面增强');

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'duplicate-list-key',
          target: { moduleAlias: 'crm.customer' },
          list: {
            actions: [
              { key: 'conversation', title: '对话', run: () => undefined },
              { key: 'conversation', title: '另一个对话', run: () => undefined },
            ],
          },
        },
      ]),
    ).toThrow('同一页面区域存在重复的贡献 key');

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'override-detail-update',
          target: { moduleAlias: 'crm.customer' },
          detail: {
            actions: [{ key: 'update', title: '覆盖编辑', run: () => undefined }],
          },
        },
      ]),
    ).toThrow('不能覆盖平台标准动作：update');

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'first-form-contribution',
          target: { moduleAlias: 'crm.customer' },
          form: {
            contributions: [
              {
                key: 'brand-mode',
                component: {},
                location: { surface: 'record-card', section: 'before-fields' },
              },
            ],
          },
        },
        {
          id: 'second-form-contribution',
          target: { moduleAlias: 'crm.customer' },
          form: {
            contributions: [
              {
                key: 'brand-mode',
                component: {},
                location: { surface: 'record-card', section: 'before-fields' },
              },
            ],
          },
        },
      ]),
    ).toThrow('同一页面区域存在重复的贡献 key');

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'first-target-action',
          target: { moduleAlias: 'crm.customer' },
          detail: { actions: [{ key: 'timeline', title: '时间线', run: () => undefined }] },
        },
        {
          id: 'second-target-action',
          target: { moduleAlias: 'crm.customer' },
          detail: { actions: [{ key: 'timeline', title: '另一个时间线', run: () => undefined }] },
        },
      ]),
    ).toThrow('同一页面区域存在重复的贡献 key');

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'first-view',
          target: { moduleAlias: 'crm.customer' },
          workspaceViews: [workspaceView('crm.customer.workbench')],
        },
        {
          id: 'second-view',
          target: { moduleAlias: 'crm.customer' },
          workspaceViews: [workspaceView('crm.customer.workbench')],
        },
      ]),
    ).toThrow('重复的模块页面工作视图类型');
  });

  it('normalizes root record actions into both record surfaces with the same stable anchors', () => {
    const rootActions = [
      { key: 'timeline', title: '时间线', run: () => undefined },
      { key: 'conversation', title: '对话', before: 'timeline', run: () => undefined },
    ];
    const single = createModulePageEnhancementRegistry([
      { id: 'customer-root-actions', target: { moduleAlias: 'crm.customer' }, recordActions: rootActions },
    ]);
    const singlePage = single.resolve('crm.customer');
    expect(singlePage?.list?.rowActions?.map((action) => action.key)).toEqual(['conversation', 'timeline']);
    expect(singlePage?.detail?.actions?.map((action) => action.key)).toEqual(['conversation', 'timeline']);

    const composite = createModulePageEnhancementRegistry([
      {
        id: 'customer-root-timeline',
        target: { moduleAlias: 'crm.customer' },
        recordActions: [rootActions[0]],
      },
      {
        id: 'customer-root-conversation',
        target: { moduleAlias: 'crm.customer' },
        recordActions: [rootActions[1]],
      },
    ]).resolve('crm.customer');
    expect(composite?.list?.rowActions?.map((action) => action.key)).toEqual(['conversation', 'timeline']);
    expect(composite?.detail?.actions?.map((action) => action.key)).toEqual(['conversation', 'timeline']);
  });

  it('rejects record action keys that collide after root and local surfaces are composed', () => {
    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'root-list-row-action',
          target: { moduleAlias: 'crm.customer' },
          recordActions: [{ key: 'timeline', title: '时间线', run: () => undefined }],
        },
        {
          id: 'local-list-row-conflict',
          target: { moduleAlias: 'crm.customer' },
          list: { rowActions: [{ key: 'timeline', title: '另一个时间线', run: () => undefined }] },
        },
      ]),
    ).toThrow('同一页面区域存在重复的贡献 key');

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'root-detail-action',
          target: { moduleAlias: 'crm.customer' },
          recordActions: [{ key: 'timeline', title: '时间线', run: () => undefined }],
        },
        {
          id: 'local-detail-conflict',
          target: { moduleAlias: 'crm.customer' },
          detail: { actions: [{ key: 'timeline', title: '另一个时间线', run: () => undefined }] },
        },
      ]),
    ).toThrow('同一页面区域存在重复的贡献 key');

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'root-standard-action-conflict',
          target: { moduleAlias: 'crm.customer' },
          recordActions: [{ key: 'view', title: '覆盖查看', run: () => undefined }],
        },
      ]),
    ).toThrow('不能覆盖平台标准动作：view');
  });

  it('retains platform defaults when a consumer configures its legacy contribution set', () => {
    configureModulePageEnhancementContributions('platform-admin-runtime', [platformModulePageEnhancement]);
    configureModulePageEnhancements([
      {
        id: 'consumer-platform-module-inspection',
        target: { moduleAlias: 'platform.module' },
        detail: { actions: [{ key: 'consumer-inspection', title: '检查', run: () => undefined }] },
        workspaceViews: [workspaceView('platform.module.consumer-inspection', 'platform.module')],
      },
    ]);

    expect(
      resolveModulePageEnhancement('platform.module')?.detail?.actions?.map((action) => action.key),
    ).toEqual(
      expect.arrayContaining([
        'module-actions-workspace',
        'module-metadata-orchestration-workspace',
        'module-openapi-page',
        'consumer-inspection',
      ]),
    );
    expect(modulePageWorkspaceViews().map((view) => view.type)).toEqual(
      expect.arrayContaining([
        'platform.module.actions',
        'platform.module.metadata-orchestration',
        'platform.module.consumer-inspection',
      ]),
    );

    configureModulePageEnhancements([]);
    expect(
      resolveModulePageEnhancement('platform.module')?.detail?.actions?.map((action) => action.key),
    ).toEqual([
      'module-actions-workspace',
      'module-metadata-orchestration-workspace',
      'module-manual-action-binding-workspace',
      'module-ui-orchestration-workspace',
      'module-openapi-page',
    ]);
  });

  it('accepts a business record-view presentation that keeps the platform view lifecycle', () => {
    const DetailDrawer = { template: '<section>业务详情</section>' };
    const registry = createModulePageEnhancementRegistry([
      {
        id: 'customer-detail-drawer',
        target: { moduleAlias: 'crm.customer' },
        recordView: { drawer: { component: DetailDrawer, width: 720, loadRecord: false } },
      },
    ]);

    expect(registry.resolve('crm.customer')?.recordView?.drawer).toEqual({
      component: DetailDrawer,
      width: 720,
      loadRecord: false,
    });
  });

  it('keeps IAM session operations in the list expansion and password behavior in detail actions', () => {
    const registry = createModulePageEnhancementRegistry([userModulePageEnhancement]);

    expect(registry.resolve('iam.user')).toMatchObject({
      target: { moduleAlias: 'iam.user' },
      list: { columns: [{ key: 'onlineStatus' }], rowExpansion: { key: 'iam-user-sessions' } },
      form: { fieldPolicies: [{ fieldName: 'password' }] },
      detail: {
        actions: [{ key: 'iam-user-password' }],
      },
    });

    const emptyListScope = userModulePageEnhancement.navigator?.emptyListScope;
    expect(
      emptyListScope?.({ currentUser: { system: true } as never, selectedNavigatorRecords: {} }),
    ).toEqual([{ fieldName: 'tenantId', operator: 'NULL', values: [] }]);
    expect(
      emptyListScope?.({
        currentUser: { system: true } as never,
        selectedNavigatorRecords: { tenant: { id: 'demo' } },
      }),
    ).toBeUndefined();
    expect(
      emptyListScope?.({ currentUser: { system: false } as never, selectedNavigatorRecords: {} }),
    ).toBeUndefined();
  });

  it('accepts one source-owned card assistant and rejects competing owners for that region', () => {
    const Assistant = { template: '<aside>assistant</aside>' };
    const registry = createModulePageEnhancementRegistry([
      {
        id: 'customer-card-assistant',
        target: { moduleAlias: 'crm.customer' },
        card: { assistant: { component: Assistant, placement: { boundary: 'inside', position: 'bottom' } } },
      },
    ]);
    expect(registry.resolve('crm.customer')?.card?.assistant.component).toBe(Assistant);

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'first-card-assistant',
          target: { moduleAlias: 'crm.customer' },
          card: {
            assistant: { component: Assistant, placement: { boundary: 'inside', position: 'bottom' } },
          },
        },
        {
          id: 'second-card-assistant',
          target: { moduleAlias: 'crm.customer' },
          card: {
            assistant: { component: Assistant, placement: { boundary: 'inside', position: 'bottom' } },
          },
        },
      ]),
    ).toThrow('重复声明记录卡片辅助区域');
  });

  it('reserves one controlled navigator extension surface for a standard page', () => {
    const ScopeTree = { template: '<aside>scope tree</aside>' };
    const registry = createModulePageEnhancementRegistry([
      {
        id: 'customer-scope-tree',
        target: { moduleAlias: 'crm.customer' },
        navigator: { extension: { key: 'customer-scope-tree', component: ScopeTree } },
      },
    ]);

    expect(registry.resolve('crm.customer')?.navigator?.extension).toEqual({
      key: 'customer-scope-tree',
      component: ScopeTree,
    });

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'first-scope-tree',
          target: { moduleAlias: 'crm.customer' },
          navigator: { extension: { key: 'first', component: ScopeTree } },
        },
        {
          id: 'second-scope-tree',
          target: { moduleAlias: 'crm.customer' },
          navigator: { extension: { key: 'second', component: ScopeTree } },
        },
      ]),
    ).toThrow('重复声明导航扩展区域');

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'blank-scope-tree',
          target: { moduleAlias: 'crm.customer' },
          navigator: { extension: { key: ' ', component: ScopeTree } },
        },
      ]),
    ).toThrow('导航扩展 key 不能为空');
  });

  it('detaches and deeply freezes card record snapshots', () => {
    const source = { title: '客户', children: [{ title: '联系人' }] };
    const snapshot = createReadonlyCardRecordSnapshot(source);

    expect(snapshot).not.toBe(source);
    expect(Object.isFrozen(snapshot)).toBe(true);
    expect(Object.isFrozen((snapshot.children as object[])[0])).toBe(true);
    source.children[0].title = '已修改';
    expect((snapshot.children as Array<{ title: string }>)[0].title).toBe('联系人');
    expect(Reflect.set(snapshot, 'title', '越界修改')).toBe(false);
  });

  it('reserves one application-owned row expansion while retaining normal list composition', () => {
    const SessionExpansion = { template: '<section>sessions</section>' };
    const registry = createModulePageEnhancementRegistry([
      {
        id: 'customer-session-expansion',
        target: { moduleAlias: 'crm.customer' },
        list: { rowExpansion: { key: 'customer-sessions', component: SessionExpansion } },
      },
    ]);

    expect(registry.resolve('crm.customer')?.list?.rowExpansion).toEqual({
      key: 'customer-sessions',
      component: SessionExpansion,
    });

    expect(() =>
      createModulePageEnhancementRegistry([
        {
          id: 'first-row-expansion',
          target: { moduleAlias: 'crm.customer' },
          list: { rowExpansion: { key: 'first', component: SessionExpansion } },
        },
        {
          id: 'second-row-expansion',
          target: { moduleAlias: 'crm.customer' },
          list: { rowExpansion: { key: 'second', component: SessionExpansion } },
        },
      ]),
    ).toThrow('重复声明列表行展开区域');
  });

  it('registers a stable business workspace view and creates a deduplicated tab descriptor', () => {
    const conversationView = {
      type: 'crm.customer.conversation',
      moduleAlias: 'crm.customer',
      component: { template: '<section>conversation</section>' },
      titleOf: (input: { customerId: string }) => `客户对话 ${input.customerId}`,
      parse: (query: Record<string, string | string[] | undefined>) =>
        typeof query.customerId === 'string' ? { customerId: query.customerId } : undefined,
    };
    createModulePageEnhancementRegistry([
      {
        id: 'customer-workspace',
        target: { moduleAlias: 'crm.customer' },
        workspaceViews: [conversationView],
      },
    ]);

    expect(
      createWorkspaceViewDescriptor(
        { ...conversationView, route: '/_workspace/crm.customer.conversation', presentations: ['tab'] },
        { customerId: 'customer-1' },
      ),
    ).toMatchObject({
      pageType: 'business-route',
      hostType: 'business-route-host',
      title: '客户对话 customer-1',
      target: {
        route: '/_workspace/crm.customer.conversation',
        moduleAlias: 'crm.customer',
        query: {
          workspaceView: 'crm.customer.conversation',
          workspacePresentation: 'tab',
          customerId: 'customer-1',
        },
      },
      tabPolicy: { identity: 'by-params' },
    });
  });
});

function workspaceView(type: string, moduleAlias = 'crm.customer') {
  return {
    type,
    moduleAlias,
    component: { template: '<section>workspace</section>' },
    titleOf: () => '工作台',
    parse: () => ({}),
  };
}
