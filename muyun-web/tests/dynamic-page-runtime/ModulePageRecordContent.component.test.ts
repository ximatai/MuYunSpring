import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import ModulePageRecordContent from '@/dynamic-page-runtime/ModulePageRecordContent.vue';

it('passes the same read-only detail context to extension subtitle and body components', () => {
  const Subtitle = defineComponent({
    props: { context: { type: Object, required: true } },
    template:
      '<span data-testid="detail-subtitle">{{ context.record.title }} · {{ context.refreshKey }}</span>',
  });
  const Body = defineComponent({
    props: { context: { type: Object, required: true } },
    template: '<span data-testid="detail-body">{{ context.record.id }}</span>',
  });
  const record = { id: 'device-1', title: '设备一号', version: 1 };
  const detailContext = {
    module: {} as never,
    record,
    refreshList: vi.fn(),
    refreshKey: 7,
    reload: vi.fn(),
  };
  const detailSectionContext = vi.fn(() => detailContext);

  const wrapper = mount(ModulePageRecordContent, {
    props: {
      context: { abilities: { has: () => false } } as never,
      mode: 'view',
      record,
      selectedRecord: record,
      detailDisplayFields: new Map(),
      formFields: new Map(),
      formSessionKey: 0,
      validationRequestKey: 0,
      pickerConfigs: {},
      relations: [],
      relationsAvailable: false,
      relationReloadKey: 0,
      showSystemInfo: false,
      extensionSections: [
        {
          key: 'device-status',
          title: '设备状态',
          subtitle: 'fallback',
          subtitleComponent: Subtitle,
          component: Body,
        },
      ],
      detailSectionContext,
    },
    global: {
      stubs: {
        RecordDetailFields: true,
        RecordFormSurface: true,
        ModulePageDetailRelations: true,
        RecordMetaSection: true,
      },
    },
  });

  expect(wrapper.get('h3').text()).toBe('设备状态');
  expect(wrapper.get('[data-testid="detail-subtitle"]').text()).toBe('设备一号 · 7');
  expect(wrapper.get('[data-testid="detail-body"]').text()).toBe('device-1');
  expect(wrapper.find('.record-content-section-heading__subtitle').text()).not.toContain('fallback');
  expect(detailSectionContext).toHaveBeenCalledTimes(2);
  expect(detailSectionContext).toHaveBeenNthCalledWith(1, record);
  expect(detailSectionContext).toHaveBeenNthCalledWith(2, record);
});

it('forwards approval interaction and departure guard through the standard detail content', async () => {
  const mayLeave = vi.fn(async () => false);
  const Panel = defineComponent({
    emits: ['interaction-change'],
    setup(_, { emit, expose }) {
      expose({ mayLeave });
      return { open: () => emit('interaction-change', { editing: true, dirty: true, busy: false }) };
    },
    template: '<button @click="open">办理审批</button>',
  });
  const wrapper = mount(ModulePageRecordContent, {
    props: {
      context: { abilities: { has: () => true } } as never,
      mode: 'view',
      record: { id: 'record' },
      detailDisplayFields: new Map(),
      formFields: new Map(),
      formSessionKey: 0,
      validationRequestKey: 0,
      pickerConfigs: {},
      relations: [],
      relationsAvailable: false,
      relationReloadKey: 0,
      showSystemInfo: false,
      extensionSections: [],
      detailSectionContext: () => ({ reload: vi.fn() }) as never,
    },
    global: {
      stubs: {
        WorkflowRecordPanel: Panel,
        RecordDetailFields: true,
        ModulePageDetailRelations: true,
        RecordMetaSection: true,
      },
    },
  });
  await wrapper.get('button').trigger('click');
  expect(wrapper.emitted('workflow-interaction-change')).toEqual([
    [{ editing: true, dirty: true, busy: false }],
  ]);
  expect(await (wrapper.vm as unknown as { mayLeave: () => Promise<boolean> }).mayLeave()).toBe(false);
  expect(mayLeave).toHaveBeenCalledOnce();
});

it('projects declared detail section headings without replacing their business body or translating literal titles', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const Body = defineComponent({ props: ['context'], template: '<p>{{ context.record.title }}</p>' });
  const record = { id: 'original-id', title: '业务原名' };
  const context = { module: {} as never, record, refreshList: vi.fn(), refreshKey: 1, reload: vi.fn() };
  const props = {
    context: { abilities: { has: () => false } } as never,
    mode: 'view' as const,
    record,
    detailDisplayFields: new Map(),
    formFields: new Map(),
    formSessionKey: 1,
    validationRequestKey: 0,
    pickerConfigs: {},
    relations: [],
    relationsAvailable: false,
    relationReloadKey: 0,
    showSystemInfo: false,
    extensionSections: [
      {
        key: 'declared',
        title: '已开通应用',
        titleKey: 'platform.entry.provisioned-applications',
        component: Body,
      },
      { key: 'business', title: '已开通应用', component: Body },
    ],
    detailSectionContext: () => context,
  };
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(ModulePageRecordContent, props);
      },
    }),
    {
      global: {
        stubs: {
          RecordDetailFields: true,
          RecordFormSurface: true,
          ModulePageDetailRelations: true,
          RecordMetaSection: true,
        },
      },
    },
  );
  const body = wrapper.findComponent(Body).vm;
  expect(wrapper.findAll('h3').map((heading) => heading.text())).toEqual([
    'Provisioned applications',
    '已开通应用',
  ]);
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(wrapper.findAll('h3')[0]!.text()).toBe('已开通应用');
  language.setLanguage('en');
  await flushPromises();
  expect(wrapper.findAll('h3')[0]!.text()).toBe('Provisioned applications');
  expect(wrapper.findComponent(Body).vm).toBe(body);
  expect(record.title).toBe('业务原名');
  expect(wrapper.text()).toContain('业务原名');
  expect(context.reload).not.toHaveBeenCalled();
  wrapper.unmount();
});
