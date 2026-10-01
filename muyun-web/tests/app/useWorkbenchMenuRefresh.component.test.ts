import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { appDataChangeDispatcher } from '@/platform-admin-runtime/realtime';
import { useWorkbenchMenuRefresh } from '@/app/useWorkbenchMenuRefresh';

let nextChangeSet = 0;
afterEach(() => vi.useRealTimers());

it('refreshes visible navigation once for committed menu and scheme changes, ignoring business records', async () => {
  vi.useFakeTimers();
  let ready = false;
  const refresh = vi.fn(async () => undefined);
  const wrapper = mount(
    defineComponent({
      setup() {
        useWorkbenchMenuRefresh({ ready: () => ready, refresh });
        return () => h('div');
      },
    }),
  );
  await change('platform.menu');
  await vi.runAllTimersAsync();
  expect(refresh).not.toHaveBeenCalled();
  ready = true;
  await change('sales.order');
  await vi.runAllTimersAsync();
  expect(refresh).not.toHaveBeenCalled();
  await change('platform.menu');
  await change('platform.menu_scheme');
  await vi.runAllTimersAsync();
  expect(refresh).toHaveBeenCalledTimes(1);
  await change('platform.menu');
  wrapper.unmount();
  await change('platform.menu_scheme');
  await vi.runAllTimersAsync();
  expect(refresh).toHaveBeenCalledTimes(1);
});

function change(moduleAlias: string) {
  return appDataChangeDispatcher.dispatch({
    changeSetId: `workbench-menu-refresh-${++nextChangeSet}`,
    changes: [{ type: 'record-created', moduleAlias, recordId: 'menu-1' }],
  });
}
