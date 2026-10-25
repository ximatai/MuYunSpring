import { describe, expect, it, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref, defineComponent, h } from 'vue';
import CodeConfigurationView from '@/views/CodeConfigurationView.vue';
import { UiInput, UiSelect, UiCheckbox, confirmAction } from '@muyun/vue-ui-antdv';
import { newCodeRule, newCodeSegment, type CodeTarget } from '@/views/codeConfiguration';
const { request, can, user, navigation } = vi.hoisted(() => ({
  request: vi.fn(),
  navigation: { openRoute: vi.fn(), openPage: vi.fn() },
  can: vi.fn<(action: string) => boolean>(() => true),
  user: { tenantId: 'tenant-a', system: false },
}));
vi.mock('@muyun/platform-workbench', async (original) => ({
  ...(await original<typeof import('@muyun/platform-workbench')>()),
  useWorkbenchNavigation: () => navigation,
}));
vi.mock('@muyun/web-core', async (original) => ({
  ...(await original<typeof import('@muyun/web-core')>()),
  useModuleContext: () => ({ http: { request }, can }),
}));
vi.mock('@/platform-admin-runtime/currentUserContext', () => ({
  useCurrentUserContext: () => ref(user),
}));
vi.mock('@muyun/platform-components', async (original) => ({
  ...(await original<typeof import('@muyun/platform-components')>()),
  presentPlatformError: vi.fn(),
}));
vi.mock('@muyun/vue-ui-antdv', async (original) => ({
  ...(await original<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(async () => false),
}));
const drawer = defineComponent({
  name: 'RecordDetailDrawer',
  props: ['open', 'beforeClose'],
  emits: ['close'],
  setup(props, { slots }) {
    return () => (props.open ? h('section', [slots['header-actions']?.(), slots.default?.()]) : null);
  },
});
const staticTarget: CodeTarget = {
  moduleAlias: 'sales.invoice',
  entityAlias: 'invoice',
  title: '单据',
  source: 'STATIC',
  fields: [{ fieldName: 'documentNo', title: '单据编码', role: 'PRIMARY', trigger: 'SAVE' }],
  sources: [{ fieldName: 'title', title: '名称', type: 'STRING' }],
};
const wrapperOf = (overrides: { moduleAlias?: string; moduleTitle?: string } = {}) =>
  mount(CodeConfigurationView, {
    props: { moduleAlias: 'sales.invoice', embedded: true, ...overrides },
    global: {
      stubs: {
        ModuleRuntimeActivationStatus: true,
        RecordPicker: true,
        RecordDetailDrawer: drawer,
        TenantScopeExplorer: true,
      },
    },
  });
const button = (wrapper: ReturnType<typeof wrapperOf>, title: string) =>
  wrapper
    .findAll('button')
    .find((item) => (item.attributes('aria-label') ?? item.text()).replace(/\s+/g, '') === title)!;
beforeEach(() => {
  user.tenantId = 'tenant-a';
  user.system = false;
  request.mockReset();
  navigation.openRoute.mockClear();
  navigation.openPage.mockClear();
  can.mockReturnValue(true);
  request.mockImplementation(async ({ path, body }) => {
    if (path.includes('/targets')) return [staticTarget];
    if (path.includes('/rules?')) return [];
    if (path.endsWith('/saveTree')) return { ...body, id: 'saved-rule', version: 1 };
    if (path.endsWith('/preview')) return { value: 'NO-0001', segments: [] };
    return undefined;
  });
});
describe('CodeConfigurationView', () => {
  it.each([false, true])(
    'omits redundant module navigation for a scoped entry with system=%s',
    async (system) => {
      user.system = system;
      const wrapper = wrapperOf({ moduleTitle: '销售开票' });
      try {
        await flushPromises();
        expect(wrapper.find('.code-config > header').exists()).toBe(false);
        expect(wrapper.findComponent({ name: 'RecordListExplorer' }).exists()).toBe(false);
        expect(wrapper.findComponent({ name: 'TenantScopeExplorer' }).exists()).toBe(system);
        expect(wrapper.findAllComponents({ name: 'ManagementExplorerColumn' })).toHaveLength(system ? 1 : 0);
        expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('explorerCount')).toBe(
          system ? 1 : 0,
        );
        expect(wrapper.findAllComponents(UiSelect)[0]!.props('value')).toBe('sales.invoice/invoice');
        expect(request.mock.calls.find(([call]) => call.path.includes('/targets'))![0].path).toContain(
          'moduleAlias=sales.invoice',
        );
        button(wrapper, '新增规则').element.click();
        await flushPromises();
        expect(button(wrapper, '保存规则').exists()).toBe(true);
      } finally {
        wrapper.unmount();
      }
    },
  );

  it('keeps dynamic activation feedback beside the standard object panel actions', async () => {
    request.mockImplementation(async ({ path }) =>
      path.includes('/targets') ? [{ ...staticTarget, source: 'DYNAMIC' }] : [],
    );
    const wrapper = wrapperOf();
    try {
      await wrapper.setProps({ embedded: false });
      await flushPromises();
      expect(wrapper.find('.code-config > header').exists()).toBe(false);
      const status = wrapper
        .getComponent({ name: 'RecordExplorerPanel' })
        .findComponent({ name: 'ModuleRuntimeActivationStatus' });
      expect(status.exists()).toBe(true);
      expect(status.props('moduleAlias')).toBe('sales.invoice');
    } finally {
      wrapper.unmount();
    }
  });

  it.each([
    { kind: 'static', targets: [], expected: '该静态模块尚未声明', model: false },
    { kind: 'dynamic', targets: [], expected: '尚未关联业务数据模型', model: true },
    {
      kind: 'dynamic',
      targets: [{ ...staticTarget, source: 'DYNAMIC', fields: [] }],
      expected: '当前数据模型没有可用于编号的字段',
      model: true,
    },
  ])(
    'explains unavailable configuration for $kind and offers a usable exit',
    async ({ kind, targets, expected, model }) => {
      request.mockImplementation(async ({ path }) =>
        path.includes('/targets')
          ? targets
          : {
              configurable: false,
              reason: kind === 'static' ? 'not-declared' : targets.length ? 'no-fields' : 'no-model',
            },
      );
      const wrapper = wrapperOf();
      try {
        await flushPromises();
        expect(wrapper.text()).toContain(expected);
        expect(button(wrapper, '新增规则')).toBeUndefined();
        await button(wrapper, '返回模块管理').trigger('click');
        expect(navigation.openRoute).toHaveBeenCalledWith('/platform/module');
        expect(!!button(wrapper, '配置模型')).toBe(model);
        if (model) {
          await button(wrapper, '配置模型').trigger('click');
          expect(navigation.openPage.mock.calls[0]![0].target.query).toMatchObject({
            moduleAlias: 'sales.invoice',
            governanceTab: 'metadata',
          });
        }
      } finally {
        wrapper.unmount();
      }
    },
  );
  it('keeps the unavailable fallback usable when module directory reads are forbidden', async () => {
    request.mockImplementation(async ({ path }) => {
      if (path.includes('/targets')) return [];
      throw new Error('forbidden');
    });
    const wrapper = wrapperOf();
    try {
      await flushPromises();
      expect(wrapper.text()).toContain('当前模块没有可配置的编号字段');
      expect(button(wrapper, '返回模块管理')).toBeDefined();
      expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    } finally {
      wrapper.unmount();
    }
  });
  it('refreshes scoped configuration through the standard panel title', async () => {
    const wrapper = wrapperOf();
    try {
      await flushPromises();
      expect(wrapper.find('.code-config > header').exists()).toBe(false);
      expect(button(wrapper, '刷新')).toBeUndefined();
      request.mockClear();
      await wrapper.getComponent({ name: 'RecordExplorerPanel' }).vm.$emit('refresh');
      await flushPromises();
      expect(request.mock.calls.find(([call]) => call.path.includes('/targets'))![0].path).toContain(
        'moduleAlias=sales.invoice',
      );
      expect(request.mock.calls.some(([call]) => call.path.includes('/rules?'))).toBe(true);
    } finally {
      wrapper.unmount();
    }
  });

  it('selects a module before loading rules and keeps business objects within that module', async () => {
    user.system = true;
    const main = { ...staticTarget, moduleTitle: '销售管理', title: '销售单' };
    const child = { ...main, entityAlias: 'line', title: '销售明细' };
    const other = { ...staticTarget, moduleAlias: 'api.receipt', moduleTitle: '接口回执', title: '回执单' };
    const empty = { ...other, moduleAlias: 'draft.empty', moduleTitle: '待配置模块', fields: [] };
    request.mockImplementation(async ({ path }) => {
      if (path.endsWith('/preview')) return { value: 'NO-0001', segments: [] };
      return path.includes('/targets') ? [main, child, other, empty] : [];
    });
    const wrapper = wrapperOf({ moduleAlias: undefined });
    await flushPromises();
    try {
      const modules = wrapper.findComponent({ name: 'RecordListExplorer' });
      expect(modules.props('records').map((record: { title: string }) => record.title)).toEqual([
        '销售管理',
        '接口回执',
      ]);
      expect(request.mock.calls.some(([call]) => call.path.includes('/rules?'))).toBe(false);
      expect(wrapper.text()).toContain('选择模块后');
      request.mockClear();
      modules.vm.$emit('select', { id: 'sales.invoice' });
      await flushPromises();
      expect(
        wrapper
          .findAllComponents(UiSelect)[0]!
          .props('options')
          .map((option) => option.label),
      ).toEqual(['销售单', '销售明细']);
      expect(request.mock.calls.find(([call]) => call.path.includes('/rules?'))![0].path).toContain(
        'moduleAlias=sales.invoice',
      );
      await button(wrapper, '新增规则').trigger('click');
      const name = wrapper.findAllComponents(UiInput).find((input) => input.props('value') === '单据编码')!;
      name.vm.$emit('update:value', '未保存规则');
      await flushPromises();
      modules.vm.$emit('select', { id: 'api.receipt' });
      await flushPromises();
      expect(modules.props('selectedId')).toBe('sales.invoice');
      await button(wrapper, '取消修改').trigger('click');
      wrapper.findComponent({ name: 'TenantScopeExplorer' }).vm.$emit('select', { id: 'tenant-b' });
      await flushPromises();
      expect(wrapper.findComponent({ name: 'RecordListExplorer' }).props('selectedId')).toBeUndefined();
      expect(wrapper.findAllComponents(UiSelect)).toHaveLength(0);
    } finally {
      wrapper.unmount();
    }
  });

  it('preserves distinct main and child entity titles inside a module', async () => {
    request.mockImplementation(async ({ path }) => {
      if (path.includes('/targets'))
        return [
          { ...staticTarget, source: 'DYNAMIC', title: '采购单' },
          { ...staticTarget, source: 'DYNAMIC', entityAlias: 'line', title: '采购明细' },
        ];
      return [];
    });
    const wrapper = wrapperOf();
    await wrapper.setProps({ moduleTitle: '采购管理' });
    await flushPromises();
    expect(
      wrapper
        .findAllComponents(UiSelect)[0]!
        .props('options')
        .map((option) => option.label),
    ).toEqual(['采购单', '采购明细']);
    wrapper.unmount();
  });

  it('waits for a system administrator to choose a tenant before requesting tenant-owned targets', async () => {
    user.tenantId = '';
    user.system = true;
    const wrapper = wrapperOf();
    await flushPromises();
    expect(request.mock.calls.some(([call]) => call.path.startsWith('/platform.code_rule/'))).toBe(false);
    expect(wrapper.text()).toContain('选择规则所属租户后');
    expect(wrapper.text()).not.toContain('尚未声明');
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    wrapper.findComponent({ name: 'TenantScopeExplorer' }).vm.$emit('select', { id: 'tenant-a' });
    await flushPromises();
    expect(request.mock.calls.some(([call]) => call.path.includes('/targets'))).toBe(true);
    expect(wrapper.text()).not.toContain('字段角色和生成时机由业务模型预设');
    expect(wrapper.find('button[aria-label="字段设置说明"]').exists()).toBe(true);
    wrapper.unmount();
  });
  it('automatically selects a sole tenant only from the initial complete candidate set', async () => {
    user.tenantId = '';
    user.system = true;
    const wrapper = wrapperOf();
    await flushPromises();
    try {
      const tenant = wrapper.findComponent({ name: 'TenantScopeExplorer' });
      tenant.vm.$emit('loaded', [{ id: 'tenant-a' }], false, 1);
      await flushPromises();
      expect(tenant.props('selectedId')).toBeUndefined();
      tenant.vm.$emit('loaded', [{ id: 'tenant-a' }], true, 2);
      await flushPromises();
      expect(tenant.props('selectedId')).toBeUndefined();
      tenant.vm.$emit('loaded', [{ id: 'tenant-a' }], true, 1);
      await flushPromises();
      expect(tenant.props('selectedId')).toBe('tenant-a');
      const targetsCall = request.mock.calls.find(([call]) => call.path.includes('/targets'))![0];
      expect(targetsCall.headers).toMatchObject({ 'X-MuYun-Tenant-Id': 'tenant-a' });
    } finally {
      wrapper.unmount();
    }
  });

  it('distinguishes a load failure from an empty catalog and offers a working retry', async () => {
    request.mockRejectedValueOnce(new Error('offline'));
    const wrapper = wrapperOf();
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('尚未声明');
    await button(wrapper, '重试加载').trigger('click');
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(button(wrapper, '新增规则')).toBeDefined();
    wrapper.unmount();
  });
  it('edits a static API-only declaration, trials without saving, then persists the full rule tree', async () => {
    const wrapper = wrapperOf();
    await flushPromises();
    expect(wrapper.text()).not.toContain('字段角色和生成时机由业务模型预设');
    expect(wrapper.find('button[aria-label="字段设置说明"]').exists()).toBe(true);
    await button(wrapper, '新增规则').trigger('click');
    expect(wrapper.findAllComponents(UiInput).some((input) => input.props('value') === 'NO-')).toBe(true);
    await button(wrapper, '刷新试算').trigger('click');
    await flushPromises();
    expect(wrapper.find('output strong').text()).toBe('NO-0001');
    expect(request.mock.calls.some(([call]) => call.path.endsWith('/saveTree'))).toBe(false);
    await button(wrapper, '保存规则').trigger('click');
    await flushPromises();
    const saved = request.mock.calls.find(([call]) => call.path.endsWith('/saveTree'))![0].body;
    expect(saved).toMatchObject({
      moduleAlias: 'sales.invoice',
      entityAlias: 'invoice',
      fieldName: 'documentNo',
      fieldRole: 'PRIMARY',
    });
    expect(saved.segments).toHaveLength(2);
    expect(saved).not.toHaveProperty('value');
    expect(wrapper.text()).toContain('规则已保存');
    wrapper.unmount();
  }, 15_000);
  it('keeps unused sources out of trial inputs and preserves a result when only the title changes', async () => {
    const wrapper = wrapperOf();
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    expect(wrapper.findAll('label').some((label) => label.text() === '名称')).toBe(false);
    const { UiSelect } = await import('@muyun/vue-ui-antdv');
    const extra = wrapper.findAllComponents(UiSelect).find((select) => select.props('mode') === 'multiple')!;
    extra.vm.$emit('update:value', ['title']);
    await flushPromises();
    expect(wrapper.findAll('label').some((label) => label.text() === '名称')).toBe(true);
    await button(wrapper, '刷新试算').trigger('click');
    await flushPromises();
    expect(wrapper.find('output').exists()).toBe(true);
    const name = wrapper.findAllComponents(UiInput).find((input) => input.props('value') === '单据编码')!;
    name.vm.$emit('update:value', '修改后的规则');
    await flushPromises();
    expect(wrapper.find('output').exists()).toBe(true);
    wrapper.unmount();
  });
  it('ignores a late trial response after the module identity changes', async () => {
    const wrapper = wrapperOf();
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    let resolve!: (value: unknown) => void;
    request.mockImplementation(async ({ path }) => {
      if (path.endsWith('/preview'))
        return new Promise((done) => {
          resolve = done;
        });
      if (path.includes('/targets')) return [];
      return [];
    });
    await button(wrapper, '刷新试算').trigger('click');
    await wrapper.setProps({ moduleAlias: 'sales.other' });
    await flushPromises();
    resolve({ value: 'STALE', segments: [] });
    await flushPromises();
    expect(wrapper.find('output').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('STALE');
    wrapper.unmount();
  });
  it('creates and previews a disabled dynamic draft without binding, then activates the saved rule directly', async () => {
    let active = false;
    const dynamic = {
      ...staticTarget,
      source: 'DYNAMIC',
      relationId: 'relation',
      fields: [{ fieldName: 'documentNo', title: '单据编码', metadataFieldId: 'field' }],
    };
    request.mockImplementation(async ({ path, body }) => {
      if (path.includes('/targets'))
        return [
          {
            ...dynamic,
            fields: [{ ...dynamic.fields[0], ...(active ? { role: 'NORMAL', trigger: 'SAVE' } : {}) }],
          },
        ];
      if (path.includes('/rules?')) return [];
      if (path.endsWith('/saveTree')) {
        active = body.enabled;
        return { ...body, id: 'draft-rule', version: active ? 2 : 1 };
      }
      if (path.endsWith('/preview')) return { value: 'NO-0001', segments: [] };
      return undefined;
    });
    const wrapper = wrapperOf();
    try {
      await flushPromises();
      expect(button(wrapper, '接入编码')).toBeUndefined();
      expect(button(wrapper, '移除编码')).toBeUndefined();
      expect(wrapper.find('.code-config__binding').exists()).toBe(true);
      expect(wrapper.find('button[aria-label="字段设置说明"]').exists()).toBe(false);
      wrapper
        .get('.code-config__binding')
        .findAllComponents(UiSelect)[1]!
        .vm.$emit('update:value', 'EXPLICIT');
      await flushPromises();
      await button(wrapper, '新增规则').trigger('click');
      await button(wrapper, '保存规则').trigger('click');
      await flushPromises();
      expect(request.mock.calls.find(([call]) => call.path.endsWith('/saveTree'))![0].body).toMatchObject({
        enabled: false,
        fieldRole: 'NORMAL',
        fieldTrigger: 'EXPLICIT',
      });
      expect(request.mock.calls.some(([call]) => call.path.endsWith('/binding'))).toBe(false);
      expect(active).toBe(false);
      wrapper.findComponent({ name: 'RecordDetailDrawer' }).vm.$emit('close');
      await flushPromises();
      wrapper.findComponent({ name: 'UiSwitch' }).vm.$emit('change', true);
      await flushPromises();
      expect(active).toBe(true);
      expect(request.mock.calls.some(([call]) => call.path.endsWith('/binding'))).toBe(false);
      wrapper.findComponent({ name: 'UiSwitch' }).vm.$emit('change', false);
      await flushPromises();
      expect(active).toBe(false);
      expect(wrapper.text()).toContain('已有编码保持不变');
    } finally {
      wrapper.unmount();
    }
  });
  it('allows drafting without field configuration permission but prevents first activation', async () => {
    can.mockImplementation((action: string) => action !== 'configureBinding');
    const dynamic = {
      ...staticTarget,
      source: 'DYNAMIC',
      fields: [{ fieldName: 'documentNo', title: '单据编码', metadataFieldId: 'field' }],
    };
    request.mockImplementation(async ({ path, body }) => {
      if (path.includes('/targets')) return [dynamic];
      if (path.includes('/rules?')) return [];
      if (path.endsWith('/preview')) return { value: 'NO-0001', segments: [] };
      return { ...body, id: 'draft-rule' };
    });
    const wrapper = wrapperOf();
    try {
      await flushPromises();
      await button(wrapper, '新增规则').trigger('click');
      await button(wrapper, '保存规则').trigger('click');
      await flushPromises();
      wrapper.findComponent({ name: 'RecordDetailDrawer' }).vm.$emit('close');
      await flushPromises();
      const toggle = wrapper.findComponent({ name: 'UiSwitch' });
      expect(toggle.props('disabled')).toBe(true);
      request.mockClear();
      toggle.vm.$emit('change', true);
      await flushPromises();
      expect(request).not.toHaveBeenCalled();
    } finally {
      wrapper.unmount();
    }
  });
  it('cancels binding changes from the overview without opening the rule drawer or saving', async () => {
    user.system = true;
    request.mockImplementation(async ({ path }) => {
      if (path.includes('/targets')) return [{ ...staticTarget, source: 'DYNAMIC', relationId: 'relation' }];
      return [];
    });
    const wrapper = wrapperOf();
    await flushPromises();
    try {
      const binding = wrapper.get('.code-config__binding');
      expect(binding.find('h3').exists()).toBe(false);
      expect(binding.findComponent({ name: 'UiHelp' }).exists()).toBe(false);
      binding.findAllComponents(UiSelect)[1]!.vm.$emit('update:value', 'EXPLICIT');
      await flushPromises();
      expect(wrapper.findComponent({ name: 'TenantScopeExplorer' }).props('disabled')).toBe(true);
      expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);
      await button(wrapper, '取消修改').trigger('click');
      expect(binding.findAllComponents(UiSelect)[1]!.props('value')).toBe('SAVE');
      expect(wrapper.findComponent({ name: 'TenantScopeExplorer' }).props('disabled')).toBe(false);
      expect(request.mock.calls.some(([call]) => call.path.endsWith('/binding'))).toBe(false);
      await button(wrapper, '新增规则').trigger('click');
      expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(true);
    } finally {
      wrapper.unmount();
    }
  });

  it('closes the drawer on cancel and restores the saved rule when reopened', async () => {
    const rule = { ...newCodeRule(staticTarget, staticTarget.fields[0]!), id: 'existing' };
    request.mockImplementation(async ({ path }) => {
      if (path.includes('/targets')) return [staticTarget];
      if (path.includes('/rules?')) return [rule];
      return undefined;
    });
    const wrapper = wrapperOf();
    try {
      await flushPromises();
      await wrapper.get('.code-config__rule-name').trigger('click');
      const title = wrapper.findAllComponents(UiInput).find((input) => input.props('value') === rule.title)!;
      title.vm.$emit('update:value', '未保存修改');
      await flushPromises();
      await button(wrapper, '取消修改').trigger('click');
      expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);
      expect(request.mock.calls.some(([call]) => call.path.endsWith('/saveTree'))).toBe(false);
      await wrapper.get('.code-config__rule-name').trigger('click');
      expect(wrapper.findAllComponents(UiInput).some((input) => input.props('value') === rule.title)).toBe(
        true,
      );
      expect(wrapper.findAllComponents(UiInput).some((input) => input.props('value') === '未保存修改')).toBe(
        false,
      );
    } finally {
      wrapper.unmount();
    }
  });

  it('edits rule applicability on the master page and preserves format and activation', async () => {
    const rule = {
      ...newCodeRule(staticTarget, staticTarget.fields[0]!),
      id: 'existing',
      version: 3,
      enabled: true,
    };
    request.mockImplementation(async ({ path, body }) => {
      if (path.includes('/targets')) return [staticTarget];
      if (path.includes('/rules?')) return [rule];
      if (path.endsWith('/navigator/reference/translate'))
        return { records: [{ id: 'org-a', title: '采购中心' }], total: 1, pageNum: 0, pageSize: 1 };
      if (path.endsWith('/saveTree')) return { ...body, version: 4 };
      return undefined;
    });
    user.system = true;
    const wrapper = wrapperOf();
    try {
      await flushPromises();
      await wrapper.get('.code-config__edit-usage').trigger('click');
      expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);
      const usage = wrapper.get('.code-config__rules-table tbody tr');
      usage.findComponent({ name: 'UiSwitch' }).vm.$emit('change', false);
      await flushPromises();
      expect(request.mock.calls.some(([call]) => call.path.endsWith('/saveTree'))).toBe(false);
      usage.findComponent(UiSelect).vm.$emit('update:value', 'ORG');
      await flushPromises();
      usage.findComponent({ name: 'RecordPicker' }).vm.$emit('update:value', 'org-a');
      const start = usage.findAllComponents(UiInput)[0]!;
      start.vm.$emit('update:value', '2026-10-01T09:00');
      await flushPromises();
      wrapper.findComponent({ name: 'TenantScopeExplorer' }).vm.$emit('select', { id: 'tenant-b' });
      await flushPromises();
      expect(wrapper.find('.code-config__rules-table .ui-date-picker input').exists()).toBe(true);
      await button(wrapper, '保存').trigger('click');
      await flushPromises();
      expect(request.mock.calls.find(([call]) => call.path.endsWith('/saveTree'))![0].body).toMatchObject({
        id: 'existing',
        version: 3,
        orgScopeType: 'ORG',
        orgScopeId: 'org-a',
        effectiveFrom: '2026-10-01T09:00',
        enabled: true,
        segments: rule.segments.map((segment, index) => ({ ...segment, sortOrder: index })),
        sequencePolicy: rule.sequencePolicy,
      });
      expect(wrapper.find('.code-config__rules-table .ui-date-picker input').exists()).toBe(false);
      expect(wrapper.get('.code-config__rules-table').text()).toContain('采购中心');
      await wrapper.get('.code-config__rule-name').trigger('click');
      expect(wrapper.find('.code-config__editor .ui-date-picker input').exists()).toBe(false);
      expect(wrapper.find('.code-config__editor [role="switch"]').exists()).toBe(false);
      expect(wrapper.find('.code-config__editor').text()).not.toContain('适用范围');
    } finally {
      wrapper.unmount();
    }
  });

  it('retains rejected applicability changes and cancels them without writing the rule', async () => {
    const rule = { ...newCodeRule(staticTarget, staticTarget.fields[0]!), id: 'existing' };
    request.mockImplementation(async ({ path }) => {
      if (path.includes('/targets')) return [staticTarget];
      if (path.includes('/rules?')) return [rule];
      if (path.endsWith('/saveTree')) throw new Error('applicability conflict');
      return undefined;
    });
    const wrapper = wrapperOf();
    try {
      await flushPromises();
      await wrapper.get('.code-config__edit-usage').trigger('click');
      wrapper
        .get('.code-config__rules-table tbody tr')
        .findAllComponents(UiInput)[1]!
        .vm.$emit('update:value', '2027-01-01T00:00');
      await flushPromises();
      await button(wrapper, '保存').trigger('click');
      await flushPromises();
      expect(
        wrapper.get('.code-config__rules-table tbody tr').findAllComponents(UiInput)[1]!.props('value'),
      ).toBe('2027-01-01T00:00');
      expect(wrapper.find('[role="alert"]').exists()).toBe(true);
      await button(wrapper, '取消修改').trigger('click');
      expect(wrapper.find('.code-config__rules-table .ui-date-picker input').exists()).toBe(false);
      await wrapper.get('.code-config__edit-usage').trigger('click');
      expect(
        wrapper.get('.code-config__rules-table tbody tr').findAllComponents(UiInput)[1]!.props('value'),
      ).toBeUndefined();
    } finally {
      wrapper.unmount();
    }
  });

  it('retains the draft after a rejected save and blocks discarding edits by switching targets', async () => {
    const wrapper = wrapperOf();
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    const title = wrapper.findAllComponents(UiInput).find((input) => input.props('value') === '单据编码')!;
    title.vm.$emit('update:value', '待解决的冲突');
    await flushPromises();
    request.mockImplementation(async ({ path }) => {
      if (path.endsWith('/saveTree')) throw new Error('rejected');
      if (path.endsWith('/preview')) return { value: 'NO-0001', segments: [] };
      return [];
    });
    await button(wrapper, '保存规则').trigger('click');
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    expect(wrapper.findAllComponents(UiInput).some((input) => input.props('value') === '待解决的冲突')).toBe(
      true,
    );
    expect(wrapper.getComponent({ name: 'RecordExplorerPanel' }).props('refreshDisabled')).toBe(true);
    wrapper.unmount();
  });
  it('shows renumbering only for sourced formats and saves a valid rule after removing the source', async () => {
    const wrapper = wrapperOf();
    try {
      await flushPromises();
      await button(wrapper, '新增规则').trigger('click');
      const linked = () =>
        wrapper.findAllComponents(UiCheckbox).find((item) => item.text() === '修改编号来源后自动换号');
      expect(linked()).toBeUndefined();
      expect(button(wrapper, '旧号复用说明')).toBeDefined();
      expect(button(wrapper, '编号策略说明')).toBeUndefined();
      const format = () => wrapper.getComponent({ name: 'CodeRuleFormatEditor' });
      await format().findAllComponents(UiSelect)[0]!.vm.$emit('update:value', 'FIELD_VALUE');
      expect(linked()).toBeUndefined();
      const source = format()
        .findAllComponents(UiSelect)
        .find((item) => item.props('options')?.some((option) => option.value === 'title'))!;
      await source.vm.$emit('update:value', 'title');
      expect(linked()).toBeDefined();
      expect(button(wrapper, '自动换号说明')).toBeDefined();
      await linked()!.vm.$emit('update:checked', true);
      expect(wrapper.get('.code-config__policy summary').text()).toContain('来源变化时换号');
      await format().findAllComponents(UiSelect)[0]!.vm.$emit('update:value', 'CONSTANT');
      expect(linked()).toBeUndefined();
      expect(wrapper.get('.code-config__policy summary').text()).not.toContain('来源变化时换号');
      await button(wrapper, '保存规则').trigger('click');
      await flushPromises();
      expect(request.mock.calls.find(([call]) => call.path.endsWith('/saveTree'))![0].body).toMatchObject({
        linkedUpdate: false,
      });
    } finally {
      wrapper.unmount();
    }
  });
  it('keeps configured policies discoverable but collapsed and preserves them when saving', async () => {
    const rule = {
      ...newCodeRule(staticTarget, staticTarget.fields[0]!),
      id: 'existing',
      allowRecycle: true,
      linkedUpdate: true,
      effectiveFrom: '2026-01-01T00:00',
    };
    rule.segments[0] = { ...newCodeSegment('FIELD_VALUE'), sourceRef: 'title' };
    rule.sequencePolicy!.startValue = 20;
    rule.sequencePolicy!.stepValue = 3;
    rule.sequencePolicy!.maxValue = 999;
    rule.sequencePolicy!.overflowPolicy = 'RESET';
    rule.segments[1]!.length = 6;
    request.mockImplementation(async ({ path, body }) => {
      if (path.includes('/targets')) return [staticTarget];
      if (path.includes('/rules?')) return [rule];
      if (path.endsWith('/saveTree')) return body;
      return undefined;
    });
    const wrapper = wrapperOf();
    await flushPromises();
    try {
      expect(wrapper.find('.code-config__editor').exists()).toBe(false);
      await wrapper.get('.code-config__rule-name').trigger('click');
      expect(wrapper.findAll<HTMLDetailsElement>('details').every((details) => !details.element.open)).toBe(
        true,
      );
      expect(wrapper.get('.code-config__policy summary').text()).toContain('旧号可复用');
      expect(wrapper.get('.code-config__policy summary').text()).not.toContain('限时生效');
      expect(wrapper.find('.code-config__editor .ui-date-picker input').exists()).toBe(false);
      expect(wrapper.get('.code-format__sequence-advanced summary').text()).toContain('20');
      expect(wrapper.get('.code-format__sequence-advanced summary').text()).toContain('从起点重新尝试');
      expect(
        wrapper
          .findAll('.code-format__advanced summary')
          .some((summary) => summary.text().includes('长度覆盖 6 位')),
      ).toBe(true);
      const length = wrapper
        .findAllComponents(UiInput)
        .find((input) => input.element.closest('label')?.textContent?.trim() === '流水长度')!;
      await length.vm.$emit('update:value', 8);
      await flushPromises();
      expect(wrapper.findAll('.code-format__advanced summary')[1]!.text()).toContain('长度覆盖 6 位');
      expect(wrapper.findAll<HTMLDetailsElement>('details').every((details) => !details.element.open)).toBe(
        true,
      );
      expect(wrapper.getComponent({ name: 'RecordExplorerPanel' }).props('refreshDisabled')).toBe(true);
      await button(wrapper, '保存规则').trigger('click');
      await flushPromises();
      expect(request.mock.calls.find(([call]) => call.path.endsWith('/saveTree'))![0].body).toMatchObject({
        id: 'existing',
        allowRecycle: true,
        linkedUpdate: true,
        effectiveFrom: '2026-01-01T00:00',
        sequencePolicy: {
          sequenceLength: 8,
          startValue: 20,
          stepValue: 3,
          maxValue: 999,
          overflowPolicy: 'RESET',
        },
      });
      expect(request.mock.calls.find(([call]) => call.path.endsWith('/saveTree'))![0].body).toMatchObject({
        segments: [expect.anything(), expect.objectContaining({ length: 6 })],
      });
    } finally {
      wrapper.unmount();
    }
  });
  it('protects unsaved drawer edits and tenant selection until they are discarded', async () => {
    user.system = true;
    const wrapper = wrapperOf();
    await flushPromises();
    try {
      const tenant = wrapper.findComponent({ name: 'TenantScopeExplorer' });
      expect(wrapper.find('.code-config > header').exists()).toBe(false);
      expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);
      await button(wrapper, '新增规则').trigger('click');
      const title = wrapper.findAllComponents(UiInput).find((input) => input.props('value') === '单据编码')!;
      title.vm.$emit('update:value', '未保存草稿');
      await flushPromises();
      expect(tenant.props('disabled')).toBe(true);
      tenant.vm.$emit('select', { id: 'tenant-b' });
      await flushPromises();
      expect(tenant.props('selectedId')).toBe('tenant-a');
      const drawer = wrapper.findComponent({ name: 'RecordDetailDrawer' });
      expect(await drawer.props('beforeClose')()).toBe(false);
      expect(wrapper.text()).toContain('保存规则');
      vi.mocked(confirmAction).mockResolvedValueOnce(true);
      expect(await drawer.props('beforeClose')()).toBe(true);
      drawer.vm.$emit('close');
      await flushPromises();
      expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);
      expect(tenant.props('disabled')).toBe(false);
    } finally {
      wrapper.unmount();
    }
  });
});

it('trials from the configured start, supports an override and displays server segment results', async () => {
  const wrapper = wrapperOf();
  try {
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    const inputFor = (label: string) =>
      wrapper
        .findAll('label')
        .find((item) => item.text().startsWith(label))!
        .findComponent(UiInput);
    await inputFor('起始值').vm.$emit('update:value', '20');
    request.mockImplementation(async ({ path }) =>
      path.endsWith('/preview')
        ? {
            value: 'NO-0020',
            segments: [
              { segmentType: 'CONSTANT', value: 'NO-' },
              { segmentType: 'SEQUENCE', value: '0020' },
            ],
          }
        : undefined,
    );
    await button(wrapper, '刷新试算').trigger('click');
    await flushPromises();
    expect(
      request.mock.calls.filter(([call]) => call.path.endsWith('/preview')).at(-1)![0].body.sequenceValue,
    ).toBe(20);
    expect(wrapper.findAll('.code-config__preview-segments code').map((item) => item.text())).toEqual([
      'NO-',
      '0020',
    ]);
    await inputFor('试算流水值').vm.$emit('update:value', '32');
    expect(wrapper.find('output').exists()).toBe(false);
    await button(wrapper, '刷新试算').trigger('click');
    await flushPromises();
    expect(
      request.mock.calls.filter(([call]) => call.path.endsWith('/preview')).at(-1)![0].body.sequenceValue,
    ).toBe(32);
    await inputFor('试算流水值').vm.$emit('update:value', '');
    await button(wrapper, '刷新试算').trigger('click');
    await flushPromises();
    expect(
      request.mock.calls.filter(([call]) => call.path.endsWith('/preview')).at(-1)![0].body.sequenceValue,
    ).toBe(20);
  } finally {
    wrapper.unmount();
  }
});

it('preserves automatic drafts across mode changes and strips them only from manual save payloads', async () => {
  const wrapper = wrapperOf();
  try {
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    const label = (text: string) => wrapper.findAll('label').find((item) => item.text().startsWith(text))!;
    await label('固定文字').findComponent(UiInput).vm.$emit('update:value', 'ACC-');
    await label('起始值').findComponent(UiInput).vm.$emit('update:value', '20');
    const mode = () => label('编号方式').findComponent(UiSelect);
    await mode().vm.$emit('update:value', 'MANUAL');
    await mode().vm.$emit('update:value', 'AUTO');
    expect(label('固定文字').findComponent(UiInput).props('value')).toBe('ACC-');
    expect(label('起始值').findComponent(UiInput).props('value')).toBe(20);
    await mode().vm.$emit('update:value', 'MANUAL');
    await button(wrapper, '保存规则').trigger('click');
    await flushPromises();
    expect(request.mock.calls.find(([call]) => call.path.endsWith('/saveTree'))![0].body).toMatchObject({
      mode: 'MANUAL',
      segments: [],
      sequencePolicy: null,
      linkedUpdate: false,
    });
    expect(wrapper.text()).toContain('可在规则列表中启用');
  } finally {
    wrapper.unmount();
  }
});

it('keeps trial errors in the trial panel and clears them on input changes and mode changes', async () => {
  const wrapper = wrapperOf();
  try {
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    const trialInput = wrapper
      .findAll('label')
      .find((item) => item.text().startsWith('试算流水值'))!
      .findComponent(UiInput);
    await trialInput.vm.$emit('update:value', '-1');
    await button(wrapper, '刷新试算').trigger('click');
    await flushPromises();
    expect(wrapper.get('.code-config__trial .code-config__trial-error').text()).toContain('非负整数');
    expect(wrapper.findAll('.ant-alert')).toHaveLength(1);
    await trialInput.vm.$emit('update:value', '1');
    expect(wrapper.find('.code-config__trial-error').exists()).toBe(false);
    await trialInput.vm.$emit('update:value', '-1');
    await button(wrapper, '刷新试算').trigger('click');
    await flushPromises();
    await wrapper
      .findAll('label')
      .find((item) => item.text().startsWith('编号方式'))!
      .findComponent(UiSelect)
      .vm.$emit('update:value', 'MANUAL');
    expect(wrapper.find('.code-config__trial-error').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('非负整数');
  } finally {
    wrapper.unmount();
  }
});

it('uses typed trial controls and keeps false and zero in the preview context', async () => {
  const typedTarget = {
    ...staticTarget,
    sources: [
      { fieldName: 'active', title: '是否到货', type: 'Boolean' },
      { fieldName: 'amount', title: '金额', type: 'BigDecimal' },
      { fieldName: 'serial', title: '序号', type: 'Long' },
      { fieldName: 'date', title: '日期', type: 'LocalDate' },
      { fieldName: 'at', title: '时间', type: 'DATETIME' },
    ],
  };
  request.mockImplementation(async ({ path }) =>
    path.includes('/targets')
      ? [typedTarget]
      : path.includes('/rules?')
        ? []
        : path.endsWith('/preview')
          ? { value: 'NO-0001', segments: [] }
          : undefined,
  );
  const wrapper = wrapperOf();
  try {
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    await wrapper
      .findAllComponents(UiSelect)
      .find((select) => select.props('mode') === 'multiple')!
      .vm.$emit('update:value', ['active', 'amount', 'serial', 'date', 'at']);
    const label = (text: string) => wrapper.findAll('label').find((item) => item.text().startsWith(text))!;
    await label('是否到货').findComponent(UiSelect).vm.$emit('update:value', 'false');
    expect(label('金额').findComponent(UiInput).props('type')).toBe('number');
    expect(label('日期').findComponent(UiInput).props('type')).toBe('date');
    expect(label('时间').findComponent(UiInput).props('type')).toBe('datetime-local');
    await label('金额').findComponent(UiInput).vm.$emit('update:value', '0');
    await button(wrapper, '刷新试算').trigger('click');
    await flushPromises();
    expect(request.mock.calls.find(([call]) => call.path.endsWith('/preview'))![0].body.context).toEqual({
      active: false,
      amount: '0',
    });
    await label('金额').findComponent(UiInput).vm.$emit('update:value', '123456789012345678.12');
    await label('序号').findComponent(UiInput).vm.$emit('update:value', '9007199254740993');
    await button(wrapper, '刷新试算').trigger('click');
    await flushPromises();
    expect(
      request.mock.calls.filter(([call]) => call.path.endsWith('/preview')).at(-1)![0].body.context,
    ).toEqual({
      active: false,
      amount: '123456789012345678.12',
      serial: '9007199254740993',
    });
  } finally {
    wrapper.unmount();
  }
});

it('automatically previews on open and debounces relevant edits without re-previewing title changes', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const wrapper = wrapperOf();
  try {
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    const previews = () => request.mock.calls.filter(([call]) => call.path.endsWith('/preview'));
    await vi.advanceTimersByTimeAsync(499);
    expect(previews()).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(1);
    expect(previews()).toHaveLength(1);
    expect(wrapper.get('output strong').text()).toBe('NO-0001');
    await button(wrapper, '新增规则').trigger('click');
    await vi.advanceTimersByTimeAsync(600);
    expect(previews()).toHaveLength(1);
    expect(wrapper.get('output strong').text()).toBe('NO-0001');
    const prefix = wrapper
      .findAll('label')
      .find((item) => item.text().startsWith('固定文字'))!
      .findComponent(UiInput);
    await prefix.vm.$emit('update:value', 'A-');
    await vi.advanceTimersByTimeAsync(300);
    await prefix.vm.$emit('update:value', 'AB-');
    await vi.advanceTimersByTimeAsync(300);
    await prefix.vm.$emit('update:value', 'ABC-');
    await vi.advanceTimersByTimeAsync(499);
    expect(previews()).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(previews()).toHaveLength(2);
    expect(previews()[1]![0].body.rule.segments[0].fixedValue).toBe('ABC-');
    const name = wrapper
      .findAll('label')
      .find((item) => item.text().startsWith('规则名称'))!
      .findComponent(UiInput);
    await name.vm.$emit('update:value', '修改名称');
    await vi.advanceTimersByTimeAsync(600);
    expect(previews()).toHaveLength(2);
    await prefix.vm.$emit('update:value', 'PENDING-');
    await button(wrapper, '取消修改').trigger('click');
    await vi.advanceTimersByTimeAsync(600);
    expect(previews()).toHaveLength(2);
  } finally {
    wrapper.unmount();
    vi.useRealTimers();
  }
});

it('keeps editing enabled during automatic requests, aborts stale work and ignores late responses', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const wrapper = wrapperOf();
  try {
    await flushPromises();
    const base = request.getMockImplementation()!;
    const pending: Array<{ signal: AbortSignal; resolve: (value: unknown) => void }> = [];
    request.mockImplementation((call) =>
      call.path.endsWith('/preview')
        ? new Promise((resolve) => pending.push({ signal: call.signal, resolve }))
        : base(call),
    );
    await button(wrapper, '新增规则').trigger('click');
    await vi.advanceTimersByTimeAsync(500);
    expect(pending).toHaveLength(1);
    const prefix = wrapper
      .findAll('label')
      .find((item) => item.text().startsWith('固定文字'))!
      .findComponent(UiInput);
    expect(prefix.props('disabled')).toBe(false);
    expect(button(wrapper, '保存规则').attributes('disabled')).toBeUndefined();
    await prefix.vm.$emit('update:value', 'NEW-');
    expect(pending[0]!.signal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(500);
    expect(pending).toHaveLength(2);
    pending[1]!.resolve({ value: 'NEW-0001', segments: [] });
    await flushPromises();
    pending[0]!.resolve({ value: 'STALE-0001', segments: [] });
    await flushPromises();
    expect(wrapper.get('output strong').text()).toBe('NEW-0001');
    await prefix.vm.$emit('update:value', 'LATER-');
    await vi.advanceTimersByTimeAsync(500);
    wrapper.unmount();
    expect(pending[2]!.signal.aborted).toBe(true);
    pending[2]!.resolve({ value: 'AFTER-UNMOUNT', segments: [] });
    await flushPromises();
  } finally {
    if (wrapper.exists()) wrapper.unmount();
    vi.useRealTimers();
  }
});

it('does not automatically preview a manual draft or when preview permission is missing', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const wrapper = wrapperOf();
  try {
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    const mode = wrapper
      .findAll('label')
      .find((item) => item.text().startsWith('编号方式'))!
      .findComponent(UiSelect);
    await mode.vm.$emit('update:value', 'MANUAL');
    await vi.advanceTimersByTimeAsync(600);
    expect(request.mock.calls.some(([call]) => call.path.endsWith('/preview'))).toBe(false);
    can.mockImplementation((action) => action !== 'preview');
    await mode.vm.$emit('update:value', 'AUTO');
    await vi.advanceTimersByTimeAsync(600);
    expect(request.mock.calls.some(([call]) => call.path.endsWith('/preview'))).toBe(false);
  } finally {
    wrapper.unmount();
    vi.useRealTimers();
  }
});
