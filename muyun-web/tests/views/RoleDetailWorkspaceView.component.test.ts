import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import StaticRoutePageHost from '@/app/StaticRoutePageHost.vue';
import { modulePageListRefreshRegistry } from '@/dynamic-page-runtime/modulePageListRefresh';
import ModuleRecordDetailActions from '@/dynamic-page-runtime/ModuleRecordDetailActions.vue';
import { pageDescriptorToUrl } from '@/platform-workbench/menuNavigation';
import { provideWorkbenchNavigation } from '@/platform-workbench/workbenchNavigation';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext.ts';
import '@/platform-admin-runtime/workspaceViews';
import {
  createLanguageContext,
  provideLanguageContext,
  type LanguageContext,
  configureModuleContext,
  createHttpClient,
} from '@muyun/web-core';

const formFields = {
  name: 'RecordFormFields',
  emits: ['update:field'],
  template: '<section />',
};

const detailFields = {
  name: 'RecordDetailFields',
  props: ['record'],
  template: '<section />',
};

type RoleRestoreOptions = {
  readFailure?: 400 | 403;
  presentation?: 'drawer' | 'tab';
  systemManaged?: boolean;
  language?: LanguageContext;
};

async function mountRestoredRole(props: Record<string, string>, options: RoleRestoreOptions = {}) {
  const closePage = vi.fn();
  const requests: Array<{ path: string; selection?: string; body?: unknown }> = [];
  let savedTitle: string | undefined;
  let version = 1;
  const menuPage = { render: () => h('p', '角色菜单页') };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/iam/role', component: menuPage, meta: { moduleAlias: 'iam.role' } }],
  });
  await router.push({
    path: '/iam/role',
    query: {
      ...props,
      workspaceView: 'iam.role.detail',
      workspacePresentation: options.presentation ?? 'tab',
    },
  });
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    requests.push({
      path: url.pathname,
      selection: new Headers(init?.headers).get('X-MuYun-Page-Selection') ?? undefined,
      ...(typeof init?.body === 'string' ? { body: JSON.parse(init.body) } : {}),
    });
    if (url.pathname.startsWith('/iam.tenant')) {
      return Response.json({ records: [{ id: 'tenant-1', title: '甲租户', enabled: true }], total: 1 });
    }
    if (url.pathname.startsWith('/iam.role/view/')) {
      if (options.readFailure) return Response.json({ message: '拒绝读取' }, { status: options.readFailure });
      return Response.json({
        id: url.pathname.split('/').at(-1),
        version,
        title: savedTitle ?? '租户管理员',
        ...(!savedTitle
          ? {
              $texts: {
                title: {
                  source: {
                    sourceKind: 'CONFIG',
                    sourceId: 'config/record/iam.role/preset/title',
                    sourceVersion: 'preset-title-version',
                    originalText: '租户管理员',
                  },
                  languages: {
                    en: {
                      text: 'Tenant administrator',
                      requestedTag: 'en',
                      resourceTag: 'en',
                      missing: false,
                      fallback: false,
                    },
                  },
                },
              },
            }
          : {}),
        ownerScopeType: props.scopeKind ?? 'tenant',
        ownerScopeId: props.scopeId,
        systemManaged: options.systemManaged === true,
        enabled: true,
      });
    }
    if (url.pathname === '/iam.role/update/role-1') {
      savedTitle = JSON.parse(String(init?.body)).title;
      ++version;
      return Response.json({ id: 'role-1', title: savedTitle, version });
    }
    if (url.pathname.startsWith('/iam.role/actions/')) {
      return Response.json({ actions: [{ actionCode: 'update', available: true }] });
    }
    if (url.pathname.startsWith('/iam.role')) return Response.json({ records: [], total: 0 });
    const moduleAlias = url.pathname.split('/')[2] ?? 'unknown';
    return Response.json({
      moduleAlias,
      capabilities: [],
      abilities: ['crud'],
      actions: [
        { actionCode: 'create', authorized: true },
        { actionCode: 'view', authorized: true },
        { actionCode: 'update', authorized: true },
      ],
      uiDescriptor: {
        moduleAlias,
        page: {
          template: 'FLAT_MANAGEMENT',
          detail: {
            editor: {
              viewCode: 'form',
              viewKind: 'FORM',
              fields: [
                {
                  fieldRef: { fieldName: 'title' },
                  label: '名称',
                  valueType: 'STRING',
                  visible: { constant: true },
                },
              ],
            },
          },
        },
      },
    });
  });
  configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
  const Harness = defineComponent({
    setup() {
      if (options.language) provideLanguageContext(options.language);
      provideCurrentUserContext(
        ref({ system: props.scopeKind === 'platform', tenantId: 'tenant-1' } as never),
      );
      provideWorkbenchNavigation({
        openPage(descriptor) {
          void router.push(pageDescriptorToUrl(descriptor));
          return { created: true };
        },
        openRoute: () => ({ created: false }),
        replaceRoute: () => ({ created: false }),
        closeCurrentTab: () => ({ created: false }),
        replacePage: vi.fn(),
        closePage,
        setTabName: vi.fn(),
      });
      return () => {
        const route = router.currentRoute.value;
        return h(StaticRoutePageHost, { key: route.fullPath, route, component: menuPage });
      };
    },
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        ADrawer: { template: '<section><slot /></section>' },
        UiActionButton: {
          props: ['title'],
          emits: ['click'],
          template: '<button :title="title" @click="$emit(\'click\')" />',
        },
        RecordFormFields: formFields,
        RecordDetailFields: detailFields,
        RecordMetaSection: { template: '<section />' },
        RecordStatusSwitch: { template: '<section />' },
        UiTree: { template: '<section />' },
        UiSpin: { template: '<section />' },
        UiError: { template: '<section />' },
      },
    },
  });
  return { wrapper, requests, router, closePage };
}

describe('legacy role detail URL', () => {
  afterEach(() => vi.unstubAllGlobals());

  it.each([
    ['platform', undefined, 'platform'],
    ['tenant', 'tenant-1', 'tenant:tenant-1'],
    ['organization', 'org-1', 'organization:org-1'],
  ] as const)('restores the record with its %s ownership scope', async (scopeKind, scopeId, key) => {
    const { wrapper, requests } = await mountRestoredRole({
      recordId: 'role-1',
      scopeKind,
      ...(scopeId ? { scopeId } : {}),
    });
    try {
      await vi.waitFor(() =>
        expect(wrapper.getComponent(detailFields).props('record')).toMatchObject({
          id: 'role-1',
          ownerScopeType: scopeKind,
        }),
      );
      expect(requests).toContainEqual({
        path: '/iam.role/view/role-1',
        selection: JSON.stringify({ kind: 'roleScope', key }),
      });
      expect(requests).toContainEqual({
        path: '/platform.module/iam.role/view-context',
        selection: undefined,
      });
      expect(wrapper.find('.record-detail-panel-region').exists()).toBe(true);
      expect(wrapper.text()).not.toContain('角色菜单页');
    } finally {
      wrapper.unmount();
    }
  });

  it.each([
    ['role belongs to another scope', { readFailure: 400 }],
    ['server denies access', { readFailure: 403 }],
  ] as const)('shows a load failure without rendering role details when the %s', async (_case, response) => {
    const { wrapper } = await mountRestoredRole(
      { recordId: 'role-1', scopeKind: 'organization', scopeId: 'org-1' },
      response,
    );
    try {
      await vi.waitFor(() => expect(wrapper.get('.record-mode-drawer-state').text()).toContain('重试'));
      expect(wrapper.findComponent(detailFields).exists()).toBe(false);
    } finally {
      wrapper.unmount();
    }
  });

  it('opens the promoted tab with the handed-off role draft and its scope intact', async () => {
    const input = { recordId: 'role-1', scopeKind: 'organization', scopeId: 'org-1' };
    const { wrapper, requests, router } = await mountRestoredRole(input, { presentation: 'drawer' });
    try {
      await vi.waitFor(() =>
        expect(wrapper.getComponent(detailFields).props('record')).toMatchObject({ id: 'role-1' }),
      );
      const drawer = wrapper.getComponent({ name: 'RecordDetailDrawer' });
      drawer.getComponent(ModuleRecordDetailActions).vm.$emit('edit');
      await vi.waitFor(() => expect(drawer.findComponent(formFields).exists()).toBe(true));
      drawer.getComponent(formFields).vm.$emit('update:field', 'title', '未保存的角色名称');
      await drawer.get('button[title="固定为页签"]').trigger('click');
      await vi.waitFor(() =>
        expect(router.currentRoute.value.query).toMatchObject({
          workspacePresentation: 'tab',
          ...input,
        }),
      );
      await vi.waitFor(() =>
        expect(wrapper.getComponent(formFields).vm.$attrs.record).toMatchObject({
          id: 'role-1',
          title: '未保存的角色名称',
          ownerScopeId: 'org-1',
        }),
      );
      expect(requests.filter(({ path }) => path === '/iam.role/view/role-1')).toHaveLength(2);
      expect(wrapper.find('.record-detail-panel-region').exists()).toBe(true);
    } finally {
      wrapper.unmount();
    }
  });

  it('keeps both drafts when the destination tab already has unsaved changes', async () => {
    const input = { recordId: 'role-1', scopeKind: 'tenant', scopeId: 'tenant-1' };
    const { wrapper: tab } = await mountRestoredRole(input);
    const { wrapper: source, router } = await mountRestoredRole(input, { presentation: 'drawer' });
    try {
      await vi.waitFor(() => {
        for (const wrapper of [tab, source]) {
          expect(wrapper.getComponent(detailFields).props('record')).toMatchObject({ id: 'role-1' });
        }
      });
      for (const [wrapper, draft] of [
        [tab, '目标草稿'],
        [source, '抽屉草稿'],
      ] as const) {
        wrapper.getComponent(ModuleRecordDetailActions).vm.$emit('edit');
        await vi.waitFor(() => expect(wrapper.findComponent(formFields).exists()).toBe(true));
        wrapper.getComponent(formFields).vm.$emit('update:field', 'title', draft);
      }
      await source.get('button[title="固定为页签"]').trigger('click');
      await flushPromises();
      expect(router.currentRoute.value.query.workspacePresentation).toBe('drawer');
      expect(tab.getComponent(formFields).vm.$attrs.record).toMatchObject({ title: '目标草稿' });
      expect(source.getComponent(formFields).vm.$attrs.record).toMatchObject({ title: '抽屉草稿' });
    } finally {
      source.unmount();
      tab.unmount();
    }
  });

  it('saves the restored tenant role with the same scope and refreshes its source list', async () => {
    const { wrapper, requests } = await mountRestoredRole({
      recordId: 'role-1',
      scopeKind: 'tenant',
      scopeId: 'tenant-1',
    });
    const refresh = vi.fn();
    const unregister = modulePageListRefreshRegistry.register('iam.role', refresh);
    try {
      await vi.waitFor(() =>
        expect(wrapper.getComponent(detailFields).props('record')).toMatchObject({ id: 'role-1' }),
      );
      wrapper.getComponent(ModuleRecordDetailActions).vm.$emit('edit');
      await vi.waitFor(() => expect(wrapper.findComponent(formFields).exists()).toBe(true));
      wrapper.getComponent(formFields).vm.$emit('update:field', 'title', '更新后的租户角色');
      wrapper.getComponent(ModuleRecordDetailActions).vm.$emit('save');
      await vi.waitFor(() =>
        expect(wrapper.getComponent(detailFields).props('record')).toMatchObject({
          title: '更新后的租户角色',
          version: 2,
        }),
      );
      expect(requests).toContainEqual({
        path: '/iam.role/update/role-1',
        selection: JSON.stringify({ kind: 'roleScope', key: 'tenant:tenant-1' }),
        body: expect.objectContaining({ title: '更新后的租户角色', version: 1 }),
      });
      expect(refresh).toHaveBeenCalledOnce();
    } finally {
      unregister();
      wrapper.unmount();
    }
  });

  it('keeps a system-managed role read-only even for a platform user', async () => {
    const { wrapper, requests } = await mountRestoredRole(
      { recordId: 'role-1', scopeKind: 'platform' },
      { systemManaged: true },
    );
    try {
      await vi.waitFor(() =>
        expect(wrapper.getComponent(detailFields).props('record')).toMatchObject({ systemManaged: true }),
      );
      const actions = wrapper.getComponent(ModuleRecordDetailActions);
      for (const code of ['update', 'delete', 'enable', 'disable']) {
        expect(actions.props('context').can(code, 'role-1')).toBe(false);
      }
      actions.vm.$emit('edit');
      await flushPromises();
      expect(wrapper.findComponent(formFields).exists()).toBe(false);
      expect(requests.some(({ path }) => path === '/iam.role/update/role-1')).toBe(false);
    } finally {
      wrapper.unmount();
    }
  });

  it('closes the owning workspace tab when the standard record session reports deletion', async () => {
    const { wrapper, closePage } = await mountRestoredRole({
      recordId: 'role-1',
      scopeKind: 'tenant',
      scopeId: 'tenant-1',
    });
    try {
      await vi.waitFor(() =>
        expect(wrapper.getComponent(detailFields).props('record')).toMatchObject({ id: 'role-1' }),
      );
      wrapper.getComponent({ name: 'ModulePageHost' }).vm.$emit('record-only-change', { type: 'deleted' });
      expect(closePage).toHaveBeenCalledOnce();
    } finally {
      wrapper.unmount();
    }
  });
});

it('updates the restored role title from its record resource without another read', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const { wrapper, requests } = await mountRestoredRole(
    { recordId: 'preset', scopeKind: 'tenant', scopeId: 'tenant-1' },
    { language },
  );
  try {
    await vi.waitFor(() =>
      expect(wrapper.get('.record-detail-panel-region h2').text()).toBe('Tenant administrator'),
    );
    const calls = requests.length;
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.get('.record-detail-panel-region h2').text()).toBe('租户管理员');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.get('.record-detail-panel-region h2').text()).toBe('Tenant administrator');
    expect(requests.length).toBe(calls);
  } finally {
    wrapper.unmount();
    vi.unstubAllGlobals();
  }
});
