import { describe, expect, it, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';
import CodeConfigurationView from '@/views/CodeConfigurationView.vue';
import { UiInput, UiSelect } from '@muyun/vue-ui-antdv';
import { newCodeRule, type CodeTarget } from '@/views/codeConfiguration';
const { request, can, user } = vi.hoisted(() => ({
  request: vi.fn(),
  can: vi.fn(() => true),
  user: { tenantId: 'tenant-a', system: false },
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
const staticTarget: CodeTarget = {
  moduleAlias: 'sales.invoice',
  entityAlias: 'invoice',
  title: '单据',
  source: 'STATIC',
  fields: [{ fieldName: 'documentNo', title: '单据编码', role: 'PRIMARY', trigger: 'SAVE' }],
  sources: [{ fieldName: 'title', title: '名称', type: 'STRING' }],
};
const wrapperOf = () =>
  mount(CodeConfigurationView, {
    props: { moduleAlias: 'sales.invoice', embedded: true },
    global: { stubs: { ModuleRuntimeActivationStatus: true, RecordPicker: true } },
  });
const button = (wrapper: ReturnType<typeof wrapperOf>, title: string) =>
  wrapper.findAll('button').find((item) => item.text().replace(/\s+/g, '') === title)!;
beforeEach(() => {
  user.tenantId = 'tenant-a';
  user.system = false;
  request.mockReset();
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
    wrapper.findComponent({ name: 'RecordPicker' }).vm.$emit('update:value', 'tenant-a');
    await flushPromises();
    expect(request.mock.calls.some(([call]) => call.path.includes('/targets'))).toBe(true);
    expect(wrapper.text()).not.toContain('字段角色和生成时机由业务模型预设');
    expect(wrapper.find('button[aria-label="字段接入说明"]').exists()).toBe(true);
    wrapper.unmount();
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
    expect(wrapper.find('button[aria-label="字段接入说明"]').exists()).toBe(true);
    await button(wrapper, '新增规则').trigger('click');
    expect(wrapper.findAllComponents(UiInput).some((input) => input.props('value') === 'NO-')).toBe(true);
    await button(wrapper, '试算').trigger('click');
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
  it('keeps unused sources out of trial inputs and clears a result when the rule changes', async () => {
    const wrapper = wrapperOf();
    await flushPromises();
    await button(wrapper, '新增规则').trigger('click');
    expect(wrapper.findAll('label').some((label) => label.text() === '名称')).toBe(false);
    const { UiSelect } = await import('@muyun/vue-ui-antdv');
    const extra = wrapper.findAllComponents(UiSelect).find((select) => select.props('mode') === 'multiple')!;
    extra.vm.$emit('update:value', ['title']);
    await flushPromises();
    expect(wrapper.findAll('label').some((label) => label.text() === '名称')).toBe(true);
    await button(wrapper, '试算').trigger('click');
    await flushPromises();
    expect(wrapper.find('output').exists()).toBe(true);
    const name = wrapper.findAllComponents(UiInput).find((input) => input.props('value') === '单据编码')!;
    name.vm.$emit('update:value', '修改后的规则');
    await flushPromises();
    expect(wrapper.find('output').exists()).toBe(false);
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
    await button(wrapper, '试算').trigger('click');
    await wrapper.setProps({ moduleAlias: 'sales.other' });
    await flushPromises();
    resolve({ value: 'STALE', segments: [] });
    await flushPromises();
    expect(wrapper.find('output').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('STALE');
    wrapper.unmount();
  });
  it('requires a dynamic field binding before allowing a rule to be created', async () => {
    const dynamic = {
      ...staticTarget,
      source: 'DYNAMIC',
      relationId: 'relation',
      fields: [{ fieldName: 'documentNo', title: '单据编码', metadataFieldId: 'field' }],
    };
    request.mockImplementation(async ({ path, body }) => {
      if (path.includes('/targets')) return [dynamic];
      if (path.includes('/rules?')) return [];
      if (path.endsWith('/binding'))
        return {
          ...dynamic,
          fields: [{ ...dynamic.fields[0], role: body.role, trigger: body.trigger, bindingVersion: 1 }],
        };
      return undefined;
    });
    const wrapper = wrapperOf();
    await flushPromises();
    expect(button(wrapper, '新增规则')).toBeUndefined();
    expect(wrapper.get<HTMLDetailsElement>('.code-config__binding').element.open).toBe(true);
    expect(wrapper.get('.code-config__binding summary').text()).toContain('未接入编码');
    await button(wrapper, '接入编码').trigger('click');
    await flushPromises();
    expect(request.mock.calls.find(([call]) => call.path.endsWith('/binding'))![0].body).toMatchObject({
      role: 'NORMAL',
      trigger: 'SAVE',
      expectedVersion: null,
    });
    expect(button(wrapper, '新增规则')).toBeDefined();
    expect(wrapper.get<HTMLDetailsElement>('.code-config__binding').element.open).toBe(false);
    expect(wrapper.get('.code-config__binding summary').text()).not.toContain('未接入编码');
    wrapper.unmount();
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
      return [];
    });
    await button(wrapper, '保存规则').trigger('click');
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    expect(wrapper.findAllComponents(UiInput).some((input) => input.props('value') === '待解决的冲突')).toBe(
      true,
    );
    expect(button(wrapper, '刷新').attributes('disabled')).toBeDefined();
    wrapper.unmount();
  });
  it('keeps configured policies discoverable but collapsed and preserves them when saving', async () => {
    const rule = {
      ...newCodeRule(staticTarget, staticTarget.fields[0]!),
      id: 'existing',
      allowRecycle: true,
      linkedUpdate: true,
      effectiveFrom: '2026-01-01T00:00',
    };
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
      expect(wrapper.findAll<HTMLDetailsElement>('details').every((details) => !details.element.open)).toBe(
        true,
      );
      expect(wrapper.get('.code-config__policy summary').text()).toContain('旧号可复用');
      expect(wrapper.get('.code-config__policy summary').text()).toContain('限时生效');
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
      expect(button(wrapper, '刷新').attributes('disabled')).toBeDefined();
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
});
