import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, KeepAlive, ref, type PropType } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import ActionControlHost from '@/platform-components/ActionControlHost.vue';
import AdaptiveHeaderActionBar from '@/platform-components/AdaptiveHeaderActionBar.vue';
import type { ActionControl, ActionControlContext } from '@/platform-components/actionControl';
import { provideRecordSurfaceActivity } from '@/platform-components/recordSurfaceActivity';

function fixture() {
  let current!: Readonly<ActionControlContext>;
  const mounted = vi.fn();
  const component = defineComponent({
    props: { context: { type: Object as PropType<Readonly<ActionControlContext>>, required: true } },
    setup(props) {
      mounted();
      return () => {
        current = props.context;
        return h('button', { disabled: props.context.disabled }, String(props.context.record.id));
      };
    },
  });
  const refresh = vi.fn();
  const control: ActionControl = {
    component,
    moduleAlias: 'demo.device',
    record: Object.freeze({ id: 'a' }),
    scopeKey: {},
    sessionKey: 1,
    active: true,
    refresh,
  };
  return { control, current: () => current, mounted, refresh };
}

describe('action control interaction session', () => {
  it('aborts on disablement and record/scope changes, and rejects refreshes from stale contexts', async () => {
    const test = fixture();
    const wrapper = mount(ActionControlHost, {
      props: { control: test.control, actionKey: 'talk', title: 'Talk' },
    });
    const first = test.current();
    expect(Object.isFrozen(first)).toBe(true);
    expect(first.active).toBe(true);
    first.refresh();
    expect(test.refresh).toHaveBeenCalledTimes(1);
    const stop = vi.fn();
    first.signal.addEventListener('abort', stop, { once: true });
    await wrapper.setProps({ disabled: true, disabledReason: 'Unavailable' });
    expect(first.signal.aborted).toBe(true);
    expect(stop).toHaveBeenCalledTimes(1);
    expect(test.current()).toMatchObject({ active: false, disabled: true, disabledReason: 'Unavailable' });
    first.refresh();
    expect(test.refresh).toHaveBeenCalledTimes(1);
    await wrapper.setProps({ disabled: false });
    const second = test.current();
    expect(second.signal.aborted).toBe(false);
    await wrapper.setProps({ control: { ...test.control, record: Object.freeze({ id: 'b' }) } });
    expect(second.signal.aborted).toBe(true);
    const third = test.current();
    await wrapper.setProps({ control: { ...test.control, scopeKey: {} } });
    expect(third.signal.aborted).toBe(true);
    const fourth = test.current();
    wrapper.unmount();
    expect(fourth.signal.aborted).toBe(true);
    expect(test.mounted).toHaveBeenCalledTimes(1);
  });

  it('stops retained content immediately when its detail surface closes or its tab deactivates', async () => {
    const test = fixture();
    const open = ref(true);
    const shown = ref(true);
    const Surface = defineComponent({
      setup() {
        provideRecordSurfaceActivity(() => open.value);
        return () => h(ActionControlHost, { control: test.control, actionKey: 'talk', title: 'Talk' });
      },
    });
    const wrapper = mount(
      defineComponent({
        setup: () => () => h(KeepAlive, null, { default: () => (shown.value ? h(Surface) : undefined) }),
      }),
    );
    const first = test.current();
    open.value = false;
    await flushPromises();
    expect(first.signal.aborted).toBe(true);
    expect(test.current().active).toBe(false);
    open.value = true;
    await flushPromises();
    const second = test.current();
    shown.value = false;
    await flushPromises();
    expect(second.signal.aborted).toBe(true);
    shown.value = true;
    await flushPromises();
    expect(test.current().signal.aborted).toBe(false);
    expect(test.mounted).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('mounts the business component once and never creates a hidden measurement instance', async () => {
    const test = fixture();
    const wrapper = mount(AdaptiveHeaderActionBar, {
      props: {
        actions: [
          { key: 'history', title: 'History' },
          { key: 'talk', title: 'Talk', placement: 'after-primary', control: test.control },
          { key: 'call', title: 'Call', level: 'primary' },
        ],
      },
    });
    await flushPromises();
    expect(test.mounted).toHaveBeenCalledTimes(1);
    expect(
      wrapper.find('.adaptive-header-action-bar__measure').findComponent(test.control.component).exists(),
    ).toBe(false);
    expect(
      wrapper.findAll('.adaptive-header-action-bar__visible button').map((button) => button.text()),
    ).toEqual(['Call', 'a', 'History']);
    await wrapper.find('[data-action-control] button').trigger('click');
    expect(wrapper.emitted('action')).toBeUndefined();
    wrapper.unmount();
  });
});
