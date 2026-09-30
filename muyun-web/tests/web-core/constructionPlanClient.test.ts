import { expect, it, vi } from 'vitest';
import { createConstructionPlanClient } from '@/web-core/constructionPlanClient';
import type { HttpClient } from '@/web-core/http';

it('reads historical initialization receipts without exposing a combined creation operation', async () => {
  const result = { receipt: { objectKey: 'customer', moduleAlias: 'trial.customer' }, runtime: null };
  const request = vi.fn(async () => result);
  const client = createConstructionPlanClient({ request } as HttpClient);
  expect(await client.initialization('plan', 'customer')).toEqual(result);
  expect(request).toHaveBeenCalledWith({
    path: '/platform.application-construction-plans/plan/initializations/customer',
  });
  expect(client).not.toHaveProperty('initialize');
  expect(client).not.toHaveProperty('previewInitialization');
});

it('reads the design contract without a plan or an initialized module', async () => {
  const contract = { recordName: { fieldName: 'title', columnName: 'title', fieldType: 'STRING' } };
  const request = vi.fn(async () => contract);
  const client = createConstructionPlanClient({ request } as HttpClient);
  expect(await client.designContract()).toBe(contract);
  expect(request).toHaveBeenCalledWith({ path: '/platform.application-construction-plans/design-contract' });
});
