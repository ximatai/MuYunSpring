import { flushPromises, shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import ManagedDetailRelationSurface from '@/dynamic-page-runtime/ManagedDetailRelationSurface.vue';
import ModulePageDetailRelations from '@/dynamic-page-runtime/ModulePageDetailRelations.vue';
import ManagedDetailRelationInlineSurface from '@/dynamic-page-runtime/ManagedDetailRelationInlineSurface.vue';
import type { ModuleContext } from '@muyun/web-core';
import type { ResolvedDetailRelationDescriptor, ResolvedModuleUiDescriptor } from '@muyun/web-contracts';

describe('managed detail relation surface', () => {
  it('allows aggregate draft rows before the parent has been persisted', async () => {
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    const wrapper = shallowMount(ModulePageDetailRelations, {
      props: {
        sourceContext: context(vi.fn()),
        uiDescriptor: descriptor(),
        relations: [aggregate],
        parentRecord: { properties: [] },
        mutationEnabled: true,
      },
      global: {
        stubs: {
          RecordDetailExtensionSection: {
            template: '<section><slot name="actions" /><slot /></section>',
          },
        },
      },
    });

    const addButton = wrapper
      .findAllComponents({ name: 'RecordPanelButton' })
      .find((button) => button.props('iconName') === 'plus');
    expect(addButton).toBeDefined();
    addButton!.vm.$emit('click');
    await flushPromises();

    expect(wrapper.findComponent({ name: 'ManagedDetailRelationInlineSurface' }).props('addRequestKey')).toBe(
      1,
    );
  });

  it('preserves invalid relation state when the same parent draft receives child records', async () => {
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    const wrapper = shallowMount(ModulePageDetailRelations, {
      props: {
        sourceContext: context(vi.fn()),
        uiDescriptor: descriptor(),
        relations: [aggregate],
        parentRecord: { id: 'text', properties: [] },
        mutationEnabled: true,
      },
      global: {
        stubs: {
          RecordDetailExtensionSection: { template: '<section><slot /></section>' },
        },
      },
    });

    wrapper.findComponent(ManagedDetailRelationInlineSurface).vm.$emit('validity-change', false);
    await wrapper.setProps({ parentRecord: { id: 'text', properties: [{}] } });

    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([false]);
  });

  it('retains aggregate child drafts when a visibility formula hides their relation', async () => {
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    aggregate.visible = {
      formula: {
        expression: "{valueShape} == 'COMPOSITE'",
        program: {
          schemaVersion: 1,
          profile: 'WEB_UI',
          referencedFields: ['valueShape'],
          root: {
            kind: 'BINARY',
            operator: '==',
            arguments: [
              { kind: 'FIELD', field: 'valueShape', arguments: [] },
              { kind: 'VALUE', value: 'COMPOSITE', arguments: [] },
            ],
          },
        },
      },
    };
    const wrapper = shallowMount(ModulePageDetailRelations, {
      props: {
        sourceContext: context(vi.fn()),
        uiDescriptor: descriptor(),
        relations: [aggregate],
        parentRecord: {
          id: 'select',
          valueShape: 'COMPOSITE',
          properties: [{ id: 'property-1', attributeAlias: 'rows', version: 1 }],
        },
        mutationEnabled: true,
      },
      global: {
        stubs: {
          RecordDetailExtensionSection: { template: '<section><slot /></section>' },
        },
      },
    });

    await wrapper.setProps({
      parentRecord: {
        id: 'select',
        valueShape: 'SCALAR',
        properties: [{ id: 'property-1', attributeAlias: 'rows', version: 1 }],
      },
    });

    expect(wrapper.findComponent(ManagedDetailRelationInlineSurface).exists()).toBe(false);
    expect(wrapper.emitted('children-change')).toBeUndefined();
  });

  it('keeps a wholly blank new row silent and excludes it from the aggregate draft', async () => {
    const request = vi.fn(async () => page([]));
    const managed = relation('properties');
    managed.embeddedField = 'properties';
    managed.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor: descriptor(),
        relation: managed,
        parentRecord: { id: 'select', properties: [] },
        addRequestKey: 0,
        mutationEnabled: true,
      },
    });
    await flushPromises();
    await wrapper.setProps({ addRequestKey: 1 });
    await flushPromises();

    expect(wrapper.find('.managed-relation-inline__required').text()).toBe('*');
    expect(wrapper.find('.managed-relation-inline__cell--validation-pulse').exists()).toBe(false);
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([true]);
    expect(wrapper.emitted('records-change')?.at(-1)).toEqual([[]]);

    await wrapper.setProps({ validationRequestKey: 1 });

    expect(wrapper.find('.managed-relation-inline__cell--validation-pulse').exists()).toBe(false);
    expect(wrapper.find('[title="属性 alias不能为空"]').exists()).toBe(false);
  });

  it('validates a new row as soon as any editable cell contains data', async () => {
    const managed = relation('properties');
    managed.embeddedField = 'properties';
    managed.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    managed.queryContract!.listProjection!.fields.push({ fieldName: 'title', title: '属性名称' });
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(vi.fn()),
        uiDescriptor: descriptor(),
        relation: managed,
        parentRecord: { id: 'select', properties: [] },
        addRequestKey: 0,
        mutationEnabled: true,
      },
    });
    await flushPromises();
    await wrapper.setProps({ addRequestKey: 1 });
    await flushPromises();

    const titleField = wrapper.findAllComponents({ name: 'RecordFormFields' })[1]!;
    titleField.vm.$emit('update:field', 'title', '已填写名称');
    await flushPromises();

    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([false]);
    expect(wrapper.emitted('records-change')?.at(-1)).toEqual([[{ title: '已填写名称' }]]);

    await wrapper.setProps({ validationRequestKey: 1 });

    expect(wrapper.find('.managed-relation-inline__cell--validation-pulse').exists()).toBe(true);
  });

  it('uses the same dynamic required formula for cell presentation and aggregate validity', async () => {
    const uiDescriptor = descriptor();
    const editorFields = uiDescriptor.editorContributions![0]!.editor.fields;
    editorFields[0]!.required = { constant: false };
    editorFields[1]!.required = {
      formula: {
        expression: 'PRESENT({attributeAlias})',
        program: {
          schemaVersion: 1,
          profile: 'WEB_UI',
          referencedFields: ['attributeAlias'],
          root: {
            kind: 'FUNCTION',
            operator: 'PRESENT',
            arguments: [{ kind: 'FIELD', field: 'attributeAlias', arguments: [] }],
          },
        },
      },
    };
    const managed = relation('properties');
    managed.embeddedField = 'properties';
    managed.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    managed.queryContract!.listProjection!.fields.push({ fieldName: 'title', title: '属性名称' });
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(vi.fn()),
        uiDescriptor,
        relation: managed,
        parentRecord: { id: 'select', properties: [{ attributeAlias: 'placeholder', title: '' }] },
        mutationEnabled: true,
      },
    });
    await flushPromises();

    expect(wrapper.findAll('.managed-relation-inline__required')).toHaveLength(1);
    expect(wrapper.find('.managed-relation-inline__cell--validation-pulse').exists()).toBe(false);
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([false]);

    await wrapper.setProps({ validationRequestKey: 1 });

    expect(wrapper.find('.managed-relation-inline__cell--validation-pulse').exists()).toBe(true);
  });

  it('keeps inline child edits as a local aggregate draft without mutation HTTP', async () => {
    const request = vi.fn(async (options: { path: string }) => {
      if (options.path.endsWith('/query')) {
        return page([{ id: 'property-1', version: 2, attributeAlias: 'rows' }]);
      }
      throw new Error(`unexpected mutation request: ${options.path}`);
    });
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    aggregate.queryContract = { ...aggregate.queryContract!, pageable: false };
    aggregate.queryContract.listProjection!.fields[0]!.width = 180;
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor: descriptor(),
        relation: aggregate,
        parentRecord: {
          id: 'select',
          properties: [{ id: 'property-1', version: 2, attributeAlias: 'rows' }],
        },
        mutationEnabled: true,
      },
    });
    await flushPromises();

    const field = wrapper.findComponent({ name: 'RecordFormFields' });
    expect(field.exists()).toBe(true);
    expect(wrapper.findAll('col')[1]!.attributes('style')).toContain('width: 180px');
    field.vm.$emit('update:field', 'attributeAlias', 'visibleRows');
    await flushPromises();

    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([
      expect.objectContaining({ id: 'property-1', version: 2, attributeAlias: 'visibleRows' }),
    ]);
    expect(request).not.toHaveBeenCalled();

    const selection = wrapper.findAllComponents({ name: 'RecordSelectionCheckbox' });
    expect(selection).toHaveLength(2);
    selection[1]!.vm.$emit('update:checked', true);
    await wrapper.setProps({ removeRequestKey: 1 });
    await flushPromises();

    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([]);
    expect(request).not.toHaveBeenCalled();
  });

  it('recovers retained children into the aggregate draft without writing immediately', async () => {
    const request = vi.fn(async (options: { path: string; method?: string }) => {
      expect(options.method).toBe('POST');
      expect(options.path).toBe(
        '/platform.field_ui_control/view/select/relations/properties/recycle-bin/query',
      );
      return page([
        {
          id: 'deleted-1',
          version: 4,
          deleted: true,
          fieldUiControlAlias: 'select',
          attributeAlias: 'rows',
          title: '显示行数',
        },
      ]);
    });
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = {
      mode: 'INLINE',
      saveMode: 'AGGREGATE_DRAFT',
      recycleBinEnabled: true,
    };
    aggregate.queryContract!.listProjection!.fields.push({ fieldName: 'title', title: '属性名称' });
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor: descriptor(),
        relation: aggregate,
        parentRecord: { id: 'select', properties: [] },
        mutationEnabled: true,
        recycleBinRequestKey: 0,
      },
      global: {
        stubs: {
          UiModal: {
            name: 'UiModal',
            props: ['open', 'confirmDisabled'],
            template: '<section v-if="open"><slot /></section>',
          },
        },
      },
    });
    await flushPromises();

    expect(request).toHaveBeenCalledTimes(1);
    expect(wrapper.emitted('recycle-bin-availability-change')?.at(-1)).toEqual([true]);
    await wrapper.setProps({ recycleBinRequestKey: 1 });
    await flushPromises();

    const choices = wrapper.findAllComponents({ name: 'RecordSelectionCheckbox' });
    expect(choices).toHaveLength(2);
    choices[1]!.vm.$emit('update:checked', true);
    await flushPromises();
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
    await flushPromises();

    expect(request).toHaveBeenCalledTimes(2);
    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([
      { attributeAlias: 'rows', title: '显示行数' },
    ]);
    expect(wrapper.emitted('recycle-bin-availability-change')?.at(-1)).toEqual([false]);

    await wrapper.setProps({ recycleBinRequestKey: 2 });
    await flushPromises();

    expect(request).toHaveBeenCalledTimes(3);
    expect(wrapper.find('.managed-relation-inline__recycle-table').text()).toContain('暂无可恢复记录');
    expect(wrapper.findAllComponents({ name: 'RecordSelectionCheckbox' })).toHaveLength(2);
    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([
      { attributeAlias: 'rows', title: '显示行数' },
    ]);
  });

  it('shows the aggregate recycle-bin entry only after retained rows are discovered', async () => {
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = {
      mode: 'INLINE',
      saveMode: 'AGGREGATE_DRAFT',
      recycleBinEnabled: true,
    };
    const wrapper = shallowMount(ModulePageDetailRelations, {
      props: {
        sourceContext: context(vi.fn()),
        uiDescriptor: descriptor(),
        relations: [aggregate],
        parentRecord: { id: 'select', properties: [] },
        mutationEnabled: true,
      },
      global: {
        stubs: {
          RecordDetailExtensionSection: {
            template: '<section><slot name="actions" /><slot /></section>',
          },
        },
      },
    });

    const recycleButton = () =>
      wrapper
        .findAllComponents({ name: 'RecordPanelButton' })
        .find((button) => button.props('iconName') === 'delete');

    expect(recycleButton()).toBeUndefined();
    wrapper
      .findComponent(ManagedDetailRelationInlineSurface)
      .vm.$emit('recycle-bin-availability-change', true);
    await flushPromises();
    expect(recycleButton()).toBeDefined();

    wrapper
      .findComponent(ManagedDetailRelationInlineSurface)
      .vm.$emit('recycle-bin-availability-change', false);
    await flushPromises();
    expect(recycleButton()).toBeUndefined();
  });

  it('ignores stale recycle-bin availability after the parent record changes', async () => {
    let resolveFirst!: (value: ReturnType<typeof page>) => void;
    const firstResponse = new Promise<ReturnType<typeof page>>((resolve) => {
      resolveFirst = resolve;
    });
    const request = vi
      .fn()
      .mockImplementationOnce(() => firstResponse)
      .mockImplementationOnce(async () => page([]));
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = {
      mode: 'INLINE',
      saveMode: 'AGGREGATE_DRAFT',
      recycleBinEnabled: true,
    };
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor: descriptor(),
        relation: aggregate,
        parentRecord: { id: 'first', properties: [] },
        mutationEnabled: true,
      },
    });

    await wrapper.setProps({ parentRecord: { id: 'second', properties: [] } });
    await flushPromises();
    resolveFirst(page([{ id: 'stale-deleted-child' }]));
    await flushPromises();

    expect(request).toHaveBeenCalledTimes(2);
    expect(wrapper.emitted('recycle-bin-availability-change')?.at(-1)).toEqual([false]);
  });

  it('uses the fixed gateway and blocks an invalid editor before HTTP', async () => {
    const request = vi.fn(async (options: { path: string; method?: string; body?: unknown }) => {
      if (options.path.endsWith('/query')) return page([]);
      return { id: 'property-1', version: 1, ...(options.body as object) };
    });
    const wrapper = shallowMount(ManagedDetailRelationSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor: descriptor(),
        relation: relation('properties'),
        parentRecord: { id: 'select', valueShape: 'COMPOSITE' },
        mutationEnabled: true,
      },
      global: {
        stubs: {
          RecordQueryListPanel: {
            name: 'RecordQueryListPanel',
            props: ['context'],
            template:
              '<section><slot name="toolbarActions" /><slot name="rowActions" :record="{ id: \'row-1\', version: 1 }" /></section>',
          },
          UiModal: { name: 'UiModal', props: ['open'], template: '<section><slot v-if="open" /></section>' },
        },
      },
    });

    const listContext = wrapper
      .findComponent({ name: 'RecordQueryListPanel' })
      .props('context') as ModuleContext<Record<string, unknown>>;
    await listContext.crud.query();
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'POST',
        path: '/platform.field_ui_control/view/select/relations/properties/query',
      }),
    );

    wrapper.findComponent({ name: 'ModuleActionButton' }).vm.$emit('click');
    await flushPromises();
    const fields = wrapper.findComponent({ name: 'RecordFormFields' });
    expect(fields.exists()).toBe(true);
    fields.vm.$emit('update:field', 'attributeAlias', 'placeholder');
    fields.vm.$emit('validity-change', { valid: false });
    await flushPromises();
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
    await flushPromises();
    expect(request.mock.calls.filter(([value]) => value.path.endsWith('/insert'))).toHaveLength(0);

    fields.vm.$emit('validity-change', { valid: true });
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
    await flushPromises();
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'POST',
        path: '/platform.field_ui_control/view/select/relations/properties/insert',
        body: { attributeAlias: 'placeholder' },
      }),
    );
  });

  it('evaluates the generic persisted-parent constraint without a module special case', () => {
    const binding = relation('bindings', { fieldName: 'valueShape', expectedValue: 'COMPOSITE' });
    const base = {
      sourceContext: context(vi.fn()),
      uiDescriptor: descriptor(),
      relations: [binding],
    };
    const scalar = shallowMount(ModulePageDetailRelations, {
      props: { ...base, parentRecord: { id: 'text', valueShape: 'SCALAR' } },
      global: { stubs: { RecordDetailExtensionSection: { template: '<section><slot /></section>' } } },
    });
    expect(scalar.findComponent({ name: 'ManagedDetailRelationSurface' }).exists()).toBe(false);

    const composite = shallowMount(ModulePageDetailRelations, {
      props: { ...base, parentRecord: { id: 'range', valueShape: 'COMPOSITE' } },
      global: { stubs: { RecordDetailExtensionSection: { template: '<section><slot /></section>' } } },
    });
    expect(composite.findComponent({ name: 'ManagedDetailRelationSurface' }).exists()).toBe(true);
  });

  it('does not expose a relation surface without a persisted parent or query authorization', () => {
    const managed = relation('properties');
    const noParentRequest = vi.fn();
    const noParent = shallowMount(ModulePageDetailRelations, {
      props: {
        sourceContext: context(noParentRequest),
        uiDescriptor: descriptor(),
        relations: [managed],
        parentRecord: {},
      },
    });
    expect(noParent.findComponent({ name: 'ManagedDetailRelationSurface' }).exists()).toBe(false);
    expect(noParentRequest).not.toHaveBeenCalled();

    const deniedRequest = vi.fn();
    const deniedContext = context(deniedRequest);
    deniedContext.can = () => false;
    const denied = shallowMount(ModulePageDetailRelations, {
      props: {
        sourceContext: deniedContext,
        uiDescriptor: descriptor(),
        relations: [managed],
        parentRecord: { id: 'select' },
      },
    });
    expect(denied.findComponent({ name: 'ManagedDetailRelationSurface' }).exists()).toBe(false);
    expect(deniedRequest).not.toHaveBeenCalled();
  });

  it('renders the same persisted relation as read-only until the parent enters edit mode', async () => {
    const request = vi.fn(async () => page([]));
    const wrapper = shallowMount(ManagedDetailRelationSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor: descriptor(),
        relation: relation('properties'),
        parentRecord: { id: 'select' },
        mutationEnabled: false,
      },
      global: {
        stubs: {
          RecordQueryListPanel: {
            name: 'RecordQueryListPanel',
            props: ['context'],
            template:
              '<section><slot name="toolbarActions" /><slot name="rowActions" :record="{ id: \'row-1\', version: 1 }" /></section>',
          },
        },
      },
    });

    expect(wrapper.findAllComponents({ name: 'ModuleActionButton' })).toHaveLength(0);
    const listContext = wrapper
      .findComponent({ name: 'RecordQueryListPanel' })
      .props('context') as ModuleContext<Record<string, unknown>>;
    await listContext.crud.query();
    expect(request).toHaveBeenCalledTimes(1);

    await wrapper.setProps({ mutationEnabled: true });
    expect(wrapper.findAllComponents({ name: 'ModuleActionButton' })).toHaveLength(3);
  });

  it('passes the compiled relation paging policy to the standard list surface', async () => {
    const configured = relation('properties');
    configured.queryContract = {
      ...configured.queryContract!,
      pageable: false,
      pageSize: undefined,
      pageSizeOptions: [],
    };
    const wrapper = shallowMount(ManagedDetailRelationSurface, {
      props: {
        sourceContext: context(vi.fn(async () => page([]))),
        uiDescriptor: descriptor(),
        relation: configured,
        parentRecord: { id: 'select' },
      },
    });

    expect(wrapper.findComponent({ name: 'RecordQueryListPanel' }).props()).toMatchObject({
      pageable: false,
      pageSize: 20,
      pageSizeOptions: [],
      showTitle: false,
      headerVisible: false,
      showRecycleBin: false,
      rowActionsVisible: false,
      embedded: true,
    });

    await wrapper.setProps({ mutationEnabled: true });
    expect(wrapper.findComponent({ name: 'RecordQueryListPanel' }).props()).toMatchObject({
      headerVisible: true,
      showRecycleBin: true,
      rowActionsVisible: true,
    });
  });
});

function context(request: ReturnType<typeof vi.fn>): ModuleContext<Record<string, unknown>> {
  const actions = ['query', 'create', 'update', 'delete'].map((operation) => ({
    actionCode: `field_ui_control_property_${operation}`,
    authorized: true,
  }));
  return {
    moduleAlias: 'platform.field_ui_control',
    http: { request } as never,
    crud: { query: vi.fn() } as never,
    runtime: {
      ready: Promise.resolve({ moduleAlias: 'platform.field_ui_control', capabilities: [], actions }),
      load: vi.fn(),
      snapshot: () => ({ moduleAlias: 'platform.field_ui_control', capabilities: [], actions }),
      error: () => undefined,
      hasAbility: () => false,
    } as never,
    abilities: {} as never,
    action: (actionCode) => ({ actionCode, available: actionCode.includes('property') }),
    runtimeAction: (actionCode) => actions.find((action) => action.actionCode === actionCode),
    can: (actionCode) => actionCode.includes('property'),
    recordActions: vi.fn(),
    recordActionsSnapshot: vi.fn(),
  };
}

function descriptor(): ResolvedModuleUiDescriptor {
  return {
    schemaVersion: 'module-ui.v6',
    moduleAlias: 'platform.field_ui_control',
    editorContributions: [
      {
        resource: 'field_ui_control_property',
        editor: {
          viewCode: 'field_ui_control_property-editor',
          viewKind: 'FORM',
          fields: [
            {
              fieldRef: { relationCode: 'field_ui_control_property', fieldName: 'attributeAlias' },
              label: '属性 alias',
              visible: { constant: true },
              required: { constant: true },
              readOnly: { constant: false },
            },
            {
              fieldRef: { relationCode: 'field_ui_control_property', fieldName: 'title' },
              label: '属性名称',
              visible: { constant: true },
              required: { constant: false },
              readOnly: { constant: false },
            },
          ],
        },
      },
    ],
  };
}

function relation(
  code: string,
  parentConstraint?: ResolvedDetailRelationDescriptor['parentConstraint'],
): ResolvedDetailRelationDescriptor {
  return {
    code,
    title: code === 'bindings' ? '字段绑定' : '控件属性',
    readOnly: false,
    sourceModuleAlias: 'platform.field_ui_control',
    sourceEntityAlias: 'field_ui_control',
    targetModuleAlias: 'platform.field_ui_control',
    targetEntityAlias: 'field_ui_control_property',
    parentBinding: 'fieldUiControlAlias',
    parentConstraint,
    refreshOnDetailReload: true,
    queryContract: {
      managedGateway: true,
      actionCode: 'field_ui_control_property_query',
      pageable: true,
      queryable: false,
      querySchema: {
        scopeName: 'property',
        fields: [],
        quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
        defaultSorts: [],
        externalCriteria: [],
      },
      listProjection: { fields: [{ fieldName: 'attributeAlias', title: '属性 alias' }] },
    },
    mutationContract: {
      createAllowed: true,
      updateAllowed: true,
      deleteAllowed: true,
      createActionCode: 'field_ui_control_property_create',
      updateActionCode: 'field_ui_control_property_update',
      deleteActionCode: 'field_ui_control_property_delete',
    },
  };
}

function page(records: Record<string, unknown>[]) {
  return { records, total: records.length, pageNum: 1, pageSize: 20, pages: 1, totalKnown: true };
}
