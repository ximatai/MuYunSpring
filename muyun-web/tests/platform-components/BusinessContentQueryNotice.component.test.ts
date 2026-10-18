import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import BusinessContentQueryNotice from '@/platform-components/BusinessContentQueryNotice.vue';

it('keeps completeness risk visible while hiding low-frequency explanation and generation entry by default', () => {
  const wrapper = mount(BusinessContentQueryNotice, {
    props: { status: { language: 'en', missingRecords: 2, readings: {} } },
  });
  expect(wrapper.get('summary').text()).toContain('2');
  expect(wrapper.get('summary').text()).toContain('English');
  expect(wrapper.get('summary').text()).toContain('查询可能不完整');
  expect(wrapper.get('details').attributes('open')).toBeUndefined();
  expect(wrapper.get('p').text()).toContain('文本筛选前的业务范围');
});

it('does not display translation administration for a scope with complete readings', () => {
  const wrapper = mount(BusinessContentQueryNotice, {
    props: {
      status: {
        language: 'zh-CN',
        missingRecords: 0,
        readings: {},
        generationScope: { moduleAlias: 'demo.product' },
      },
    },
  });
  expect(wrapper.find('details').exists()).toBe(false);
  expect(wrapper.findComponent({ name: 'BusinessContentGenerationPanel' }).exists()).toBe(false);
});
