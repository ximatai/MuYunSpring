import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import RecordDetailDrawer from '../../src/platform-components/RecordDetailDrawer.vue';

describe('RecordDetailDrawer', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps an explicitly inline workspace drawer under its owning page', () => {
    const wrapper = mount(RecordDetailDrawer, {
      props: { open: true, title: '职员详情', renderMode: 'inline' },
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

    expect(wrapper.findComponent({ name: 'ADrawer' }).props('getContainer')).toBe(false);
    wrapper.unmount();
  });

  it('forwards transition completion from its inline workspace drawer', async () => {
    const wrapper = mount(RecordDetailDrawer, {
      props: { open: true, title: '职员详情', renderMode: 'inline' },
      global: {
        stubs: {
          ADrawer: {
            name: 'ADrawer',
            emits: ['afterOpenChange'],
            template: '<section><slot /></section>',
          },
        },
      },
    });

    wrapper.findComponent({ name: 'ADrawer' }).vm.$emit('afterOpenChange', false);
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('afterClose')).toHaveLength(1);
    wrapper.unmount();
  });

  it('forwards transition completion from the side-panel workspace drawer', async () => {
    const wrapper = mount(RecordDetailDrawer, {
      props: { open: true, title: '职员详情', scope: 'viewport' },
      global: {
        stubs: {
          UiSidePanel: {
            name: 'UiSidePanel',
            emits: ['afterClose'],
            template: '<section><slot /></section>',
          },
        },
      },
    });

    wrapper.findComponent({ name: 'UiSidePanel' }).vm.$emit('afterClose');
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('afterClose')).toHaveLength(1);
  });

  it('does not create a portal drawer without an active side-panel host', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const wrapper = mount(RecordDetailDrawer, {
      props: { open: true, title: '职员详情' },
      global: { stubs: { ADrawer: true } },
    });

    expect(wrapper.findComponent({ name: 'ADrawer' }).exists()).toBe(false);
    expect(error).toHaveBeenCalledWith('[RecordDetailDrawer] portal 模式打开抽屉前必须存在活动侧栏宿主。');
  });
});
