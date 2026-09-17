import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import DynamicModuleWorkspaceDetailView from '@/dynamic-page-runtime/DynamicModuleWorkspaceDetailView.vue';
import { provideModulePageUnsavedStateHost } from '@/dynamic-page-runtime/modulePageUnsavedState';
import { configureModuleContext, ModuleContextProvider, type HttpClient } from '@/web-core';

const ReferenceDetailBrowserStub = defineComponent({
  name: 'ModuleReferenceRecordDetailBrowser',
  emits: ['interaction-state-change'],
  template: '<section />',
});

it('reports nested reference record edits and mutations through the workspace state host', async () => {
  let reportedDirty: (() => boolean) | undefined;
  let reportedBusy: (() => boolean) | undefined;
  const unregister = vi.fn();
  const registerUnsavedState = vi.fn((_: string, isDirty: () => boolean, isBusy?: () => boolean) => {
    reportedDirty = isDirty;
    reportedBusy = isBusy;
    return unregister;
  });
  const http: HttpClient = {
    async request(options) {
      if (options.path === '/platform.module/crm.customer/context') {
        return {
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [{ actionCode: 'view', authorized: true }],
          uiDescriptor: { moduleAlias: 'crm.customer', page: { detail: {} } },
        } as never;
      }
      if (options.path === '/crm.customer/view/customer-1') {
        return { id: 'customer-1', version: 1, title: '客户 A' } as never;
      }
      throw new Error(`unexpected request: ${options.path}`);
    },
  };
  configureModuleContext({ httpFactory: () => http });
  const Harness = defineComponent({
    setup() {
      provideModulePageUnsavedStateHost({ registerUnsavedState });
      return () =>
        h(ModuleContextProvider, { moduleAlias: 'crm.customer' }, () =>
          h(DynamicModuleWorkspaceDetailView, { recordId: 'customer-1' }),
        );
    },
  });

  const wrapper = mount(Harness, {
    global: {
      stubs: {
        ModuleReferenceRecordDetailBrowser: ReferenceDetailBrowserStub,
        RecordDetailFields: true,
        RecordDetailPanel: true,
        RecordFormSurface: true,
        RecordModeDrawer: true,
        RecordPanelState: true,
        RecordStatusSwitch: true,
        RecordMetaSection: true,
        RecordDetailExtensionSection: true,
        DrawerTitleActions: true,
        ModuleRecordDetailActions: true,
      },
    },
  });
  await flushPromises();

  expect(registerUnsavedState).toHaveBeenCalledWith('记录详情', expect.any(Function), expect.any(Function));
  expect(reportedDirty?.()).toBe(false);
  expect(reportedBusy?.()).toBe(false);

  const referenceDetail = wrapper.findComponent(ReferenceDetailBrowserStub);
  referenceDetail.vm.$emit('interaction-state-change', { editing: true, busy: false, dirty: true });
  await flushPromises();

  expect(reportedDirty?.()).toBe(true);
  expect(reportedBusy?.()).toBe(false);

  referenceDetail.vm.$emit('interaction-state-change', { editing: true, busy: true, dirty: true });
  await flushPromises();

  expect(reportedBusy?.()).toBe(true);

  referenceDetail.vm.$emit('interaction-state-change', { editing: false, busy: false, dirty: false });
  await flushPromises();

  expect(reportedDirty?.()).toBe(false);
  expect(reportedBusy?.()).toBe(false);
  wrapper.unmount();
  expect(unregister).toHaveBeenCalledOnce();
});
