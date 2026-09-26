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
        createConstructionDeliveryCapabilities(
          client as unknown as ConstructionPlanClient,
          () => ({ ...state }),
          accept,
        ),
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
  listFields: ['number'],
  formFields: ['number'],
  searchFields: ['number'],
};
it('keeps page publication frozen and human-only; a lost response recovers the exact receipt', async () => {
  const { invoke, client, accept } = fixture();
  const result = await invoke('construction.prepare-page', proposal);
  expect(JSON.stringify(result.value)).not.toContain('private-proof');
  expect(client.publishDelivery).not.toHaveBeenCalled();
  const original = client.publishDelivery.getMockImplementation()!;
  client.publishDelivery.mockImplementationOnce(async (...args) => {
    await original(...args);
    throw new Error('lost');
  });
  await result.confirmation!.confirm();
  expect(result.confirmation!.state).toBe('unknown');
  await result.confirmation!.check();
  expect(client.publishDelivery).toHaveBeenCalledTimes(1);
  expect(client.delivery).toHaveBeenCalledWith('plan', expect.any(String));
  expect(accept).toHaveBeenCalledOnce();
});
it.each(['generation', 'revision', 'dirty', 'editing'] as const)(
  'expires prepared publication and acceptance when %s changes',
  async (change) => {
    const { invoke, state, client } = fixture();
    const page = await invoke('construction.prepare-page', proposal);
    const acceptance = await invoke('construction.prepare-acceptance', { objectKey: 'order' });
    if (change === 'generation') state.generation++;
    else if (change === 'revision') state.saved = { ...state.saved, revision: 2 };
    else state[change] = true;
    await page.confirmation!.confirm();
    await acceptance.confirmation!.confirm();
    expect(page.confirmation!.state).toBe('expired');
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

it('keeps committed success when the workbench menu refresh fails', async () => {
  const { invoke, client, accept } = fixture();
  accept.mockRejectedValueOnce(new Error('menu refresh failed'));
  const result = await invoke('construction.prepare-entry', { objectKey: 'order', title: '订单' });
  await result.confirmation!.confirm();
  expect(result.confirmation!.state).toBe('succeeded');
  expect(client.publishDelivery).toHaveBeenCalledOnce();
  expect(result.confirmation!.result?.lines.join(' ')).toContain('刷新失败');
});
