import { expect, it, vi } from 'vitest';
import { createRecordFormAssistantCapabilities } from '@/dynamic-page-runtime/recordFormAssistantCapabilities';
import {
  referenceDraftChanges,
  validateStagedDraft,
  type RecordFormDraftAccess,
} from '@/dynamic-page-runtime/recordFormDraftAccess';
import { createAssistantSurfaceRegistry, runAssistantStep } from '@muyun/web-core';

function fixture() {
  const candidate = { id: 'product-id', title: '商品', affectPatch: { unitPrice: '17.80' } };
  const searchPage = vi.fn(async () => ({ records: [candidate], total: 1 }));
  let revision = 'one';
  const view: RecordFormDraftAccess = {
    editorMode: 'edit',
    editingRecord: { quantity: '1', unitPrice: '8.80' },
    formFields: new Map([
      [
        'productId',
        {
          fieldRef: { fieldName: 'productId' },
          label: '商品',
          reference: { cardinality: 'ONE', targetModuleAlias: 'test.product' },
        },
      ],
      [
        'quantity',
        { fieldRef: { fieldName: 'quantity' }, label: '数量', valueType: 'DECIMAL', uiType: 'number' },
      ],
      [
        'unitPrice',
        { fieldRef: { fieldName: 'unitPrice' }, label: '单价', valueType: 'DECIMAL', uiType: 'number' },
      ],
      ['amount', { fieldRef: { fieldName: 'amount' }, label: '小计', readOnly: { constant: true } }],
      ['hidden', { fieldRef: { fieldName: 'hidden' }, label: '隐藏', assistantPolicy: 'HIDDEN' }],
    ]) as RecordFormDraftAccess['formFields'],
    referencePickerConfigs: { productId: { provider: { searchPage, resolve: vi.fn() } } } as never,
    contextRevision: () => revision,
    updateDraftFields: vi.fn(),
    updateDraftReference: vi.fn((name, record, _source, changes, validate) => {
      const next = {
        ...view.editingRecord,
        ...Object.fromEntries(
          referenceDraftChanges(name, record, changes).map(({ fieldName, value }) => [fieldName, value]),
        ),
      };
      validateStagedDraft(next, validate);
      Object.assign(view.editingRecord!, next);
    }),
  };
  const capabilities = createRecordFormAssistantCapabilities(view);
  const invoke = async (input: unknown, current = () => true) => {
    const capability = capabilities().find((item) => item.descriptor.code === 'reference.resolve-and-patch')!;
    return capability.execute(capability.parseInput(input), {
      signal: new AbortController().signal,
      isCurrent: current,
      commitInternalState: (effect) => effect(),
      applyEffect: (effect) => effect(),
    });
  };
  return {
    view,
    searchPage,
    invoke,
    changeRevision: () => {
      revision = 'two';
    },
  };
}

it('combines a unique authorized reference and explicit ordinary values into one draft update', async () => {
  const { view, invoke } = fixture();
  const result = await invoke({
    fieldName: 'productId',
    title: '商品',
    changes: [
      { fieldName: 'quantity', value: '2' },
      { fieldName: 'unitPrice', value: '20.00' },
    ],
  });
  expect(view.editingRecord).toEqual({ productId: 'product-id', quantity: '2', unitPrice: '20.00' });
  expect(view.updateDraftReference).toHaveBeenCalledTimes(1);
  expect(view.updateDraftFields).not.toHaveBeenCalled();
  expect(result).toMatchObject({ changedFields: ['productId', 'quantity', 'unitPrice'] });
  expect(JSON.stringify(result)).not.toContain('product-id');
});

it.each([
  [[{ fieldName: 'productId', value: 'forged' }]],
  [[{ fieldName: 'quantity', value: 2 }]],
  [[{ fieldName: 'quantity', value: 'invalid' }]],
  [[{ fieldName: 'amount', value: '1' }]],
  [[{ fieldName: 'hidden', value: 'secret' }]],
  [
    [
      { fieldName: 'quantity', value: '1' },
      { fieldName: 'quantity', value: '2' },
    ],
  ],
])('rejects an invalid combined change before producing any effect: %j', async (changes) => {
  const { view, invoke } = fixture();
  await expect(invoke({ fieldName: 'productId', title: '商品', changes })).rejects.toThrow();
  expect(view.editingRecord).toEqual({ quantity: '1', unitPrice: '8.80' });
  expect(view.updateDraftReference).not.toHaveBeenCalled();
});

it.each([0, 2])(
  'leaves all fields unchanged when reference matching returns %i candidates',
  async (total) => {
    const { view, searchPage, invoke } = fixture();
    searchPage.mockResolvedValueOnce({
      records: total ? [{ id: 'other', title: '其它', affectPatch: { unitPrice: '1' } }] : [],
      total,
    });
    await expect(
      invoke({ fieldName: 'productId', title: '商品', changes: [{ fieldName: 'quantity', value: '2' }] }),
    ).rejects.toThrow();
    expect(view.updateDraftReference).not.toHaveBeenCalled();
    expect(view.editingRecord).toEqual({ quantity: '1', unitPrice: '8.80' });
  },
);

it.each(['revision', 'cancel', 'field'])(
  'rejects changes when %s changes during reference search',
  async (change) => {
    const { view, searchPage, invoke, changeRevision } = fixture();
    let finish!: (result: Awaited<ReturnType<typeof searchPage>>) => void;
    searchPage.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    let current = true;
    const pending = invoke(
      { fieldName: 'productId', title: '商品', changes: [{ fieldName: 'quantity', value: '2' }] },
      () => current,
    );
    if (change === 'revision') changeRevision();
    if (change === 'cancel') current = false;
    if (change === 'field') view.formFields.get('quantity')!.readOnly = { constant: true };
    finish({ records: [{ id: 'product-id', title: '商品', affectPatch: { unitPrice: '17.80' } }], total: 1 });
    await expect(pending).rejects.toThrow();
    expect(view.updateDraftReference).not.toHaveBeenCalled();
  },
);

function lockQuantityAfterReference(view: RecordFormDraftAccess) {
  view.formFields.get('quantity')!.readOnly = {
    formula: {
      expression: "{productId} == 'product-id'",
      program: {
        schemaVersion: 1,
        profile: 'WEB_UI',
        referencedFields: ['productId'],
        root: {
          kind: 'BINARY',
          operator: '==',
          arguments: [
            { kind: 'FIELD', field: 'productId', arguments: [] },
            { kind: 'VALUE', value: 'product-id', arguments: [] },
          ],
        },
      },
    },
  } as never;
}

it('rejects an ordinary field that becomes read-only after the combined reference mapping', async () => {
  const { view, invoke } = fixture();
  lockQuantityAfterReference(view);
  await expect(
    invoke({ fieldName: 'productId', title: '商品', changes: [{ fieldName: 'quantity', value: '2' }] }),
  ).rejects.toThrow('not editable');
  expect(view.editingRecord).toEqual({ quantity: '1', unitPrice: '8.80' });
});

it('reports a staged reference rejection as not-applied through the real registry and runtime', async () => {
  const { view } = fixture();
  lockQuantityAfterReference(view);
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'order',
    contextRevision: view.contextRevision,
    surface: {
      describe: () => ({ surface: 'module-page', facts: {} }),
      capabilities: createRecordFormAssistantCapabilities(view),
      requestTurn: async () => ({
        toolCalls: [
          {
            id: 'select',
            code: 'reference.resolve-and-patch',
            input: {
              fieldName: 'productId',
              title: '商品',
              changes: [{ fieldName: 'quantity', value: '2' }],
            },
          },
        ],
      }),
    },
  });
  registry.activate('order');
  const result = await runAssistantStep(registry, '选择商品并填写数量');
  expect(result).toMatchObject({
    contextChanged: false,
    appliedEffectCount: 0,
    results: [{ execution: 'not-applied', error: { code: 'CAPABILITY_USAGE_INVALID' } }],
  });
  expect(result.results[0]!.error!.message).toContain('not editable');
  expect(view.editingRecord).toEqual({ quantity: '1', unitPrice: '8.80' });
});
