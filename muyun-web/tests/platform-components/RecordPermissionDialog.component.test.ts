import { shallowMount, flushPromises } from '@vue/test-utils';
import { it, expect, vi } from 'vitest';
import type { ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import RecordPermissionDialog from '@/platform-components/RecordPermissionDialog.vue';

vi.mock('@/platform-components/platformActionResultFeedback', () => ({
  handlePlatformActionSuccess: vi.fn(),
}));
vi.mock('@/platform-components/platformErrorFeedback', () => ({ presentPlatformError: vi.fn() }));
it('submits a versioned transfer command and closes only after persistence succeeds', async () => {
  const request = vi.fn(async (input: { method: string; path: string }) =>
    input.method === 'POST'
      ? { changed: true }
      : input.path.endsWith('candidates')
        ? [{ id: 'new', title: '新人' }]
        : { version: 3, ownerId: 'old', assigneeIds: [], memberIds: [], titles: { old: '原归属人' } },
  );
  const context = {
    moduleAlias: 'test.order',
    http: { request },
  } as unknown as ModuleContext<QueryListRecord>;
  const wrapper = shallowMount(RecordPermissionDialog, {
    props: { open: true, context, recordId: 'record' },
    global: {
      stubs: {
        UiModal: { template: '<section><slot /></section>', name: 'UiModal' },
        UiSpin: { template: '<div><slot /></div>' },
        RecordFormGrid: { template: '<div><slot /></div>' },
      },
    },
  });
  await flushPromises();
  const selects = wrapper.findAllComponents({ name: 'UiSelect' });
  selects[1]!.vm.$emit('update:value', 'new');
  await flushPromises();
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await flushPromises();
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      body: expect.objectContaining({
        version: 3,
        operation: 'TRANSFER',
        userIds: ['new'],
        retainPreviousOwner: false,
      }),
    }),
  );
  expect(wrapper.emitted('changed')).toHaveLength(1);
  expect(wrapper.emitted('close')).toHaveLength(1);
  wrapper.unmount();
});

it('retains selected names in the confirmation summary across searches', async () => {
  const request = vi.fn(async (input: { path: string; query?: { keyword: string } }) =>
    input.path.endsWith('candidates')
      ? input.query?.keyword === '李'
        ? [{ id: 'li', title: '李四' }]
        : [{ id: 'zhang', title: '张三' }]
      : { version: 0, assigneeIds: [], memberIds: [], titles: {} },
  );
  const wrapper = shallowMount(RecordPermissionDialog, {
    props: {
      open: true,
      recordId: 'record',
      context: { moduleAlias: 'test.order', http: { request } } as unknown as ModuleContext<QueryListRecord>,
    },
    global: {
      stubs: {
        UiModal: { template: '<section><slot /></section>', name: 'UiModal' },
        UiSpin: { template: '<div><slot /></div>' },
        RecordFormGrid: { template: '<div><slot /></div>' },
      },
    },
  });
  await flushPromises();
  wrapper.findAllComponents({ name: 'UiSelect' })[0]!.vm.$emit('update:value', 'ADD');
  await flushPromises();
  const picker = wrapper.findAllComponents({ name: 'UiSelect' })[2]!;
  picker.vm.$emit('update:value', ['zhang']);
  picker.vm.$emit('search', '李');
  await flushPromises();
  picker.vm.$emit('update:value', ['zhang', 'li']);
  await flushPromises();
  expect(wrapper.find('.permission-summary').text()).toBe('追加负责人：张三、李四');
  wrapper.unmount();
});
