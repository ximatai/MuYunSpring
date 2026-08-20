import { flushPromises, shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import ManagedDetailRelationSurface from '@/dynamic-page-runtime/ManagedDetailRelationSurface.vue';
import ModulePageDetailRelations from '@/dynamic-page-runtime/ModulePageDetailRelations.vue';
import type { ModuleContext } from '@muyun/web-core';
import type { ResolvedDetailRelationDescriptor, ResolvedModuleUiDescriptor } from '@muyun/web-contracts';

describe('managed detail relation surface', () => {
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
