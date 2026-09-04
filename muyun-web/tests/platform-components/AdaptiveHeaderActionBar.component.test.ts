import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AdaptiveHeaderActionBar from '@/platform-components/AdaptiveHeaderActionBar.vue';

describe('AdaptiveHeaderActionBar', () => {
  afterEach(() => vi.restoreAllMocks());

  it('configures 更多 as a platform hover dropdown', async () => {
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(60);
    const wrapper = mount(AdaptiveHeaderActionBar, {
      attachTo: document.body,
      props: {
        actions: [
          { key: 'create', title: '新建', level: 'primary' },
          { key: 'edit', title: '编辑', level: 'standard' },
        ],
      },
    });

    await flushPromises();
    await flushPromises();

    expect(wrapper.findComponent({ name: 'UiDropdown' }).props('trigger')).toBe('hover');
  });
});
