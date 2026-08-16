/* eslint-disable vue/one-component-per-file -- local contract-test harness */

import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import WorkspaceViewOutlet from '@/platform-workbench/WorkspaceViewOutlet.vue';
import { provideWorkbenchNavigation } from '@/platform-workbench/workbenchNavigation.ts';
import {
  configureWorkspaceViewContributions,
  createWorkspaceViewDescriptor,
} from '@/platform-workbench/workspaceViews.ts';
import { tabKeyOf } from '@/platform-workbench/menuNavigation.ts';

it('routes a workspace close intent through the owning Workbench navigation', async () => {
  const closePage = vi.fn();
  const DetailWorkspace = defineComponent({
    emits: ['close-workspace'],
    setup(_, { emit }) {
      return () => h('button', { onClick: () => emit('close-workspace') }, '删除并关闭');
    },
  });
  const definition = {
    type: 'crm.customer.detail',
    route: '/_workspace/crm.customer.detail',
    moduleAlias: 'crm.customer',
    component: DetailWorkspace,
    presentations: ['tab'] as const,
    titleOf: ({ recordId }: { recordId: string }) => `客户 ${recordId}`,
    parse: (query: Record<string, unknown>) =>
      typeof query.recordId === 'string' ? { recordId: query.recordId } : undefined,
  };
  configureWorkspaceViewContributions('workspace-close-test', [definition]);
  const descriptor = createWorkspaceViewDescriptor(definition, { recordId: 'customer-1' });
  const Harness = defineComponent({
    setup() {
      provideWorkbenchNavigation({
        openPage: () => ({ created: true }),
        replacePage: vi.fn(),
        closePage,
      });
      return () => h(WorkspaceViewOutlet, { descriptor });
    },
  });

  try {
    const wrapper = mount(Harness);
    await wrapper.get('button').trigger('click');
    expect(closePage).toHaveBeenCalledWith(tabKeyOf(descriptor));
  } finally {
    configureWorkspaceViewContributions('workspace-close-test', []);
  }
});
