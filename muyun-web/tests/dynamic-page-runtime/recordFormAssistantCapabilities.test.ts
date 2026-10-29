import { createRecordReferenceDisplay } from '@/dynamic-page-runtime/recordReferenceDisplay';
import { createLanguageContext } from '@/web-core';
import { recordFormAssistantFacts } from '@/dynamic-page-runtime/recordFormAssistantCapabilities';
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

it('uses the owning form language for live and explicit reads without changing fields or resolving on a language switch', async () => {
  const original = fixture();
  const language = createLanguageContext({ languageTag: 'en' });
  const view = { ...original.view, language };
  const current = () =>
    recordFormAssistantFacts(view).fields.find((field) => field.fieldName === 'productId')!;
  expect(current().currentValue).toBe('Not selected');
  view.editingRecord!.productId = 'product-id';
  expect(current().currentValue).toBe('Selected (verify name when needed)');
  const resolve = vi.mocked(view.referencePickerConfigs.productId!.provider!.resolve);
  resolve.mockRejectedValue(new Error('private diagnostic'));
  const describe = createRecordFormAssistantCapabilities(view)().find(
    ({ descriptor }) => descriptor.code === 'form.describe',
  )!;
  const context = {
    signal: new AbortController().signal,
    isCurrent: () => true,
    commitInternalState: <T>(commit: () => T) => commit(),
    applyEffect: <T>(commit: () => T) => commit(),
  };
  const read = (await describe.execute({}, context)) as ReturnType<typeof recordFormAssistantFacts>;
  expect(read.fields.find((field) => field.fieldName === 'productId')?.currentValue).toBe(
    'Selected (name unavailable)',
  );
  language.setLanguage('zh-CN');
  expect(current().currentValue).toBe('已选择（名称按需核实）');
  expect(resolve).toHaveBeenCalledOnce();
  expect(view.editingRecord?.productId).toBe('product-id');
  expect(view.updateDraftFields).not.toHaveBeenCalled();
});

it('formats observed candidate names in the owning language for assistant live facts without querying or editing', () => {
  const { view, searchPage } = fixture();
  const language = createLanguageContext({ languageTag: 'en' });
  const form = { ...view, language };
  form.editingRecord!.productId = ['product-id', 'A001'];
  const display = createRecordReferenceDisplay(form, form.contextRevision);
  form.referenceDisplay = display.current;
  const candidates = [
    { id: 'product-id', title: 'Alice', affectPatch: { unitPrice: '99' } },
    { id: 'A001', title: 'A001' },
  ];
  display.observe('productId', candidates);
  const current = () =>
    recordFormAssistantFacts(form).fields.find((field) => field.fieldName === 'productId')!.currentValue;
  expect(current()).toBe('Alice, A001');
  candidates[0]!.title = 'Mutated';
  language.setLanguage('zh-CN');
  expect(current()).toBe('Alice、A001');
  language.setLanguage('en');
  expect(current()).toBe('Alice, A001');
  expect(searchPage).not.toHaveBeenCalled();
  expect(form.referencePickerConfigs.productId!.provider!.resolve).not.toHaveBeenCalled();
  expect(form.editingRecord).toEqual({ quantity: '1', unitPrice: '8.80', productId: ['product-id', 'A001'] });
  expect(form.updateDraftFields).not.toHaveBeenCalled();
  display.observe('productId', [
    { id: 'product-id', title: 'Alice' },
    { id: 'A001', title: 'A001', identifierFallback: true },
  ]);
  expect(current()).toBe('Selected (verify name when needed)');
});

it('shares remote dictionary candidates with the standard form and rejects disabled or withdrawn values', async () => {
  const { loadOptionFieldItems, optionFieldState } =
    await import('@/platform-components/optionFieldOptionCache');
  const context = {
    moduleAlias: 'demo.record',
    http: {
      request: vi.fn().mockResolvedValue([
        { code: 'A', title: '可选', enabled: true },
        { code: 'B', title: '停用', enabled: false },
      ]),
    },
  } as unknown as import('@muyun/web-core').ModuleContext<unknown>;
  const { view } = fixture();
  view.formFields.set('state', {
    fieldRef: { fieldName: 'state' },
    label: '状态',
    option: { binding: { sourceType: 'dictionary', source: 'demo.state' }, selectionMode: 'SINGLE' },
  } as never);
  view.optionState = (name) => optionFieldState(context, name);
  const capability = createRecordFormAssistantCapabilities(view)().find(
    (item) => item.descriptor.code === 'form.patch-draft',
  )!;
  const apply = (value: string) =>
    capability.execute(capability.parseInput({ changes: [{ fieldName: 'state', value }] }), {
      signal: new AbortController().signal,
      isCurrent: () => true,
      commitInternalState: (effect) => effect(),
      applyEffect: (effect) => effect(),
    });
  await expect(apply('A')).rejects.toThrow();
  await loadOptionFieldItems(context, 'state');
  expect(recordFormAssistantFacts(view).fields.find((field) => field.fieldName === 'state')).toMatchObject({
    options: [{ value: 'A', label: '可选' }],
  });
  await apply('A');
  expect(view.updateDraftFields).toHaveBeenCalledWith([{ fieldName: 'state', value: 'A' }], 'assistant');
  await expect(apply('B')).rejects.toThrow();
  const refresh = loadOptionFieldItems(context, 'state', undefined, context.moduleAlias, true);
  await expect(apply('A')).rejects.toThrow();
  await refresh;
  view.optionState = () => ({
    status: 'ready',
    items: Array.from({ length: 55 }, (_, index) => ({
      code: String(index),
      title: String(index),
      enabled: true,
    })),
  });
  const fact = recordFormAssistantFacts(view).fields.find((field) => field.fieldName === 'state');
  expect(fact?.options).toHaveLength(50);
  expect(fact).toMatchObject({ optionsTruncated: true });
});
