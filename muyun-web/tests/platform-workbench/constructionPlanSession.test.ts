import { expect, it, vi } from 'vitest';
import {
  createConstructionPlanSession,
  parseConstructionPlan,
} from '@/platform-workbench/constructionPlanSession';
import {
  createAssistantOperationConfirmation,
  createAssistantSurfaceRegistry,
  type ConstructionPlanClient,
} from '@muyun/web-core';
import type { ConstructionPlanContent, ConstructionPlanSnapshot } from '@muyun/web-contracts';

const content: ConstructionPlanContent = {
  title: '订单管理',
  goal: '先录入并查看订单',
  inScope: ['订单录入'],
  outOfScope: ['库存结算'],
  objects: [{ key: 'order', name: '订单', purpose: '记录订购' }],
  relationships: [],
  rules: [],
  questions: ['是否需要审核'],
  assumptions: [],
  decisions: [{ statement: '第一版不做库存', source: 'RECOMMENDATION' }],
  acceptanceExamples: ['可以录入并查看一张订单'],
};
function fixture() {
  let identity = 'user-a';
  let snapshot: ConstructionPlanSnapshot;
  const client: ConstructionPlanClient = {
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
    previewInitialization: vi.fn(),
    initialize: vi.fn(),
    initialization: vi.fn(),
    list: vi.fn(async () => [
      { planId: snapshot.planId, title: snapshot.content.title, revision: snapshot.revision, updatedAt: '' },
    ]),
    read: vi.fn(async () => structuredClone(snapshot)),
    history: vi.fn(async () => [structuredClone(snapshot)]),
    confirm: vi.fn(async (planId, command) => {
      snapshot = {
        planId,
        content: structuredClone(command.content),
        revision: command.expectedRevision + 1,
        confirmedAt: '',
        constructionStatus: 'NOT_STARTED',
        deliveries: [],
        fieldChanges: [],
        initializations: [],
      };
      return structuredClone(snapshot);
    }),
    confirmation: vi.fn(async () => snapshot && structuredClone(snapshot)),
  };
  const session = createConstructionPlanSession(client, () => identity);
  return {
    session,
    client,
    changeIdentity() {
      identity = 'user-b';
    },
  };
}
it('persists only after human confirmation, preserves revisions, and never authorizes construction', async () => {
  const { session, client } = fixture();
  session.edit(content);
  expect(session.facts().persistence).toBe('UNSAVED_CANDIDATE');
  const confirmation = createAssistantOperationConfirmation(session.prepare(), () => true);
  expect(client.confirm).not.toHaveBeenCalled();
  expect(confirmation.presentation.details?.lines).toContain('建议：第一版不做库存');
  await Promise.all([confirmation.confirm(), confirmation.confirm()]);
  expect(client.confirm).toHaveBeenCalledOnce();
  expect(session.current().saved?.revision).toBe(1);
  expect(session.dirty()).toBe(false);
  expect(session.facts().constructionStatus).toBe('NOT_STARTED');
  expect(session.facts().persistence).toBe('SAVED_REQUIREMENTS');
  expect(session.facts().persistenceExplanation).toContain('不代表应用可用');
  expect(() => session.prepare()).toThrow('先整理或修改');
  session.edit({ ...content, title: '订单第二版' });
  expect(session.changes()).toEqual(['名称']);
  await createAssistantOperationConfirmation(session.prepare(), () => true).confirm();
  expect(session.current().saved?.revision).toBe(2);
  expect(client.confirm).toHaveBeenLastCalledWith(
    expect.any(String),
    expect.objectContaining({ expectedRevision: 1 }),
  );
});
it('expires old review on edits and discards state on identity changes', async () => {
  const { session, client, changeIdentity } = fixture();
  session.edit(content);
  const confirmation = createAssistantOperationConfirmation(session.prepare(), () => true);
  session.edit({ ...content, goal: '改变需求' });
  await confirmation.confirm();
  expect(confirmation.state).toBe('expired');
  expect(client.confirm).not.toHaveBeenCalled();
  changeIdentity();
  expect(session.current().candidate).toBeUndefined();
});
it('queries the original confirmation after a lost response without repeating a write', async () => {
  const { session, client } = fixture();
  session.edit(content);
  const original = client.confirm;
  client.confirm = vi.fn(async (...args: Parameters<ConstructionPlanClient['confirm']>) => {
    await original(...args);
    throw new Error('lost response');
  });
  const confirmation = createAssistantOperationConfirmation(session.prepare(), () => true);
  await confirmation.confirm();
  expect(confirmation.state).toBe('unknown');
  expect(session.facts().persistence).toBe('UNKNOWN');
  expect(session.facts().persistenceExplanation).toContain('尚未确定');
  expect(() => session.newPlan()).toThrow('尚未查明');
  await session.recovery.value?.();
  expect(session.current().saved?.revision).toBe(1);
  expect(session.recovery.value).toBeUndefined();
  expect(client.confirm).toHaveBeenCalledOnce();
});
it('restores persisted requirements after a new session but does not restore an approval', async () => {
  const { session, client } = fixture();
  session.edit(content);
  await session.prepare().execute();
  const resumed = createConstructionPlanSession(client, () => 'user-a');
  const entries = await resumed.listSaved();
  await resumed.restore(entries[0]!.planId);
  expect(resumed.current().candidate).toEqual(content);
  expect(resumed.dirty()).toBe(false);
  expect(() => resumed.prepare()).toThrow('先整理或修改');
  resumed.edit({ ...content, title: '另一个想法' });
  await expect(resumed.restore(entries[0]!.planId)).rejects.toThrow('当前候选');
});
it('discards restore responses after identity switch or manual edits', async () => {
  const { session, client, changeIdentity } = fixture();
  session.edit(content);
  await session.prepare().execute();
  let resolve!: (value: ConstructionPlanSnapshot) => void;
  const saved = session.current().saved!;
  client.read = vi.fn(
    () =>
      new Promise<ConstructionPlanSnapshot>((r) => {
        resolve = r;
      }),
  );
  const pending = session.restore(saved.planId);
  changeIdentity();
  resolve(saved);
  await expect(pending).rejects.toThrow('当前方案已变化');
  expect(session.current().candidate).toBeUndefined();
});
it('keeps plan context across pages and rejects stale-generation proposals', async () => {
  const { session } = fixture();
  session.edit(content);
  const registry = createAssistantSurfaceRegistry(
    () => 'user-a',
    () => ({ revision: String(session.current().generation), facts: { constructionPlan: session.facts() } }),
  );
  const requester = vi.fn<import('@muyun/web-core').AssistantTurnRequester>(async () => ({
    text: '继续',
    toolCalls: [],
  }));
  for (const page of ['a', 'b'])
    registry.register({
      pageInstanceKey: page,
      contextRevision: () => '0',
      surface: {
        describe: () => ({ surface: 'workbench', facts: {} }),
        capabilities: session.capabilities,
        requestTurn: requester,
      },
    });
  registry.activate('a');
  registry.activate('b');
  const snapshot = registry.snapshot()!;
  await registry.requestTurn({ message: '继续', history: [], results: [] }, snapshot.token);
  expect(requester.mock.calls[0]?.[0]).toMatchObject({
    context: { facts: { workspace: { constructionPlan: { candidate: { goal: content.goal } } } } },
  });
  await expect(
    registry.invoke(
      { id: '1', code: 'construction.propose', input: { generation: 0, content } },
      snapshot.token,
    ),
  ).rejects.toThrow('候选已变化');
});
it('validates bounded business content and refuses executable schema fields', () => {
  expect(() => parseConstructionPlan({ ...content, moduleSchema: {} })).toThrow();
  expect(() => parseConstructionPlan({ ...content, inScope: Array(17).fill('过多') })).toThrow();
  expect(() =>
    parseConstructionPlan({ ...content, objects: [content.objects[0], content.objects[0]] }),
  ).toThrow('不能重复');
  expect(() =>
    parseConstructionPlan({ ...content, decisions: [{ statement: '建成', source: 'EXECUTED' }] }),
  ).toThrow();
});

it('does not create a new confirmation handle while the previous result remains unknown', async () => {
  const { session, client } = fixture();
  session.edit(content);
  client.confirm = vi.fn().mockRejectedValue(new Error('response lost'));
  client.confirmation = vi.fn().mockResolvedValue(undefined);
  const first = createAssistantOperationConfirmation(session.prepare(), () => true);
  await first.confirm();
  await first.check();
  expect(first.state).toBe('unknown');
  expect(() => session.prepare()).toThrow('尚未查明');
  expect(client.confirm).toHaveBeenCalledTimes(1);
});

it('marks dependent requirements for reconciliation after manual edits without erasing unanswered questions', () => {
  const { session } = fixture();
  session.edit(content);
  const original = session.prepare();
  session.editManually({ ...content, inScope: ['订单录入，不审核'] });
  expect(original.isCurrent()).toBe(false);
  expect(session.facts().reviewRequired).toBe(true);
  expect(session.current().candidate?.questions).toEqual(content.questions);
  expect(() => session.prepare()).toThrow('核对');
  session.edit({ ...session.current().candidate!, questions: [] });
  expect(session.facts().reviewRequired).toBe(false);
  expect(session.prepare().presentation.details?.lines).toContain('本期范围：订单录入，不审核');
});

it('keeps explicit realization visible in review and rejects invalid clause references', () => {
  const mapped = {
    ...content,
    requirements: [
      {
        section: 'RULE' as const,
        index: 0,
        objectKey: 'order',
        mode: 'MANUAL' as const,
        fieldName: '',
        explanation: '人工逐笔核对',
      },
    ],
    rules: ['逐笔核对'],
  };
  const { session } = fixture();
  session.edit(mapped);
  expect(session.prepare().presentation.details?.lines.join('\n')).toContain('人工处理并核验');
  expect(() =>
    parseConstructionPlan({ ...mapped, requirements: [{ ...mapped.requirements[0], index: 1 }] }),
  ).toThrow('本版要求');
  expect(() =>
    parseConstructionPlan({ ...mapped, requirements: [{ ...mapped.requirements[0], objectKey: 'missing' }] }),
  ).toThrow('业务对象不存在');
});

it('derives resumable task facts from the server and drops them after a candidate change', async () => {
  const { session, client } = fixture();
  session.edit(content);
  await session.prepare().execute();
  vi.mocked(client.task).mockResolvedValue({
    planRevision: 1,
    objects: [
      {
        objectKey: 'order',
        title: '订单',
        stage: 'REVIEW_REQUIREMENTS',
        nextAction: '先核对兑现方式',
        requirements: [],
      },
    ],
  });
  await session.readTask();
  expect(session.facts().task?.objects[0]?.stage).toBe('REVIEW_REQUIREMENTS');
  session.edit({ ...content, title: '新想法' });
  expect(session.currentTask()).toBeUndefined();
  await expect(session.readTask()).rejects.toThrow('确认最新需求');
});

it('rejects a task for a newer externally confirmed plan revision', async () => {
  const { session, client } = fixture();
  session.edit(content);
  await session.prepare().execute();
  vi.mocked(client.task).mockResolvedValue({ planRevision: 2, objects: [] });
  await expect(session.readTask()).rejects.toThrow('恢复最新方案');
  expect(session.currentTask()).toBeUndefined();
});
