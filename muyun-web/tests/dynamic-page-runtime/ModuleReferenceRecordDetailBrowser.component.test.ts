import { sidePanelHostKey } from '@/vue-ui-antdv/components/sidePanelHost';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as platformComponents from '@muyun/platform-components';
import ModuleReferenceRecordDetailBrowser from '@/dynamic-page-runtime/ModuleReferenceRecordDetailBrowser.vue';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import { configureModulePageEnhancements } from '@/dynamic-page-runtime/modulePageEnhancements';
import { AppError, configureModuleContext, type HttpClient } from '@/web-core';
import RecordDetailLayout from '@/platform-components/RecordDetailLayout.vue';
import {
  createReferenceRecordDetailBrowser,
  RecordModeDrawer,
  ReferenceRecordDetailBrowser,
} from '@/platform-components';

const TargetLink = defineComponent({
  props: { browser: { type: Object, required: true } },
  template: "<button @click=\"browser.open('purchase.supplier', 'supplier-1')\">打开供应商</button>",
});

const DrawerLayoutHarness = defineComponent({
  name: 'DrawerLayoutHarness',
  components: { RecordDetailLayout },
  props: { title: { type: String, required: true }, open: Boolean },
  emits: ['close'],
  template: `
    <RecordDetailLayout v-if="open" :title="title" surface="drawer" scrollable-content>
      <template #status><slot name="status" /></template>
      <template #actions><button data-testid="close" @click="$emit('close')">关闭</button></template>
      <slot />
      <template #operation><slot name="operation" /></template>
    </RecordDetailLayout>`,
});

function flush() {
  return flushPromises().then(() => Promise.resolve());
}

describe('ModuleReferenceRecordDetailBrowser', () => {
  afterEach(() => {
    configureModulePageEnhancements([]);
    vi.restoreAllMocks();
  });

  it('uses one standard drawer layout for a loaded full surface, with status in the header and actions in the footer', async () => {
    const http: HttpClient = {
      async request(options) {
        if (options.path === '/platform.module/purchase.supplier/view-context') {
          return { moduleAlias: 'purchase.supplier', actions: [], capabilities: [] } as never;
        }
        return { id: 'supplier-1', title: '供应商 A' } as never;
      },
    };
    const browser = createReferenceRecordDetailBrowser(http);
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { browser, inlineAnchor: true },
      slots: {
        default: () => h(TargetLink, { browser }),
        'full-surface': () =>
          h(
            RecordModeDrawer,
            { open: true, title: '供应商 A', mode: 'view', renderMode: 'inline' },
            {
              status: () => h('span', { 'data-testid': 'status' }, '已启用'),
              view: () => h('p', '供应商详情'),
              operation: () => h('button', { 'data-testid': 'edit' }, '编辑'),
            },
          ),
      },
      global: {
        provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
        stubs: { RecordDetailDrawer: DrawerLayoutHarness },
      },
    });

    await wrapper.get('button').trigger('click');
    await flush();

    expect(wrapper.findAllComponents(RecordModeDrawer)).toHaveLength(1);
    expect(wrapper.findAllComponents(RecordDetailLayout)).toHaveLength(1);
    const layout = wrapper.findComponent(RecordDetailLayout);
    expect(layout.find('.record-detail-layout-title-group').text()).toContain('已启用');
    expect(layout.find('.record-detail-layout-operation').text()).toContain('编辑');
    wrapper.unmount();
  });

  it('runs the real Host in VIEW scope without target menu, query, or navigator initialization', async () => {
    const requests: Array<{ path: string; method?: string; body?: unknown }> = [];
    let viewCount = 0;
    const delayedView: { response?: Promise<{ id: string; version: number; title: string }> } = {};
    const http: HttpClient = {
      async request(options) {
        requests.push({ path: options.path, method: options.method, body: options.body });
        if (options.path === '/platform.module/purchase.supplier/view-context') {
          return {
            moduleAlias: 'purchase.supplier',
            capabilities: ['ENABLE'],
            actions: [
              { actionCode: 'view', authorized: true },
              { actionCode: 'update', authorized: true },
              { actionCode: 'status', actionLevel: 'RECORD', category: 'CUSTOM', authorized: true },
            ],
            uiDescriptor: {
              schemaVersion: '1',
              moduleAlias: 'purchase.supplier',
              page: {
                template: 'LIST_DETAIL_CARD',
                navigator: {
                  levels: [
                    { key: 'tenant', kind: 'MICRO_LIST', sourceModuleAlias: 'iam.tenant', title: '租户' },
                  ],
                },
                detail: {
                  editor: {
                    viewCode: 'default_form',
                    viewKind: 'FORM',
                    fields: [
                      { fieldRef: { fieldName: 'title' }, label: '名称', valueType: 'STRING' },
                      {
                        fieldRef: { fieldName: 'customerId' },
                        label: '客户',
                        valueType: 'STRING',
                        reference: {
                          targetModuleAlias: 'crm.customer',
                          cardinality: 'ONE',
                          displayProjections: [{ targetField: 'title', outputField: 'customerTitle' }],
                        },
                      },
                    ],
                  },
                },
                actions: [
                  {
                    actionCode: 'status',
                    anchor: 'DETAIL',
                    operation: 'INVOKE',
                    invocation: {
                      method: 'POST',
                      path: '/purchase.supplier/status/{recordId}',
                      input: 'NONE',
                    },
                  },
                ],
              },
            },
          } as never;
        }
        if (options.path === '/purchase.supplier/actions/supplier-1') {
          return {
            recordId: 'supplier-1',
            actions: [
              { actionCode: 'view', available: true },
              { actionCode: 'update', available: true },
              { actionCode: 'status', available: true },
            ],
          } as never;
        }
        if (options.path === '/purchase.supplier/view/supplier-1') {
          viewCount += 1;
          if (delayedView.response) return delayedView.response as never;
          return {
            id: 'supplier-1',
            enabled: true,
            version: viewCount > 2 ? 2 : 1,
            title: viewCount > 2 ? '已保存供应商' : '原供应商',
            customerId: 'customer-1',
            customerTitle: '只读客户投影',
          } as never;
        }
        if (options.path === '/purchase.supplier/update/supplier-1') {
          return { id: 'supplier-1', version: 2, title: '写入值' } as never;
        }
        throw new Error(`unexpected request: ${options.path}`);
      },
    };
    const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
      props: { http, inlineAnchor: true },
      global: {
        provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
        stubs: {
          RecordDetailDrawer: DrawerLayoutHarness,
          RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
          RecordPanelState: { template: '<section />' },
          RecordDetailFields: { template: '<section />' },
          RecordFormFields: { template: '<section />' },
          RecordMetaSection: { template: '<section />' },
          ModulePageDetailRelations: { template: '<section />' },
          RecordStatusSwitch: { template: '<section data-testid="record-status" />' },
          RecordActionBar: { template: '<section />' },
        },
      },
    });

    const browser = wrapper.findComponent(ReferenceRecordDetailBrowser).vm.browser;
    await browser.open('purchase.supplier', 'supplier-1');
    await flush();
    await flush();

    const host = wrapper.findComponent({ name: 'ModulePageHostRuntime' });
    expect(host.exists()).toBe(true);
    expect((host.props('session') as { props: { recordOnly?: unknown } }).props.recordOnly).toMatchObject({
      renderMode: 'inline',
      scope: 'tab',
    });
    expect(wrapper.find('.module-workspace').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordQueryListPanel' }).exists()).toBe(false);
    expect(wrapper.find('[title="固定到右侧展示"]').exists()).toBe(false);
    expect(requests.map((request) => request.path)).not.toContain('/purchase.supplier/query');
    expect(requests.map((request) => request.path)).not.toContain(
      '/platform.module/iam.tenant/reference-context',
    );
    expect(requests.map((request) => request.path)).not.toContain(
      '/platform.module/purchase.supplier/context',
    );

    await wrapper.setProps({ inlineAnchor: false });
    await flush();
    expect(
      (
        wrapper.findComponent({ name: 'ModulePageHostRuntime' }).props('session') as {
          props: { recordOnly?: unknown };
        }
      ).props.recordOnly,
    ).toMatchObject({ renderMode: 'portal', scope: 'tab' });
    expect(host.findComponent({ name: 'ModuleReferenceRecordDetailBrowser' }).props()).toMatchObject({
      renderMode: 'portal',
      scope: 'tab',
    });
    await wrapper.setProps({ inlineAnchor: true });
    await flush();

    const loadedContent = wrapper.findComponent({ name: 'ModulePageRecordContent' });
    expect(loadedContent.props('formFields').get('customerId')?.reference?.displayProjections).toEqual([
      { targetField: 'title', outputField: 'customerTitle' },
    ]);
    expect(wrapper.findAllComponents(RecordDetailLayout)).toHaveLength(1);
    const layout = wrapper.findComponent(RecordDetailLayout);
    expect(layout.find('.record-detail-layout-title-group [data-testid="record-status"]').exists()).toBe(
      true,
    );
    const actions = layout
      .find('.record-detail-layout-operation')
      .findComponent({ name: 'ModuleRecordDetailActions' });
    expect(actions.exists()).toBe(true);
    actions.vm.$emit('edit');
    await flush();
    const content = wrapper.findComponent({ name: 'ModulePageRecordContent' });
    content.vm.$emit('update:field', 'title', '未保存草稿');
    await flush();
    const confirm = vi.spyOn(platformComponents, 'confirmAction').mockResolvedValue(false);
    await wrapper.vm.openRecord('purchase.supplier', 'supplier-2');
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(browser.active.value?.recordId).toBe('supplier-1');
    expect(content.props('record')).toMatchObject({ title: '未保存草稿' });
    const editSession = host.props('session') as { handleReferenceRecordChange(value: unknown): void };
    editSession.handleReferenceRecordChange({
      type: 'saved',
      targetModuleAlias: 'crm.customer',
      recordId: 'customer-1',
      record: { id: 'customer-1', title: '客户新名称' },
    });
    await flush();
    await flush();
    expect(content.props('record')).toMatchObject({ title: '未保存草稿' });
    wrapper.findComponent({ name: 'ModuleRecordDetailActions' }).vm.$emit('save');
    await flush();
    await flush();

    const update = requests.find((request) => request.path === '/purchase.supplier/update/supplier-1');
    expect(update?.body).toMatchObject({ id: 'supplier-1', version: 1 });
    expect(update?.body).not.toHaveProperty('customerTitle');
    expect(browser.active.value?.record).toMatchObject({
      id: 'supplier-1',
      version: 2,
      title: '已保存供应商',
    });
    expect(wrapper.emitted('record-change')?.at(-1)?.[0]).toMatchObject({
      type: 'saved',
      recordId: 'supplier-1',
    });

    const changesBeforeReload = wrapper.emitted('record-change')?.length ?? 0;
    const session = host.props('session') as {
      detailSectionContext(record: { id: string }): { reload(): void };
      recordViewContext(record: { id: string }): { close(): void };
    };
    session.detailSectionContext({ id: 'supplier-1' }).reload();
    await flush();
    await flush();
    expect(wrapper.emitted('record-change')).toHaveLength(changesBeforeReload + 1);
    expect(browser.active.value?.recordId).toBe('supplier-1');

    // Both response orders must retain the latest requested snapshot.
    for (const latestFirst of [false, true]) {
      let finishOld!: (record: { id: string; version: number; title: string }) => void;
      let finishNew!: (record: { id: string; version: number; title: string }) => void;
      delayedView.response = new Promise((resolve) => {
        finishOld = resolve;
      });
      session.detailSectionContext({ id: 'supplier-1' }).reload();
      await flush();
      delayedView.response = new Promise((resolve) => {
        finishNew = resolve;
      });
      session.detailSectionContext({ id: 'supplier-1' }).reload();
      await flush();
      const before = wrapper.emitted('record-change')?.length ?? 0;
      const oldResult = { id: 'supplier-1', version: 3, title: '旧快照' };
      const newResult = { id: 'supplier-1', version: 4, title: '最新快照' };
      if (latestFirst) finishNew(newResult);
      else finishOld(oldResult);
      await flush();
      if (latestFirst) finishOld(oldResult);
      else finishNew(newResult);
      await flush();
      expect(browser.active.value?.record).toMatchObject(newResult);
      expect(wrapper.emitted('record-change')).toHaveLength(before + 1);
    }

    let finishRefresh!: (record: { id: string; version: number; title: string }) => void;
    delayedView.response = new Promise((resolve) => {
      finishRefresh = resolve;
    });
    const changesBeforeLateRefresh = wrapper.emitted('record-change')?.length ?? 0;
    session.detailSectionContext({ id: 'supplier-1' }).reload();
    await flush();
    wrapper.findComponent({ name: 'ModuleRecordDetailActions' }).vm.$emit('edit');
    await flush();
    const lateEditor = wrapper.findComponent({ name: 'ModulePageRecordContent' });
    lateEditor.vm.$emit('update:field', 'title', '刷新期间新写的草稿');
    await flush();
    finishRefresh({ id: 'supplier-1', version: 3, title: '晚到的服务器数据' });
    await flush();
    await flush();
    expect(lateEditor.props('record')).toMatchObject({ title: '刷新期间新写的草稿' });
    expect(wrapper.emitted('record-change')).toHaveLength(changesBeforeLateRefresh);
    confirm.mockResolvedValue(true);

    const detailDrawer = host.findComponent(RecordModeDrawer);
    session.recordViewContext({ id: 'supplier-1' }).close();
    await flush();
    expect(browser.active.value?.recordId).toBe('supplier-1');
    expect(detailDrawer.props('open')).toBe(false);

    detailDrawer.vm.$emit('afterClose');
    await flush();
    expect(browser.active.value).toBeUndefined();
    expect(wrapper.findComponent({ name: 'ModulePageHost' }).exists()).toBe(false);

    wrapper.unmount();
  });

  it('protects the parent record while a contributed correction is busy or rejects leaving', async () => {
    const Body = defineComponent({
      name: 'CorrectionBody',
      props: { context: { type: Object, required: true } },
      template: '<section />',
    });
    configureModulePageEnhancements([
      {
        id: 'supplier-correction',
        target: { moduleAlias: 'purchase.supplier' },
        detail: {
          actions: [
            {
              key: 'correct',
              title: '更正',
              run: ({ openDrawer }) => openDrawer({ title: '更正', component: Body }),
            },
          ],
        },
      },
    ]);
    const requests: string[] = [];
    const http: HttpClient = {
      async request(options) {
        requests.push(options.path);
        if (options.path.endsWith('view-context'))
          return {
            moduleAlias: 'purchase.supplier',
            capabilities: [],
            actions: [{ actionCode: 'view', authorized: true }],
            uiDescriptor: {
              moduleAlias: 'purchase.supplier',
              page: { template: 'LIST_DETAIL_CARD', detail: {} },
            },
          } as never;
        if (options.path.includes('/actions/'))
          return { recordId: 'supplier-1', actions: [{ actionCode: 'view', available: true }] } as never;
        return { id: 'supplier-1', version: 1, title: '供应商' } as never;
      },
    };
    const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
      props: { http, inlineAnchor: true },
      global: {
        provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
        stubs: {
          RecordDetailDrawer: DrawerLayoutHarness,
          RecordDetailFields: true,
          RecordMetaSection: true,
          ModulePageDetailRelations: true,
        },
      },
    });
    await wrapper.vm.openRecord('purchase.supplier', 'supplier-1');
    await flush();
    await flush();
    const actions = wrapper.findComponent({ name: 'ModuleRecordDetailActions' });
    const correction = (actions.props('actions') as Array<{ key: string }>).find(
      (action) => action.key === 'correct',
    );
    actions.vm.$emit('detailAction', correction);
    await flush();
    await flush();
    const context = wrapper
      .findComponent(Body)
      .props('context') as import('@/dynamic-page-runtime/modulePageEnhancements').ModulePageDrawerContext;
    context.setCloseBlocked(true);
    await flush();
    const browser = wrapper.findComponent(ReferenceRecordDetailBrowser).vm.browser;
    expect(browser.active.value?.busy).toBe(true);
    await wrapper.vm.closeRecord();
    await wrapper.vm.openRecord('purchase.supplier', 'supplier-2');
    const parent = wrapper.findComponent(RecordModeDrawer);
    expect(await parent.props('beforeClose')!('close-button')).toBe(false);
    expect(browser.active.value?.recordId).toBe('supplier-1');
    expect(requests).not.toContain('/purchase.supplier/view/supplier-2');
    const changesBeforeUnlock = wrapper.emitted('record-change')?.length ?? 0;
    const readsBeforeUnlock = requests.filter((path) => path.includes('/view/')).length;
    context.refreshList();
    context.refreshList();
    await flush();
    expect(requests.filter((path) => path.includes('/view/'))).toHaveLength(readsBeforeUnlock);
    expect(wrapper.emitted('record-change') ?? []).toHaveLength(changesBeforeUnlock);
    context.setCloseBlocked(false);
    await flush();
    await flush();
    expect(requests.filter((path) => path.includes('/view/'))).toHaveLength(readsBeforeUnlock + 1);
    expect(wrapper.emitted('record-change')).toHaveLength(changesBeforeUnlock + 1);
    expect(wrapper.emitted('record-change')?.at(-1)?.[0]).toMatchObject({
      type: 'saved',
      recordId: 'supplier-1',
    });
    context.setBeforeClose(() => false);
    await wrapper.vm.closeRecord();
    expect(browser.active.value?.recordId).toBe('supplier-1');
    context.setBeforeClose(() => true);
    await wrapper.vm.closeRecord();
    expect(browser.active.value).toBeUndefined();
    wrapper.unmount();
  });

  it.each([
    { name: 'mutation is denied', mutationStatus: 403, refreshStatus: undefined },
    { name: 'refresh temporarily fails', mutationStatus: undefined, refreshStatus: 500 },
    { name: 'refresh loses access', mutationStatus: undefined, refreshStatus: 403 },
  ])('keeps configured detail action behavior when $name', async ({ mutationStatus, refreshStatus }) => {
    let statusExecuted = false;
    const requests: string[] = [];
    const http: HttpClient = {
      async request(options) {
        requests.push(options.path);
        if (options.path === '/platform.module/purchase.supplier/view-context') {
          return {
            moduleAlias: 'purchase.supplier',
            capabilities: [],
            actions: [
              { actionCode: 'view', authorized: true },
              { actionCode: 'status', actionLevel: 'RECORD', category: 'CUSTOM', authorized: true },
            ],
            uiDescriptor: {
              schemaVersion: '1',
              moduleAlias: 'purchase.supplier',
              page: {
                template: 'LIST_DETAIL_CARD',
                detail: {},
                actions: [
                  {
                    actionCode: 'status',
                    anchor: 'DETAIL',
                    operation: 'INVOKE',
                    invocation: {
                      method: 'POST',
                      path: '/purchase.supplier/status/{recordId}',
                      input: 'NONE',
                    },
                  },
                ],
              },
            },
          } as never;
        }
        if (options.path === '/purchase.supplier/actions/supplier-1') {
          return {
            recordId: 'supplier-1',
            actions: [
              { actionCode: 'view', available: true },
              { actionCode: 'status', available: true },
            ],
          } as never;
        }
        if (options.path === '/purchase.supplier/view/supplier-1') {
          if (statusExecuted && refreshStatus)
            throw new AppError('target view failed after configured action', { status: refreshStatus });
          return { id: 'supplier-1', version: 1, title: '供应商' } as never;
        }
        if (options.path === '/purchase.supplier/status/supplier-1') {
          if (mutationStatus) throw new AppError('configured action denied', { status: mutationStatus });
          statusExecuted = true;
          return { message: '状态已更新' } as never;
        }
        throw new Error(`unexpected request: ${options.path}`);
      },
    };
    const browser = createReferenceRecordDetailBrowser(http);
    const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
      props: { browser, inlineAnchor: true },
      global: {
        provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
        stubs: {
          RecordDetailDrawer: DrawerLayoutHarness,
          RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
          RecordPanelState: { template: '<section />' },
          RecordDetailFields: { template: '<section />' },
          RecordFormFields: { template: '<section />' },
          RecordMetaSection: { template: '<section />' },
          ModulePageDetailRelations: { template: '<section />' },
          RecordActionBar: { template: '<section />' },
        },
      },
    });

    browser.open('purchase.supplier', 'supplier-1');
    await flush();
    await flush();
    const actionBar = wrapper.findComponent({ name: 'ModuleRecordDetailActions' });
    const status = (actionBar.props('configuredActions') as Array<{ actionCode?: string }>).find(
      (action) => action.actionCode === 'status',
    );
    expect(status).toBeDefined();
    const viewRequestsBeforeAction = requests.filter(
      (path) => path === '/purchase.supplier/view/supplier-1',
    ).length;
    actionBar.vm.$emit('detailAction', status);
    await flush();
    await flush();

    expect(requests).toContain('/purchase.supplier/status/supplier-1');
    if (mutationStatus) {
      expect(requests.filter((path) => path === '/purchase.supplier/view/supplier-1')).toHaveLength(
        viewRequestsBeforeAction,
      );
      expect(wrapper.emitted('record-change')).toBeUndefined();
    } else if (refreshStatus === 500) {
      expect(browser.active.value?.recordId).toBe('supplier-1');
      expect(wrapper.emitted('record-change')).toBeUndefined();
    } else {
      expect(browser.active.value).toBeUndefined();
      expect(wrapper.emitted('record-change')?.at(-1)?.[0]).toMatchObject({ type: 'unavailable' });
    }
    wrapper.unmount();
  });

  it.each([true, false])('refreshes a record-only extension only on success (failure=%s)', async (fails) => {
    configureModulePageEnhancements([
      {
        id: 'supplier-failing-detail-extension',
        target: { moduleAlias: 'purchase.supplier' },
        detail: {
          actions: [
            {
              key: 'failing-extension',
              title: '失败扩展',
              async run() {
                if (fails) throw new Error('扩展执行失败');
              },
            },
          ],
        },
      },
    ]);
    const requests: string[] = [];
    const http: HttpClient = {
      async request(options) {
        requests.push(options.path);
        if (options.path === '/platform.module/purchase.supplier/view-context') {
          return {
            moduleAlias: 'purchase.supplier',
            capabilities: [],
            actions: [{ actionCode: 'view', authorized: true }],
            uiDescriptor: {
              schemaVersion: '1',
              moduleAlias: 'purchase.supplier',
              page: { template: 'LIST_DETAIL_CARD', detail: {} },
            },
          } as never;
        }
        if (options.path === '/purchase.supplier/actions/supplier-1') {
          return { recordId: 'supplier-1', actions: [{ actionCode: 'view', available: true }] } as never;
        }
        if (options.path === '/purchase.supplier/view/supplier-1') {
          return { id: 'supplier-1', version: 1, title: '供应商' } as never;
        }
        throw new Error(`unexpected request: ${options.path}`);
      },
    };
    const browser = createReferenceRecordDetailBrowser(http);
    const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
      props: { browser, inlineAnchor: true },
      global: {
        provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
        stubs: {
          RecordDetailDrawer: DrawerLayoutHarness,
          RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
          RecordPanelState: { template: '<section />' },
          RecordDetailFields: { template: '<section />' },
          RecordFormFields: { template: '<section />' },
          RecordMetaSection: { template: '<section />' },
          ModulePageDetailRelations: { template: '<section />' },
          RecordActionBar: { template: '<section />' },
        },
      },
    });

    browser.open('purchase.supplier', 'supplier-1');
    await flush();
    await flush();
    const viewRequestsBeforeFailure = requests.filter(
      (path) => path === '/purchase.supplier/view/supplier-1',
    ).length;

    const actions = wrapper.findComponent({ name: 'ModuleRecordDetailActions' });
    const extension = (actions.props('actions') as Array<{ key: string }>).find(
      (action) => action.key === 'failing-extension',
    );
    expect(extension).toBeDefined();
    actions.vm.$emit('detailAction', extension);
    await flush();
    await flush();

    expect(requests.filter((path) => path === '/purchase.supplier/view/supplier-1')).toHaveLength(
      viewRequestsBeforeFailure + (fails ? 0 : 1),
    );
    if (fails) expect(wrapper.emitted('record-change')).toBeUndefined();
    else expect(wrapper.emitted('record-change')?.at(-1)?.[0]).toMatchObject({ type: 'saved' });
    wrapper.unmount();
  });

  it.each([false, true])(
    'keeps a record-only detail extension busy through %s and restores its reference browser',
    async (fails) => {
      let finishAction!: () => void;
      let finishRefresh!: (record: { id: string; version: number; title: string }) => void;
      let runs = 0;
      let viewCount = 0;
      const pendingAction = new Promise<void>((resolve) => {
        finishAction = resolve;
      });
      const pendingRefresh = new Promise<{ id: string; version: number; title: string }>((resolve) => {
        finishRefresh = resolve;
      });
      configureModulePageEnhancements([
        {
          id: 'supplier-pending-detail-extension',
          target: { moduleAlias: 'purchase.supplier' },
          detail: {
            actions: [
              {
                key: 'pending-extension',
                title: '延迟扩展',
                async run() {
                  runs += 1;
                  await pendingAction;
                  if (fails) throw new Error('扩展执行失败');
                },
              },
            ],
          },
        },
      ]);
      const http: HttpClient = {
        async request(options) {
          if (options.path === '/platform.module/purchase.supplier/view-context') {
            return {
              moduleAlias: 'purchase.supplier',
              capabilities: [],
              actions: [{ actionCode: 'view', authorized: true }],
              uiDescriptor: {
                schemaVersion: '1',
                moduleAlias: 'purchase.supplier',
                page: { template: 'LIST_DETAIL_CARD', detail: {} },
              },
            } as never;
          }
          if (options.path === '/purchase.supplier/actions/supplier-1') {
            return { recordId: 'supplier-1', actions: [{ actionCode: 'view', available: true }] } as never;
          }
          if (options.path === '/purchase.supplier/view/supplier-1') {
            viewCount += 1;
            if (viewCount > 2) return pendingRefresh as never;
            return { id: 'supplier-1', version: 1, title: '供应商' } as never;
          }
          throw new Error(`unexpected request: ${options.path}`);
        },
      };
      const browser = createReferenceRecordDetailBrowser(http);
      const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
        props: { browser, inlineAnchor: true },
        global: {
          provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
          stubs: {
            RecordDetailDrawer: DrawerLayoutHarness,
            RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
            RecordPanelState: { template: '<section />' },
            RecordDetailFields: { template: '<section />' },
            RecordFormFields: { template: '<section />' },
            RecordMetaSection: { template: '<section />' },
            ModulePageDetailRelations: { template: '<section />' },
            RecordActionBar: { template: '<section />' },
          },
        },
      });

      browser.open('purchase.supplier', 'supplier-1');
      await flush();
      await flush();
      const actions = wrapper.findComponent({ name: 'ModuleRecordDetailActions' });
      const extension = (actions.props('actions') as Array<{ key: string }>).find(
        (action) => action.key === 'pending-extension',
      );
      expect(extension).toBeDefined();
      expect(viewCount).toBe(2);
      expect(actions.props('detailLoading')).toBe(false);
      actions.vm.$emit('detailAction', extension);
      await flush();
      expect(browser.active.value?.busy).toBe(true);
      browser.close();
      browser.open('purchase.supplier', 'supplier-2');
      actions.vm.$emit('detailAction', extension);
      await flush();
      expect(browser.active.value?.recordId).toBe('supplier-1');
      expect(runs).toBe(1);

      finishAction();
      await flush();
      if (!fails) {
        expect(viewCount).toBe(3);
        expect(browser.active.value?.busy).toBe(true);
        browser.close();
        expect(browser.active.value?.recordId).toBe('supplier-1');
        finishRefresh({ id: 'supplier-1', version: 2, title: '已回读供应商' });
        await flush();
      }
      expect(browser.active.value?.busy).toBe(false);
      await browser.close();
      expect(browser.active.value).toBeUndefined();
      wrapper.unmount();
    },
  );

  it.each([
    {
      name: 'no capability',
      capability: false,
      enabled: true,
      operation: 'DISABLE',
      available: true,
      visible: false,
    },
    {
      name: 'missing state',
      capability: true,
      enabled: undefined,
      operation: 'DISABLE',
      available: true,
      visible: false,
    },
    {
      name: 'no configured action',
      capability: true,
      enabled: true,
      operation: undefined,
      available: true,
      visible: false,
    },
    {
      name: 'only the opposite action',
      capability: true,
      enabled: true,
      operation: 'ENABLE',
      available: true,
      visible: false,
    },
    {
      name: 'authorized disable',
      capability: true,
      enabled: true,
      operation: 'DISABLE',
      available: true,
      visible: true,
    },
    {
      name: 'authorized enable',
      capability: true,
      enabled: false,
      operation: 'ENABLE',
      available: true,
      visible: true,
    },
    {
      name: 'record action denied',
      capability: true,
      enabled: true,
      operation: 'DISABLE',
      available: false,
      visible: true,
    },
  ])(
    'renders status according to capability, page configuration and record authority: $name',
    async (scenario) => {
      const http: HttpClient = {
        async request(options) {
          if (options.path.endsWith('/view-context'))
            return {
              moduleAlias: 'purchase.supplier',
              capabilities: scenario.capability ? ['ENABLE'] : [],
              actions: ['view', 'enable', 'disable'].map((actionCode) => ({
                actionCode,
                authorized: true,
                actionLevel: 'RECORD',
              })),
              uiDescriptor: {
                schemaVersion: '1',
                moduleAlias: 'purchase.supplier',
                page: {
                  template: 'LIST_DETAIL_CARD',
                  managedActions: true,
                  detail: {},
                  actions: scenario.operation
                    ? [
                        {
                          anchor: 'DETAIL',
                          actionCode: scenario.operation.toLowerCase(),
                          operation: scenario.operation,
                        },
                      ]
                    : [],
                },
              },
            } as never;
          if (options.path.includes('/actions/'))
            return {
              recordId: 'supplier-1',
              actions: ['enable', 'disable'].map((actionCode) => ({
                actionCode,
                available: scenario.available,
                reason: scenario.available ? undefined : '无权执行启停',
              })),
            } as never;
          if (options.path.includes('/view/'))
            return { id: 'supplier-1', version: 1, title: '供应商', enabled: scenario.enabled } as never;
          throw new Error(`unexpected request: ${options.path}`);
        },
      };
      const browser = createReferenceRecordDetailBrowser(http);
      const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
        props: { browser, inlineAnchor: true },
        global: {
          provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
          stubs: { RecordDetailDrawer: DrawerLayoutHarness },
        },
      });
      browser.open('purchase.supplier', 'supplier-1');
      await flush();
      await flush();
      const status = wrapper.findComponent({ name: 'RecordStatusSwitch' });
      expect(status.exists()).toBe(scenario.visible);
      if (scenario.visible) {
        expect(status.props('enabled')).toBe(scenario.enabled);
        expect(status.props('disabled')).toBe(!scenario.available);
        if (!scenario.available) expect(status.attributes('title')).toBe('无权执行启停');
      }
      wrapper.unmount();
    },
  );

  it.each([
    { name: 'mutation is denied', mutationStatus: 403, refreshStatus: undefined },
    {
      name: 'a successful mutation has a temporary detail refresh failure',
      mutationStatus: undefined,
      refreshStatus: 500,
    },
    { name: 'a successful mutation loses detail access', mutationStatus: undefined, refreshStatus: 403 },
    {
      name: 'a successful mutation loses detail access with not found',
      mutationStatus: undefined,
      refreshStatus: 404,
    },
  ])('handles record-only status behavior when $name', async ({ mutationStatus, refreshStatus }) => {
    let statusToggled = false;
    const requests: string[] = [];
    const http: HttpClient = {
      async request(options) {
        requests.push(options.path);
        if (options.path === '/platform.module/purchase.supplier/view-context') {
          return {
            moduleAlias: 'purchase.supplier',
            capabilities: ['ENABLE'],
            actions: ['view', 'enable', 'disable'].map((actionCode) => ({
              actionCode,
              authorized: true,
              actionLevel: 'RECORD',
            })),
            uiDescriptor: {
              schemaVersion: '1',
              moduleAlias: 'purchase.supplier',
              page: { template: 'LIST_DETAIL_CARD', detail: {} },
            },
          } as never;
        }
        if (options.path === '/purchase.supplier/actions/supplier-1') {
          return {
            recordId: 'supplier-1',
            actions: [
              { actionCode: 'view', available: true },
              { actionCode: 'disable', available: true },
            ],
          } as never;
        }
        if (options.path === '/purchase.supplier/view/supplier-1') {
          if (statusToggled && refreshStatus)
            throw new AppError('target view failed after toggle', { status: refreshStatus });
          return { id: 'supplier-1', version: 1, title: '供应商', enabled: true } as never;
        }
        if (options.path === '/purchase.supplier/disable/supplier-1') {
          if (mutationStatus) throw new AppError('target mutation denied', { status: mutationStatus });
          statusToggled = true;
          return { message: '已停用' } as never;
        }
        throw new Error(`unexpected request: ${options.path}`);
      },
    };
    const browser = createReferenceRecordDetailBrowser(http);
    const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
      props: { browser, inlineAnchor: true },
      global: {
        provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
        stubs: {
          RecordDetailDrawer: DrawerLayoutHarness,
          RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
          RecordPanelState: { template: '<section />' },
          RecordDetailFields: { template: '<section />' },
          RecordFormFields: { template: '<section />' },
          RecordMetaSection: { template: '<section />' },
          ModulePageDetailRelations: { template: '<section />' },
          RecordActionBar: { template: '<section />' },
          RecordStatusSwitch: {
            name: 'RecordStatusSwitch',
            emits: ['change'],
            template: '<button data-testid="status-toggle" @click="$emit(\'change\', false)">切换</button>',
          },
        },
      },
    });

    browser.open('purchase.supplier', 'supplier-1');
    await flush();
    await flush();
    const viewRequestsBeforeToggle = requests.filter(
      (path) => path === '/purchase.supplier/view/supplier-1',
    ).length;
    await wrapper.get('[data-testid="status-toggle"]').trigger('click');
    await flush();
    await flush();

    expect(requests).toContain('/purchase.supplier/disable/supplier-1');
    if (mutationStatus) {
      expect(requests.filter((path) => path === '/purchase.supplier/view/supplier-1')).toHaveLength(
        viewRequestsBeforeToggle,
      );
      expect(wrapper.emitted('record-change')).toBeUndefined();
    } else if (refreshStatus === 500) {
      expect(browser.active.value?.recordId).toBe('supplier-1');
      expect(wrapper.emitted('record-change')).toBeUndefined();
    } else {
      expect(browser.active.value).toBeUndefined();
      expect(wrapper.emitted('record-change')?.at(-1)?.[0]).toMatchObject({ type: 'unavailable' });
    }
    wrapper.unmount();
  });

  it('carries portal and tab presentation through two record-only reference layers', async () => {
    const http: HttpClient = {
      async request(options) {
        const moduleAlias = options.path.match(/^\/platform\.module\/([^/]+)\/view-context$/)?.[1];
        if (moduleAlias) {
          return {
            moduleAlias,
            capabilities: [],
            actions:
              moduleAlias === 'crm.customer'
                ? [
                    { actionCode: 'view', authorized: true },
                    { actionCode: 'update', authorized: true },
                  ]
                : [{ actionCode: 'view', authorized: true }],
            uiDescriptor: {
              schemaVersion: '1',
              moduleAlias,
              page: {
                template: 'LIST_DETAIL_CARD',
                detail:
                  moduleAlias === 'purchase.supplier'
                    ? {
                        editor: {
                          fields: [
                            {
                              fieldRef: { fieldName: 'customerId' },
                              label: '客户',
                              valueType: 'STRING',
                              reference: { targetModuleAlias: 'crm.customer', cardinality: 'ONE' },
                            },
                          ],
                        },
                      }
                    : {},
              },
            },
          } as never;
        }
        if (options.path.endsWith('/actions/supplier-1') || options.path.endsWith('/actions/customer-1')) {
          return {
            recordId: options.path.endsWith('supplier-1') ? 'supplier-1' : 'customer-1',
            actions: options.path.endsWith('customer-1') ? [{ actionCode: 'update', available: true }] : [],
          } as never;
        }
        if (options.path === '/purchase.supplier/view/supplier-1') {
          return { id: 'supplier-1', version: 1, title: '供应商', customerId: 'customer-1' } as never;
        }
        if (options.path === '/crm.customer/view/customer-1') {
          return { id: 'customer-1', version: 1, title: '客户' } as never;
        }
        throw new Error(`unexpected request: ${options.path}`);
      },
    };
    const browser = createReferenceRecordDetailBrowser(http);
    const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
      props: { browser, inlineAnchor: false },
      global: {
        provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
        stubs: {
          RecordDetailDrawer: DrawerLayoutHarness,
          RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
          RecordPanelState: { template: '<section />' },
          RecordDetailFields: { template: '<section />' },
          RecordFormFields: { template: '<section />' },
          RecordMetaSection: { template: '<section />' },
          ModulePageDetailRelations: { template: '<section />' },
          RecordActionBar: { template: '<section />' },
        },
      },
    });

    browser.open('purchase.supplier', 'supplier-1');
    await flush();
    await flush();
    const firstHost = wrapper.findComponent(ModulePageHost);
    const nestedBridge = firstHost.findComponent({ name: 'ModuleReferenceRecordDetailBrowser' });
    expect(nestedBridge.props()).toMatchObject({ renderMode: 'portal', scope: 'tab' });
    const nestedBrowser = nestedBridge.findComponent(ReferenceRecordDetailBrowser);
    (nestedBrowser.vm as unknown as { browser: typeof browser }).browser.open('crm.customer', 'customer-1');
    await flush();
    await flush();

    const hostLayers = wrapper.findAllComponents(ModulePageHost);
    expect(hostLayers).toHaveLength(2);
    expect(hostLayers.map((host) => host.props('recordOnly'))).toEqual([
      expect.objectContaining({ renderMode: 'portal', scope: 'tab' }),
      expect.objectContaining({ renderMode: 'portal', scope: 'tab' }),
    ]);
    const customerActions = hostLayers[1].findComponent({ name: 'ModuleRecordDetailActions' });
    customerActions.vm.$emit('edit');
    await flush();
    expect(wrapper.emitted('interaction-state-change')?.at(-1)?.[0]).toEqual({
      editing: true,
      busy: false,
      dirty: false,
    });
    const customerContent = hostLayers[1].findComponent({ name: 'ModulePageRecordContent' });
    customerContent.vm.$emit('update:field', 'title', '客户新名称');
    await flush();
    expect(wrapper.emitted('interaction-state-change')?.at(-1)?.[0]).toEqual({
      editing: true,
      busy: false,
      dirty: true,
    });
    const confirm = vi.spyOn(platformComponents, 'confirmAction').mockResolvedValue(false);
    await wrapper.vm.closeRecord();
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(browser.active.value?.recordId).toBe('supplier-1');
    confirm.mockResolvedValue(true);
    await wrapper.vm.closeRecord();
    await flush();
    expect(wrapper.emitted('interaction-state-change')?.at(-1)?.[0]).toEqual({
      editing: false,
      busy: false,
      dirty: false,
    });
    wrapper.unmount();
  });

  it('refreshes a source view while preserving an edit draft and its reference id', async () => {
    let sourceViews = 0;
    const http: HttpClient = {
      async request(options) {
        if (options.path === '/platform.module/crm.order/context') {
          return {
            moduleAlias: 'crm.order',
            capabilities: [],
            actions: [
              { actionCode: 'view', authorized: true },
              { actionCode: 'update', authorized: true },
            ],
            uiDescriptor: {
              moduleAlias: 'crm.order',
              page: {
                template: 'LIST_DETAIL_CARD',
                list: { fields: { fields: [] } },
                detail: {
                  editor: {
                    fields: [
                      {
                        fieldRef: { fieldName: 'supplierId' },
                        label: '供应商',
                        valueType: 'STRING',
                        reference: {
                          targetModuleAlias: 'purchase.supplier',
                          cardinality: 'ONE',
                          displayProjections: [{ targetField: 'title', outputField: 'supplierTitle' }],
                        },
                      },
                      { fieldRef: { fieldName: 'note' }, label: '备注', valueType: 'STRING' },
                    ],
                  },
                },
              },
            },
          } as never;
        }
        if (options.path === '/crm.order/view/order-1') {
          sourceViews += 1;
          return {
            id: 'order-1',
            version: 1,
            supplierId: 'supplier-1',
            supplierTitle: sourceViews > 1 ? '目标新名称' : '目标旧名称',
            note: '服务器备注',
          } as never;
        }
        if (options.path === '/crm.order/actions/order-1')
          return { recordId: 'order-1', actions: [{ actionCode: 'update', available: true }] } as never;
        throw new Error(`unexpected request: ${options.path}`);
      },
    };
    configureModuleContext({ httpFactory: () => http });
    const wrapper = mount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          tabPolicy: { identity: 'by-target' },
          target: { moduleAlias: 'crm.order' },
        },
      },
      global: {
        provide: { [sidePanelHostKey as symbol]: ref(document.createElement('section')) },
        stubs: {
          RecordQueryListPanel: { name: 'RecordQueryListPanel', template: '<section />' },
          RecordDetailDrawer: { template: '<aside><slot /></aside>' },
          RecordPanelState: { template: '<section />' },
          RecordActionBar: { template: '<section />' },
          RecordFormFields: { template: '<section />' },
          RecordMetaSection: { template: '<section />' },
          ModulePageDetailRelations: { template: '<section />' },
        },
      },
    });
    await flush();
    const panel = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    panel.vm.$emit('rowDblclick', { id: 'order-1' });
    await flush();
    const browser = wrapper.findComponent({ name: 'ModuleReferenceRecordDetailBrowser' });
    browser.vm.$emit('record-change', {
      type: 'saved',
      targetModuleAlias: 'purchase.supplier',
      recordId: 'supplier-1',
      record: { id: 'supplier-1', title: '目标新名称' },
    });
    await flush();
    expect(sourceViews).toBe(2);

    const sourceActions = wrapper.findComponent({ name: 'ModuleRecordDetailActions' });
    sourceActions.vm.$emit('edit');
    await flush();
    const content = wrapper.findComponent({ name: 'ModulePageRecordContent' });
    expect(content.props('mode')).toBe('edit');
    content.vm.$emit('update:field', 'note', '本地草稿');
    await flush();
    browser.vm.$emit('record-change', {
      type: 'saved',
      targetModuleAlias: 'purchase.supplier',
      recordId: 'supplier-1',
      record: { id: 'supplier-1', title: '草稿期目标名称' },
    });
    await flush();
    expect(sourceViews).toBe(2);
    expect(content.props('record')).toMatchObject({
      id: 'order-1',
      supplierId: 'supplier-1',
      supplierTitle: '草稿期目标名称',
      note: '本地草稿',
    });
    wrapper.unmount();
  });
});

it('keeps the consumer openRecord facade bound to the same browser state', () => {
  const browser = createReferenceRecordDetailBrowser({ request: async () => ({}) as never });
  const wrapper = mount(ModuleReferenceRecordDetailBrowser, { props: { browser, inlineAnchor: true } });
  try {
    expect(typeof wrapper.vm.openRecord).toBe('function');
    wrapper.vm.openRecord('crm.customer', 'record-1');
    expect(browser.active.value?.recordId).toBe('record-1');
  } finally {
    wrapper.unmount();
  }
});
