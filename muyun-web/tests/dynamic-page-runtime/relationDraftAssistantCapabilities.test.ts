import { expect, it, vi } from 'vitest';
import { createRelationDraftRegistry } from '@/dynamic-page-runtime/relationDraftController';
import { createRelationDraftAssistantCapabilities } from '@/dynamic-page-runtime/relationDraftAssistantCapabilities';
import type { RecordFormDraftAccess } from '@/dynamic-page-runtime/recordFormDraftAccess';

it('keeps every row reachable across pagination and oversized details', async () => {
  const registry = createRelationDraftRegistry();
  const update = vi.fn();
  const form: RecordFormDraftAccess = {
    editorMode: 'edit',
    editingRecord: {},
    referencePickerConfigs: {},
    formFields: new Map(
      Array.from({ length: 100 }, (_, index) => [
        `field${index}`,
        { fieldRef: { fieldName: `field${index}` }, label: '名称'.repeat(80), uiType: 'text' },
      ]),
    ),
    contextRevision: () => '1',
    updateDraftFields: update,
    updateDraftReference: vi.fn(),
  };
  const keys = Array.from({ length: 25 }, (_, index) => `row-${index}`);
  registry.register({
    code: 'lines',
    title: '明细',
    revision: () => '1',
    settle: async () => {},
    rowKeys: () => keys,
    form: (key) => (keys.includes(key) ? form : undefined),
    add: () => {
      throw new Error('not used');
    },
    remove: vi.fn(),
  });
  const capabilities = createRelationDraftAssistantCapabilities(registry, registry.revision);
  const context = {
    signal: new AbortController().signal,
    isCurrent: () => true,
    commitInternalState: <T>(commit: () => T) => commit(),
    applyEffect: <T>(commit: () => T) => commit(),
  };
  const invoke = async (code: string, input: unknown) => {
    const capability = capabilities().find((item) => item.descriptor.code === code)!;
    return capability.execute(capability.parseInput(input), context);
  };
  const first = (await invoke('relation.describe', {})) as {
    relations: Array<{ nextOffset: number; rows: Array<{ rowKey: string; detailsOmitted?: boolean }> }>;
  };
  expect(first.relations[0]!.rows).toHaveLength(20);
  expect(first.relations[0]!.rows[0]!.detailsOmitted).toBe(true);
  expect(first.relations[0]!.nextOffset).toBe(20);
  const last = (await invoke('relation.describe', { relationCode: 'lines', offset: 20 })) as typeof first;
  expect(last.relations[0]!.rows.map((row) => row.rowKey)).toEqual(keys.slice(20));
  expect(last.relations[0]!.nextOffset).toBeNull();
  const selectedFacts = (await invoke('relation.select-row', {
    relationCode: 'lines',
    rowKey: 'row-24',
  })) as { detailsOmitted?: boolean; form?: { fields: unknown[] } };
  expect(selectedFacts.detailsOmitted).toBe(true);
  expect(selectedFacts.form).toBeUndefined();
  expect(capabilities.selection()).toEqual({ relationCode: 'lines', rowKey: 'row-24' });
  expect(
    capabilities()
      .filter((item) => item.schemaDiscovery === 'eager')
      .map((item) => item.descriptor.code),
  ).toEqual(['relation.form.describe', 'relation.form.patch-draft']);
  const selected = (await invoke('relation.form.describe', {})) as { fields: unknown[] };
  expect(selected.fields).toHaveLength(100);
  expect(update).not.toHaveBeenCalled();
  await expect(invoke('relation.describe', { offset: -1 })).rejects.toThrow('无效明细分页参数');
  await expect(invoke('relation.describe', { relationCode: 'missing' })).rejects.toThrow('明细已不可用');
});

it('reads selected aggregate reference names through the row provider without modifying the draft', async () => {
  const registry = createRelationDraftRegistry();
  const resolve = vi.fn(async () => [
    { id: 'product-id', title: '业务商品', projections: { secret: 'hidden' } },
  ]);
  const form: RecordFormDraftAccess = {
    editorMode: 'edit',
    editingRecord: { productId: 'product-id' },
    referencePickerConfigs: {
      productId: { provider: { resolve } },
    } as unknown as RecordFormDraftAccess['referencePickerConfigs'],
    formFields: new Map([
      [
        'productId',
        {
          fieldRef: { fieldName: 'productId' },
          label: '商品',
          reference: { cardinality: 'ONE', targetModuleAlias: 'demo.product' },
        },
      ],
    ]) as RecordFormDraftAccess['formFields'],
    contextRevision: () => '1',
    updateDraftFields: vi.fn(),
    updateDraftReference: vi.fn(),
  };
  registry.register({
    code: 'lines',
    title: '明细',
    revision: () => '1',
    settle: async () => {},
    rowKeys: () => Array.from({ length: 20 }, (_, index) => `row-${index}`),
    form: () => form,
    add: vi.fn(),
    remove: vi.fn(),
  });
  const capabilities = createRelationDraftAssistantCapabilities(registry, registry.revision);
  const context = {
    signal: new AbortController().signal,
    isCurrent: () => true,
    commitInternalState: <T>(fn: () => T) => fn(),
    applyEffect: <T>(fn: () => T) => fn(),
  };
  const describe = capabilities().find((item) => item.descriptor.code === 'relation.describe')!;
  await describe.execute(describe.parseInput({}), context);
  expect(resolve).not.toHaveBeenCalled();
  const select = capabilities().find((item) => item.descriptor.code === 'relation.select-row')!;
  const selectionFacts = await select.execute(
    select.parseInput({ relationCode: 'lines', rowKey: 'row-0' }),
    context,
  );
  expect(resolve).not.toHaveBeenCalled();
  expect(JSON.stringify(selectionFacts)).not.toMatch(/product-id|hidden|secret/);
  const selected = capabilities().find((item) => item.descriptor.code === 'relation.form.describe')!;
  const result = await selected.execute({}, context);
  expect(resolve).toHaveBeenCalledWith(['product-id']);
  expect(JSON.stringify(result)).toContain('业务商品');
  expect(JSON.stringify(result)).not.toMatch(/product-id|hidden|secret/);
  expect(form.updateDraftReference).not.toHaveBeenCalled();
});

it('returns bounded field facts after adding a row through the real effect boundary', async () => {
  const { createAssistantSurfaceRegistry } = await import('@muyun/web-core');
  let revision = 0;
  const keys: string[] = [];
  const resolve = vi.fn();
  const rows = createRelationDraftRegistry();
  const form: RecordFormDraftAccess = {
    editorMode: 'edit',
    editingRecord: { quantity: 2 },
    referencePickerConfigs: {},
    formFields: new Map([['quantity', { fieldRef: { fieldName: 'quantity' }, label: '数量' }]]),
    contextRevision: () => String(revision),
    updateDraftFields: vi.fn(),
    updateDraftReference: resolve,
  };
  rows.register({
    code: 'lines',
    title: '明细',
    revision: () => String(revision),
    settle: async () => {},
    rowKeys: () => keys,
    form: (key) => (keys.includes(key) ? form : undefined),
    add: () => {
      keys.push('new-row');
      revision++;
      return 'new-row';
    },
    remove: vi.fn(),
  });
  const capabilities = createRelationDraftAssistantCapabilities(rows, rows.revision);
  const registry = createAssistantSurfaceRegistry(() => 'user');
  registry.register({
    pageInstanceKey: 'page',
    contextRevision: () => String(revision),
    interactionRevision: () => 'stable',
    executionScopeKey: () => 'tenant',
    surface: { describe: () => ({ surface: 'test', facts: {} }), capabilities, requestTurn: vi.fn() },
  });
  registry.activate('page');
  const result = await registry.invoke(
    { id: 'add', code: 'relation.add-row', input: { relationCode: 'lines' } },
    registry.snapshot()!.token,
  );
  expect(result.contextChanged).toBe(true);
  expect(result.value).toMatchObject({
    rowKey: 'new-row',
    saved: false,
    form: { editable: true, fields: [{ fieldName: 'quantity' }] },
  });
  expect(keys).toEqual(['new-row']);
  expect(resolve).not.toHaveBeenCalled();
});
