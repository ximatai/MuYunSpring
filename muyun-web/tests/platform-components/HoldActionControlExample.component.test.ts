import { flushPromises, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { nextTick } from 'vue';
import HoldActionControlExample from '../../examples/business-web/src/components/HoldActionControlExample.vue';
import type { ActionControlContext } from '@/platform-components/actionControl';

it('ends a hold on release, cancellation, lost capture, blur, session abort and unmount', async () => {
  const controller = new AbortController();
  const context: Readonly<ActionControlContext> = Object.freeze({
    actionKey: 'hold',
    title: 'Hold',
    moduleAlias: 'demo.device',
    record: Object.freeze({ id: 'a' }),
    sessionKey: 1,
    active: true,
    disabled: false,
    loading: false,
    signal: controller.signal,
    refresh() {},
  });
  const wrapper = mount(HoldActionControlExample, { props: { context } });
  const button = wrapper.get('button');
  const pointer = async (type: string, id = 1) => {
    const event = new MouseEvent(type, { button: 0, bubbles: true });
    Object.defineProperties(event, { isPrimary: { value: true }, pointerId: { value: id } });
    button.element.dispatchEvent(event);
    await nextTick();
  };
  const down = () => pointer('pointerdown');
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
    await down();
    expect(button.attributes('aria-pressed')).toBe('true');
    await pointer(type, 2);
    expect(button.attributes('aria-pressed')).toBe('true');
    await pointer(type);
    expect(button.attributes('aria-pressed')).toBe('false');
  }
  await button.trigger('keydown', { key: ' ', repeat: false });
  expect(button.attributes('aria-pressed')).toBe('true');
  await button.trigger('keyup', { key: ' ' });
  expect(button.attributes('aria-pressed')).toBe('false');
  await down();
  window.dispatchEvent(new Event('blur'));
  await flushPromises();
  expect(button.attributes('aria-pressed')).toBe('false');
  await down();
  controller.abort();
  await flushPromises();
  expect(button.attributes('aria-pressed')).toBe('false');
  await down();
  expect(button.attributes('aria-pressed')).toBe('false');
  wrapper.unmount();
});
