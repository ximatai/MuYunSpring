import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { ref } from 'vue';
import { AppError, configureModuleContext, createHttpClient } from '@muyun/web-core';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import { configureModulePageEnhancements } from '@/dynamic-page-runtime/modulePageEnhancements';
import type { ModulePageSessionView } from '@/dynamic-page-runtime/useModulePageSession';
import '@/styles.css';

it('opens one scoped record directly and keeps an empty scope on the standard create flow', async () => {
  await page.viewport(1215, 730);
  let records = [{ id: 'policy-platform', title: 'Platform policy', threshold: 5 }];
  let failQuery = false;
  const views: string[] = [];
  const runtime = {
    moduleAlias: 'test.policy',
    moduleKind: 'STATIC',
    abilities: ['crud'],
    capabilities: [],
    actions: ['query', 'view', 'create', 'update', 'delete'].map((actionCode) => ({
      actionCode,
      authorized: true,
    })),
    uiDescriptor: {
      moduleAlias: 'test.policy',
      page: {
        template: 'FLAT_MANAGEMENT',
        traits: ['STANDARD_CRUD'],
        navigator: {
          levels: [{ key: 'tenant', kind: 'MICRO_LIST', sourceModuleAlias: 'iam.tenant', title: 'Tenant' }],
          contextBindings: [
            {
              source: 'NAVIGATOR',
              sourceKey: 'tenant',
              target: 'LIST_QUERY',
              targetKey: 'tenantId',
              navigatorListQueryMode: 'OPTIONAL_FILTER',
            },
            { source: 'NAVIGATOR', sourceKey: 'tenant', target: 'FORM_DEFAULT', targetKey: 'tenantId' },
          ],
        },
        explorer: { title: 'Policies', labelField: 'title' },
        detail: {
          createTitle: 'Customize policy',
          emptyDescription: 'Inherit platform policy',
          editor: {
            viewCode: 'form',
            viewKind: 'FORM',
            fields: [
              {
                fieldRef: { fieldName: 'threshold' },
                label: 'Threshold',
                valueType: 'INTEGER',
                visible: { constant: true },
                readOnly: { constant: false },
              },
            ],
          },
        },
      },
    },
  };
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = new Request(input, init);
    const path = new URL(request.url).pathname;
    if (path.endsWith('/context')) return Response.json(runtime);
    if (path.endsWith('/reference-context'))
      return Response.json({ moduleAlias: 'iam.tenant', abilities: ['crud'], capabilities: [], actions: [] });
    if (path.includes('/iam.tenant/') && path.endsWith('/query'))
      return Response.json({
        records: [{ id: 'tenant-a', title: 'Tenant A' }],
        total: 1,
        pageNum: 1,
        pageSize: 200,
        pages: 1,
        totalKnown: true,
      });
    if (path.includes('/save-receipts/'))
      return Response.json({ committed: true, recordId: 'saved-policy', recordVersion: 1 });
    if (path.includes('/view/')) {
      views.push(path);
      return Response.json(records[0]);
    }
    if (path.includes('/actions'))
      return Response.json({
        actions: ['view', 'update', 'delete'].map((actionCode) => ({ actionCode, available: true })),
      });
    if (path.endsWith('/query/schema'))
      return Response.json({ scopeName: 'test.policy', fields: [], externalCriteria: [], defaultSorts: [] });
    if (path.endsWith('/query'))
      return failQuery
        ? Response.json({ message: 'Unavailable' }, { status: 503 })
        : Response.json({
            records,
            total: records.length,
            pageNum: 1,
            pageSize: 200,
            pages: records.length ? 1 : 0,
            totalKnown: true,
          });
    return Response.json([]);
  });
  configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
  configureModulePageEnhancements([
    {
      id: 'single-policy',
      target: { moduleAlias: 'test.policy' },
      list: { singleRecord: true },
      standardActions: { disabled: ['delete'] },
      card: {
        assistant: { component: { render: () => null }, placement: { boundary: 'inside', position: 'top' } },
      },
      navigator: {
        emptySelectionLabel: () => 'Platform default',
        retainSelectedRecord: (key) => key === 'tenant',
      },
    },
  ]);
  const host = document.createElement('div');
  host.style.cssText = 'width:1180px;height:600px;position:relative;overflow:hidden';
  document.body.append(host);
  const wrapper = mount(ModulePageHost, {
    attachTo: host,
    props: {
      descriptor: {
        pageType: 'dynamic-module',
        openMode: 'dynamic-runner',
        hostType: 'module-page-host',
        tabPolicy: { identity: 'by-menu' },
        target: { moduleAlias: 'test.policy', pageMode: 'LIST' },
      },
    },
  });
  (host.firstElementChild as HTMLElement).style.height = '100%';
  try {
    await expect.poll(() => views.length).toBe(1);
    await flushPromises();
    expect(wrapper.findAll('.management-explorer-column')).toHaveLength(1);
    expect(wrapper.find('.static-management-sidebar').exists()).toBe(false);
    expect(wrapper.findAll('.record-list-explorer')).toHaveLength(2); // default range + tenant list
    expect(wrapper.text()).toContain('Threshold');
    const session = wrapper
      .findComponent({ name: 'ModulePageHostRuntime' })
      .props('session') as ModulePageSessionView;
    expect(session.selectedRecord?.id).toBe('policy-platform');
    await expect.poll(() => session.context.recordActionsSnapshot('policy-platform')).toBeDefined();
    expect(session.context.can('delete', 'policy-platform')).toBe(false);
    expect(session.context.action('delete', 'policy-platform')).toBeUndefined();
    expect(
      session.context.recordActionsSnapshot('policy-platform')?.actions.map((action) => action.actionCode),
    ).not.toContain('delete');
    expect(wrapper.findAll('button').some((button) => button.text() === '删除')).toBe(false);
    expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
    const tenantColumn = wrapper.get('.management-explorer-column').element;
    records = [];
    session.selectNavigatorRecord('tenant', { id: 'tenant-a' });
    await expect.poll(() => session.singleRecordScopeEmpty).toBe(true);
    await page.getByText('Tenant A', { exact: true }).click();
    expect(session.selectedNavigatorRecords.tenant?.id).toBe('tenant-a');
    await page.getByText('Platform default', { exact: true }).click();
    await expect.poll(() => session.selectedNavigatorRecords.tenant).toBeUndefined();
    await page.getByText('Tenant A', { exact: true }).click();
    await expect.poll(() => session.selectedNavigatorRecords.tenant?.id).toBe('tenant-a');
    await expect.poll(() => session.singleRecordScopeEmpty).toBe(true);
    expect(session.selectedRecord).toBeUndefined();
    expect(wrapper.get('.management-explorer-column').element).toBe(tenantColumn);
    expect(wrapper.text()).toContain('Inherit platform policy');
    const create = wrapper.findAll('button').find((button) => button.text() === 'Customize policy');
    expect(create?.attributes('disabled')).toBeUndefined();
    await create!.trigger('click');
    await flushPromises();
    expect(session.editorMode).toBe('create');
    const ready = ref(false);
    let completeSave!: (result: { record: { id: string } }) => void;
    const save = vi.fn(
      () =>
        new Promise<{ record: { id: string } }>((resolve) => {
          completeSave = resolve;
        }),
    );
    session.cardAssistantContext!.registerWorkspaceDraft!({
      ready: () => ready.value,
      dirty: () => false,
      save,
    });
    await flushPromises();
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '保存')
        ?.attributes('disabled'),
    ).toBeDefined();
    ready.value = true;
    await flushPromises();
    const pending = session.saveRecord();
    await expect.poll(() => save.mock.calls.length).toBe(1);
    const viewCount = views.length;
    // Replaced or unmounted contributions cannot commit their response to the current card.
    session.cardAssistantContext!.registerWorkspaceDraft!(undefined);
    completeSave({ record: { id: 'previous-scope-policy' } });
    await pending;
    expect(views).toHaveLength(viewCount);
    expect(session.editorMode).toBe('create');
    session.cardAssistantContext!.registerWorkspaceDraft!({
      ready: () => true,
      dirty: () => true,
      save: async () => {
        throw new AppError('draft rejected', { status: 400 });
      },
    });
    await session.saveRecord();
    expect(session.recordSaveNeedsCheck).toBe(false);
    expect(session.editorMode).toBe('create');
    const uncertainSave = vi.fn(async ({ requestId }: { requestId?: string }) => {
      expect(requestId).toMatch(/^[0-9a-f-]{36}$/);
      records = [{ id: 'saved-policy', title: 'Saved policy', threshold: 3 }];
      throw new Error('response lost after commit');
    });
    session.cardAssistantContext!.registerWorkspaceDraft!({
      ready: () => true,
      dirty: () => true,
      save: uncertainSave,
    });
    await session.saveRecord();
    expect(session.recordSaveNeedsCheck).toBe(true);
    await session.saveRecord();
    expect(uncertainSave).toHaveBeenCalledTimes(1);
    expect(session.recordSaveNeedsCheck).toBe(false);
    expect(session.selectedRecord?.id).toBe('saved-policy');
    expect(session.editorMode).toBe('view');
    session.cardAssistantContext!.registerWorkspaceDraft!(undefined);
    await session.handleFlatManagementAction({ key: 'cancel', title: 'Cancel' });
    records = [];
    session.refreshList();
    await expect.poll(() => session.singleRecordScopeEmpty).toBe(true);
    failQuery = true;
    session.refreshList();
    await expect.poll(() => wrapper.text().includes('重新加载')).toBe(true);
    expect(session.singleRecordScopeEmpty).toBe(false);
    const retryCreate = wrapper.findAll('button').find((button) => button.text() === 'Customize policy');
    expect(!retryCreate || retryCreate.attributes('disabled') !== undefined).toBe(true);
  } finally {
    wrapper.unmount();
    host.remove();
    vi.unstubAllGlobals();
    configureModulePageEnhancements([]);
  }
});
