import { shallowMount, flushPromises } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import ReadonlyRecordWorkspace from '@/platform-components/ReadonlyRecordWorkspace.vue';
import RecordQueryListSurface from '@/platform-components/RecordQueryListSurface.vue';
import RecordAdaptiveDetailPanel from '@/platform-components/RecordAdaptiveDetailPanel.vue';
import { readWorkspaceFixture, readQuerySchema } from './readonlyRecordWorkspaceFixture';

it('uses standard surfaces and compiled fields without a writable module context', async () => {
  const source = readWorkspaceFixture();
  const query = vi.spyOn(source, 'query');
  const wrapper = shallowMount(ReadonlyRecordWorkspace, {
    props: { view: source },
    global: {
      renderStubDefaultSlot: true,
      stubs: { RecordQueryListSurface: false, ManagementPanelHeader: false },
    },
  });
  await flushPromises();
  const list = wrapper.getComponent(RecordQueryListSurface);
  expect(list.props('columns')).toMatchObject([{ key: 'title', title: '事项', width: '220px' }]);
  expect(list.props('rows')).toHaveLength(2);
  list.vm.$emit('rowClick', list.props('rows')[1], new MouseEvent('click'));
  await flushPromises();
  expect(wrapper.getComponent(RecordAdaptiveDetailPanel).props('title')).toBe('reception B');
  expect(wrapper.findComponent({ name: 'RecordDetailFields' }).props('record')).toMatchObject({
    summary: 'Summary for visit',
  });
  list.vm.$emit('pageChange', 2);
  await flushPromises();
  expect(query).toHaveBeenLastCalledWith(
    expect.objectContaining({ page: { pageNum: 2, pageSize: 20 } }),
    expect.anything(),
  );
  expect(wrapper.getComponent(RecordAdaptiveDetailPanel).props('open')).toBe(false);
});

it('switches named views and clears failed results instead of retaining old source facts', async () => {
  const wrapper = shallowMount(ReadonlyRecordWorkspace, {
    props: { view: readWorkspaceFixture() },
    global: {
      renderStubDefaultSlot: true,
      stubs: { RecordQueryListSurface: false, ManagementPanelHeader: false },
    },
  });
  await flushPromises();
  const history = readWorkspaceFixture('history');
  await wrapper.setProps({ view: history });
  await flushPromises();
  expect(wrapper.getComponent(RecordQueryListSurface).props('rows')[0].title).toBe('history A');
  await wrapper.setProps({
    view: {
      ...history,
      scopeKey: 'tenant-b:user-b',
      query: async () => {
        throw new Error('Forbidden');
      },
    },
  });
  await flushPromises();
  expect(wrapper.getComponent(RecordQueryListSurface).props('rows')).toEqual([]);
  expect(wrapper.getComponent(RecordQueryListSurface).props('tableVisible')).toBe(false);
});

it('reuses schema filters and sorting, preserving drafts on collapse and resetting pages only on apply', async () => {
  const source = { ...readWorkspaceFixture(), querySchema: readQuerySchema() };
  const query = vi.spyOn(source, 'query');
  const wrapper = shallowMount(ReadonlyRecordWorkspace, {
    props: { view: source },
    global: {
      renderStubDefaultSlot: true,
      stubs: { RecordQueryListSurface: false, ManagementPanelHeader: false },
    },
  });
  await flushPromises();
  const list = wrapper.getComponent(RecordQueryListSurface);
  expect(query).toHaveBeenLastCalledWith(
    expect.objectContaining({ sorts: [{ field: 'title', desc: false }], quickSearchFields: ['title'] }),
    expect.anything(),
  );
  list.vm.$emit('pageChange', 2);
  await flushPromises();
  list.vm.$emit('rowClick', list.props('rows')[0]);
  await flushPromises();
  const controls = wrapper.getComponent({ name: 'RecordQueryControls' });
  controls.vm.$emit('toggle');
  await flushPromises();
  const composer = wrapper.getComponent({ name: 'QueryCriteriaComposer' });
  // A reference has no editable raw-ID fallback when its source supplied no picker.
  expect(composer.props('fields').map((field: { name: string }) => field.name)).toEqual(['title', 'state']);
  const count = query.mock.calls.length;
  controls.vm.$emit('toggle');
  controls.vm.$emit('toggle');
  await flushPromises();
  expect(wrapper.getComponent({ name: 'QueryCriteriaComposer' }).vm).toBe(composer.vm);
  expect(query).toHaveBeenCalledTimes(count);
  composer.vm.$emit('apply', {
    kind: 'GROUP',
    operator: 'OR',
    children: [
      { kind: 'CONDITION', fieldName: 'title', operator: 'EQ', values: ['A'] },
      { kind: 'CONDITION', fieldName: 'state', operator: 'EQ', values: ['OPEN'] },
    ],
  });
  await flushPromises();
  expect(query).toHaveBeenLastCalledWith(
    expect.objectContaining({
      page: { pageNum: 1, pageSize: 20 },
      criteria: { kind: 'GROUP', operator: 'OR', children: expect.any(Array) },
    }),
    expect.anything(),
  );
  expect(wrapper.getComponent(RecordAdaptiveDetailPanel).props('open')).toBe(false);
  controls.vm.$emit('sortChange', [{ field: 'title', desc: true }]);
  await flushPromises();
  expect(query).toHaveBeenLastCalledWith(
    expect.objectContaining({ sorts: [{ field: 'title', desc: true }] }),
    expect.anything(),
  );
  composer.vm.$emit('clear');
  await flushPromises();
  expect(query.mock.calls.at(-1)?.[0].criteria).toBeUndefined();
  wrapper.unmount();
});

it('clears filters, sort overrides and search before querying another view or scope', async () => {
  const source = { ...readWorkspaceFixture(), querySchema: readQuerySchema() };
  const wrapper = shallowMount(ReadonlyRecordWorkspace, {
    props: { view: source },
    global: {
      renderStubDefaultSlot: true,
      stubs: { RecordQueryListSurface: false, ManagementPanelHeader: false },
    },
  });
  await flushPromises();
  const list = wrapper.getComponent(RecordQueryListSurface);
  list.vm.$emit('quickSearch', 'private search');
  await flushPromises();
  const controls = wrapper.getComponent({ name: 'RecordQueryControls' });
  controls.vm.$emit('toggle');
  await flushPromises();
  wrapper.getComponent({ name: 'QueryCriteriaComposer' }).vm.$emit('apply', {
    kind: 'GROUP',
    operator: 'AND',
    children: [{ kind: 'CONDITION', fieldName: 'state', operator: 'EQ', values: ['old-private-value'] }],
  });
  controls.vm.$emit('sortChange', [{ field: 'title', desc: true }]);
  await flushPromises();
  const next = {
    ...readWorkspaceFixture('history'),
    scopeKey: 'tenant-b:user-b',
    querySchema: readQuerySchema(),
  };
  const query = vi.spyOn(next, 'query');
  await wrapper.setProps({ view: next });
  await flushPromises();
  for (const [request] of query.mock.calls) {
    expect(request.quickSearch).toBeUndefined();
    expect(request.criteria).toBeUndefined();
    expect(request.sorts).toEqual([{ field: 'title', desc: false }]);
    expect(request.page?.pageNum).toBe(1);
  }
  expect(query).toHaveBeenCalled();
  expect(wrapper.findComponent({ name: 'QueryCriteriaComposer' }).exists()).toBe(false);
  wrapper.unmount();
});

it('shares persistent field editors and honors defaults, pending validity and flat composition', async () => {
  const querySchema = readQuerySchema();
  querySchema.criteriaComposition = 'FLAT_AND';
  querySchema.quickSearch.enabled = false;
  querySchema.fields[1]!.persistentControl = {
    id: 'state',
    title: '状态',
    operator: 'EQ',
    defaultValues: ['OPEN'],
  };
  const source = { ...readWorkspaceFixture(), querySchema };
  const query = vi.spyOn(source, 'query');
  const wrapper = shallowMount(ReadonlyRecordWorkspace, {
    props: {
      view: source,
      queryOptionsByField: {
        state: [
          { value: 'OPEN', label: '打开' },
          { value: 'CLOSED', label: '关闭' },
        ],
      },
    },
    global: {
      renderStubDefaultSlot: true,
      stubs: { RecordQueryListSurface: false, ManagementPanelHeader: false },
    },
  });
  await flushPromises();
  const fields = wrapper.getComponent({ name: 'RecordQueryFieldControls' });
  expect(fields.props('controls')[0]).toMatchObject({
    values: ['OPEN'],
    options: [{ label: '打开' }, { label: '关闭' }],
  });
  expect(query.mock.calls.at(-1)?.[0].criteria?.children).toMatchObject([
    { fieldName: 'state', values: ['OPEN'] },
  ]);
  expect(wrapper.getComponent(RecordQueryListSurface).props('quickSearchVisible')).toBe(false);
  wrapper.getComponent(RecordQueryListSurface).vm.$emit('pageChange', 2);
  await flushPromises();
  fields.vm.$emit('apply');
  await flushPromises();
  expect(query.mock.calls.at(-1)?.[0].page?.pageNum).toBe(1);
  const count = query.mock.calls.length;
  fields.vm.$emit('update:values', 'state', ['CLOSED']);
  fields.vm.$emit('validity', 'state', { valid: false, status: 'resolving' });
  fields.vm.$emit('apply');
  await flushPromises();
  expect(query).toHaveBeenCalledTimes(count);
  fields.vm.$emit('validity', 'state', { valid: true, status: 'ready' });
  fields.vm.$emit('apply');
  await flushPromises();
  expect(query.mock.calls.at(-1)?.[0].criteria?.children).toMatchObject([
    { fieldName: 'state', values: ['CLOSED'] },
  ]);
  wrapper.getComponent({ name: 'RecordQueryControls' }).vm.$emit('toggle');
  await flushPromises();
  const composer = wrapper.getComponent({ name: 'QueryCriteriaComposer' });
  expect(composer.props('excludedFieldNames')).toEqual(['state']);
  composer.vm.$emit('apply', {
    kind: 'GROUP',
    operator: 'AND',
    children: [{ kind: 'CONDITION', fieldName: 'title', operator: 'EQ', values: ['Needle'] }],
  });
  await flushPromises();
  expect(query.mock.calls.at(-1)?.[0].criteria?.children).toMatchObject([
    { fieldName: 'state', values: ['CLOSED'] },
    { fieldName: 'title', values: ['Needle'] },
  ]);
  fields.vm.$emit('reset');
  await flushPromises();
  expect(query.mock.calls.at(-1)?.[0].criteria?.children[0]).toMatchObject({
    fieldName: 'state',
    values: ['OPEN'],
  });
  wrapper.unmount();
});

it('pairs new view and host query props only after the complete host update', async () => {
  const wrapper = shallowMount(ReadonlyRecordWorkspace, {
    props: {
      view: readWorkspaceFixture(),
      query: { conditions: [{ fieldName: 'context', operator: 'EQ', values: ['old-private'] }] },
    },
    global: { renderStubDefaultSlot: true },
  });
  await flushPromises();
  const next = { ...readWorkspaceFixture('history'), scopeKey: 'tenant-b' };
  const query = vi.spyOn(next, 'query');
  await wrapper.setProps({
    view: next,
    query: { conditions: [{ fieldName: 'context', operator: 'EQ', values: ['new-scope'] }] },
  });
  await flushPromises();
  expect(query).toHaveBeenCalledTimes(1);
  expect(JSON.stringify(query.mock.calls)).not.toContain('old-private');
  expect(query.mock.calls[0]?.[0].conditions?.[0]?.values).toEqual(['new-scope']);
  wrapper.unmount();
});

it('clears invalid user query state when capabilities shrink within the same view', async () => {
  const initial = { ...readWorkspaceFixture(), querySchema: readQuerySchema() };
  const wrapper = shallowMount(ReadonlyRecordWorkspace, {
    props: { view: initial },
    global: {
      renderStubDefaultSlot: true,
      stubs: { RecordQueryListSurface: false, ManagementPanelHeader: false },
    },
  });
  await flushPromises();
  wrapper.getComponent(RecordQueryListSurface).vm.$emit('quickSearch', 'old-search');
  wrapper
    .getComponent({ name: 'RecordQueryControls' })
    .vm.$emit('sortChange', [{ field: 'title', desc: true }]);
  wrapper.getComponent({ name: 'RecordQueryControls' }).vm.$emit('toggle');
  await flushPromises();
  wrapper.getComponent({ name: 'QueryCriteriaComposer' }).vm.$emit('apply', {
    kind: 'GROUP',
    operator: 'AND',
    children: [{ kind: 'CONDITION', fieldName: 'title', operator: 'EQ', values: ['old-filter'] }],
  });
  await flushPromises();
  const next = {
    ...initial,
    querySchema: {
      ...initial.querySchema,
      fields: [],
      criteriaComposition: 'NONE' as const,
      quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
      defaultSorts: [],
    },
  };
  const query = vi.spyOn(next, 'query');
  await wrapper.setProps({ view: next });
  await flushPromises();
  expect(query).toHaveBeenCalledTimes(1);
  expect(query.mock.calls[0]?.[0]).toMatchObject({ page: { pageNum: 1 }, sorts: [] });
  expect(query.mock.calls[0]?.[0].criteria).toBeUndefined();
  expect(query.mock.calls[0]?.[0].quickSearch).toBeUndefined();
  expect(wrapper.getComponent({ name: 'RecordQueryControls' }).props('filterVisible')).toBe(false);
  expect(wrapper.findComponent({ name: 'QueryCriteriaComposer' }).exists()).toBe(false);
  wrapper.unmount();
});

it('rejects over-budget persistent changes without replacing the last applied query', async () => {
  const initial = { ...readWorkspaceFixture(), querySchema: readQuerySchema() };
  initial.querySchema.fields[1]!.persistentControl = {
    id: 'state',
    title: '状态',
    operator: 'EQ',
    defaultValues: [],
  };
  const query = vi.spyOn(initial, 'query');
  const wrapper = shallowMount(ReadonlyRecordWorkspace, {
    props: { view: initial },
    global: {
      renderStubDefaultSlot: true,
      stubs: { RecordQueryListSurface: false, ManagementPanelHeader: false },
    },
  });
  await flushPromises();
  wrapper.getComponent({ name: 'RecordQueryControls' }).vm.$emit('toggle');
  await flushPromises();
  wrapper.getComponent({ name: 'QueryCriteriaComposer' }).vm.$emit('apply', {
    kind: 'GROUP',
    operator: 'AND',
    children: Array.from({ length: 49 }, () => ({
      kind: 'CONDITION',
      fieldName: 'title',
      operator: 'EQ',
      values: ['needle'],
    })),
  });
  await flushPromises();
  const count = query.mock.calls.length;
  const fields = wrapper.getComponent({ name: 'RecordQueryFieldControls' });
  fields.vm.$emit('update:values', 'state', ['OPEN']);
  fields.vm.$emit('apply');
  await flushPromises();
  expect(query).toHaveBeenCalledTimes(count);
  expect(
    wrapper
      .findAllComponents({ name: 'UiError' })
      .some((error) => error.props('message') === '最多支持 50 个条件和括号组'),
  ).toBe(true);
  expect(query.mock.calls.at(-1)?.[0].criteria?.children).toHaveLength(49);
  wrapper.unmount();
});
