import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import RecordDetailDrawer from '../../src/platform-components/RecordDetailDrawer.vue';

describe('RecordDetailDrawer', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('mounts Ant Design Drawer into the root DOM supplied by its owning page', () => {
    const container = document.createElement('section');
    document.body.append(container);
    const wrapper = mount(RecordDetailDrawer, {
      props: { open: true, title: '职员详情', container },
      global: {
        stubs: {
          ADrawer: {
            name: 'ADrawer',
            props: ['getContainer'],
            template: '<section><slot /></section>',
          },
        },
      },
    });

    expect(wrapper.findComponent({ name: 'ADrawer' }).props('getContainer')).toBe(container);
    wrapper.unmount();
    container.remove();
  });

  it('does not create a drawer without its owning page root', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const wrapper = mount(RecordDetailDrawer, {
      props: { open: true, title: '职员详情', container: null },
      global: { stubs: { ADrawer: true } },
    });

    expect(wrapper.findComponent({ name: 'ADrawer' }).exists()).toBe(false);
    expect(error).toHaveBeenCalledWith('[RecordDetailDrawer] 打开抽屉前必须传入所属页面的根 DOM 容器。');
  });
});
