import { expect, it, vi } from 'vitest';
import type {
  ConstructionPlanContent,
  ConstructionInitializationPreview,
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
    deliveries: [],
    fieldChanges: [],
    initializations: [],
  };
  const preview: ConstructionInitializationPreview = {
    proposal: {
      planRevision: 1,
      objectKey: 'entry',
      applicationAlias: 'business',
      applicationTitle: '业务',
      moduleName: 'entry',
    },
    applicationTitle: '业务',
    createsApplication: true,
    applicationVersion: null,
    moduleAlias: 'business.entry',
    moduleTitle: '登记',
    schemaName: 'public',
    tableName: 'app_new',
    remainingWork: ['字段与页面仍待建设'],
    fingerprint: 'server-proof',
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
    describeFields: vi.fn(),
    previewFields: vi.fn(),
    publishFields: vi.fn(),
    fieldChange: vi.fn(),
    previewInitialization: vi.fn(async () => preview),
    initialize: vi.fn(async (_id, command) => {
      result.receipt.requestId = command.requestId;
      return structuredClone(result);
    }),
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
    preview,
    result,
    switchIdentity() {
      identity = 'other';
      session.current();
    },
  };
}
it('previews without writing and keeps the publication fingerprint out of model messages', async () => {
  const { session, client, prepare, preview } = fixture();
  await session.restore('plan');
  const proposal = await prepare();
  expect(client.initialize).not.toHaveBeenCalled();
  expect(JSON.stringify(proposal.value)).not.toContain('server-proof');
  expect(proposal.confirmation?.presentation.lines.join('\n')).toContain('还不能记单');
  preview.fingerprint = 'tampered-after-review';
  preview.proposal.moduleName = 'changed';
  await Promise.all([proposal.confirmation!.confirm(), proposal.confirmation!.confirm()]);
  expect(client.initialize).toHaveBeenCalledOnce();
  expect(client.initialize).toHaveBeenCalledWith(
    'plan',
    expect.objectContaining({
      fingerprint: 'server-proof',
      proposal: expect.objectContaining({ moduleName: 'entry' }),
    }),
  );
  expect(session.current().saved?.initializations).toHaveLength(1);
  expect(proposal.confirmation!.result?.lines.join('\n')).toContain('尚待核实');
  expect(session.facts().constructionStatus).toBe('INITIALIZED');
});
it('requires confirmed scope and invalidates initialization when a human starts revising it', async () => {
  const { session, client, prepare } = fixture();
  session.edit(content);
  await expect(prepare()).rejects.toThrow('Capability is no longer available');
  expect(client.previewInitialization).not.toHaveBeenCalled();
  session.newPlan();
  await session.restore('plan');
  const proposal = await prepare();
  session.beginManualEdit();
  await proposal.confirmation!.confirm();
  expect(proposal.confirmation!.state).toBe('expired');
  expect(client.initialize).not.toHaveBeenCalled();
});
it('queries the exact initialization receipt after a lost response without reissuing the write', async () => {
  const { session, client, prepare } = fixture();
  await session.restore('plan');
  const original = client.initialize;
  client.initialize = vi.fn(async (...args: Parameters<ConstructionPlanClient['initialize']>) => {
    await original(...args);
    throw new Error('lost response');
  });
  const proposal = await prepare();
  await proposal.confirmation!.confirm();
  expect(proposal.confirmation!.state).toBe('unknown');
  await proposal.confirmation!.check();
  expect(proposal.confirmation!.state).toBe('succeeded');
  expect(client.initialize).toHaveBeenCalledOnce();
  expect(session.current().saved?.initializations).toHaveLength(1);
});
it('does not accept another request receipt or restore execution authorization after identity changes', async () => {
  const { session, client, prepare, switchIdentity } = fixture();
  await session.restore('plan');
  client.initialize = vi.fn(async () => {
    throw new Error('not delivered');
  });
  const proposal = await prepare();
  await proposal.confirmation!.confirm();
  await proposal.confirmation!.check();
  expect(proposal.confirmation!.state).toBe('unknown');
  expect(session.current().saved?.initializations).toEqual([]);
  switchIdentity();
  await proposal.confirmation!.check();
  expect(client.initialization).toHaveBeenCalledOnce();
  expect(session.current().saved).toBeUndefined();
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
