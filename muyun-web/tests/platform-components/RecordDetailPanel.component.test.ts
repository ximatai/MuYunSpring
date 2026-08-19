import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordDetailPanel from '@/platform-components/RecordDetailPanel.vue';

it('keeps caller class and style on its root region for scoped parent layouts', () => {
  const wrapper = mount(RecordDetailPanel, {
    attrs: { class: 'consumer-detail-card', style: 'min-width: 640px' },
    props: { title: '详情' },
  });

  expect(wrapper.classes()).toContain('record-detail-panel-region');
  expect(wrapper.classes()).toContain('consumer-detail-card');
  expect(wrapper.attributes('style')).toContain('min-width: 640px');
  expect(wrapper.find('.record-detail-layout').classes()).not.toContain('consumer-detail-card');
});
