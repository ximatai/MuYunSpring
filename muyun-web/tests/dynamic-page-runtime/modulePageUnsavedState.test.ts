// @vitest-environment jsdom

/* eslint-disable vue/one-component-per-file -- local injection harness */

import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import {
  provideModulePageUnsavedStateHost,
  useModulePageUnsavedState,
} from '@/dynamic-page-runtime/modulePageUnsavedState.ts';

it('reports dynamic workspace draft state through the optional host bridge and unregisters on teardown', () => {
  const unregister = vi.fn();
  let reportedDirty: (() => boolean) | undefined;
  const registerUnsavedState = vi.fn((_: string, isDirty: () => boolean) => {
    reportedDirty = isDirty;
    return unregister;
  });
  const Child = defineComponent({
    setup() {
      useModulePageUnsavedState('记录详情', () => true);
      return () => h('div');
    },
  });
  const Harness = defineComponent({
    setup() {
      provideModulePageUnsavedStateHost({ registerUnsavedState });
      return () => h(Child);
    },
  });

  const wrapper = mount(Harness);
  expect(registerUnsavedState).toHaveBeenCalledWith('记录详情', expect.any(Function));
  expect(reportedDirty?.()).toBe(true);
  wrapper.unmount();
  expect(unregister).toHaveBeenCalledOnce();
});
