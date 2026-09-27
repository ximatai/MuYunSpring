import { createModulePageAssistantSurface } from '@/dynamic-page-runtime/modulePageAssistantSurface';
import { createRelationDraftRegistry } from '@/dynamic-page-runtime/relationDraftController';
import { createWorkbenchAssistantCapabilities } from '@/platform-workbench/workbenchAssistantCapabilities';
import type { ModulePageSessionView } from '@/dynamic-page-runtime/useModulePageSession';
import type { RecordFormDraftAccess } from '@/dynamic-page-runtime/recordFormDraftAccess';
import { expect, it, vi } from 'vitest';
import type {
  ConstructionField,
  ConstructionPlanSnapshot,
  ConstructionFieldResult,
} from '@muyun/web-contracts';
import { createAssistantSurfaceRegistry, type ConstructionPlanClient } from '@muyun/web-core';
import { createConstructionPlanSession } from '@/platform-workbench/constructionPlanSession';

async function fixture() {
  const field: ConstructionField = {
    name: 'orderNumber',
    title: '订单号',
    specAlias: 'text-32',
    required: true,
    unique: true,
    indexed: false,
  };
  const snapshot: ConstructionPlanSnapshot = {
    planId: 'plan',
    revision: 1,
    confirmedAt: '',
    constructionStatus: 'INITIALIZED',
    deliveries: [],
    fieldChanges: [],
    initializations: [
      {
        objectKey: 'order',
        planRevision: 1,
        moduleAlias: 'sales.order',
        metadataId: 'metadata',
        relationId: 'relation',
        requestId: 'init',
      },
    ],
    content: {
      title: '订单',
      goal: '登记',
      inScope: ['登记订单号'],
      outOfScope: [],
      objects: [{ key: 'order', name: '订单', purpose: '登记' }],
      relationships: [],
      rules: [],
      questions: [],
      assumptions: [],
      decisions: [],
      acceptanceExamples: ['可以登记'],
    },
  };
  let receipt: ConstructionFieldResult | undefined;
  const client: ConstructionPlanClient = {
    read: vi.fn(async () => structuredClone(snapshot)),
    list: vi.fn(),
    history: vi.fn(),
    confirm: vi.fn(),
    confirmation: vi.fn(),
    previewInitialization: vi.fn(),
    initialize: vi.fn(),
    initialization: vi.fn(),
    task: vi.fn(),
    previewAcceptance: vi.fn(),
    confirmAcceptance: vi.fn(),
    acceptance: vi.fn(),
    previewDelivery: vi.fn(),
    publishDelivery: vi.fn(),
    delivery: vi.fn(),
    progress: vi.fn(),
    describeFields: vi.fn(async () => ({
      moduleAlias: 'sales.order',
      planRevision: 1,
      metadataVersion: 4,
      fields: [],
      specs: [
        { alias: 'text-32', title: '短文本', type: 'STRING', length: 32, precision: null, scale: null },
      ],
    })),
    previewFields: vi.fn(async (_id, proposal) => ({
      proposal,
      moduleAlias: 'sales.order',
      fieldImpacts: [],
      schemaImpacts: [],
      warnings: [],
      errors: [],
      fingerprint: 'private-proof',
    })),
    publishFields: vi.fn(async (_id, command) => {
      receipt = {
        receipt: {
          requestId: command.requestId,
          objectKey: 'order',
          planRevision: 1,
          moduleAlias: 'sales.order',
          fields: command.proposal.fields,
        },
        runtime: null,
      };
      return structuredClone(receipt);
    }),
    fieldChange: vi.fn(async () => receipt),
  };
  const session = createConstructionPlanSession(
    client,
    () => 'owner',
    () => true,
  );
  const registry = createAssistantSurfaceRegistry(
    () => 'owner',
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
  await session.restore('plan');
  const invoke = (code: string, input: unknown) =>
    registry.invoke({ id: code, code, input }, registry.snapshot()!.token);
  const discover = () => invoke('construction.describe-fields', { objectKey: 'order' });
  const prepare = () => invoke('construction.prepare-fields', { objectKey: 'order', fields: [field] });
  return { client, session, field, discover, prepare, invoke };
}
it('requires an actual catalog and publishes only the frozen reviewed fields after a human click', async () => {
  const f = await fixture();
  await expect(f.prepare()).rejects.toThrow('请先读取');
  await f.discover();
  const prepared = await f.prepare();
  expect(f.client.publishFields).not.toHaveBeenCalled();
  expect(JSON.stringify(prepared.value)).not.toContain('private-proof');
  expect(prepared.confirmation!.presentation.lines.join(' ')).toContain('短文本');
  f.field.title = 'changed after preview';
  await Promise.all([prepared.confirmation!.confirm(), prepared.confirmation!.confirm()]);
  expect(f.client.publishFields).toHaveBeenCalledOnce();
  expect(f.session.current().saved!.fieldChanges[0]!.fields[0]!.title).toBe('订单号');
  expect(prepared.confirmation!.result!.lines.join(' ')).toContain('页面、入口和业务验收请查询实际建设进度');
});
it('rejects invented specifications, invalid previews and old requirements', async () => {
  const f = await fixture();
  await f.discover();
  f.field.specAlias = 'invented';
  await expect(f.prepare()).rejects.toThrow('不在实际目录');
  f.field.specAlias = 'text-32';
  vi.mocked(f.client.previewFields).mockResolvedValueOnce({
    proposal: { planRevision: 1, objectKey: 'order', expectedMetadataVersion: 4, fields: [f.field] },
    moduleAlias: 'sales.order',
    fieldImpacts: [],
    schemaImpacts: [],
    warnings: [],
    errors: [{ code: 'DUPLICATE', message: '字段已存在' }],
    fingerprint: 'invalid',
  });
  await expect(f.prepare()).rejects.toThrow('字段已存在');
  const prepared = await f.prepare();
  f.session.edit({ ...f.session.current().candidate!, goal: '调整范围' });
  await prepared.confirmation!.confirm();
  expect(prepared.confirmation!.state).toBe('expired');
  expect(f.client.publishFields).not.toHaveBeenCalled();
});
it('recovers a committed field receipt after response loss without repeating publication', async () => {
  const f = await fixture();
  const publish = f.client.publishFields;
  f.client.publishFields = vi.fn(async (...args: Parameters<ConstructionPlanClient['publishFields']>) => {
    await publish(...args);
    throw new Error('response lost');
  });
  await f.discover();
  const prepared = await f.prepare();
  await prepared.confirmation!.confirm();
  expect(prepared.confirmation!.state).toBe('unknown');
  await prepared.confirmation!.check();
  expect(prepared.confirmation!.state).toBe('succeeded');
  expect(f.client.publishFields).toHaveBeenCalledOnce();
  expect(f.session.current().saved!.fieldChanges).toHaveLength(1);
});

it('bounds model catalog pages without losing full metadata or later fields', async () => {
  const f = await fixture();
  const base = await f.client.describeFields('plan', 'order');
  const fields = Array.from({ length: 500 }, (_, index) => ({
    fieldName: `field${index}`,
    title: '名称'.repeat(60),
    fieldType: 'STRING' as const,
  }));
  const full = { ...base, fields };
  expect(JSON.stringify(full).length).toBeGreaterThan(64_000);
  vi.mocked(f.client.describeFields).mockResolvedValue(full);
  let offset: number | null = 0;
  const seen: unknown[] = [];
  while (offset !== null) {
    const response = await f.invoke('construction.describe-fields', {
      objectKey: 'order',
      fieldOffset: offset,
    });
    const page = response.value as { fields: unknown[]; fieldPage: { nextOffset: number | null } };
    expect(JSON.stringify(page).length).toBeLessThan(17_000);
    seen.push(...page.fields);
    offset = page.fieldPage.nextOffset;
  }
  expect(seen).toEqual(fields);
  await f.prepare();
  expect(f.client.previewFields).toHaveBeenCalled();
  vi.mocked(f.client.describeFields).mockResolvedValue({
    ...base,
    fields: [{ fieldName: 'large', title: 'x'.repeat(9000) }, ...fields.slice(0, 2)],
  });
  const response = await f.discover();
  expect(response.value).toMatchObject({
    fieldPage: { oversizedIndexes: [0], nextOffset: null },
    fields: fields.slice(0, 2),
  });
});

it('composes initialized construction, navigation and referenced aggregate editors within the turn budget', async () => {
  const f = await fixture();
  const formFields = new Map([
    [
      'customerId',
      {
        fieldName: 'customerId',
        label: '客户',
        visible: true,
        readOnly: false,
        controlType: 'recordPicker',
        reference: { cardinality: 'ONE', targetModuleAlias: 'sales.customer' },
      },
    ],
  ]);
  const referencePickerConfigs = {
    customerId: {
      provider: {
        identity: {
          targetModuleAlias: 'sales.customer',
          source: { kind: 'targetReference', id: 'customer' },
        },
        searchPage: vi.fn(),
        resolve: vi.fn(),
      },
    },
  };
  const relations = createRelationDraftRegistry();
  const form = {
    editorMode: 'edit',
    editingRecord: { id: 'line' },
    formFields,
    referencePickerConfigs,
    contextRevision: () => '1',
    updateDraftFields: vi.fn(),
    updateDraftReference: vi.fn(),
  } as unknown as RecordFormDraftAccess;
  relations.register({
    code: 'lines',
    title: '明细',
    revision: () => '1',
    settle: async () => {},
    rowKeys: () => ['line'],
    form: () => form,
    add: vi.fn(),
    remove: vi.fn(),
  });
  const view = {
    editorMode: 'edit',
    editingRecord: { id: 'order' },
    formFields,
    referencePickerConfigs,
    context: { moduleAlias: 'sales.order', can: () => false },
    relationDrafts: relations,
    recordCreationState: () => ({ ready: false }),
    assistantNavigatorScopes: () => [],
    assistantSaveAvailable: true,
  } as unknown as ModulePageSessionView;
  const surface = createModulePageAssistantSurface(view, vi.fn(), () => [
    ...createWorkbenchAssistantCapabilities(
      () => [],
      () => false,
    ),
    ...f.session.capabilities(),
  ]);
  const select = surface.capabilities().find((entry) => entry.descriptor.code === 'relation.select-row')!;
  await select.execute(select.parseInput({ relationCode: 'lines', rowKey: 'line' }), {
    signal: new AbortController().signal,
    isCurrent: () => true,
    commitInternalState: (commit) => commit(),
    applyEffect: (commit) => commit(),
  });
  const catalog = surface.capabilities().map((entry) => entry.descriptor);
  expect(catalog.length).toBeGreaterThan(32);
  expect(catalog.length).toBeLessThanOrEqual(64);
  expect(catalog.map((entry) => entry.code)).toEqual(
    expect.arrayContaining([
      'construction.describe-fields',
      'workbench.find-menu',
      'form.prepare-save',
      'relation.reference.search-options',
    ]),
  );
  expect(JSON.stringify(catalog).length).toBeLessThan(64_000);
});
