import { flushPromises, shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import DetailRelationListPanel from '@/platform-components/DetailRelationListPanel.vue';
import type { ModuleContext } from '@muyun/web-core';
import type { ResolvedDetailRelationDescriptor } from '@muyun/web-contracts';

describe('DetailRelationListPanel', () => {
  it('uses only the server-issued relation query contract and its list projection', async () => {
    const request = vi.fn();
    const wrapper = shallowMount(DetailRelationListPanel, {
      props: {
        sourceContext: sourceContext(request),
        recordId: 'customer / 1',
        relation: executableRelation(),
      },
    });
    await flushPromises();

    const list = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(list.exists()).toBe(true);
    expect(list.props('columns')).toEqual([
      expect.objectContaining({
        key: 'summary',
        title: '摘要',
        width: '240px',
        align: 'center',
        maxDisplayLines: 2,
      }),
    ]);
    expect(list.props('uiConfigId')).toBe('contract-list');
    expect(list.props('queryTemplateId')).toBe('contract-default');
    expect(list.props('querySchema')).toEqual(
      expect.objectContaining({ scopeName: 'crm.contract', entityAlias: 'contract' }),
    );
    expect(list.props('queryable')).toBe(true);
    expect(list.props('pageable')).toBe(true);

    const relationContext = list.props('context') as ModuleContext<Record<string, unknown>>;
    await relationContext.crud.query({
      conditions: [{ fieldName: 'summary', operator: 'LIKE', values: ['A'] }],
    });
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/crm.customer/view/customer%20%2F%201/associations/contracts/query',
      body: { conditions: [{ fieldName: 'summary', operator: 'LIKE', values: ['A'] }] },
    });
  });

  it('does not mount or request for a static/incomplete relation declaration', async () => {
    const request = vi.fn();
    const relation: ResolvedDetailRelationDescriptor = {
      ...executableRelation(),
      queryContract: undefined,
    };
    const wrapper = shallowMount(DetailRelationListPanel, {
      props: { sourceContext: sourceContext(request), recordId: 'customer-1', relation },
    });
    await flushPromises();

    expect(wrapper.findComponent({ name: 'RecordQueryListPanel' }).exists()).toBe(false);
    expect(request).not.toHaveBeenCalled();
  });
});

function executableRelation(): ResolvedDetailRelationDescriptor {
  return {
    code: 'contracts',
    title: '合同',
    readOnly: true,
    sourceModuleAlias: 'crm.customer',
    sourceEntityAlias: 'customer',
    targetModuleAlias: 'crm.contract',
    targetEntityAlias: 'contract',
    parentBinding: 'customerId',
    refreshOnDetailReload: true,
    queryContract: {
      queryPath: '/crm.customer/view/{id}/associations/contracts/query',
      targetUiConfigId: 'contract-list',
      queryTemplateId: 'contract-default',
      pageable: true,
      queryable: true,
      querySchema: {
        scopeName: 'crm.contract',
        entityAlias: 'contract',
        quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
        fields: [],
        externalCriteria: [],
        defaultSorts: [],
      },
      listProjection: {
        uiConfigId: 'contract-list',
        fields: [
          {
            fieldName: 'summary',
            title: '摘要',
            width: 240,
            align: 'center',
            maxDisplayLines: 2,
          },
        ],
      },
    },
  };
}

function sourceContext(request: ReturnType<typeof vi.fn>): ModuleContext<Record<string, unknown>> {
  return {
    moduleAlias: 'crm.customer',
    http: { request: request.mockResolvedValue({}) },
  } as unknown as ModuleContext<Record<string, unknown>>;
}
