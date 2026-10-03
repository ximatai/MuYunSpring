import { defineComponent, h, ref } from 'vue';
import {
  provideRelationDraftRegistry,
  createRelationDraftRegistry,
  type RelationDraftRegistry,
} from '@/dynamic-page-runtime/relationDraftController';
import { createRelationDraftAssistantCapabilities } from '@/dynamic-page-runtime/relationDraftAssistantCapabilities';
import { config, flushPromises, mount, shallowMount } from '@vue/test-utils';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import ManagedDetailRelationSurface from '@/dynamic-page-runtime/ManagedDetailRelationSurface.vue';
import ModulePageDetailRelations from '@/dynamic-page-runtime/ModulePageDetailRelations.vue';
import ManagedDetailRelationInlineSurface from '@/dynamic-page-runtime/ManagedDetailRelationInlineSurface.vue';
import {
  createReferenceRecordDetailBrowser,
  referenceRecordDetailBrowserKey,
} from '@/platform-components/referenceRecordDetailBrowser';
import type { HttpClient, ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import type { ResolvedDetailRelationDescriptor, ResolvedModuleUiDescriptor } from '@muyun/web-contracts';

const originalStubs = config.global.stubs;
beforeEach(() => {
  config.global.stubs = { ...originalStubs, RecordRelationTable: false, RecordRelationValue: false };
});
afterEach(() => {
  config.global.stubs = originalStubs;
});

describe('managed detail relation surface', () => {
  it('preserves loaded children when opening an editable aggregate with parent draft feedback', async () => {
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    const original = [{ id: 'existing', attributeAlias: 'old' }];
    const draft = ref<{ id: string; properties: QueryListRecord[] }>({ id: 'parent', properties: original });
    const sourceContext = context(vi.fn());
    const uiDescriptor = descriptor();
    const wrapper = mount(
      defineComponent({
        setup: () => () =>
          h(ManagedDetailRelationInlineSurface, {
            sourceContext,
            uiDescriptor,
            relation: aggregate,
            parentRecord: draft.value,
            mutationEnabled: true,
            'onRecords-change': (records: QueryListRecord[]) => {
              draft.value = { ...draft.value, properties: records };
            },
          }),
      }),
    );
    await flushPromises();
    const child = wrapper.findComponent(ManagedDetailRelationInlineSurface);
    expect(
      child
        .emitted('records-change')
        ?.every(([records]) => JSON.stringify(records) === JSON.stringify(original)),
    ).toBe(true);
    expect(draft.value.properties).toEqual(original);
    wrapper.unmount();
  });

  it.each(['static.child', 'dynamic_child'])(
    'shares mounted aggregate draft operations with the assistant (%s)',
    async (entity) => {
      const aggregate = relation('properties');
      aggregate.targetEntityAlias = entity;
      aggregate.embeddedField = 'properties';
      aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
      aggregate.queryContract!.listProjection!.fields.push({ fieldName: 'title', title: '名称' });
      const ui = descriptor();
      ui.editorContributions![0]!.resource = entity;
      for (const field of ui.editorContributions![0]!.editor.fields) field.fieldRef.relationCode = entity;
      ui.editorContributions![0]!.editor.fields[1]!.assistantPolicy = 'HIDDEN';
      let registry!: RelationDraftRegistry;
      const request = vi.fn();
      const parent = mount(
        defineComponent({
          setup() {
            registry = createRelationDraftRegistry();
            provideRelationDraftRegistry(() => registry);
            return () =>
              h(ManagedDetailRelationInlineSurface, {
                sourceContext: context(request),
                uiDescriptor: ui,
                relation: aggregate,
                parentRecord: {
                  id: 'parent',
                  properties: [{ id: 'existing', attributeAlias: 'old', title: 'secret' }],
                },
                mutationEnabled: true,
              });
          },
        }),
      );
      await flushPromises();
      const child = parent.findComponent(ManagedDetailRelationInlineSurface);
      const displayRevision = registry.revision();
      child
        .findAllComponents({ name: 'RecordFormFields' })[0]!
        .vm.$emit('reference-projections-change', 'attributeAlias', { unrelatedTitle: '已解析名称' });
      await flushPromises();
      expect(registry.revision()).toBe(displayRevision);
      const capabilities = createRelationDraftAssistantCapabilities(registry, registry.revision);
      const execution = {
        signal: new AbortController().signal,
        isCurrent: () => true,
        commitInternalState: <T>(commit: () => T) => commit(),
        applyEffect: <T>(commit: () => T) => commit(),
      };
      const invoke = async (code: string, input = {}) => {
        const capability = capabilities().find((item) => item.descriptor.code === code)!;
        return capability.execute(capability.parseInput(input), execution);
      };
      expect(JSON.stringify(await invoke('relation.describe'))).not.toContain('secret');
      const added = (await invoke('relation.add-row', { relationCode: 'properties' })) as { rowKey: string };
      await flushPromises();
      const before = registry.revision();
      await expect(
        invoke('relation.form.patch-draft', {
          changes: [
            { fieldName: 'attributeAlias', value: 'must-not-apply' },
            { fieldName: 'title', value: 'blocked' },
          ],
        }),
      ).rejects.toThrow('not editable');
      expect(registry.revision()).toBe(before);
      await invoke('relation.form.patch-draft', { changes: [{ fieldName: 'attributeAlias', value: 'new' }] });
      await flushPromises();
      expect(child.emitted('records-change')?.at(-1)?.[0]).toEqual([
        { id: 'existing', attributeAlias: 'old', title: 'secret' },
        { attributeAlias: 'new' },
      ]);
      expect(registry.interactionRevision()).toBe(0);
      await invoke('relation.remove-row', { relationCode: 'properties', rowKey: added.rowKey });
      expect(child.emitted('records-change')?.at(-1)?.[0]).toEqual([
        { id: 'existing', attributeAlias: 'old', title: 'secret' },
      ]);
      expect(capabilities().some((item) => item.descriptor.code === 'relation.form.patch-draft')).toBe(false);
      expect(request).not.toHaveBeenCalled();
      parent.unmount();
      expect(capabilities()).toEqual([]);
    },
  );

  it.each(['static.child', 'dynamic_child'])(
    'publishes a complete multi-field draft once (%s)',
    async (entity) => {
      const aggregate = relation('properties');
      aggregate.targetEntityAlias = entity;
      aggregate.embeddedField = 'properties';
      aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
      aggregate.queryContract!.listProjection!.fields.push({ fieldName: 'title', title: '名称' });
      aggregate.formComputeRules = [
        {
          code: 'clearOtherTitles',
          targetField: 'title',
          targetValueType: 'STRING',
          triggerFields: ['attributeAlias', 'title'],
          program: {
            schemaVersion: 1,
            profile: 'FORM_COMPUTE',
            referencedFields: ['title'],
            root: {
              kind: 'ASSIGN',
              operator: '=',
              arguments: [
                { kind: 'OTHERS', field: 'properties.title', arguments: [] },
                { kind: 'VALUE', value: '不应写入', arguments: [] },
                {
                  kind: 'BINARY',
                  operator: '==',
                  arguments: [
                    { kind: 'FIELD', field: 'title', arguments: [] },
                    { kind: 'VALUE', value: '旧名称', arguments: [] },
                  ],
                },
              ],
            },
          },
        },
      ];
      const sibling = { id: 'other', attributeAlias: 'other', title: '另一行' };
      const ui = descriptor();
      ui.editorContributions![0]!.resource = entity;
      for (const field of ui.editorContributions![0]!.editor.fields) field.fieldRef.relationCode = entity;
      const registry = createRelationDraftRegistry();
      const parent = mount(
        defineComponent({
          setup() {
            provideRelationDraftRegistry(() => registry);
            return () =>
              h(ManagedDetailRelationInlineSurface, {
                sourceContext: context(vi.fn()),
                uiDescriptor: ui,
                relation: aggregate,
                parentRecord: {
                  id: 'parent',
                  properties: [{ id: 'existing', attributeAlias: 'old', title: '旧名称' }, sibling],
                },
                mutationEnabled: true,
              });
          },
        }),
      );
      await flushPromises();
      const child = parent.findComponent(ManagedDetailRelationInlineSurface);
      const controller = registry.list()[0]!;
      const form = controller.form(controller.rowKeys()[0]!)!;
      const before = child.emitted('records-change')?.length ?? 0;
      form.updateDraftFields(
        [
          { fieldName: 'attributeAlias', value: 'new' },
          { fieldName: 'title', value: '新名称' },
        ],
        'assistant',
      );
      expect(
        child
          .emitted('records-change')
          ?.slice(before)
          .map(([records]) => records),
      ).toEqual([[{ id: 'existing', attributeAlias: 'new', title: '新名称' }, sibling]]);
      expect(registry.interactionRevision()).toBe(0);
      const beforeSelection = child.emitted('records-change')?.length ?? 0;
      form.updateDraftReference(
        'attributeAlias',
        {
          id: 'selected',
          title: '选中的属性',
          affectPatch: { title: '回填名称' },
          projections: { 'attributeAlias.title': '选中的属性' },
        },
        'assistant',
      );
      const selectionEvents = child.emitted('records-change')?.slice(beforeSelection);
      expect(selectionEvents).toHaveLength(1);
      expect(selectionEvents?.[0]?.[0]).toEqual([
        { id: 'existing', attributeAlias: 'selected', title: '回填名称' },
        sibling,
      ]);
      expect(selectionEvents?.[0]?.[1]).toMatchObject([
        {
          id: 'existing',
          attributeAlias: 'selected',
          title: '回填名称',
          'attributeAlias.title': '选中的属性',
        },
        sibling,
      ]);
      form.updateDraftFields([{ fieldName: 'attributeAlias', value: 'different' }], 'user');
      expect(form.editingRecord?.['attributeAlias.title']).toBeUndefined();
      expect(registry.interactionRevision()).toBe(1);
      parent.unmount();
    },
  );

  it.each(['static.child', 'dynamic_child'])(
    'previews same-row calculations for human and assistant edits without saving (%s)',
    async (entity) => {
      const aggregate = relation('properties');
      aggregate.targetEntityAlias = entity;
      aggregate.embeddedField = 'properties';
      aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
      aggregate.listProjection = {
        fields: ['quantity', 'unitPrice', 'amount'].map((fieldName) => ({ fieldName, title: fieldName })),
      };
      const ui = descriptor();
      const editor = ui.editorContributions![0]!.editor;
      ui.editorContributions![0]!.resource = entity;
      editor.fields = ['quantity', 'unitPrice', 'amount'].map((fieldName) => ({
        fieldRef: { relationCode: entity, fieldName },
        label: fieldName,
        valueType: 'DECIMAL',
        visible: { constant: true },
        required: { constant: false },
        readOnly: { constant: fieldName === 'amount' },
        ...(fieldName === 'amount' ? { calculationTiming: 'IMMEDIATE' as const } : {}),
      }));
      editor.formComputeRules = [
        {
          code: 'amount',
          targetField: 'amount',
          targetValueType: 'DECIMAL',
          triggerFields: ['quantity', 'unitPrice'],
          writePolicy: 'ALWAYS',
          program: {
            schemaVersion: 1,
            profile: 'FORM_COMPUTE',
            referencedFields: ['amount', 'quantity', 'unitPrice'],
            root: {
              kind: 'ASSIGN',
              operator: '=',
              arguments: [
                { kind: 'FIELD', field: 'amount', arguments: [] },
                {
                  kind: 'BINARY',
                  operator: '*',
                  arguments: [
                    { kind: 'FIELD', field: 'quantity', arguments: [] },
                    { kind: 'FIELD', field: 'unitPrice', arguments: [] },
                  ],
                },
              ],
            },
          },
        },
      ];
      aggregate.formComputeRules = [
        {
          code: 'setOtherQuantity',
          targetField: 'quantity',
          targetValueType: 'DECIMAL',
          triggerFields: ['quantity'],
          program: {
            schemaVersion: 1,
            profile: 'FORM_COMPUTE',
            referencedFields: ['quantity'],
            root: {
              kind: 'ASSIGN',
              operator: '=',
              arguments: [
                { kind: 'OTHERS', field: 'quantity', arguments: [] },
                { kind: 'VALUE', value: 1, arguments: [] },
                {
                  kind: 'BINARY',
                  operator: '==',
                  arguments: [
                    { kind: 'FIELD', field: 'quantity', arguments: [] },
                    { kind: 'VALUE', value: 7, arguments: [] },
                  ],
                },
              ],
            },
          },
        },
      ];
      const registry = createRelationDraftRegistry();
      const request = vi.fn();
      const parent = mount(
        defineComponent({
          setup() {
            provideRelationDraftRegistry(() => registry);
            return () =>
              h(ManagedDetailRelationInlineSurface, {
                sourceContext: context(request),
                uiDescriptor: ui,
                relation: aggregate,
                parentRecord: {
                  id: 'parent',
                  properties: [
                    { id: 'first', quantity: 12, unitPrice: 17.8, amount: 213.6 },
                    { id: 'second', quantity: 5, unitPrice: 8.8, amount: 44 },
                  ],
                },
                mutationEnabled: true,
              });
          },
        }),
      );
      await flushPromises();
      const child = parent.findComponent(ManagedDetailRelationInlineSurface);
      child.findAllComponents({ name: 'RecordFormFields' })[0]!.vm.$emit('update:field', 'quantity', 13);
      await flushPromises();
      expect(
        (child.emitted('records-change')?.at(-1)?.[1] as QueryListRecord[]).map((record) => {
          const displayed = { ...record };
          delete displayed.__draftKey;
          return displayed;
        }),
      ).toEqual([
        { id: 'first', quantity: 13, unitPrice: 17.8, amount: 231.4 },
        { id: 'second', quantity: 5, unitPrice: 8.8, amount: 44 },
      ]);
      expect(child.emitted('records-change')?.at(-1)?.[0]).toEqual([
        { id: 'first', quantity: 13, unitPrice: 17.8 },
        { id: 'second', quantity: 5, unitPrice: 8.8 },
      ]);
      const form = registry.list()[0]!.form(registry.list()[0]!.rowKeys()[0]!)!;
      const before = child.emitted('records-change')?.length ?? 0;
      form.updateDraftFields(
        [
          { fieldName: 'quantity', value: 3 },
          { fieldName: 'unitPrice', value: 20 },
        ],
        'assistant',
      );
      expect(child.emitted('records-change')?.slice(before)).toHaveLength(1);
      expect(form.editingRecord).toMatchObject({ quantity: 3, unitPrice: 20, amount: 60 });
      form.updateDraftFields([{ fieldName: 'quantity', value: 7 }], 'assistant');
      expect(child.emitted('records-change')?.at(-1)?.[1]).toEqual([
        expect.objectContaining({ quantity: 7, unitPrice: 20, amount: 140 }),
        expect.objectContaining({ quantity: 1, unitPrice: 8.8, amount: 8.8 }),
      ]);
      expect(request).not.toHaveBeenCalled();
      parent.unmount();
    },
  );

  it('recomputes a row after a platform dependency clears a reference', async () => {
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    aggregate.listProjection = {
      fields: ['categoryId', 'productId', 'hasProduct'].map((fieldName) => ({ fieldName, title: fieldName })),
    };
    const ui = descriptor();
    const editor = ui.editorContributions![0]!.editor;
    editor.fields = ['categoryId', 'productId', 'hasProduct'].map((fieldName) => ({
      fieldRef: { relationCode: aggregate.targetEntityAlias, fieldName },
      label: fieldName,
      valueType: fieldName === 'hasProduct' ? 'BOOLEAN' : 'STRING',
      visible: { constant: true },
      required: { constant: false },
      readOnly: { constant: fieldName === 'hasProduct' },
      ...(fieldName === 'hasProduct' ? { calculationTiming: 'IMMEDIATE' as const } : {}),
      ...(fieldName === 'productId'
        ? {
            reference: {
              targetModuleAlias: 'demo.product',
              candidateDelivery: 'SOURCE_FIELD' as const,
              resolvePath: '/references/productId/resolve',
              cardinality: 'ONE' as const,
              candidateDependencies: [
                { sourceField: 'categoryId', targetField: 'categoryId', required: true },
              ],
            },
          }
        : {}),
    }));
    editor.formComputeRules = [
      {
        code: 'hasProduct',
        targetField: 'hasProduct',
        targetValueType: 'BOOLEAN',
        triggerFields: ['productId'],
        writePolicy: 'ALWAYS',
        program: {
          schemaVersion: 1,
          profile: 'FORM_COMPUTE',
          referencedFields: ['hasProduct', 'productId'],
          root: {
            kind: 'ASSIGN',
            operator: '=',
            arguments: [
              { kind: 'FIELD', field: 'hasProduct', arguments: [] },
              {
                kind: 'FUNCTION',
                operator: 'PRESENT',
                arguments: [{ kind: 'FIELD', field: 'productId', arguments: [] }],
              },
            ],
          },
        },
      },
    ];
    const request = vi.fn();
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor: ui,
        relation: aggregate,
        parentRecord: {
          id: 'parent',
          properties: [{ id: 'row', categoryId: 'old', productId: 'product', hasProduct: true }],
        },
        mutationEnabled: true,
      },
    });
    await flushPromises();
    wrapper.findAllComponents({ name: 'RecordFormFields' })[0]!.vm.$emit('update:field', 'categoryId', 'new');
    await flushPromises();
    expect(wrapper.emitted('records-change')?.at(-1)?.[1]).toEqual([
      expect.objectContaining({ categoryId: 'new', productId: undefined, hasProduct: false }),
    ]);
    expect(request).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('allows aggregate draft rows before the parent has been persisted', async () => {
    const aggregate = relation('properties');
    aggregate.embeddedField = 'properties';
    aggregate.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    const wrapper = shallowMount(ModulePageDetailRelations, {
      props: {
        sourceContext: context(vi.fn(async () => page([]))),
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
    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([]);

    await wrapper.setProps({ validationRequestKey: 1 });

    expect(wrapper.find('.managed-relation-inline__cell--validation-pulse').exists()).toBe(false);
    expect(wrapper.find('[title="属性 alias不能为空"]').exists()).toBe(false);
  });

  it('renders relation booleans with semantic labels instead of JavaScript literals', async () => {
    const managed = relation('properties');
    managed.embeddedField = 'properties';
    managed.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    managed.queryContract!.listProjection = {
      fields: [
        { fieldName: 'primary', title: '主项' },
        { fieldName: 'enabled', title: '启用状态' },
      ],
    };
    const uiDescriptor = descriptor();
    uiDescriptor.editorContributions![0]!.editor.fields.push(
      {
        fieldRef: { relationCode: 'field_ui_control_property', fieldName: 'primary' },
        label: '主项',
        visible: { constant: true },
        required: { constant: false },
        readOnly: { constant: false },
        uiType: 'switch',
      },
      {
        fieldRef: { relationCode: 'field_ui_control_property', fieldName: 'enabled' },
        label: '启用状态',
        visible: { constant: true },
        required: { constant: false },
        readOnly: { constant: false },
        uiType: 'enabledStatus',
      },
    );
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(vi.fn()),
        uiDescriptor,
        relation: managed,
        parentRecord: { id: 'select', properties: [{ id: 'row-1', primary: false, enabled: true }] },
        mutationEnabled: false,
      },
    });
    await flushPromises();

    expect(wrapper.find('.managed-relation-inline__value').text()).toBe('否');
    expect(wrapper.findComponent({ name: 'RecordStatusTag' }).props('enabled')).toBe(true);
  });

  it('resolves dictionary labels for a read-only inline child table', async () => {
    const managed = relation('properties');
    managed.embeddedField = 'properties';
    managed.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    managed.queryContract!.listProjection = {
      fields: [{ fieldName: 'attendanceStatus', title: '参加状态' }],
    };
    const uiDescriptor = descriptor();
    uiDescriptor.editorContributions![0]!.editor.fields.push({
      fieldRef: { relationCode: 'field_ui_control_property', fieldName: 'attendanceStatus' },
      label: '参加状态',
      visible: { constant: true },
      required: { constant: true },
      readOnly: { constant: false },
      option: {
        binding: { sourceType: 'dictionary', source: 'education.exam_attendance_status' },
        selectionMode: 'SINGLE',
      },
    });
    const request = vi.fn(async () => [{ code: 'ATTENDED', title: '已参加', enabled: true }]);
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor,
        relation: managed,
        parentRecord: { id: 'exam-1', properties: [{ id: 'row-1', attendanceStatus: 'ATTENDED' }] },
        mutationEnabled: false,
      },
    });
    await flushPromises();

    expect(wrapper.find('.managed-relation-inline__value').text()).toBe('已参加');
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/platform.module/platform.field_ui_control/fields/attendanceStatus/options',
        query: { enabledOnly: false, entityAlias: 'field_ui_control_property' },
      }),
    );
  });

  it('keeps a read-only inline reference projection available to the shared detail browser', async () => {
    const managed = relation('properties');
    managed.embeddedField = 'properties';
    managed.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    managed.queryContract!.listProjection = { fields: [{ fieldName: 'supplierId', title: '供应商' }] };
    const uiDescriptor = descriptor();
    uiDescriptor.editorContributions![0]!.editor.fields = [
      {
        fieldRef: { relationCode: 'field_ui_control_property', fieldName: 'supplierId' },
        label: '供应商',
        visible: { constant: true },
        required: { constant: false },
        readOnly: { constant: true },
        reference: {
          targetModuleAlias: 'purchase.supplier',
          cardinality: 'ONE',
          titleField: 'supplierSummary',
        },
      },
    ];
    const request = vi.fn(async (options: { path: string }) => {
      if (options.path.endsWith('/view-context')) {
        return {
          moduleAlias: 'purchase.supplier',
          actions: [],
          capabilities: [],
          uiDescriptor: { page: { detail: { display: { fields: [] } } } },
        };
      }
      return { id: 'supplier-1', title: '星河供应商详情' };
    });
    const browser = createReferenceRecordDetailBrowser({ request } as HttpClient);
    const wrapper = mount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(vi.fn()),
        uiDescriptor,
        relation: managed,
        parentRecord: {
          id: 'purchase-1',
          properties: [
            {
              id: 'row-1',
              supplierId: 'supplier-1',
              supplierSummary: { id: 'supplier-1', title: '星河供应商' },
            },
          ],
        },
        mutationEnabled: false,
      },
      global: { provide: { [referenceRecordDetailBrowserKey]: browser } },
    });
    await flushPromises();

    const value = wrapper.findComponent({ name: 'RecordRelationValue' });
    expect(value.props('text')).toBeUndefined();
    expect(value.text()).toContain('星河供应商');
    await wrapper.get('button[title="查看 星河供应商"]').trigger('click');
    await flushPromises();

    expect(request).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ path: '/platform.module/purchase.supplier/view-context' }),
    );
    expect(request).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ path: '/purchase.supplier/view/supplier-1' }),
    );
    expect(browser.active.value?.record).toMatchObject({ id: 'supplier-1', title: '星河供应商详情' });
  });

  it('keeps reference projection columns read-only while refreshing them from the selected record', async () => {
    const managed = relation('properties');
    managed.embeddedField = 'properties';
    managed.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    managed.queryContract!.listProjection = {
      fields: [
        { fieldName: 'studentId', title: '学生' },
        { fieldName: 'studentNo', title: '学号' },
      ],
    };
    const uiDescriptor = descriptor();
    uiDescriptor.editorContributions![0]!.editor.fields = [
      {
        fieldRef: { relationCode: 'field_ui_control_property', fieldName: 'studentId' },
        label: '学生',
        visible: { constant: true },
        required: { constant: true },
        readOnly: { constant: false },
        reference: {
          targetModuleAlias: 'education.student',
          cardinality: 'ONE',
          displayProjections: [{ targetField: 'studentNo', outputField: 'studentNo' }],
        },
      },
    ];
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(vi.fn(async () => page([]))),
        uiDescriptor,
        relation: managed,
        parentRecord: { id: 'exam-1', properties: [{ id: 'row-1', studentId: 'student-1' }] },
        mutationEnabled: true,
      },
    });
    await flushPromises();

    const editors = wrapper.findAllComponents({ name: 'RecordFormFields' });
    expect(editors).toHaveLength(1);
    editors[0]!.vm.$emit('reference-projections-change', 'studentId', { studentNo: 'S2026001' });
    await flushPromises();

    expect(wrapper.find('.managed-relation-inline__value').text()).toBe('S2026001');
    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([{ id: 'row-1', studentId: 'student-1' }]);
    expect(wrapper.emitted('records-change')?.at(-1)?.[1]).toMatchObject([
      { id: 'row-1', studentId: 'student-1', studentNo: 'S2026001' },
    ]);
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
    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([{ title: '已填写名称' }]);

    await wrapper.setProps({ validationRequestKey: 1 });

    expect(wrapper.find('.managed-relation-inline__cell--validation-pulse').exists()).toBe(true);
    const firstPulse = wrapper.get('.managed-relation-inline__cell--validation-pulse').element;
    const firstEditor = wrapper.findAllComponents({ name: 'RecordFormFields' })[1]!;
    await wrapper.setProps({ validationRequestKey: 2 });
    expect(wrapper.get('.managed-relation-inline__cell--validation-pulse').element).toBe(firstPulse);
    expect(wrapper.findAllComponents({ name: 'RecordFormFields' })[1]!.vm).toBe(firstEditor.vm);
    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([{ title: '已填写名称' }]);
  });

  it('retains an unmatched reference draft across repeated aggregate validation without replaying its prior ID', async () => {
    const managed = relation('properties');
    managed.embeddedField = 'properties';
    managed.editing = { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' };
    managed.queryContract!.listProjection = { fields: [{ fieldName: 'studentId', title: '学生' }] };
    const uiDescriptor = descriptor();
    uiDescriptor.editorContributions![0]!.editor.fields = [
      {
        fieldRef: { relationCode: 'field_ui_control_property', fieldName: 'studentId' },
        label: '学生',
        visible: { constant: true },
        required: { constant: true },
        readOnly: { constant: false },
        uiType: 'recordPicker',
        reference: {
          targetModuleAlias: 'education.student',
          cardinality: 'ONE',
          candidateDelivery: 'SOURCE_FIELD',
          resolvePath: '/references/studentId/resolve',
        },
      },
    ];
    const request = vi.fn(async () => ({ options: [], results: [], total: 0 }));
    const wrapper = mount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor,
        relation: managed,
        parentRecord: { id: 'exam-1', properties: [{ id: 'row-1', studentId: 'student-1' }] },
        mutationEnabled: true,
      },
      global: {
        stubs: {
          UiSearchInput: {
            name: 'UiSearchInput',
            props: ['value'],
            emits: ['update:value', 'search', 'blur'],
            template: `
              <div>
                <input :value="value" @input="$emit('update:value', $event.target.value)" @blur="$emit('blur', $event)" />
                <button @click="$emit('search', value)">搜索</button>
              </div>
            `,
          },
          UiModal: { name: 'UiModal', props: ['open'], template: '<section v-if="open"><slot /></section>' },
          UiDataTable: { name: 'UiDataTable', template: '<section />' },
          UiTree: { name: 'UiTree', template: '<section />' },
          RecordExplorerPanel: { name: 'RecordExplorerPanel', template: '<section><slot /></section>' },
        },
      },
    });
    await flushPromises();

    const picker = wrapper.findComponent({ name: 'ReferencePicker' });
    expect(picker.exists()).toBe(true);
    expect(picker.props('value')).toBe('student-1');
    const input = picker.get('input');
    await input.setValue('不存在的学生');
    await input.trigger('blur', { relatedTarget: document.body });
    await flushPromises();

    expect((input.element as HTMLInputElement).value).toBe('不存在的学生');
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([false]);
    expect(picker.props('value')).toBe('student-1');
    const recordsBeforeValidation = wrapper.emitted('records-change')?.length ?? 0;

    await wrapper.setProps({ validationRequestKey: 1 });
    await wrapper.setProps({ validationRequestKey: 2 });

    const retainedPicker = wrapper.findComponent({ name: 'ReferencePicker' });
    expect(retainedPicker.element).toBe(picker.element);
    expect((retainedPicker.get('input').element as HTMLInputElement).value).toBe('不存在的学生');
    expect(retainedPicker.props('value')).toBe('student-1');
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([false]);
    expect(wrapper.emitted('records-change')?.length ?? 0).toBe(recordsBeforeValidation);
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
    expect(field.props('optionEntityAlias')).toBe('field_ui_control_property');
    const columns = wrapper.findAll('col');
    expect(columns[0]!.classes()).toContain('managed-relation-inline__selection-column');
    expect(columns[1]!.attributes('style')).toBeUndefined();
    expect(wrapper.find('.managed-relation-inline__table').attributes('style')).toContain('min-width: 214px');
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
    const ui = descriptor();
    const editor = ui.editorContributions![0]!.editor;
    const title = editor.fields.find((field) => field.fieldRef.fieldName === 'title')!;
    title.readOnly = { constant: true };
    title.calculationTiming = 'IMMEDIATE';
    title.valueType = 'STRING';
    editor.formComputeRules = [
      {
        code: 'currentTitle',
        targetField: 'title',
        targetValueType: 'STRING',
        triggerFields: ['attributeAlias'],
        writePolicy: 'ALWAYS',
        program: {
          schemaVersion: 1,
          profile: 'FORM_COMPUTE',
          referencedFields: ['title', 'attributeAlias'],
          root: {
            kind: 'ASSIGN',
            operator: '=',
            arguments: [
              { kind: 'FIELD', field: 'title', arguments: [] },
              {
                kind: 'BINARY',
                operator: '+',
                arguments: [
                  { kind: 'VALUE', value: '新规则:', arguments: [] },
                  { kind: 'FIELD', field: 'attributeAlias', arguments: [] },
                ],
              },
            ],
          },
        },
      },
    ];
    const wrapper = shallowMount(ManagedDetailRelationInlineSurface, {
      props: {
        sourceContext: context(request),
        uiDescriptor: ui,
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
    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([{ attributeAlias: 'rows' }]);
    expect(wrapper.emitted('records-change')?.at(-1)?.[1]).toEqual([
      expect.objectContaining({ attributeAlias: 'rows', title: '新规则:rows' }),
    ]);
    expect(wrapper.emitted('recycle-bin-availability-change')?.at(-1)).toEqual([false]);

    await wrapper.setProps({ recycleBinRequestKey: 2 });
    await flushPromises();

    expect(request).toHaveBeenCalledTimes(3);
    expect(wrapper.find('.managed-relation-inline__recycle-table').text()).toContain('暂无可恢复记录');
    expect(wrapper.findAllComponents({ name: 'RecordSelectionCheckbox' })).toHaveLength(2);
    expect(wrapper.emitted('records-change')?.at(-1)?.[0]).toEqual([{ attributeAlias: 'rows' }]);
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
    expect(fields.props('optionEntityAlias')).toBe('field_ui_control_property');
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

  it('passes reference presentation companions to child list cells', () => {
    const configured = relation('properties');
    configured.queryContract!.querySchema!.fields = [
      {
        name: 'studentId',
        title: '学生',
        valueType: 'STRING',
        operators: [],
        defaultOperator: 'LIKE',
        quickSearch: false,
        sortable: false,
        optionTitleField: 'studentTitle',
      },
    ];
    configured.queryContract!.listProjection = { fields: [{ fieldName: 'studentId', title: '学生' }] };
    const wrapper = shallowMount(ManagedDetailRelationSurface, {
      props: {
        sourceContext: context(vi.fn(async () => page([]))),
        uiDescriptor: descriptor(),
        relation: configured,
        parentRecord: { id: 'select' },
      },
    });

    expect(wrapper.findComponent({ name: 'RecordQueryListPanel' }).props('columns')).toEqual([
      expect.objectContaining({ key: 'studentId', titleField: 'studentTitle', optionBinding: undefined }),
    ]);
  });

  it('marks a managed child dictionary column with its target entity option context', () => {
    const configured = relation('properties');
    configured.queryContract!.listProjection = {
      fields: [{ fieldName: 'attendanceStatus', title: '参加状态' }],
    };
    const uiDescriptor = descriptor();
    uiDescriptor.editorContributions![0]!.editor.fields.push({
      fieldRef: { relationCode: 'field_ui_control_property', fieldName: 'attendanceStatus' },
      label: '参加状态',
      visible: { constant: true },
      required: { constant: false },
      readOnly: { constant: false },
      option: {
        binding: { sourceType: 'dictionary', source: 'platform.attendance_status' },
        selectionMode: 'SINGLE',
      },
    });
    const wrapper = shallowMount(ManagedDetailRelationSurface, {
      props: {
        sourceContext: context(vi.fn(async () => page([]))),
        uiDescriptor,
        relation: configured,
        parentRecord: { id: 'select' },
      },
    });

    expect(wrapper.findComponent({ name: 'RecordQueryListPanel' }).props('columns')).toEqual([
      expect.objectContaining({
        key: 'attendanceStatus',
        optionBinding: true,
        optionEntityAlias: 'field_ui_control_property',
      }),
    ]);
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
