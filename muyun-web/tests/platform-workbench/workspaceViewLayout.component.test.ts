/* eslint-disable vue/one-component-per-file -- local layout contract harness */
import { mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it } from 'vitest';
import type { BusinessRoutePageDescriptor, PageLayoutMode } from '@muyun/web-contracts';
import { providePageLayout, usePageLayout } from '@/platform-components/pageLayoutContext';
import WorkspaceViewOutlet from '@/platform-workbench/WorkspaceViewOutlet.vue';
import {
  configureWorkspaceViewContributions,
  createWorkspaceViewDescriptor,
} from '@/platform-workbench/workspaceViews';

it.each(['tab', 'drawer'] as const)(
  'resolves reactive %s layouts without replacing the view draft',
  async (presentation) => {
    const Probe = defineComponent({
      setup() {
        const layout = usePageLayout();
        const draft = ref('');
        return () =>
          h('section', [
            h('output', layout.value),
            h('input', {
              value: draft.value,
              onInput: (event: Event) => {
                draft.value = (event.target as HTMLInputElement).value;
              },
            }),
          ]);
      },
    });
    const definition = {
      type: 'test.layout',
      route: '/test/layout',
      moduleAlias: 'test.layout',
      component: Probe,
      presentations: ['tab', 'drawer'] as const,
      layout: 'workspace' as PageLayoutMode | undefined,
      titleOf: () => '布局',
      parse: () => ({}),
    };
    configureWorkspaceViewContributions('layout-contract', [definition]);
    const descriptor = ref<BusinessRoutePageDescriptor>(
      createWorkspaceViewDescriptor(definition, {}, presentation),
    );
    const inherited = ref<PageLayoutMode>('flow');
    const wrapper = mount(
      defineComponent({
        setup() {
          providePageLayout(inherited);
          return () => h(WorkspaceViewOutlet, { descriptor: descriptor.value });
        },
      }),
    );
    try {
      const body = wrapper.findComponent(Probe).vm;
      await wrapper.get('input').setValue('未保存');
      expect(wrapper.get('output').text()).toBe('workspace');
      descriptor.value = { ...descriptor.value, layout: 'flow' };
      await wrapper.vm.$nextTick();
      expect(wrapper.get('output').text()).toBe('flow');
      // A URL-restored descriptor may omit layout; the view declaration supplies it.
      descriptor.value = { ...descriptor.value, layout: undefined };
      await wrapper.vm.$nextTick();
      expect(wrapper.get('output').text()).toBe('workspace');
      definition.layout = undefined;
      descriptor.value = { ...descriptor.value };
      await wrapper.vm.$nextTick();
      expect(wrapper.get('output').text()).toBe('flow');
      inherited.value = 'workspace';
      await wrapper.vm.$nextTick();
      expect(wrapper.get('output').text()).toBe('workspace');
      expect(wrapper.findComponent(Probe).vm).toBe(body);
      expect(wrapper.get('input').element.value).toBe('未保存');
    } finally {
      wrapper.unmount();
      configureWorkspaceViewContributions('layout-contract', []);
    }
  },
);
