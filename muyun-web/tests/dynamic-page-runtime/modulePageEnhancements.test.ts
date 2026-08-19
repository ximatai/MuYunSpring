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

describe('module page enhancements', () => {
  afterEach(() => {
    configureModulePageEnhancements([]);
    configureModulePageEnhancementContributions('platform-admin-runtime', [
      platformModulePageEnhancement,
      passwordPolicyPageEnhancement,
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
    expect(registry.resolve('crm.customer', 'other_list')).toBe(fallback);
    expect(registry.resolve('crm.order')).toBeUndefined();
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
    ).toEqual(['module-actions-workspace', 'module-metadata-orchestration-workspace', 'module-openapi-page']);
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
