import { expect, it, vi } from 'vitest';
import type {
  ConstructionPlanContent,
  ConstructionInitializationResult,
  ConstructionPlanSnapshot,
} from '@muyun/web-contracts';
import { createAssistantSurfaceRegistry, type ConstructionPlanClient } from '@muyun/web-core';
import { createConstructionPlanSession } from '@/platform-workbench/constructionPlanSession';
const content: ConstructionPlanContent = {
  title: '业务登记',
  goal: '登记与查询',
  inScope: ['登记'],
  outOfScope: ['审批'],
  objects: [{ key: 'entry', name: '登记', purpose: '登记业务' }],
  relationships: [],
  rules: [],
  questions: [],
  assumptions: [],
  decisions: [],
  acceptanceExamples: ['录入并查看登记'],
};
function fixture() {
  let identity = 'owner';
  const saved: ConstructionPlanSnapshot = {
    planId: 'plan',
    revision: 1,
    content,
    confirmedAt: '',
    constructionStatus: 'NOT_STARTED',
    deliveredObjectKeys: [],
    deliveries: [],
    fieldChanges: [],
    initializations: [],
  };
  const result: ConstructionInitializationResult = {
    receipt: {
      objectKey: 'entry',
      planRevision: 1,
      moduleAlias: 'business.entry',
      metadataId: 'metadata',
      relationId: 'relation',
      requestId: 'request',
    },
    runtime: null,
  };
  const client: ConstructionPlanClient = {
    list: vi.fn(async () => []),
    read: vi.fn(async () => saved),
    history: vi.fn(async () => []),
    confirm: vi.fn(async () => saved),
    confirmation: vi.fn(async () => saved),
    task: vi.fn(),
    previewAcceptance: vi.fn(),
    confirmAcceptance: vi.fn(),
    acceptance: vi.fn(),
    previewDelivery: vi.fn(),
    publishDelivery: vi.fn(),
    delivery: vi.fn(),
    progress: vi.fn(),
    designContract: vi.fn(async () => ({
      recordName: { fieldName: 'title', columnName: 'title', fieldType: 'STRING' },
      inheritedFields: ['id'],
      declarableCapabilities: { capabilities: [], metadataFields: [] },
    })),
    businessObjects: vi.fn(async () => [
      {
        alias: 'crm.customer',
        title: '客户',
        applicationAlias: 'crm',
        applicationTitle: '客户管理',
        kind: 'DYNAMIC',
        referenceReady: true,
        explanation: '可复用',
      },
    ]),
    referenceTarget: vi.fn(async () => ({
      targetModuleAlias: 'crm.customer',
      targetMetadataId: 'customer-metadata',
      keyFields: [{ fieldName: 'id', title: '标识', defaultField: true, selectable: true }],
      labelFields: [{ fieldName: 'name', title: '客户名称', defaultField: true, selectable: true }],
    })),
    describeFields: vi.fn(),
    previewFields: vi.fn(),
    publishFields: vi.fn(),
    fieldChange: vi.fn(),
    initialization: vi.fn(async () => structuredClone(result)),
  };
  const session = createConstructionPlanSession(
    client,
    () => identity,
    () => identity === 'owner',
  );
  const registry = createAssistantSurfaceRegistry(
    () => identity,
    () => ({ revision: String(session.current().generation), facts: { plan: session.facts() } }),
  );
  registry.register({
    pageInstanceKey: 'home',
    contextRevision: () => '0',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: session.capabilities,
      requestTurn: vi.fn(),
    },
  });
  registry.activate('home');
  async function prepare() {
    return registry.invoke(
      {
        id: 'initialize',
        code: 'construction.prepare-initialization',
        input: {
          objectKey: 'entry',
          applicationAlias: 'business',
          applicationTitle: '业务',
          moduleName: 'entry',
        },
      },
      registry.snapshot()!.token,
    );
  }
  return {
    client,
    session,
    registry,
    prepare,
    result,
    switchIdentity() {
      identity = 'other';
      session.current();
    },
  };
}
it('does not expose a combined application and module creation command after scope confirmation', async () => {
  const f = fixture();
  f.session.edit(content);
  await f.session.prepare().execute();
  await expect(f.prepare()).rejects.toThrow();
});

it('refreshes durable progress through a read capability without turning initialization into completion', async () => {
  const { session, registry } = fixture();
  await session.restore('plan');
  const outcome = await registry.invoke(
    { id: 'status', code: 'construction.initialization-status', input: { objectKey: 'entry' } },
    registry.snapshot()!.token,
  );
  expect(outcome.contextChanged).toBe(false);
  expect(session.current().saved?.constructionStatus).toBe('INITIALIZED');
  expect(session.current().saved?.initializations[0]?.moduleAlias).toBe('business.entry');
});

it('keeps initialization tools out of a personal planning session without system configuration eligibility', () => {
  const { client } = fixture();
  const personal = createConstructionPlanSession(client, () => 'tenant-user');
  expect(personal.capabilities().map((capability) => capability.descriptor.code)).not.toContain(
    'construction.prepare-initialization',
  );
  expect(personal.facts().initializationAvailable).toBe(false);
  expect(personal.capabilities().map((capability) => capability.descriptor.code)).toContain(
    'construction.propose',
  );
});
