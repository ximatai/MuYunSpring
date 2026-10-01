import { createModulePageAssistantSurface } from '@/dynamic-page-runtime/modulePageAssistantSurface';
import { createRelationDraftRegistry } from '@/dynamic-page-runtime/relationDraftController';
import { createWorkbenchAssistantCapabilities } from '@/platform-workbench/workbenchAssistantCapabilities';
import type { ModulePageSessionView } from '@/dynamic-page-runtime/useModulePageSession';
import type { RecordFormDraftAccess } from '@/dynamic-page-runtime/recordFormDraftAccess';
import { expect, it, vi } from 'vitest';
import type { ConstructionPlanSnapshot } from '@muyun/web-contracts';
import { createAssistantSurfaceRegistry, type ConstructionPlanClient } from '@muyun/web-core';
import {
  createConstructionPlanSession,
  parseConstructionPlan,
} from '@/platform-workbench/constructionPlanSession';

async function fixture() {
  const snapshot: ConstructionPlanSnapshot = {
    planId: 'plan',
    revision: 1,
    confirmedAt: '',
    constructionStatus: 'INITIALIZED',
    deliveredObjectKeys: [],
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
  const client: ConstructionPlanClient = {
    read: vi.fn(async () => structuredClone(snapshot)),
    list: vi.fn(),
    history: vi.fn(),
    confirm: vi.fn(),
    confirmation: vi.fn(),
    initialization: vi.fn(),
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
    describeFields: vi.fn(async () => ({
      moduleAlias: 'sales.order',
      planRevision: 1,
      metadataVersion: 4,
      fields: [],
      specs: [
        { alias: 'text-32', title: '短文本', type: 'STRING', length: 32, precision: null, scale: null },
      ],
    })),
    previewFields: vi.fn(),
    publishFields: vi.fn(),
    fieldChange: vi.fn(),
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
  return { client, session, discover, invoke, snapshot };
}
it('exposes construction evidence without a parallel field publication candidate', async () => {
  const f = await fixture();
  expect(f.session.capabilities().map((capability) => capability.descriptor.code)).not.toContain(
    'construction.prepare-fields',
  );
  await f.discover();
  expect(f.client.previewFields).not.toHaveBeenCalled();
  expect(f.client.publishFields).not.toHaveBeenCalled();
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
    assistantNavigatorCreationTargets: () => [],
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

it('discovers reusable objects and their authoritative reference catalog', async () => {
  const f = await fixture();
  const discovery = await f.invoke('construction.find-business-objects', { search: '客户' });
  expect(discovery.value).toMatchObject({ modules: [{ alias: 'crm.customer', title: '客户' }] });
  const target = await f.invoke('construction.describe-reference-target', { moduleAlias: 'crm.customer' });
  expect(target.value).toMatchObject({ targetMetadataId: 'customer-metadata' });
  expect(f.client.publishFields).not.toHaveBeenCalled();
});

it('preserves reviewed reuse intent and rejects ambiguous or self-referencing target identities', async () => {
  const f = await fixture();
  const content = structuredClone(f.session.current().candidate!);
  content.requirements = [
    {
      section: 'SCOPE',
      index: 0,
      objectKey: 'order',
      mode: 'REFERENCE',
      fieldName: 'customerId',
      explanation: '复用客户资料',
      reference: { objectKey: '', moduleAlias: 'crm.customer' },
    },
  ];
  expect(parseConstructionPlan(content).requirements![0]!.reference).toEqual({
    objectKey: '',
    moduleAlias: 'crm.customer',
  });
  content.requirements[0]!.reference!.objectKey = 'order';
  expect(() => parseConstructionPlan(content)).toThrow('之一');
  content.requirements[0]!.reference!.moduleAlias = '';
  expect(() => parseConstructionPlan(content)).toThrow('另一个对象');
});

it('discovers child configuration and calculation evidence without treating it as main field creation', async () => {
  const f = await fixture();
  vi.mocked(f.client.describeFields).mockResolvedValue({
    moduleAlias: 'sales.order',
    planRevision: 1,
    metadataVersion: 4,
    fields: [],
    specs: [],
    children: {
      lines: {
        relation: { id: 'child', relationAlias: 'lines', relationRole: 'CHILD' },
        metadataVersion: 7,
        fields: [{ fieldName: 'amount', title: '小计' }],
        references: {},
      },
    },
    calculationRules: [
      { alias: 'amount', targetField: 'lines.amount', expression: '{lines.quantity} * {lines.price}' },
    ],
  });
  const read = await f.invoke('construction.describe-fields', { objectKey: 'order' });
  expect(read.value).toMatchObject({
    children: { lines: { relation: { id: 'child' }, fields: [{ fieldName: 'amount' }] } },
    calculationRules: [{ targetField: 'lines.amount' }],
  });
  expect(f.client.publishFields).not.toHaveBeenCalled();
});

it('reads historical field receipts without preparing or replaying a publication', async () => {
  const f = await fixture();
  vi.mocked(f.client.fieldChange).mockResolvedValue({
    receipt: {
      requestId: 'previous-request',
      objectKey: 'order',
      planRevision: 1,
      moduleAlias: 'sales.order',
      fields: [],
    },
    runtime: null,
  });
  const status = await f.invoke('construction.field-change-status', { requestId: 'previous-request' });
  expect(status.value).toMatchObject({ receipt: { requestId: 'previous-request' } });
  expect(f.session.current().saved!.fieldChanges).toHaveLength(1);
  expect(f.client.publishFields).not.toHaveBeenCalled();
});
