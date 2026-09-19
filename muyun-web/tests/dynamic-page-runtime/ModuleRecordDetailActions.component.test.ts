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
    await wrapper.setProps({ saving: true, activeActionKey: 'save-entry' });
    expect(actions().find((item: { key: string }) => item.key === 'save-entry')).toMatchObject({
      disabled: true,
      loading: true,
    });
    await wrapper.setProps({ saving: false, activeActionKey: undefined });
    await wrapper.setProps({ formActions: [] });
    expect(actions().map((item: { key: string }) => item.key)).toEqual(['__platform-cancel']);
    await wrapper.setProps({
      mode: 'view',
      configuredActions: [{ key: 'delete-entry', actionCode: 'delete', title: '删除' }],
    });
    expect(actions().map((item: { key: string }) => item.key)).toEqual(['delete-entry']);
    await wrapper.setProps({ saving: true, activeActionKey: 'delete-entry' });
    expect(actions().find((item: { key: string }) => item.key === 'delete-entry')).toMatchObject({
      disabled: true,
      loading: true,
    });
  } finally {
    wrapper.unmount();
  }
});

it('locks peer detail actions while showing loading on the action that owns the session', async () => {
  const context = { can: () => true } as unknown as ModuleContext<QueryListRecord>;
  const wrapper = shallowMount(ModuleRecordDetailActions, {
    props: {
      context,
      mode: 'view',
      record: { id: '1' },
      saving: true,
      activeActionKey: 'test-connection',
      configuredActions: [{ key: 'test-connection', title: '测试连接' }],
    },
  });
  const actions = () =>
    wrapper.findComponent({ name: 'RecordActionBar' }).props('actions') as {
      key: string;
      disabled?: boolean;
      loading?: boolean;
    }[];
  try {
    expect(actions().find((action) => action.key === 'test-connection')).toMatchObject({
      loading: true,
      disabled: true,
    });
    expect(actions().find((action) => action.key === '__platform-edit')?.disabled).toBe(true);
    expect(actions().find((action) => action.key === '__platform-delete')).toMatchObject({
      disabled: true,
      loading: false,
    });
  } finally {
    wrapper.unmount();
  }
});

it('keeps the delete loading indicator on the delete button while locking its peers', () => {
  const context = { can: () => true } as unknown as ModuleContext<QueryListRecord>;
  const wrapper = shallowMount(ModuleRecordDetailActions, {
    props: {
      context,
      mode: 'view',
      record: { id: '1' },
      saving: true,
      activeActionKey: 'delete',
      configuredActions: [{ key: 'test-connection', title: '测试连接' }],
    },
  });
  const actions = wrapper.findComponent({ name: 'RecordActionBar' }).props('actions') as {
    key: string;
    disabled?: boolean;
    loading?: boolean;
  }[];
  try {
    expect(actions.find((action) => action.key === '__platform-edit')?.disabled).toBe(true);
    expect(actions.find((action) => action.key === '__platform-delete')).toMatchObject({
      disabled: true,
      loading: true,
    });
    expect(actions.find((action) => action.key === 'test-connection')).toMatchObject({
      disabled: true,
      loading: false,
    });
  } finally {
    wrapper.unmount();
  }
});
