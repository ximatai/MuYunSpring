import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import DynamicRecordDetailActions from '@/dynamic-page-runtime/DynamicRecordDetailActions.vue';

const context = {
  can: () => true,
} as never;

const stubs = {
  RecordPanelButton: {
    emits: ['click'],
    template: '<button class="record-panel-button" @click="$emit(\'click\')"><slot /></button>',
  },
  ModuleActionButton: {
    emits: ['click'],
    template: '<button class="module-action-button" @click="$emit(\'click\')"><slot /></button>',
  },
  RecordActionBar: {
    template: '<div class="record-action-bar" />',
  },
};

describe('DynamicRecordDetailActions', () => {
  it('renders standard CRUD actions only in view mode', () => {
    const wrapper = mount(DynamicRecordDetailActions, {
      props: { context, record: { id: 'record-1', title: '记录一' }, mode: 'view' },
      global: { stubs },
    });

    expect(wrapper.text()).toContain('编辑');
    expect(wrapper.text()).toContain('删除');
    expect(wrapper.text()).not.toContain('新建');
    expect(wrapper.text()).not.toContain('保存');
    expect(wrapper.text()).not.toContain('取消');
  });

  it('renders cancel and save only while editing', async () => {
    const wrapper = mount(DynamicRecordDetailActions, {
      props: { context, record: { id: 'record-1', title: '记录一' }, mode: 'edit' },
      global: { stubs },
    });

    expect(wrapper.text()).toContain('取消');
    expect(wrapper.text()).toContain('保存');
    expect(wrapper.text()).not.toContain('新建');
    expect(wrapper.text()).not.toContain('编辑');
    expect(wrapper.text()).not.toContain('删除');

    await wrapper.find('.record-panel-button').trigger('click');
    expect(wrapper.emitted('cancel')).toHaveLength(1);
  });
});
