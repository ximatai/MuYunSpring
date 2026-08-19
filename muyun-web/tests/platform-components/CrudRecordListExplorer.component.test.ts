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

  it('clears an externally owned selection when the same record is selected again', async () => {
    const wrapper = shallowMount(CrudRecordListExplorer, {
      props: {
        context: createContext([]),
        selectedId: 'rule-1',
      },
    });

    await flushPromises();
    wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('select', { id: 'rule-1' });

    expect(wrapper.emitted('deselect')).toEqual([[]]);
    expect(wrapper.emitted('select')).toBeUndefined();
  });
});

function createContext(requests: Array<WebQueryRequest | undefined>): ModuleContext<{ id: string }> {
  return {
    moduleAlias: 'iam.organization',
    runtime: { ready: Promise.resolve({}) },
    abilities: {
      has: () => false,
      crud: () => ({
        query: async (request?: WebQueryRequest) => {
          requests.push(request);
          return { records: [], total: 0, pageNum: 1, pageSize: 200 };
        },
      }),
    },
    can: () => false,
  } as unknown as ModuleContext<{ id: string }>;
}
