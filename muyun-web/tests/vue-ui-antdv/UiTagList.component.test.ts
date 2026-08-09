import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import UiTagList from '@/vue-ui-antdv/components/UiTagList.vue';

describe('UiTagList', () => {
  it('renders visible tags and an overflow indicator', () => {
    const wrapper = mount(UiTagList, {
      props: {
        items: [
          { key: 'a', label: '研发' },
          { key: 'b', label: '平台' },
          { key: 'c', label: '管理' },
        ],
        maxVisible: 2,
      },
    });

    expect(wrapper.text()).toContain('研发');
    expect(wrapper.text()).toContain('平台');
    expect(wrapper.text()).not.toContain('管理');
    expect(wrapper.get('.ui-tag-list-overflow').text()).toBe('+1');
  });

  it('renders the empty visual state when no tags are available', () => {
    const wrapper = mount(UiTagList, { props: { items: [], emptyText: '暂无标签' } });

    expect(wrapper.get('.ui-tag-list-empty').text()).toBe('暂无标签');
  });
});
