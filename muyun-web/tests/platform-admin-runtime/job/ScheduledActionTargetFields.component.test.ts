import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, reactive } from 'vue';
import { expect, it, vi } from 'vitest';
import { createLanguageContext, provideLanguageContext, provideModuleContextConfig } from '@muyun/web-core';
import { ReferencePicker } from '@muyun/platform-components';
import type { ModulePageFormContributionContext } from '@muyun/dynamic-page-runtime';
import ScheduledActionTargetFields from '@/platform-admin-runtime/job/ScheduledActionTargetFields.vue';

const targets = [
  {
    moduleAlias: 'test.one',
    moduleTitle: 'First module',
    actionCode: 'run',
    actionTitle: 'Run',
    source: 'static',
    parametersRequired: true,
  },
  {
    moduleAlias: 'test.two',
    moduleTitle: 'Second module',
    actionCode: 'sync',
    actionTitle: 'Sync',
    source: 'dynamic',
    parametersRequired: null,
  },
];
function setup(
  request = vi.fn().mockResolvedValue(targets),
  draft: Record<string, unknown> = { moduleAlias: 'test.one', actionCode: 'run' },
  stubPicker = true,
) {
  const language = createLanguageContext({ languageTag: 'en' });
  const context = reactive({
    mode: 'edit',
    draft,
    formSessionKey: 1,
    fields: ['moduleAlias', 'actionCode'].map((fieldName) => ({
      fieldName,
      label: fieldName,
      readOnly: false,
    })),
    setField: vi.fn((field: string, value: unknown) => {
      context.draft[field] = value;
    }),
    reportValidity: vi.fn(),
  });
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        provideModuleContextConfig({ httpFactory: () => ({ request }) });
        return () =>
          h(ScheduledActionTargetFields, {
            context: context as unknown as ModulePageFormContributionContext,
          });
      },
    }),
    {
      global: {
        stubs: {
          ReferencePicker: stubPicker,
          UiSelect: { name: 'UiSelect', props: ['options', 'value', 'disabled'], template: '<div />' },
          UiModal: true,
        },
      },
    },
  );
  return { wrapper, context, request, language, pickers: () => wrapper.findAllComponents(ReferencePicker) };
}

it('searches localized modules and scoped action codes, clears action on module change and reports parameters', async () => {
  const { wrapper, context, pickers } = setup();
  await flushPromises();
  expect(context.reportValidity).toHaveBeenLastCalledWith({ valid: true, errors: {} });
  expect(wrapper.text()).toContain('This action requires parameters');
  pickers()[1]!.vm.$emit('validity-change', { valid: false, status: 'editing' });
  await flushPromises();
  expect(context.reportValidity.mock.lastCall?.[0].valid).toBe(false);
  pickers()[1]!.vm.$emit('validity-change', { valid: true, status: 'ready' });
  await flushPromises();
  expect(context.reportValidity.mock.lastCall?.[0].valid).toBe(true);
  const query = { keyword: 'first', pageNum: 1, pageSize: 20, scope: { selections: [] } };
  expect(await pickers()[0]!.props('provider').searchPage(query)).toMatchObject({
    total: 1,
    records: [{ id: 'test.one', title: 'First module' }],
  });
  expect(
    await pickers()[1]!
      .props('provider')
      .searchPage({ ...query, keyword: '' }),
  ).toMatchObject({ records: [{ id: 'run' }] });
  pickers()[0]!.vm.$emit('update:value', 'test.two');
  await flushPromises();
  expect(context.draft.moduleAlias).toBe('test.two');
  expect(context.draft.actionCode).toBeUndefined();
  expect(
    await pickers()[1]!
      .props('provider')
      .searchPage({ ...query, keyword: '' }),
  ).toMatchObject({ records: [{ id: 'sync' }] });
  expect(context.reportValidity.mock.lastCall?.[0].valid).toBe(false);
  pickers()[1]!.vm.$emit('update:value', 'sync');
  await flushPromises();
  expect(context.reportValidity.mock.lastCall?.[0].valid).toBe(true);
  wrapper.unmount();
});

it('resolves titles and cascades selections through the real ReferencePicker', async () => {
  const { wrapper, context, pickers } = setup(undefined, undefined, false);
  await flushPromises();
  const selects = () => wrapper.findAllComponents({ name: 'UiSelect' });
  expect(selects()[0]!.props('options')).toContainEqual({
    value: 'test.one',
    label: 'First module · test.one',
    disabled: false,
  });
  expect(selects()[1]!.props('options')).toContainEqual({
    value: 'run',
    label: 'Run · run · Static',
    disabled: false,
  });
  selects()[0]!.vm.$emit('update:value', 'test.two');
  await flushPromises();
  expect(context.draft).toEqual({ moduleAlias: 'test.two', actionCode: undefined });
  expect(selects()[1]!.props('options')).toEqual([
    { value: 'sync', label: 'Sync · sync · Dynamic', disabled: false },
  ]);
  selects()[1]!.vm.$emit('update:value', 'sync');
  await flushPromises();
  expect(context.reportValidity.mock.lastCall?.[0].valid).toBe(true);
  expect(pickers()).toHaveLength(2);
  wrapper.unmount();
});

it('retains invalid history, warns in both languages and respects readonly fields', async () => {
  const { wrapper, context, pickers, language } = setup(undefined, {
    moduleAlias: 'retired.module',
    actionCode: 'old',
  });
  await flushPromises();
  expect(await pickers()[0]!.props('provider').resolve(['retired.module'])).toEqual([
    { id: 'retired.module', title: 'retired.module', disabled: true, unavailable: true },
  ]);
  expect(context.draft.actionCode).toBe('old');
  expect(wrapper.text()).toContain('unavailable or unauthorized');
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(wrapper.text()).toContain('当前目标已失效或无权调度');
  context.fields[0]!.readOnly = true;
  await flushPromises();
  expect(pickers()[0]!.props('disabled')).toBe(true);
  expect(context.setField).not.toHaveBeenCalled();
  wrapper.unmount();
});

it('ignores stale tenant responses and blocks saving after a candidate load failure', async () => {
  let resolveOld!: (value: unknown) => void;
  const request = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    )
    .mockResolvedValueOnce([])
    .mockRejectedValueOnce(new Error('offline'));
  const { wrapper, context, pickers } = setup(request);
  context.draft.executionTenantId = 'tenant-b';
  await flushPromises();
  expect(request).toHaveBeenLastCalledWith({
    path: '/platform.scheduled_action_plan/scheduled-targets',
    query: { executionTenantId: 'tenant-b' },
  });
  resolveOld(targets);
  await flushPromises();
  expect(
    await pickers()[0]!
      .props('provider')
      .searchPage({
        keyword: '',
        pageNum: 1,
        pageSize: 20,
        scope: { selections: [] },
      }),
  ).toMatchObject({ total: 0 });
  context.draft.executionTenantId = 'tenant-c';
  await flushPromises();
  expect(wrapper.text()).toContain('Could not load targets');
  expect(context.reportValidity.mock.lastCall?.[0].valid).toBe(false);
  expect(pickers()[0]!.props('disabled')).toBe(true);
  wrapper.unmount();
});
