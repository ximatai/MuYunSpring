import { expect, it, vi } from 'vitest';
import type {
  ConstructionPlanSnapshot,
  ConstructionDeliveryProposal,
  ConstructionDeliveryReceipt,
} from '@muyun/web-contracts';
import { createAssistantSurfaceRegistry, type ConstructionPlanClient } from '@muyun/web-core';
import { createConstructionDeliveryCapabilities } from '@/platform-workbench/constructionDelivery';

function fixture() {
  const saved: ConstructionPlanSnapshot = {
    planId: 'plan',
    revision: 1,
    confirmedAt: '',
    constructionStatus: 'INITIALIZED',
    deliveredObjectKeys: [],
    initializations: [],
    fieldChanges: [],
    deliveries: [],
    content: {
      title: '订单',
      goal: '登记',
      inScope: ['登记'],
      outOfScope: [],
      objects: [{ key: 'order', name: '订单', purpose: '登记' }],
      relationships: [],
      rules: [],
      questions: [],
      assumptions: [],
      decisions: [],
      acceptanceExamples: ['录入并查询'],
    },
  };
  const state = { saved, generation: 1, dirty: false, editing: false };
  let stored: ConstructionDeliveryReceipt | undefined;
  const client = {
    previewDelivery: vi.fn(async (_id: string, proposal: ConstructionDeliveryProposal) => ({
      proposal,
      moduleAlias: 'sales.order',
      lines: ['列表、表单与详情'],
      fingerprint: 'private-proof',
    })),
    publishDelivery: vi.fn(
      async (_id: string, command: { requestId: string; proposal: ConstructionDeliveryProposal }) => {
        stored = {
          requestId: command.requestId,
          objectKey: 'order',
          planRevision: 1,
          kind: command.proposal.kind,
          moduleAlias: 'sales.order',
          pageId: 'page',
          variantId: 'variant',
          revisionId: 'revision',
          menuId: null,
          metadataVersion: 1,
        };
        return stored;
      },
    ),
    delivery: vi.fn(async () => stored),
    task: vi.fn(),
    previewAcceptance: vi.fn(async () => ({
      objectKey: 'order',
      planRevision: 1,
      checks: ['实际录入并查询'],
      fingerprint: 'acceptance-proof',
    })),
    confirmAcceptance: vi.fn(async () => ({
      requestId: 'accept',
      objectKey: 'order',
      planRevision: 1,
      baseline: 'baseline',
    })),
    acceptance: vi.fn(),
  };
  const accept = vi.fn();
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'construction',
    contextRevision: () => String(state.generation),
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      requestTurn: vi.fn(),
      capabilities: () =>
        createConstructionDeliveryCapabilities(client as unknown as ConstructionPlanClient, () => ({
          ...state,
        })),
    },
  });
  registry.activate('construction');
  const invoke = (code: string, input: unknown) =>
    registry.invoke({ id: 'call', code, input }, registry.snapshot()!.token);
  return { state, client, accept, invoke };
}
const proposal = {
  objectKey: 'order',
  title: '订单',
};
it.each(['generation', 'revision', 'dirty', 'editing'] as const)(
  'expires prepared publication and acceptance when %s changes',
  async (change) => {
    const { invoke, state, client } = fixture();
    const acceptance = await invoke('construction.prepare-acceptance', { objectKey: 'order' });
    if (change === 'generation') state.generation++;
    else if (change === 'revision') state.saved = { ...state.saved, revision: 2 };
    else state[change] = true;
    await acceptance.confirmation!.confirm();
    expect(acceptance.confirmation!.state).toBe('expired');
    expect(client.publishDelivery).not.toHaveBeenCalled();
    expect(client.confirmAcceptance).not.toHaveBeenCalled();
  },
);
it('requires an independent human confirmation for business acceptance', async () => {
  const { invoke, client } = fixture();
  const result = await invoke('construction.prepare-acceptance', { objectKey: 'order' });
  expect(client.confirmAcceptance).not.toHaveBeenCalled();
  expect(JSON.stringify(result.value)).not.toContain('acceptance-proof');
  await result.confirmation!.confirm();
  expect(client.confirmAcceptance).toHaveBeenCalledOnce();
});

it('retires both legacy write capabilities while keeping acceptance available', async () => {
  const { invoke, client } = fixture();
  await expect(invoke('construction.prepare-page', proposal)).rejects.toThrow();
  await expect(invoke('construction.prepare-entry', proposal)).rejects.toThrow();
  expect(client.previewDelivery).not.toHaveBeenCalled();
  expect(client.publishDelivery).not.toHaveBeenCalled();
});
