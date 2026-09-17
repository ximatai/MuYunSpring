import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { confirmAction } from '@muyun/vue-ui-antdv';
import RoleManagementView from '@/views/RoleManagementView.vue';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext.ts';
import { configureModuleContext, createHttpClient } from '@muyun/web-core';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));

const roleList = {
  name: 'RecordQueryListPanel',
  emits: ['action'],
  template: '<button data-testid="create-role" @click="$emit(\'action\', { key: \'create\' })">新建</button>',
};

const formFields = {
  name: 'RecordFormFields',
  emits: ['update:field'],
  template: '<section />',
};

function mountRoleManagement(props: Record<string, string> = {}) {
  const requests: Array<{ path: string; selection?: string }> = [];
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
      return Response.json({
        id: url.pathname.split('/').at(-1),
        title: '租户管理员',
        ownerScopeType: 'tenant',
        ownerScopeId: props.scopeId,
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
      provideCurrentUserContext(ref({ system: false, tenantId: 'tenant-1' } as never));
      return () => h(RoleManagementView, props);
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
        RecordActionBar: { template: '<section />' },
        RecordExplorerPanel: { template: '<section><slot /></section>' },
        RecordDetailFields: { template: '<section />' },
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
  return { wrapper, requests };
}

describe('RoleManagementView create draft dismissal', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.mocked(confirmAction).mockReset();
  });

  it('keeps changed creation drafts when dismissal is rejected, clears after confirmation, and closes reverted drafts directly', async () => {
    const { wrapper } = mountRoleManagement();
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

  it('restores a role workspace through the IAM-owned navigator scope header', async () => {
    const { wrapper, requests } = mountRoleManagement({
      recordId: 'tenant_admin_2a97516c354b6884',
      scopeKind: 'tenant',
      scopeId: 'demo',
    });

    await flushPromises();
    await flushPromises();

    expect(requests).toContainEqual({
      path: '/iam.role/view/tenant_admin_2a97516c354b6884',
      selection: JSON.stringify({ kind: 'roleScope', key: 'tenant:demo' }),
    });
    wrapper.unmount();
  });
});
