import { flushPromises, shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import type { ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import PasswordSecurityRules from '@/views/PasswordSecurityRules.vue';

const can = vi.fn<(action: string, recordId?: string) => boolean>(() => true);
const targetContext = { can } as unknown as ModuleContext<QueryListRecord>;
const row = {
  id: 'r1',
  version: 2,
  title: '数字',
  pattern: '.*[0-9].*',
  message: '需要数字',
  enabled: true,
  sortOrder: 10,
};
function mountRules(editing: boolean) {
  return shallowMount(PasswordSecurityRules, {
    props: { rows: [row], originalRows: [row], targetContext, editing, disabled: false },
    global: {
      stubs: {
        RecordQueryListSurface: {
          name: 'RecordQueryListSurface',
          props: ['rows', 'showActionColumn'],
          template: '<div><slot name="operations" /><slot name="rowActions" :record="rows[0]" /></div>',
        },
        RecordModeDrawer: {
          name: 'RecordModeDrawer',
          props: ['open'],
          template: '<div><slot name="form" /></div>',
        },
        RecordFormFields: { name: 'RecordFormFields', props: ['record', 'fieldNames'], template: '<div />' },
      },
    },
  });
}
describe('password security rule draft', () => {
  it('has no mutation controls in view mode', () => {
    const wrapper = mountRules(false);
    expect(wrapper.findComponent({ name: 'RecordQueryListSurface' }).props('showActionColumn')).toBe(false);
    expect(wrapper.findAllComponents({ name: 'UiActionButton' })).toHaveLength(0);
  });
  it('adds a rule only to the parent draft', async () => {
    can.mockReturnValue(true);
    const wrapper = mountRules(true);
    wrapper.findAllComponents({ name: 'UiActionButton' })[0].vm.$emit('click');
    expect(wrapper.emitted('update:editing')?.at(-1)).toEqual([true]);
    expect(wrapper.findComponent({ name: 'RecordModeDrawer' }).attributes('scope')).toBeUndefined();
    const form = wrapper.findComponent({ name: 'RecordFormFields' });
    form.vm.$emit('update:field', 'title', '长度');
    form.vm.$emit('update:field', 'pattern', '.{8,}');
    form.vm.$emit('update:field', 'message', '至少八位');
    form.vm.$emit('validity-change', { valid: true, pending: false });
    wrapper.findComponent({ name: 'RecordModeDrawer' }).vm.$emit('save');
    await flushPromises();
    expect(wrapper.emitted('update:editing')?.at(-1)).toEqual([false]);
    expect(wrapper.emitted('update:rows')?.[0]?.[0]).toEqual([
      row,
      expect.objectContaining({ title: '长度', pattern: '.{8,}', message: '至少八位', enabled: true }),
    ]);
  });
  it('keeps an unauthorized action unavailable', () => {
    can.mockReturnValue(false);
    const wrapper = mountRules(true);
    expect(wrapper.findAllComponents({ name: 'UiActionButton' })).toHaveLength(0);
  });
  it('permits sorting without granting edits to other fields', async () => {
    can.mockImplementation((action) => action === 'sort');
    const wrapper = mountRules(true);
    const buttons = wrapper.findAllComponents({ name: 'UiActionButton' });
    expect(buttons).toHaveLength(1);
    buttons[0].vm.$emit('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordFormFields' }).props('fieldNames')).toEqual(['sortOrder']);
  });
  it('allows a staged disable to be reversed without an enable grant', async () => {
    can.mockImplementation((action) => action === 'disable');
    const wrapper = mountRules(true);
    wrapper.findComponent({ name: 'UiActionButton' }).vm.$emit('click');
    const disabled = wrapper.emitted('update:rows')![0][0] as QueryListRecord[];
    await wrapper.setProps({ rows: disabled });
    wrapper.findComponent({ name: 'UiActionButton' }).vm.$emit('click');
    expect(wrapper.emitted('update:rows')![1][0]).toEqual([row]);
  });
  it('uses standard field validation before accepting a rule draft', async () => {
    can.mockReturnValue(true);
    const wrapper = mountRules(true);
    wrapper.findAllComponents({ name: 'UiActionButton' })[0].vm.$emit('click');
    const form = wrapper.findComponent({ name: 'RecordFormFields' });
    form.vm.$emit('validity-change', { valid: false, pending: false });
    wrapper.findComponent({ name: 'RecordModeDrawer' }).vm.$emit('save');
    await flushPromises();
    expect(wrapper.emitted('update:rows')).toBeUndefined();
    expect(form.attributes('validation-request-key')).toBe('1');
    expect(wrapper.findComponent({ name: 'RecordModeDrawer' }).props('open')).toBe(true);
  });
  it('keeps secondary row actions in the platform menu with destructive intent', () => {
    can.mockReturnValue(true);
    const wrapper = mountRules(true);
    const menu = wrapper.findComponent({ name: 'UiDropdown' });
    expect(menu.props('items')).toEqual([
      expect.objectContaining({ key: 'disable' }),
      expect.objectContaining({ key: 'delete', danger: true }),
    ]);
    expect(wrapper.findComponent({ name: 'RecordQueryListSurface' }).attributes('chrome-free')).toBe('false');
  });
});
