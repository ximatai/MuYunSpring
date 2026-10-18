import { mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it } from 'vitest';
import { useWorkspaceViewUnsavedState } from '@muyun/web-core';
import type { ModulePageDescriptor } from '@muyun/web-contracts';
import WorkbenchOutlet from '@/platform-workbench/WorkbenchOutlet.vue';
import { tabKeyOf } from '@/platform-workbench/menuNavigation';
import {
  workspaceViewBusyStateSources,
  workspaceViewUnsavedStateSources,
} from '@/platform-workbench/workspaceViewUnsavedState';

it('protects local editor drafts and writes inside ordinary module tabs and releases only its own signals', () => {
  const dirty = ref(true),
    busy = ref(false);
  const Panel = defineComponent({
    setup() {
      useWorkspaceViewUnsavedState(
        'Translation',
        () => dirty.value,
        () => busy.value,
      );
      return () => h('div', 'Ordinary module');
    },
  });
  const descriptor: ModulePageDescriptor = {
    pageType: 'dynamic-module',
    openMode: 'dynamic-runner',
    hostType: 'module-page-host',
    title: 'Products',
    tabPolicy: { identity: 'by-menu' },
    menuId: 'products',
    target: { moduleAlias: 'demo.product' },
  };
  const wrapper = mount(WorkbenchOutlet, {
    props: { descriptor },
    global: { stubs: { ModulePageHost: Panel } },
  });
  const key = tabKeyOf(descriptor);
  expect(workspaceViewUnsavedStateSources(key)).toEqual(['Translation']);
  busy.value = true;
  expect(workspaceViewBusyStateSources(key)).toEqual(['Translation']);
  dirty.value = false;
  expect(workspaceViewUnsavedStateSources(key)).toEqual([]);
  wrapper.unmount();
  expect(workspaceViewBusyStateSources(key)).toEqual([]);
});
