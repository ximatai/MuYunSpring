import { shallowMount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import type { ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import ModuleRecordDetailActions from '@/dynamic-page-runtime/ModuleRecordDetailActions.vue';

it('keeps placed save actions mode-specific and disabled during saving or failed detail loads', async () => {
  const context = { can: () => true } as unknown as ModuleContext<QueryListRecord>;
  const wrapper = shallowMount(ModuleRecordDetailActions, {
    props: {
      context,
      mode: 'edit',
      record: { id: '1' },
      formActions: [
        { key: 'placed-create', actionCode: 'create', title: '创建' },
        { key: 'placed-update', actionCode: 'update', title: '提交' },
      ],
    },
  });
  const actions = () =>
    wrapper.findComponent({ name: 'RecordActionBar' }).props('actions') as {
      key: string;
      disabled?: boolean;
    }[];
  try {
    expect(actions().some((action) => action.key === 'placed-create')).toBe(false);
    expect(actions().find((action) => action.key === 'placed-update')?.disabled).not.toBe(true);
    await wrapper.setProps({ saving: true });
    expect(actions().find((action) => action.key === 'placed-update')?.disabled).toBe(true);
    await wrapper.setProps({ saving: false, detailLoadFailed: true });
    expect(actions().find((action) => action.key === 'placed-update')?.disabled).toBe(true);
  } finally {
    wrapper.unmount();
  }
});

it('uses managed entries as the only configurable buttons while retaining cancel', async () => {
  const context = { can: () => true } as unknown as ModuleContext<QueryListRecord>;
  const wrapper = shallowMount(ModuleRecordDetailActions, {
    props: {
      context,
      mode: 'edit',
      managedActions: true,
      record: { id: '1' },
      formActions: [{ key: 'save-entry', actionCode: 'update', title: '提交修改' }],
    },
  });
  const actions = () => wrapper.findComponent({ name: 'RecordActionBar' }).props('actions');
  try {
    expect(actions().map((item: { key: string }) => item.key)).toEqual(['__platform-cancel', 'save-entry']);
    await wrapper.setProps({ formActions: [] });
    expect(actions().map((item: { key: string }) => item.key)).toEqual(['__platform-cancel']);
    await wrapper.setProps({
      mode: 'view',
      configuredActions: [{ key: 'delete-entry', actionCode: 'delete', title: '删除' }],
    });
    expect(actions().map((item: { key: string }) => item.key)).toEqual(['delete-entry']);
  } finally {
    wrapper.unmount();
  }
});
