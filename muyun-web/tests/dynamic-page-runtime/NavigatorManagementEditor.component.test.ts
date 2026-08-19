import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import NavigatorManagementEditor from '@/dynamic-page-runtime/NavigatorManagementEditor.vue';

describe('NavigatorManagementEditor', () => {
  it('exposes enable status only when the manager declares it and forwards the standard toggle intent', async () => {
    const wrapper = shallowMount(NavigatorManagementEditor, {
      props: {
        open: true,
        title: '编辑分类',
        saving: false,
        loading: false,
        loadFailed: false,
        draft: { id: 'category-1', version: 3, enabled: false },
        fields: new Map(),
        formSessionKey: 0,
        context: {} as never,
        pickerConfigs: {},
        showEnabled: true,
        enabled: false,
        enabledDisabled: false,
        enabledLoading: false,
      },
    });

    const status = wrapper.findComponent({ name: 'RecordStatusSwitch' });
    expect(status.exists()).toBe(true);
    expect(status.props('enabled')).toBe(false);

    status.vm.$emit('change', true);
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('toggleEnabled')).toEqual([[true]]);
  });

  it('does not manufacture an enabled field for navigator sources without the enable ability', () => {
    const wrapper = shallowMount(NavigatorManagementEditor, {
      props: {
        open: true,
        title: '编辑目录',
        saving: false,
        loading: false,
        loadFailed: false,
        draft: { id: 'directory-1', version: 1 },
        fields: new Map(),
        formSessionKey: 0,
        context: {} as never,
        pickerConfigs: {},
        showEnabled: false,
      },
    });

    expect(wrapper.findComponent({ name: 'RecordStatusSwitch' }).exists()).toBe(false);
  });

  it('holds save, cancel and field edits while a versioned enabled mutation is pending', () => {
    const wrapper = shallowMount(NavigatorManagementEditor, {
      props: {
        open: true,
        title: '编辑分类',
        saving: false,
        loading: false,
        loadFailed: false,
        draft: { id: 'category-1', version: 3, enabled: true, title: '原名称' },
        fields: new Map(),
        formSessionKey: 0,
        context: {} as never,
        pickerConfigs: {},
        showEnabled: true,
        enabled: true,
        enabledDisabled: false,
        enabledLoading: true,
      },
    });

    for (const button of wrapper.findAllComponents({ name: 'RecordPanelButton' })) {
      expect(button.props('disabled')).toBe(true);
    }
    expect(wrapper.findComponent({ name: 'RecordFormFields' }).props('disabled')).toBe(true);
  });
});
