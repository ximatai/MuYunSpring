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
