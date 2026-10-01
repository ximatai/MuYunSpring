import { expect, it, vi } from 'vitest';
import { createConstructionReferenceDiscoveryCapabilities } from '@/platform-workbench/constructionReferenceDiscovery';
import type { ConstructionPlanClient } from '@/web-core/constructionPlanClient';

it('exposes authoritative design facts before initialization without rewriting returned field names', async () => {
  const contract = {
    recordName: { fieldName: 'standardName', columnName: 'standard_name', fieldType: 'STRING' },
    inheritedFields: ['id'],
    declarableCapabilities: { capabilities: [], metadataFields: [] },
  };
  const designContract = vi.fn(async () => contract);
  const capabilities = createConstructionReferenceDiscoveryCapabilities({
    designContract,
  } as unknown as ConstructionPlanClient);
  const capability = capabilities.find(
    (item) => item.descriptor.code === 'construction.describe-design-contract',
  )!;
  expect(capability.effect).toBe('read');
  expect(await capability.execute!(capability.parseInput({}), {} as never)).toBe(contract);
  expect(designContract).toHaveBeenCalledOnce();
});

function fixture() {
  const customer = {
    alias: 'qinghe.customer',
    title: '客户',
    applicationAlias: 'qinghe',
    applicationTitle: '青禾文具',
    kind: 'DYNAMIC',
    referenceReady: false,
    explanation: '尚需配置',
  };
  const businessObjects = vi.fn(async () => [
    customer,
    {
      ...customer,
      alias: 'other.customer',
      applicationAlias: 'other',
      applicationTitle: '另一家店',
      referenceReady: true,
    },
    ...Array.from({ length: 21 }, (_, index) => ({
      ...customer,
      alias: `qinghe.product_${index}`,
      title: `商品${index}`,
    })),
  ]);
  const capability = createConstructionReferenceDiscoveryCapabilities({
    businessObjects,
  } as unknown as ConstructionPlanClient).find(
    (entry) => entry.descriptor.code === 'construction.find-business-objects',
  )!;
  const invoke = (input: unknown) => capability.execute!(capability.parseInput(input), {} as never);
  return { capability, invoke, businessObjects };
}

it('finds modules by application business name and preserves paging and unready objects', async () => {
  const f = fixture();
  const first = await f.invoke({ search: '青禾文具' });
  expect(first).toMatchObject({ total: 22, nextOffset: 20 });
  expect(first).toHaveProperty('modules.0.referenceReady', false);
  expect(await f.invoke({ search: '青禾文具', offset: 20 })).toMatchObject({
    total: 22,
    nextOffset: null,
    modules: [{ alias: 'qinghe.product_19' }, { alias: 'qinghe.product_20' }],
  });
});

it('limits same-named modules to the confirmed application without guessing another scope', async () => {
  const f = fixture();
  expect(await f.invoke({ applicationAlias: 'qinghe', search: '客户' })).toMatchObject({
    total: 1,
    modules: [{ alias: 'qinghe.customer', applicationAlias: 'qinghe' }],
  });
  expect(await f.invoke({ applicationAlias: 'missing', search: '客户' })).toEqual({
    total: 0,
    nextOffset: null,
    modules: [],
  });
  expect(await f.invoke({ search: '客户' })).toMatchObject({ total: 2 });
});

it.each([
  null,
  [],
  { applicationAlias: null },
  { applicationAlias: '青禾文具' },
  { applicationAlias: 'qinghe.customer' },
  { offset: 1.5 },
  { search: 42 },
])('rejects invalid discovery input %j before reading', (input) => {
  const f = fixture();
  expect(() => f.capability.parseInput(input)).toThrow();
  expect(f.businessObjects).not.toHaveBeenCalled();
});
