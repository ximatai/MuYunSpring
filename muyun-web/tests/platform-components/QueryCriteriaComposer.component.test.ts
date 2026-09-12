import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import QueryCriteriaComposer from '@/platform-components/QueryCriteriaComposer.vue';
import QueryCriteriaGroupEditor from '@/platform-components/QueryCriteriaGroupEditor.vue';
import type { QuerySchemaField } from '@/web-contracts';

describe('QueryCriteriaComposer', () => {
  const fields: QuerySchemaField[] = [
    { name: 'status', title: '状态', valueType: 'STRING', operators: ['EQ', 'BETWEEN'] },
  ];

  function mountComposer(composition: 'FLAT_AND' | 'TREE' = 'TREE') {
    return shallowMount(QueryCriteriaComposer, {
      props: {
        fields,
        optionItemsByField: {},
        referenceContexts: {},
        disabled: false,
        composition,
      },
      global: { stubs: { UiButton: false } },
    });
  }

  it('keeps an incomplete condition in place and identifies its row instead of widening the query', async () => {
    const wrapper = mountComposer();
    wrapper.findComponent({ name: 'QueryCriteriaGroupEditor' }).vm.$emit('update:group', {
      kind: 'GROUP',
      id: 1,
      operator: 'AND',
      children: [{ kind: 'CONDITION', id: 2, fieldName: 'status', operator: 'EQ', values: [] }],
    });
    await wrapper.vm.$nextTick();

    (wrapper.vm as unknown as { apply: () => void }).apply();
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('apply')).toBeUndefined();
    expect(wrapper.emitted('validation')?.[0]).toEqual(['请修正标记的筛选条件后再应用']);
    expect(wrapper.findComponent({ name: 'QueryCriteriaGroupEditor' }).props('validationErrors')).toEqual({
      2: '状态 需要填写条件值',
    });
  });

  it('requires both BETWEEN endpoints before applying the condition', async () => {
    const wrapper = mountComposer();
    wrapper.findComponent({ name: 'QueryCriteriaGroupEditor' }).vm.$emit('update:group', {
      kind: 'GROUP',
      id: 1,
      operator: 'AND',
      children: [{ kind: 'CONDITION', id: 2, fieldName: 'status', operator: 'BETWEEN', values: ['A'] }],
    });
    await wrapper.vm.$nextTick();

    (wrapper.vm as unknown as { apply: () => void }).apply();
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('apply')).toBeUndefined();
    expect(wrapper.findComponent({ name: 'QueryCriteriaGroupEditor' }).props('validationErrors')).toEqual({
      2: '状态 需要填写起始和结束两个值',
    });
  });

  it('keeps a flat query as root AND leaves and never offers a group editor affordance', async () => {
    const wrapper = mountComposer('FLAT_AND');
    expect(wrapper.findComponent({ name: 'QueryCriteriaGroupEditor' }).props('composition')).toBe('FLAT_AND');

    wrapper.findComponent({ name: 'QueryCriteriaGroupEditor' }).vm.$emit('update:group', {
      kind: 'GROUP',
      id: 1,
      operator: 'AND',
      children: [{ kind: 'CONDITION', id: 2, fieldName: 'status', operator: 'EQ', values: ['OPEN'] }],
    });
    await wrapper.vm.$nextTick();

    (wrapper.vm as unknown as { apply: () => void }).apply();
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('apply')?.[0]).toEqual([
      {
        kind: 'GROUP',
        operator: 'AND',
        children: [{ kind: 'CONDITION', fieldName: 'status', operator: 'EQ', values: ['OPEN'] }],
      },
    ]);
  });

  it('keeps field selection available on a flat query surface', () => {
    const wrapper = shallowMount(QueryCriteriaGroupEditor, {
      props: {
        group: {
          kind: 'GROUP',
          id: 1,
          operator: 'AND',
          children: [{ kind: 'CONDITION', id: 2, fieldName: 'status', operator: 'EQ', values: [] }],
        },
        fields: [
          ...fields,
          { name: 'moduleAlias', title: '业务模块', valueType: 'STRING', operators: ['EQ'] },
        ],
        optionItemsByField: {},
        referenceContexts: {},
        nextId: () => 3,
        disabled: false,
        composition: 'FLAT_AND',
      },
      global: { stubs: { UiSelect: false } },
    });

    expect(wrapper.findAllComponents({ name: 'UiSelect' }).at(1)?.props('disabled')).toBe(false);
  });

  it('does not offer fields already owned by a flat persistent control', () => {
    const wrapper = shallowMount(QueryCriteriaComposer, {
      props: {
        fields: [
          ...fields,
          { name: 'operatorId', title: '操作用户', valueType: 'STRING', operators: ['EQ'] },
        ],
        excludedFieldNames: ['status'],
        optionItemsByField: {},
        referenceContexts: {},
        disabled: false,
        composition: 'FLAT_AND',
      },
    });

    expect(wrapper.findComponent({ name: 'QueryCriteriaGroupEditor' }).props('fields')).toEqual([
      { name: 'operatorId', title: '操作用户', valueType: 'STRING', operators: ['EQ'] },
    ]);
  });
});
