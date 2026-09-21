import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
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
      context: {} as never,
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
