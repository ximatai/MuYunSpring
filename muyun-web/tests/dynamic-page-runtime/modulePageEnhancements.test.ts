import { describe, expect, it } from 'vitest';
import { createModulePageEnhancementRegistry } from '@/dynamic-page-runtime/modulePageEnhancements.ts';
import { createWorkspaceViewDescriptor } from '@/platform-workbench/workspaceViews.ts';

describe('module page enhancements', () => {
  it('prefers a view-specific enhancement and falls back to the module enhancement', () => {
    const fallback = { id: 'customer-default', target: { moduleAlias: 'crm.customer' } };
    const viewSpecific = {
      id: 'customer-list',
      target: { moduleAlias: 'crm.customer', viewCode: 'default_list' },
    };
    const registry = createModulePageEnhancementRegistry([fallback, viewSpecific]);

    expect(registry.resolve('crm.customer', 'default_list')).toBe(viewSpecific);
    expect(registry.resolve('crm.customer', 'other_list')).toBe(fallback);
    expect(registry.resolve('crm.order')).toBeUndefined();
  });

  it('rejects duplicate targets and contribution keys during application startup', () => {
    expect(() =>
      createModulePageEnhancementRegistry([
        { id: 'first', target: { moduleAlias: 'crm.customer' } },
        { id: 'second', target: { moduleAlias: 'crm.customer' } },
      ]),
    ).toThrow('模块页面增强目标重复');

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
    ).toThrow('同一列表区域存在重复的贡献 key');

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
  });

  it('accepts a business detail drawer that keeps the platform view lifecycle', () => {
    const DetailDrawer = { template: '<section>业务详情</section>' };
    const registry = createModulePageEnhancementRegistry([
      {
        id: 'customer-detail-drawer',
        target: { moduleAlias: 'crm.customer' },
        detail: { drawer: { component: DetailDrawer, width: 720, loadRecord: false } },
      },
    ]);

    expect(registry.resolve('crm.customer')?.detail?.drawer).toEqual({
      component: DetailDrawer,
      width: 720,
      loadRecord: false,
    });
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
