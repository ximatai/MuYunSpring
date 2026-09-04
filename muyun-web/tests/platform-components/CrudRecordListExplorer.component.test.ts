import { flushPromises, shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import CrudRecordListExplorer from '@/platform-components/CrudRecordListExplorer.vue';
import type { ModuleContext } from '@muyun/web-core';
import type { WebQueryRequest } from '@muyun/web-contracts';

describe('CrudRecordListExplorer', () => {
  it('forwards upstream navigator criteria and reloads when they change', async () => {
    const requests: Array<WebQueryRequest | undefined> = [];
    const wrapper = shallowMount(CrudRecordListExplorer, {
      props: {
        context: createContext(requests),
        externalQueryValues: { tenantId: 'tenant-a' },
      },
    });

    await flushPromises();

    expect(requests.at(-1)).toMatchObject({
      page: { pageNum: 1, pageSize: 200 },
      externalQueryValues: { tenantId: 'tenant-a' },
    });

    await wrapper.setProps({ externalQueryValues: { tenantId: 'tenant-b' } });
    await flushPromises();

    expect(requests.at(-1)).toMatchObject({
      externalQueryValues: { tenantId: 'tenant-b' },
    });
    expect(requests).toHaveLength(2);
  });

  it('forwards an explorer deselect event for an externally owned selection', async () => {
    const wrapper = shallowMount(CrudRecordListExplorer, {
      props: {
        context: createContext([]),
        selectedId: 'rule-1',
      },
    });

    await flushPromises();
    wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('deselect');

    expect(wrapper.emitted('deselect')).toEqual([[]]);
    expect(wrapper.emitted('select')).toBeUndefined();
  });

  it('persists flat vertical drops through the standard sort contract', async () => {
    const sortCalls: Array<{ id: string; request: unknown }> = [];
    const wrapper = shallowMount(CrudRecordListExplorer, {
      props: {
        context: createContext([], [{ id: 'first' }, { id: 'second' }], sortCalls),
        sorting: true,
      },
    });

    await flushPromises();
    wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('sort', {
      dragRecord: { id: 'first' },
      dropRecord: { id: 'second' },
      position: 1,
    });
    await flushPromises();

    expect(sortCalls).toEqual([{ id: 'first', request: { previousId: 'second', nextId: null } }]);
    expect(wrapper.emitted('sorted')).toEqual([[]]);
  });

  it('uses the loaded row as an anchor even when the result has more records', async () => {
    const sortCalls: Array<{ id: string; request: unknown }> = [];
    const wrapper = shallowMount(CrudRecordListExplorer, {
      props: {
        context: createContext([], [{ id: 'first' }, { id: 'second' }], sortCalls, [], 201),
        sorting: true,
      },
    });

    await flushPromises();
    wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('sort', {
      dragRecord: { id: 'first' },
      dropRecord: { id: 'second' },
      position: 1,
    });
    await flushPromises();

    expect(sortCalls).toEqual([{ id: 'first', request: { previousId: 'second', nextId: null } }]);
  });

  it('derives drop partitions from the module runtime contract', async () => {
    const wrapper = shallowMount(CrudRecordListExplorer, {
      props: {
        context: createContext([], [], [], ['tenantId', 'scopeType', 'organizationId']),
      },
    });

    await flushPromises();
    const partitionOf = wrapper.findComponent({ name: 'RecordListExplorer' }).props('sortPartitionOf') as (
      record: Record<string, unknown>,
    ) => string;

    const emptyOrganization = partitionOf({
      tenantId: 'tenant-a',
      scopeType: 'TENANT',
      organizationId: '',
    });
    const nullOrganization = partitionOf({
      tenantId: 'tenant-a',
      scopeType: 'TENANT',
      organizationId: null,
    });
    expect(emptyOrganization).not.toBe(nullOrganization);
    expect(partitionOf({ tenantId: 'tenant-a', scopeType: 'TENANT', organizationId: undefined })).not.toBe(
      emptyOrganization,
    );
    expect(partitionOf({ tenantId: 'tenant-a', scopeType: 'TENANT' })).toBeUndefined();
  });

  it('calculates sort neighbors inside the dragged record partition', async () => {
    const sortCalls: Array<{ id: string; request: unknown }> = [];
    const wrapper = shallowMount(CrudRecordListExplorer, {
      props: {
        context: createContext(
          [],
          [
            { id: 'system', scope: 'system' },
            { id: 'tenant-first', scope: 'tenant' },
            { id: 'tenant-second', scope: 'tenant' },
          ],
          sortCalls,
          ['scope'],
        ),
        sorting: true,
      },
    });

    await flushPromises();
    wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('sort', {
      dragRecord: { id: 'tenant-second', scope: 'tenant' },
      dropRecord: { id: 'tenant-first', scope: 'tenant' },
      position: -1,
    });
    await flushPromises();

    expect(sortCalls).toEqual([
      { id: 'tenant-second', request: { previousId: null, nextId: 'tenant-first' } },
    ]);
  });
});

function createContext(
  requests: Array<WebQueryRequest | undefined>,
  records: Array<{ id: string; [key: string]: unknown }> = [],
  sortCalls: Array<{ id: string; request: unknown }> = [],
  sortPartitionFields: string[] = [],
  total = records.length,
): ModuleContext<{ id: string }> {
  const crud = {
    query: async (request?: WebQueryRequest) => {
      requests.push(request);
      return {
        records,
        total,
        pageNum: 1,
        pageSize: 200,
        pages: total === 0 ? 0 : Math.ceil(total / 200),
        totalKnown: true,
      };
    },
    sort: async (id: string, request: unknown) => sortCalls.push({ id, request }),
  };
  return {
    moduleAlias: 'iam.organization',
    runtime: { ready: Promise.resolve({}), snapshot: () => ({ sortPartitionFields }) },
    crud,
    abilities: {
      has: () => false,
      crud: () => crud,
    },
    can: () => false,
  } as unknown as ModuleContext<{ id: string }>;
}
