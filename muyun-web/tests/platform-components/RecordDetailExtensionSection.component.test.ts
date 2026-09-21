import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordDetailExtensionSection from '@/platform-components/RecordDetailExtensionSection.vue';

it('renders a plain subtitle in the platform-owned section heading', () => {
  const wrapper = mount(RecordDetailExtensionSection, {
    props: { title: '设备状态', subtitle: '采集于 09:13:26' },
    slots: { default: '<span>在线</span>' },
  });

  expect(wrapper.find('h3').text()).toBe('设备状态');
  expect(wrapper.find('.record-content-section-heading__subtitle').text()).toBe('采集于 09:13:26');
  expect(wrapper.find('.record-detail-extension-section-content').text()).toBe('在线');
});

it('lets a reactive business component own subtitle content without replacing the heading', () => {
  const wrapper = mount(RecordDetailExtensionSection, {
    props: { title: '设备状态', subtitle: 'fallback' },
    slots: { subtitle: '<span data-testid="runtime-subtitle">采集于 09:15:58</span>' },
  });

  expect(wrapper.get('[data-testid="runtime-subtitle"]').text()).toBe('采集于 09:15:58');
  expect(wrapper.find('.record-content-section-heading__subtitle').text()).not.toContain('fallback');
});
