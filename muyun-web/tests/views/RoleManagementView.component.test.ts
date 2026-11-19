import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { confirmAction } from '@muyun/vue-ui-antdv';
import StaticRoutePageHost from '@/app/StaticRoutePageHost.vue';
import { pageDescriptorToUrl } from '@/platform-workbench/menuNavigation';
import { provideWorkbenchNavigation } from '@/platform-workbench/workbenchNavigation';
import RoleManagementView from '@/views/RoleManagementView.vue';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext.ts';
import {
  createLanguageContext,
  provideLanguageContext,
  type LanguageContext,
  configureModuleContext,
  createHttpClient,
} from '@muyun/web-core';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));

const roleList = {
  name: 'RecordQueryListPanel',
  props: ['sortable'],
  emits: ['action'],
  template: '<button data-testid="create-role" @click="$emit(\'action\', { key: \'create\' })">新建</button>',
};

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

const actionBar = {
  name: 'RecordActionBar',
  emits: ['action'],
  template: '<section />',
};

type RoleRestoreOptions = { ownerScopeId?: string; denied?: boolean; presentation?: 'drawer' | 'tab' };

async function mountRoleManagement(
  props: Record<string, string> = {},
  language?: LanguageContext,
  restore?: RoleRestoreOptions,
) {
  const requests: Array<{ path: string; selection?: string }> = [];
  const menuPage = { render: () => h('p', '角色菜单页') };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/iam/role', component: menuPage, meta: { moduleAlias: 'iam.role' } }],
  });
  if (restore) {
    await router.push({
      path: '/iam/role',
      query: {
        ...props,
        workspaceView: 'iam.role.detail',
        workspacePresentation: restore.presentation ?? 'tab',
      },
    });
  }
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    requests.push({
      path: url.pathname,
      selection: new Headers(init?.headers).get('X-MuYun-Page-Selection') ?? undefined,
    });
    if (url.pathname.startsWith('/iam.tenant')) {
      return Response.json({ records: [{ id: 'tenant-1', title: '甲租户', enabled: true }], total: 1 });
    }
    if (url.pathname.startsWith('/iam.role/view/')) {
      if (restore?.denied) return Response.json({ message: 'Forbidden' }, { status: 403 });
      return Response.json({
        id: url.pathname.split('/').at(-1),
        title: '租户管理员',
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
        ownerScopeType: props.scopeKind ?? 'tenant',
        ownerScopeId: restore?.ownerScopeId ?? props.scopeId,
        enabled: true,
      });
    }
    if (url.pathname.startsWith('/iam.role')) return Response.json({ records: [], total: 0 });
    const moduleAlias = url.pathname.split('/')[2] ?? 'unknown';
    return Response.json({
      moduleAlias,
      capabilities: [],
      actions: [
        { actionCode: 'create', authorized: true },
        { actionCode: 'update', authorized: true },
      ],
      uiDescriptor: { moduleAlias },
    });
  });
  configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
  const Harness = defineComponent({
    setup() {
      if (language) provideLanguageContext(language);
      provideCurrentUserContext(
        ref({ system: props.scopeKind === 'platform', tenantId: 'tenant-1' } as never),
      );
      if (!restore) return () => h(RoleManagementView, props);
      provideWorkbenchNavigation({
        openPage(descriptor) {
          void router.push(pageDescriptorToUrl(descriptor));
          return { created: true };
        },
        openRoute: () => ({ created: false }),
        replaceRoute: () => ({ created: false }),
        closeCurrentTab: () => ({ created: false }),
        replacePage: vi.fn(),
        closePage: vi.fn(),
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
        RecordQueryListPanel: roleList,
        RecordFormFields: formFields,
        RecordActionBar: actionBar,
        RecordExplorerPanel: { template: '<section><slot /></section>' },
        RecordDetailFields: detailFields,
        RecordMetaSection: { template: '<section />' },
        RecordStatusSwitch: { template: '<section />' },
        UiTree: { template: '<section />' },
        UiSpin: { template: '<section />' },
        UiError: { template: '<section />' },
        RoleAccountGrantDrawer: { template: '<section />' },
        RoleEmploymentGrantDrawer: { template: '<section />' },
        RoleGroupMemberSelector: { template: '<section />' },
        RoleAuthorizationView: { template: '<section />' },
      },
    },
  });
  return { wrapper, requests, router };
}

describe('RoleManagementView create draft dismissal', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.mocked(confirmAction).mockReset();
  });

  it('keeps changed creation drafts when dismissal is rejected, clears after confirmation, and closes reverted drafts directly', async () => {
    const { wrapper } = await mountRoleManagement();
    await flushPromises();
    await flushPromises();
    const list = wrapper.findComponent(roleList);
    list.vm.$emit('action', { key: 'create' });
    await flushPromises();
    const drawer = () => wrapper.findComponent({ name: 'RecordDetailDrawer' });
    const form = wrapper.findComponent(formFields);
    expect(drawer().props('open')).toBe(true);

    form.vm.$emit('update:field', 'title', '临时角色');
    await flushPromises();
    vi.mocked(confirmAction).mockResolvedValueOnce(false);
    await drawer().find('button[title="关闭"]').trigger('click');
    await flushPromises();
    expect(confirmAction).toHaveBeenCalledOnce();
    expect(drawer().props('open')).toBe(true);

    vi.mocked(confirmAction).mockResolvedValueOnce(true);
    await drawer().find('button[title="关闭"]').trigger('click');
    await flushPromises();
    expect(drawer().props('open')).toBe(false);

    list.vm.$emit('action', { key: 'create' });
    await flushPromises();
    form.vm.$emit('update:field', 'title', '临时角色');
    await flushPromises();
    form.vm.$emit('update:field', 'title', undefined);
    await flushPromises();
    await drawer().find('button[title="关闭"]').trigger('click');
    await flushPromises();
    expect(confirmAction).toHaveBeenCalledTimes(2);
    expect(drawer().props('open')).toBe(false);
    wrapper.unmount();
  });

  it('opts the scoped role list into platform drag sorting instead of exposing a manual order field', async () => {
    const { wrapper } = await mountRoleManagement();
    await flushPromises();
    await flushPromises();

    expect(wrapper.findComponent(roleList).props('sortable')).toBe(true);
    wrapper.unmount();
  });
});

describe('legacy role detail URL', () => {
  afterEach(() => vi.unstubAllGlobals());

  function mountRestoredRole(props: Record<string, string>, response: RoleRestoreOptions = {}) {
    return mountRoleManagement(props, undefined, response);
  }

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
      expect(wrapper.text()).not.toContain('角色菜单页');
    } finally {
      wrapper.unmount();
    }
  });

  it.each([
    ['role belongs to another scope', { ownerScopeId: 'org-2' }],
    ['server denies access', { denied: true }],
  ])('shows a load failure without rendering role details when the %s', async (_case, response) => {
    const { wrapper } = await mountRestoredRole(
      { recordId: 'role-1', scopeKind: 'organization', scopeId: 'org-1' },
      response,
    );
    try {
      await vi.waitFor(() => expect(wrapper.get('.role-detail-state').text()).toContain('重试'));
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
      drawer.getComponent(actionBar).vm.$emit('action', { key: 'edit' });
      await flushPromises();
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
      expect(requests.filter(({ path }) => path === '/iam.role/view/role-1')).toHaveLength(1);
    } finally {
      wrapper.unmount();
    }
  });
});

it('translates selected scope presentation while retaining its identity and the unsaved role', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const { wrapper, requests } = await mountRoleManagement({}, language);
  try {
    await flushPromises();
    await flushPromises();
    wrapper.findComponent(roleList).vm.$emit('action', { key: 'create' });
    await flushPromises();
    const form = wrapper.findComponent(formFields);
    form.vm.$emit('update:field', 'title', '未保存的业务角色名');
    await flushPromises();
    const record = form.vm.$attrs.record;
    const before = JSON.stringify(record);
    const calls = requests.length;
    const drawer = wrapper.findComponent({ name: 'RecordDetailDrawer' });
    expect(drawer.props('subtitle')).toContain('Tenant level');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(drawer.props('subtitle')).toContain('租户本级');
    expect(form.vm.$attrs.record).toBe(record);
    expect(JSON.stringify(form.vm.$attrs.record)).toBe(before);
    expect(requests.length).toBe(calls);
    expect(drawer.props('open')).toBe(true);
    expect(confirmAction).not.toHaveBeenCalled();
    language.setLanguage('en');
    await flushPromises();
    expect(drawer.props('subtitle')).toContain('Tenant level');
    expect(JSON.stringify(form.vm.$attrs.record)).toBe(before);
  } finally {
    wrapper.unmount();
    vi.unstubAllGlobals();
  }
});

it('shows a preset role title through its record resource without refetching on language changes', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const { wrapper, requests } = await mountRoleManagement(
    { recordId: 'preset', scopeKind: 'tenant', scopeId: 'tenant-1' },
    language,
  );
  try {
    await flushPromises();
    await flushPromises();
    const panel = wrapper.findComponent({ name: 'RecordDetailPanel' });
    expect(panel.props('title')).toBe('Tenant administrator');
    const calls = requests.length;
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(panel.props('title')).toBe('租户管理员');
    language.setLanguage('en');
    await flushPromises();
    expect(panel.props('title')).toBe('Tenant administrator');
    expect(requests.length).toBe(calls);
  } finally {
    wrapper.unmount();
    vi.unstubAllGlobals();
  }
});
