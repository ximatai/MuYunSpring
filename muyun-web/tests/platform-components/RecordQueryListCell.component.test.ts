import { shallowMount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordQueryListCell from '@/platform-components/RecordQueryListCell.vue';

it('renders descriptor-derived list types through the shared cell semantics', async () => {
  const wrapper = shallowMount(RecordQueryListCell, {
    props: {
      record: {
        enabled: false,
        published: true,
        tags: [{ id: 'a', title: '平台', color: '#1677FF' }],
        updatedAt: '2026-08-30T09:30:00+08:00',
        fileSize: 1024,
        color: '#1677FF',
      },
      column: { key: 'enabled', title: '启用状态', type: 'enabledStatus' },
    },
  });

  expect(wrapper.findComponent({ name: 'RecordStatusTag' }).props()).toMatchObject({ enabled: false });

  await wrapper.setProps({
    column: {
      key: 'published',
      title: '发布状态',
      type: 'booleanStatus',
      booleanStatus: { trueLabel: '已发布', falseLabel: '未发布', trueTone: 'SUCCESS' },
    },
  });
  expect(wrapper.findComponent({ name: 'RecordStatusTag' }).props()).toMatchObject({
    enabled: true,
    enabledLabel: '已发布',
    disabledLabel: '未发布',
  });

  await wrapper.setProps({ column: { key: 'tags', title: '标签', type: 'tagList' } });
  expect(wrapper.findComponent({ name: 'RecordTagList' }).props('items')).toEqual([
    { id: 'a', title: '平台', color: '#1677FF' },
  ]);

  await wrapper.setProps({ column: { key: 'updatedAt', title: '更新时间', type: 'datetime' } });
  expect(wrapper.findComponent({ name: 'DateTimeText' }).props('value')).toBe('2026-08-30T09:30:00+08:00');

  await wrapper.setProps({ column: { key: 'fileSize', title: '大小', type: 'fileSize' } });
  expect(wrapper.findComponent({ name: 'FileSizeText' }).props('value')).toBe(1024);
});

it('preserves text, option title, custom rendering, color and multiline semantics', async () => {
  const wrapper = shallowMount(RecordQueryListCell, {
    props: {
      record: { ownerId: 'owner-1', ownerTitle: '平台管理员', color: '#1677FF', summary: '默认值' },
      column: { key: 'ownerId', title: '负责人', titleField: 'ownerTitle' },
      cellRenderers: { summary: () => '外部渲染值' },
    },
  });

  expect(wrapper.find('.record-query-list-text').text()).toBe('平台管理员');

  await wrapper.setProps({
    record: { ownerId: 'owner-1', ownerIdTitle: '教学管理员', color: '#1677FF', summary: '默认值' },
    column: { key: 'ownerId', title: '负责人', titleField: 'legacyOwnerTitle' },
  });
  expect(wrapper.find('.record-query-list-text').text()).toBe('教学管理员');

  await wrapper.setProps({
    record: { attendanceStatus: 'ATTENDED', color: '#1677FF', summary: '默认值' },
    column: {
      key: 'attendanceStatus',
      title: '参加状态',
      optionItems: [{ code: 'ATTENDED', title: '已参加', enabled: true }],
    },
  });
  expect(wrapper.find('.record-query-list-text').text()).toBe('已参加');

  await wrapper.setProps({
    record: { attendanceStatuses: '["ATTENDED","ABSENT"]' },
    column: {
      key: 'attendanceStatuses',
      title: '参加状态',
      optionItems: [
        { code: 'ATTENDED', title: '已参加', enabled: true },
        { code: 'ABSENT', title: '缺考', enabled: true },
      ],
    },
  });
  expect(wrapper.find('.record-query-list-text').text()).toBe('已参加、缺考');

  await wrapper.setProps({ record: { attendanceStatuses: ['ABSENT', 'ATTENDED'] } });
  expect(wrapper.find('.record-query-list-text').text()).toBe('缺考、已参加');

  await wrapper.setProps({ column: { key: 'summary', title: '摘要', maxDisplayLines: 3 } });
  expect(wrapper.find('.record-query-list-text').text()).toBe('外部渲染值');
  expect(wrapper.find('.record-query-list-text').attributes('style')).toContain(
    '--record-query-list-max-lines: 3',
  );

  await wrapper.setProps({
    record: { color: '#1677FF' },
    column: { key: 'color', title: '颜色', type: 'colorPicker' },
  });
  expect(wrapper.find('.record-query-list-color').text()).toContain('#1677FF');
  expect(wrapper.find('.record-query-list-color i').attributes('style')).toContain(
    'background-color: rgb(22, 119, 255)',
  );
});

it('renders reference summaries in stored ID order without affecting dictionary rendering', async () => {
  const wrapper = shallowMount(RecordQueryListCell, {
    props: {
      record: {
        reviewerIds: ['reviewer-2', 'reviewer-1', 'reviewer-missing'],
        reviewerSummaries: [
          { id: 'reviewer-1', title: '王华' },
          { id: 'reviewer-2', title: '李明', unavailable: true },
        ],
      },
      column: {
        key: 'reviewerIds',
        title: '评审人',
        titleField: 'reviewerSummaries',
        reference: { targetModuleAlias: 'iam.user', cardinality: 'MANY' },
      },
    },
  });

  expect(wrapper.find('.record-query-list-text').text()).toBe('李明（不可用）、王华、reviewer-missing');
  await wrapper.setProps({
    record: { reviewerIds: [], reviewerSummaries: [{ id: 'reviewer-1', title: '旧摘要' }] },
  });
  expect(wrapper.find('.record-query-list-text').text()).toBe('');
});

it('uses the owning provider for option labels and never writes display text into record values', async () => {
  const { mount } = await import('@vue/test-utils');
  const { defineComponent, h } = await import('vue');
  const { createLanguageContext, provideLanguageContext, platformLanguageContext } =
    await import('@muyun/web-core');
  const language = createLanguageContext({ languageTag: 'en' });
  const record = { status: 'ATTENDED', statusTitle: '已参加' };
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () =>
        h(RecordQueryListCell, {
          record,
          column: {
            key: 'status',
            title: '状态',
            optionItems: [
              {
                code: 'ATTENDED',
                title: '已参加',
                enabled: true,
                titleText: {
                  source: {
                    sourceKind: 'CODE',
                    sourceId: 'test/status',
                    sourceVersion: 'v1',
                    originalText: '已参加',
                  },
                  languages: {
                    en: {
                      text: 'Attended',
                      requestedTag: 'en',
                      resourceTag: 'en',
                      missing: false,
                      fallback: false,
                    },
                  },
                },
              },
            ],
          },
        });
    },
  });
  const wrapper = mount(Host);
  try {
    expect(wrapper.text()).toBe('Attended');
    expect(platformLanguageContext.languageTag.value).toBe('zh-CN');
    language.setLanguage('zh-CN');
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toBe('已参加');
    expect(record).toEqual({ status: 'ATTENDED', statusTitle: '已参加' });
  } finally {
    wrapper.unmount();
  }
});

it('switches delivered configuration cell labels in the owning language without replacing record values', async () => {
  const { mount } = await import('@vue/test-utils');
  const { defineComponent, h } = await import('vue');
  const { createLanguageContext, provideLanguageContext } = await import('@muyun/web-core');
  const language = createLanguageContext({ languageTag: 'en' });
  const record = {
    id: 'configuration',
    title: '配置名称',
    $texts: {
      title: {
        source: {
          sourceKind: 'CONFIG',
          sourceId: 'configuration/title',
          sourceVersion: 'v1',
          originalText: '配置名称',
        },
        languages: {
          en: {
            text: 'Configuration name',
            requestedTag: 'en',
            resourceTag: 'en',
            missing: false,
            fallback: false,
          },
        },
      },
    },
  };
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(RecordQueryListCell, { record, column: { key: 'title', title: 'Name' } });
    },
  });
  const wrapper = mount(Host);
  try {
    expect(wrapper.text()).toBe('Configuration name');
    language.setLanguage('zh-CN');
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toBe('配置名称');
    language.setLanguage('en');
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toBe('Configuration name');
    expect(record.title).toBe('配置名称');
  } finally {
    wrapper.unmount();
  }
});

it('shows a reading projection while preserving the raw record and explicit custom renderers', async () => {
  const raw = Object.freeze({ id: 'equipment', title: '设备原名' });
  const wrapper = shallowMount(RecordQueryListCell, {
    props: { record: raw, column: { key: 'title', title: '名称' }, reading: 'Equipment' },
  });
  expect(wrapper.text()).toBe('Equipment');
  expect(wrapper.find('.record-query-list-text').attributes('title')).toContain('设备原名');
  expect(raw.title).toBe('设备原名');
  await wrapper.setProps({ cellRenderers: { title: () => 'Special label' } });
  expect(wrapper.text()).toBe('Special label');
  await wrapper.setProps({ cellRenderers: {}, reading: undefined });
  expect(wrapper.text()).toBe('设备原名');
});

it('keeps the original tooltip raw when the same record also carries a translated presentation snapshot', async () => {
  const { mount } = await import('@vue/test-utils');
  const { defineComponent, h, ref, nextTick } = await import('vue');
  const { createLanguageContext, provideLanguageContext, businessContentLanguageContext } =
    await import('@muyun/web-core');
  const language = createLanguageContext({ languageTag: 'en' });
  const record = Object.freeze({
    id: 'one',
    title: 'original source',
    $contentTexts: {
      title: { originalText: 'original source', readings: { en: 'Equipment', de: 'Gerät' } },
    },
  });
  const reading = ref('Equipment');
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () =>
          h(RecordQueryListCell, { record, column: { key: 'title', title: 'Name' }, reading: reading.value });
      },
    }),
  );
  try {
    expect(wrapper.text()).toBe('Equipment');
    expect(wrapper.find('.record-query-list-text').attributes('title')).toBe(
      'Reading translation; original: original source',
    );
    businessContentLanguageContext(language).setLanguage('de');
    reading.value = 'Gerät';
    await nextTick();
    expect(wrapper.text()).toBe('Gerät');
    expect(wrapper.find('.record-query-list-text').attributes('title')).toBe(
      'Reading translation; original: original source',
    );
    expect(record.title).toBe('original source');
  } finally {
    wrapper.unmount();
  }
});
