import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import DynamicModuleWorkspaceDetailView from '@/dynamic-page-runtime/DynamicModuleWorkspaceDetailView.vue';
import { provideModulePageUnsavedStateHost } from '@/dynamic-page-runtime/modulePageUnsavedState';
import {
  configureModuleContext,
  createModuleContext,
  ModuleContextProvider,
  type HttpClient,
} from '@/web-core';

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

it('refreshes ordinary record rights after workflow changes for an approval record without enable', async () => {
  let locked = false;
  const request = vi.fn(async (options: { path: string }) => {
    if (options.path.endsWith('/context'))
      return {
        moduleAlias: 'education.purchase_request',
        abilities: ['crud', 'approval'],
        capabilities: [],
        actions: [
          { actionCode: 'view', authorized: true },
          { actionCode: 'update', authorized: true },
        ],
        uiDescriptor: { page: { detail: {} } },
      };
    if (options.path.endsWith('/actions/r'))
      return {
        recordId: 'r',
        actions: [{ actionCode: 'update', available: !locked }],
      };
    if (options.path.endsWith('/view/r')) return { id: 'r', version: locked ? 2 : 1, title: '采购申请' };
    throw new Error(`unexpected request: ${options.path}`);
  });
  const context = createModuleContext({
    http: { request } as HttpClient,
    moduleAlias: 'education.purchase_request',
  });
  await context.runtime.ready;
  await context.recordActions('r');
  const Harness = defineComponent({
    setup: () => () =>
      h(ModuleContextProvider, { context }, () => h(DynamicModuleWorkspaceDetailView, { recordId: 'r' })),
  });
  const panel = defineComponent({
    name: 'RecordDetailPanel',
    setup(_, { slots }) {
      return () => h('section', [slots.actions?.(), slots.default?.()]);
    },
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        RecordDetailPanel: panel,
        WorkflowRecordPanel: true,
        RecordDetailFields: true,
        RecordFormSurface: true,
        RecordModeDrawer: true,
        RecordMetaSection: true,
        ModuleReferenceRecordDetailBrowser: true,
        ModuleRecordDetailActions: true,
      },
    },
  });
  await flushPromises();
  expect(context.action('update', 'r')?.available).toBe(true);
  const workflow = wrapper.findComponent({ name: 'WorkflowRecordPanel' });
  locked = true;
  workflow.vm.$emit('changed');
  await flushPromises();
  expect(context.action('update', 'r')?.available).toBe(false);
  locked = false;
  wrapper.findComponent({ name: 'WorkflowRecordPanel' }).vm.$emit('changed');
  await flushPromises();
  expect(context.action('update', 'r')?.available).toBe(true);
});

it('reloads sibling workspace details and record rights from committed facts while protecting workflow drafts', async () => {
  const { appDataChangeDispatcher } = await import('@/platform-admin-runtime/realtime');
  let version = 1;
  const request = vi.fn(async ({ path }: { path: string }) => {
    if (path.endsWith('/context'))
      return {
        moduleAlias: 'education.purchase_request',
        abilities: ['crud', 'approval'],
        capabilities: [],
        actions: [
          { actionCode: 'view', authorized: true },
          { actionCode: 'update', authorized: true },
        ],
        uiDescriptor: { page: { detail: {} } },
      };
    if (path.endsWith('/view/r')) return { id: 'r', version, title: `采购版本 ${version}` };
    if (path.endsWith('/actions/r'))
      return { recordId: 'r', actions: [{ actionCode: 'update', available: version === 1 }] };
    throw new Error(`unexpected request: ${path}`);
  });
  const contexts = [1, 2].map(() =>
    createModuleContext({ http: { request } as HttpClient, moduleAlias: 'education.purchase_request' }),
  );
  await Promise.all(
    contexts.map(async (context) => {
      await context.runtime.ready;
      await context.recordActions('r');
    }),
  );
  const panel = defineComponent({
    name: 'RecordDetailPanel',
    props: ['title'],
    setup(props, { slots }) {
      return () => h('section', [h('h2', props.title), slots.actions?.(), slots.default?.()]);
    },
  });
  const wrappers = contexts.map((context) =>
    mount(
      defineComponent({
        setup: () => () =>
          h(ModuleContextProvider, { context }, () => h(DynamicModuleWorkspaceDetailView, { recordId: 'r' })),
      }),
      {
        global: {
          stubs: {
            RecordDetailPanel: panel,
            WorkflowRecordPanel: true,
            RecordDetailFields: true,
            RecordFormSurface: true,
            RecordModeDrawer: true,
            RecordMetaSection: true,
            ModuleReferenceRecordDetailBrowser: true,
            ModuleRecordDetailActions: true,
          },
        },
      },
    ),
  );
  const settle = async () => {
    await flushPromises();
    await new Promise((resolve) => setTimeout(resolve, 5));
    await flushPromises();
  };
  await settle();
  const workflow = wrappers[0]!.findComponent({ name: 'WorkflowRecordPanel' });
  workflow.vm.$emit('interaction-change', { editing: true, busy: false, dirty: true });
  await flushPromises();
  version = 2;
  const event = {
    changeSetId: 'workspace-task-completed',
    changes: [{ type: 'record-updated', moduleAlias: 'education.purchase_request', recordId: 'r' }],
  };
  const readsBefore = request.mock.calls.filter(([options]) => options.path.endsWith('/view/r')).length;
  await appDataChangeDispatcher.dispatch(event);
  await appDataChangeDispatcher.dispatch(event);
  await settle();
  expect(request.mock.calls.filter(([options]) => options.path.endsWith('/view/r')).length).toBe(
    readsBefore + 1,
  );
  expect(wrappers[0]!.text()).toContain('采购版本 1');
  expect(wrappers[1]!.text()).toContain('采购版本 2');
  expect(contexts[1]!.action('update', 'r')?.available).toBe(false);
  workflow.vm.$emit('interaction-change', { editing: false, busy: false, dirty: false });
  await settle();
  expect(wrappers[0]!.text()).toContain('采购版本 2');
  expect(contexts[0]!.action('update', 'r')?.available).toBe(false);
  wrappers.forEach((wrapper) => wrapper.unmount());
});
