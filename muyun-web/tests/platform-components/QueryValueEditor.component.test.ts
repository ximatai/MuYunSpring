import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import QueryValueEditor from '@/platform-components/QueryValueEditor.vue';
import ReferencePicker from '@/platform-components/ReferencePicker.vue';
import type { ReferencePickerConfig } from '@/platform-components/referencePickerModel';

function source(): ReferencePickerConfig {
  return {
    provider: {
      identity: {
        targetModuleAlias: 'sales.customer',
        source: { kind: 'businessPurpose', id: 'visible-order-customers' },
      },
      searchPage: vi.fn().mockResolvedValue({ records: [], total: 0 }),
      resolve: vi.fn().mockResolvedValue([]),
    },
  };
}

describe('source-owned reference query values', () => {
  it('uses the source provider and emits IDs without applying selection projections or form patches', () => {
    const referencePicker = source();
    const wrapper = shallowMount(QueryValueEditor, {
      props: {
        field: {
          name: 'customerId',
          title: '客户',
          valueType: 'STRING',
          operators: ['EQ', 'IN'],
          reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE' },
        },
        operator: 'EQ',
        values: ['customer-1'],
        referencePicker,
        referenceContext: { crud: { query: vi.fn() } } as never,
      },
    });
    const picker = wrapper.findComponent(ReferencePicker);
    expect(picker.props('provider')).toEqual(referencePicker.provider);
    expect(picker.props('value')).toBe('customer-1');
    expect(picker.props('multiple')).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordPicker' }).exists()).toBe(false);
    picker.vm.$emit('selection-resolved', [{ id: 'customer-1', title: '客户名称' }]);
    picker.vm.$emit('select', [{ id: 'customer-2', title: '新客户', affectPatch: { amount: 100 } }]);
    expect(wrapper.emitted('update:values')).toBeUndefined();
    picker.vm.$emit('update:value', 'customer-2');
    expect(wrapper.emitted('update:values')).toEqual([[['customer-2']]]);
    picker.vm.$emit('update:value', undefined);
    expect(wrapper.emitted('update:values')?.at(-1)).toEqual([[]]);
  });

  it('takes multiple query values for IN while the persisted reference remains single-valued', () => {
    const wrapper = shallowMount(QueryValueEditor, {
      props: {
        field: {
          name: 'customerId',
          title: '客户',
          valueType: 'STRING',
          operators: ['EQ', 'IN'],
          reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE' },
        },
        operator: 'IN',
        values: ['a', 'b'],
        referencePicker: source(),
      },
    });
    const picker = wrapper.findComponent(ReferencePicker);
    expect(picker.props('multiple')).toBe(true);
    expect(picker.props('value')).toEqual(['a', 'b']);
    picker.vm.$emit('update:value', ['b', 'c']);
    expect(wrapper.emitted('update:values')).toEqual([[['b', 'c']]]);
  });

  it('forwards unfinished reference completion states so a query owner can keep prior IDs unapplied', () => {
    const wrapper = shallowMount(QueryValueEditor, {
      props: {
        field: {
          name: 'customerId',
          title: '客户',
          valueType: 'STRING',
          operators: ['EQ'],
          reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE' },
        },
        operator: 'EQ',
        values: ['customer-1'],
        referencePicker: source(),
      },
    });

    const picker = wrapper.findComponent(ReferencePicker);
    picker.vm.$emit('validity-change', { valid: false, status: 'editing', message: '请完成引用选择' });
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
      { valid: false, status: 'editing', message: '请完成引用选择' },
    ]);

    picker.vm.$emit('validity-change', { valid: true, status: 'ready' });
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, status: 'ready' }]);
  });

  it('treats value-less reference operators as complete and lets a remounted real picker report ready when switching back', async () => {
    const wrapper = mount(QueryValueEditor, {
      props: {
        field: {
          name: 'customerId',
          title: '客户',
          valueType: 'STRING',
          operators: ['EQ', 'NULL'],
          reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE' },
        },
        operator: 'EQ',
        values: ['customer-1'],
        referencePicker: source(),
      },
    });
    wrapper.findComponent(ReferencePicker).vm.$emit('validity-change', {
      valid: false,
      status: 'editing',
      message: '请完成引用选择',
    });

    await wrapper.setProps({ operator: 'NULL' });
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, status: 'ready' }]);

    await wrapper.setProps({ operator: 'EQ' });
    await flushPromises();
    expect(wrapper.findComponent(ReferencePicker).exists()).toBe(true);
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, status: 'ready' }]);
  });

  it('uses the shared picker for a non-tree target REFERENCE context while keeping query cardinality operator-owned', async () => {
    const request = vi.fn().mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 20 });
    const wrapper = shallowMount(QueryValueEditor, {
      props: {
        field: {
          name: 'customerId',
          title: '客户',
          valueType: 'STRING',
          operators: ['EQ', 'IN'],
          reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE', labelField: 'name' },
        },
        operator: 'EQ',
        values: ['customer-1'],
        referenceContext: {
          http: { request },
          runtime: { ready: Promise.resolve({}) },
          abilities: { tryTree: () => undefined },
        } as never,
      },
    });

    await flushPromises();
    const picker = wrapper.findComponent(ReferencePicker);
    expect(picker.exists()).toBe(true);
    expect(picker.props('multiple')).toBe(false);
    expect(picker.props('maxSelection')).toBeUndefined();
    await wrapper.setProps({ operator: 'IN' });
    const multiplePicker = wrapper.findComponent(ReferencePicker);
    expect(multiplePicker.props('multiple')).toBe(true);
    expect(multiplePicker.props('maxSelection')).toBeUndefined();
    await (multiplePicker.props('provider') as ReferencePickerConfig['provider']).searchPage({
      keyword: '杭州',
      pageNum: 1,
      pageSize: 20,
      scope: { selections: [] },
    });
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/sales.customer/navigator/reference/query' }),
    );
    expect(request).not.toHaveBeenCalledWith(expect.objectContaining({ path: '/sales.customer/query' }));
  });

  it('keeps a tree target on the existing tree picker path', async () => {
    const wrapper = shallowMount(QueryValueEditor, {
      props: {
        field: {
          name: 'categoryId',
          valueType: 'STRING',
          operators: ['EQ'],
          reference: { targetModuleAlias: 'catalog.category', cardinality: 'ONE' },
        },
        operator: 'EQ',
        referenceContext: {
          http: { request: vi.fn() },
          runtime: { ready: Promise.resolve({ capabilities: ['tree'] }) },
          abilities: { tryTree: () => ({}) },
        } as never,
      },
    });

    await flushPromises();
    expect(wrapper.findComponent(ReferencePicker).exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordPicker' }).exists()).toBe(true);
  });
});
