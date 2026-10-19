import { describe, expect, it, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';
import CodeConfigurationView from '@/views/CodeConfigurationView.vue';
import { UiInput } from '@muyun/vue-ui-antdv';
import type { CodeTarget } from '@/views/codeConfiguration';
const { request, can } = vi.hoisted(() => ({ request: vi.fn(), can: vi.fn(() => true) }));
vi.mock('@muyun/web-core', async (original) => ({
  ...(await original<typeof import('@muyun/web-core')>()),
  useModuleContext: () => ({ http: { request }, can }),
}));
vi.mock('@/platform-admin-runtime/currentUserContext', () => ({
  useCurrentUserContext: () => ref({ tenantId: 'tenant-a', system: false }),
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
  it('edits a static API-only declaration, trials without saving, then persists the full rule tree', async () => {
    const wrapper = wrapperOf();
    await flushPromises();
    expect(wrapper.text()).toContain('字段角色和触发方式由 Java 模型声明');
    await button(wrapper, '新增规则').trigger('click');
    expect(wrapper.findAllComponents(UiInput).some((input) => input.props('value') === 'NO-')).toBe(true);
    await button(wrapper, '试算').trigger('click');
    await flushPromises();
    expect(wrapper.find('output').text()).toBe('NO-0001');
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
    await button(wrapper, '接入编码').trigger('click');
    await flushPromises();
    expect(request.mock.calls.find(([call]) => call.path.endsWith('/binding'))![0].body).toMatchObject({
      role: 'NORMAL',
      trigger: 'SAVE',
      expectedVersion: null,
    });
    expect(button(wrapper, '新增规则')).toBeDefined();
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
});
