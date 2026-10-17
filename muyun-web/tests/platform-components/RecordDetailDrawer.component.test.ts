import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h, KeepAlive, nextTick, ref, watchEffect } from 'vue';
import RecordDetailDrawer from '../../src/platform-components/RecordDetailDrawer.vue';
import { useRecordSurfaceActivity } from '../../src/platform-components/recordSurfaceActivity';

describe('RecordDetailDrawer', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('suspends retained reading surfaces when a drawer closes, including nested drawers', async () => {
    const Reader = defineComponent({
      setup() {
        const active = useRecordSurfaceActivity();
        return () => h('output', String(active.value));
      },
    });
    const wrapper = mount(RecordDetailDrawer, {
      props: { open: true, title: '业务详情', renderMode: 'inline' },
      slots: {
        default: () =>
          h(RecordDetailDrawer, { open: true, title: '引用', renderMode: 'inline' }, () => h(Reader)),
      },
      global: { stubs: { UiSidePanel: { template: '<section><slot /></section>' } } },
    });
    expect(wrapper.find('output').text()).toBe('true');
    await wrapper.setProps({ open: false });
    expect(wrapper.find('output').text()).toBe('false');
    await wrapper.setProps({ open: true });
    expect(wrapper.find('output').text()).toBe('true');
    wrapper.unmount();
  });

  it('suspends reading while its workbench tab is kept alive in the background', async () => {
    const shown = ref(true);
    let active = false;
    const Reader = defineComponent({
      setup() {
        const activity = useRecordSurfaceActivity();
        watchEffect(() => {
          active = activity.value;
        });
        return () => h('span', '保留的表单');
      },
    });
    const wrapper = mount(
      defineComponent({
        setup: () => () => h(KeepAlive, () => (shown.value ? h(Reader) : null)),
      }),
    );
    await nextTick();
    expect(active).toBe(true);
    shown.value = false;
    await nextTick();
    await nextTick();
    expect(active).toBe(false);
    shown.value = true;
    await nextTick();
    await nextTick();
    expect(active).toBe(true);
    wrapper.unmount();
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

  it('resolves a semantic width tier before rendering an inline drawer', () => {
    const wrapper = mount(RecordDetailDrawer, {
      props: { open: true, title: '职员详情', renderMode: 'inline', width: 'wide' },
      global: {
        stubs: {
          ADrawer: {
            name: 'ADrawer',
            props: ['width'],
            template: '<section><slot /></section>',
          },
        },
      },
    });

    expect(wrapper.findComponent({ name: 'ADrawer' }).props('width')).toBe('min(760px, calc(100% - 32px))');
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

    await wrapper.setProps({ open: false });
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
