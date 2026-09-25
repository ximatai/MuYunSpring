import { inputComponents } from './pageCompositionComponentFixtures';
import { describe, expect, it } from 'vitest';
import {
  componentField,
  componentFieldForSave,
  placedComponentFields,
  componentFieldUsage,
  sameTitleFields,
} from '@/views/pageCompositionComponents';

describe('component field drafts', () => {
  it('derives the save candidate from the final title without changing the temporary identity', () => {
    const draft = { key: 'abc', fieldSpecAlias: 'string', component: 'text' as const, title: '预算' };
    expect(componentFieldForSave(draft).suggestedName).toBe('yuSuan');
    draft.title = '联系电话';
    expect(componentFieldForSave(draft)).toEqual({
      key: draft.key,
      component: draft.component,
      title: draft.title,
      required: undefined,
      suggestedName: 'lianXiDianHua',
    });
    expect(componentField(draft).fieldName).toBe('fieldabc');
  });
  it('reports grouped and separate placements without confusing data identity with display labels', () => {
    const tree = JSON.stringify({
      nodes: [
        { slot: 'list', fields: [{ field: 'name', props: { label: '其他标题' } }] },
        { slot: 'detail', groups: [{ fields: ['name'] }] },
        { slot: 'form', fields: ['other'] },
      ],
    });
    expect(componentFieldUsage('name', tree)).toEqual(['列表', '详情']);
    expect(
      componentFieldUsage('name', JSON.stringify({ nodes: [{ slot: 'form', fields: ['name'] }] })),
    ).toEqual(['详情 / 表单']);
    expect(componentFieldUsage('missing', tree)).toEqual([]);
  });
  it('counts navigation, queries and summaries as references without crossing child scopes', () => {
    const inputs = ['a', 'b', 'c', 'd', 'e'].map((key) => ({
      key,
      title: key,
      fieldSpecAlias: 'string',
      component: 'text' as const,
    }));
    const json = JSON.stringify({
      quickSearchFields: ['fielda'],
      querySummaries: [{ fieldName: 'fieldb', groupByField: 'fieldc' }],
      nodes: [
        { slot: 'explorer', titleField: 'fieldd', secondaryField: 'fielda' },
        { slot: 'form', relations: [{ fields: ['fielde'] }] },
      ],
    });
    expect(placedComponentFields(inputs, json).map((field) => field.key)).toEqual(['a', 'b', 'c', 'd']);
    expect(componentFieldUsage('fielda', json)).toEqual(['快速查询', '导航']);
    expect(componentFieldUsage('fieldb', json)).toEqual(['汇总统计']);
  });
  it('finds other same-named fields but permits deliberate reuse of the same identity', () => {
    const own = componentField({
      key: 'own',
      fieldSpecAlias: 'string',
      component: 'text',
      title: '名称',
      required: true,
    });
    const other = componentField({
      key: 'other',
      fieldSpecAlias: 'string',
      component: 'text',
      title: '名称',
    });
    expect(own.required).toBe(true);
    expect(sameTitleFields(' 名称 ', own.id, [own, other])).toEqual([other]);
    expect(sameTitleFields(' ', own.id, [own, other])).toEqual([]);
  });
  it.each(inputComponents)('maps $title to its field specification', (component) => {
    const field = componentField({
      key: 'abc',
      fieldSpecAlias: component.fieldSpecAlias,
      component: component.component,
      title: '业务标题',
    });
    expect(field).toMatchObject({
      id: 'abc',
      fieldName: 'fieldabc',
      title: '业务标题',
      fieldSpecAlias: component.fieldSpecAlias,
    });
  });
  it('deduplicates reused fields and excludes removed components and relation-only references', () => {
    const inputs = ['a', 'b', 'c'].map((key) => ({
      key,
      title: key,
      fieldSpecAlias: 'string',
      component: 'text' as const,
    }));
    const tree = {
      nodes: [
        { slot: 'list', fields: ['fielda'] },
        {
          slot: 'form',
          fields: [],
          groups: [{ fields: [{ field: 'fielda', props: {} }] }],
          relations: [{ fields: ['fieldc'] }],
        },
        { slot: 'detail', fields: ['fieldb'] },
      ],
    };
    expect(placedComponentFields(inputs, JSON.stringify(tree)).map((item) => item.key)).toEqual(['a', 'b']);
    expect(placedComponentFields(inputs, '{"nodes":[]}')).toEqual([]);
  });
});
