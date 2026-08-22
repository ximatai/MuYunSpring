import { afterEach, describe, expect, it } from 'vitest';
import { shallowMount } from '@vue/test-utils';
import BusinessRouteHost from '@/platform-workbench/hosts/BusinessRouteHost.vue';
import { configureModulePageEnhancements } from '@/dynamic-page-runtime/modulePageEnhancements.ts';
import { ModuleContextProvider } from '@/web-core/module/moduleContext.ts';
import { pageDescriptorFromUrl } from '@/platform-workbench/menuNavigation.ts';
import { configureWorkspaceViewContributions } from '@/platform-workbench/workspaceViews.ts';

describe('BusinessRouteHost', () => {
  afterEach(() => {
    configureModulePageEnhancements([]);
    configureWorkspaceViewContributions('module-page-enhancements', []);
  });

  it('renders a registered stable module workspace view inside the module context', () => {
    configureModulePageEnhancements([
      {
        id: 'customer-conversation',
        target: { moduleAlias: 'crm.customer' },
        workspaceViews: [
          {
            type: 'crm.customer.conversation',
            moduleAlias: 'crm.customer',
            component: { name: 'CustomerConversationView', template: '<section>对话</section>' },
            titleOf: (input: { customerId: string }) => `客户对话 ${input.customerId}`,
            parse: (query) =>
              typeof query.customerId === 'string' ? { customerId: query.customerId } : undefined,
          },
        ],
      },
    ]);
    configureWorkspaceViewContributions('module-page-enhancements', [
      {
        type: 'crm.customer.conversation',
        route: '/_workspace/crm.customer.conversation',
        moduleAlias: 'crm.customer',
        component: { name: 'CustomerConversationView', template: '<section>对话</section>' },
        presentations: ['tab'],
        titleOf: (input: { customerId: string }) => `客户对话 ${input.customerId}`,
        parse: (query) =>
          typeof query.customerId === 'string' ? { customerId: query.customerId } : undefined,
      },
    ]);

    const wrapper = shallowMount(BusinessRouteHost, {
      props: {
        descriptor: {
          pageType: 'business-route',
          openMode: 'workbench-route',
          hostType: 'business-route-host',
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
        },
      },
    });

    expect(wrapper.findComponent(ModuleContextProvider).props('moduleAlias')).toBe('crm.customer');
    expect(wrapper.find('.page-host').exists()).toBe(false);
  });

  it('restores the generated workspace URL as a business route', () => {
    expect(
      pageDescriptorFromUrl(
        '/_platform/workspace/crm.customer.conversation?workspaceView=crm.customer.conversation&workspacePresentation=tab&customerId=customer-1',
      ),
    ).toMatchObject({ pageType: 'business-route', hostType: 'business-route-host' });
  });
});
