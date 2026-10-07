import { describe, expect, it, vi } from 'vitest';
import {
  createBusinessRuleAssistantSurface,
  parseBusinessRuleProposal,
  parseBusinessRuleTrial,
  type BusinessRuleAssistantAdapter,
} from '@/views/businessRuleAssistantSurface';
import { createAssistantSurfaceRegistry } from '@/web-core';

it('rejects unsupported commands and oversized samples without evaluating expressions', () => {
  expect(() =>
    parseBusinessRuleProposal({
      code: 'sum',
      kind: 'CALCULATION',
      expression: '1',
      enabled: true,
      script: 'alert(1)',
    }),
  ).toThrow();
  expect(() =>
    parseBusinessRuleProposal({ code: 'sum', kind: 'CALCULATION', expression: '1', enabled: true }),
  ).toThrow();
  expect(() =>
    parseBusinessRuleTrial({ sampleValues: {}, sampleChildren: { lines: Array(101).fill({}) } }),
  ).toThrow();
  expect(parseBusinessRuleTrial({ sampleValues: {}, sampleChildren: { lines: [] } }).sampleChildren).toEqual({
    lines: [],
  });
  expect(() =>
    parseBusinessRuleProposal({
      code: 'positiveQuantity',
      kind: 'VALIDATION',
      targetField: 'lines.quantity',
      expression: '{lines.quantity} > 0',
      enabled: true,
    }),
  ).toThrow('主记录字段');
});

describe('business-rule assistant boundary', () => {
  function fixture() {
    let revision = 0;
    const execute = vi.fn(async () => ({ title: '已应用', lines: [] }));
    const adapter = {
      summary: () => ({ moduleAlias: 'demo.any', editable: true }),
      catalog: (() =>
        Array.from({ length: 12 }, (_, index) => ({
          fieldName: `field${index}`,
        }))) as BusinessRuleAssistantAdapter['catalog'],
      revise: vi.fn(() => {
        revision++;
      }),
      preview: vi.fn(async () => ({
        errors: [],
        executionOrder: ['sum'],
        proposalFingerprint: 'p',
        snapshot: {} as never,
      })),
      trial: vi.fn(async () => ({
        values: { total: 18 },
        changedFields: ['total'],
        errors: [],
        preview: {} as never,
      })),
      prepareConfirmation: vi.fn(async () => {
        const captured = revision;
        return {
          presentation: { title: '应用规则', lines: [] },
          expiresAt: Date.now() + 60000,
          isCurrent: () => captured === revision,
          execute,
          lookup: async () => undefined,
        };
      }),
    };
    const registry = createAssistantSurfaceRegistry();
    registry.register({
      pageInstanceKey: 'page',
      contextRevision: () => String(revision),
      surface: createBusinessRuleAssistantSurface(adapter, vi.fn()),
    });
    registry.activate('page');
    const invoke = (code: string, input: unknown = {}) =>
      registry.invoke({ id: 'call', code, input }, registry.snapshot()!.token);
    return { adapter, execute, registry, invoke };
  }
  it('paginates the authoritative directory and trials without persistence', async () => {
    const { invoke, execute, adapter } = fixture();
    const page = await invoke('rules.describe', { section: 'fields' });
    expect(page.value).toMatchObject({ total: 12, nextOffset: 10 });
    expect((await invoke('rules.describe', { section: 'fields', offset: 10 })).value).toMatchObject({
      nextOffset: null,
    });
    await invoke('rules.trial', {
      sampleValues: {},
      sampleChildren: { lines: [{ amount: 10 }, { amount: 8 }] },
    });
    expect(adapter.trial).toHaveBeenCalledWith(
      expect.objectContaining({ sampleChildren: { lines: [{ amount: 10 }, { amount: 8 }] } }),
      expect.any(AbortSignal),
    );
    expect(execute).not.toHaveBeenCalled();
  });
  it('exposes bounded child calculation and aggregate catalogs before further discovery calls', () => {
    const { registry, adapter } = fixture();
    adapter.catalog = ((section: string) =>
      Array.from({ length: 200 }, (_, index) => ({
        fieldName: `${section === 'fields' ? '' : 'lines.'}amount${index}`,
        title: '明细金额',
      }))) as BusinessRuleAssistantAdapter['catalog'];
    const facts = registry.snapshot()!.context.facts;
    for (const section of ['fields', 'childFields', 'aggregateFields']) {
      expect(facts[section]).toMatchObject({ total: 200 });
      expect(JSON.stringify(facts[section]).length).toBeLessThan(3500);
    }
    expect(facts.childFields).toMatchObject({ nextOffset: expect.any(Number) });
  });
  it('finds fields beyond the initial page by business title without changing candidates', async () => {
    const { registry, adapter, invoke, execute } = fixture();
    adapter.catalog = (section) =>
      section === 'childFields'
        ? Array.from({ length: 13 }, (_, index) => ({
            fieldName: `expenses.field${index}`,
            title: index === 12 ? '行成本' : '其他字段',
          }))
        : [];
    expect(registry.snapshot()!.context.facts.childFields).toMatchObject({
      coverage: 'partial',
      nextOffset: 10,
    });
    const result = await invoke('rules.describe', { section: 'childFields', keyword: '成本' });
    expect(result.value).toMatchObject({
      items: [{ fieldName: 'expenses.field12', title: '行成本' }],
      total: 1,
      catalogTotal: 13,
      coverage: 'complete',
      nextOffset: null,
    });
    expect(
      (await invoke('rules.describe', { section: 'childFields', keyword: '不存在' })).value,
    ).toMatchObject({
      items: [],
      total: 0,
      catalogTotal: 13,
      coverage: 'complete',
    });
    expect(adapter.revise).not.toHaveBeenCalled();
    expect(execute).not.toHaveBeenCalled();
  });
  it('keeps filtered observations bounded and never treats oversized matches as absent', async () => {
    const { adapter, invoke } = fixture();
    adapter.catalog = () => [{ fieldName: 'lines.cost', title: '成本'.repeat(4000) }];
    expect(
      (await invoke('rules.describe', { section: 'childFields', keyword: 'lines.cost' })).value,
    ).toMatchObject({
      items: [],
      total: 1,
      coverage: 'partial',
      nextOffset: null,
      oversizedIndexes: [0],
    });
    await expect(invoke('rules.describe', { section: 'rules', keyword: 'cost' })).rejects.toThrow('字段目录');
    await expect(invoke('rules.describe', { section: 'fields', keyword: ' ' })).rejects.toThrow();
    await expect(invoke('rules.describe', { section: 'fields', extra: 'value' })).rejects.toThrow();
  });
  it('presents draft changes separately from compiler checks and formal application', async () => {
    const { adapter, invoke, execute } = fixture();
    const changed = await invoke('rules.revise', {
      code: 'positive',
      kind: 'VALIDATION',
      expression: '{amount} > 0',
      enabled: true,
    });
    expect(changed.presentation).toMatchObject({ title: '业务规则草稿已更新' });
    expect(changed.presentation!.lines.join('')).toContain('尚未检查或应用');
    expect(adapter.preview).not.toHaveBeenCalled();
    const checked = await invoke('rules.preview');
    expect(checked.presentation).toMatchObject({ title: '计算与保存校验检查通过' });
    expect(checked.presentation!.lines.join('')).toContain('没有应用任何更改');
    adapter.preview.mockResolvedValueOnce({
      errors: [{ message: '计算目标不存在' }],
      executionOrder: [],
      proposalFingerprint: 'p',
      snapshot: {} as never,
    } as never);
    expect((await invoke('rules.preview')).presentation).toMatchObject({
      title: '计算与保存校验检查未通过',
      lines: expect.arrayContaining(['计算目标不存在']),
    });
    expect(execute).not.toHaveBeenCalled();
  });
  it('declares calculation, validation and UI-control inputs separately without asking for empty optional values', () => {
    const { adapter } = fixture();
    const surface = createBusinessRuleAssistantSurface(adapter, vi.fn());
    const schema = surface.capabilities().find((item) => item.descriptor.code === 'rules.revise')!.descriptor
      .inputSchema as {
      oneOf: Array<{
        additionalProperties: boolean;
        required: string[];
        description?: string;
        properties: Record<string, unknown>;
      }>;
    };
    const [calculation, validation, uiControl] = schema.oneOf;
    expect(calculation.required).toContain('targetField');
    expect(calculation.properties).not.toHaveProperty('formKey');
    expect(validation.properties.kind).toEqual({ type: 'string', const: 'VALIDATION' });
    expect(validation.description).toContain('为假时阻止保存');
    expect(validation.description).toContain('不支持只提醒而仍允许保存');
    expect(validation.properties).not.toHaveProperty('severity');
    expect(validation.properties).not.toHaveProperty('formKey');
    expect(validation.properties).not.toHaveProperty('targets');
    expect(validation.properties.messageTemplate).toMatchObject({ minLength: 1, maxLength: 500 });
    expect(validation.properties.targetField).toMatchObject({ pattern: '^[^.]+$' });
    expect(uiControl.required).toEqual(expect.arrayContaining(['formKey', 'targets']));
    expect(uiControl.properties).not.toHaveProperty('targetField');
    for (const branch of schema.oneOf) expect(branch.additionalProperties).toBe(false);
    expect(
      parseBusinessRuleProposal({
        code: 'priceNonNegative',
        kind: 'VALIDATION',
        expression: '{price} >= 0',
        messageTemplate: '售价不能小于零',
        enabled: true,
      }),
    ).toMatchObject({ kind: 'VALIDATION', messageTemplate: '售价不能小于零' });
  });
  it('exposes blocking validation semantics during read-only inspection without requiring editing commands', () => {
    const { adapter } = fixture();
    const summary = adapter.summary();
    adapter.summary = () => ({ ...summary, editable: false });
    const surface = createBusinessRuleAssistantSurface(adapter, vi.fn());
    expect(surface.describe().facts?.validationBehavior).toContain('不支持只提醒而仍允许保存');
    expect(surface.capabilities().some((capability) => capability.descriptor.code === 'rules.revise')).toBe(
      false,
    );
  });
  it('provides current facts and advances past oversized catalog entries explicitly', async () => {
    const { adapter, registry, invoke } = fixture();
    adapter.catalog = () => [{ fieldName: 'large', title: 'x'.repeat(7000) }, { fieldName: 'amount' }];
    expect(registry.snapshot()!.context.facts).toMatchObject({
      fields: { items: [{ fieldName: 'amount' }], oversizedIndexes: [0], nextOffset: null },
    });
    expect((await invoke('rules.describe', { section: 'fields' })).value).toMatchObject({
      items: [{ fieldName: 'amount' }],
      oversizedIndexes: [0],
      nextOffset: null,
    });
  });
  it('keeps confirmation current when an identical candidate is resubmitted', async () => {
    const { adapter, invoke, execute } = fixture();
    const rule = { code: 'amountPositive', kind: 'VALIDATION', expression: '{amount} >= 0', enabled: true };
    adapter.catalog = (section) => (section === 'rules' ? [rule] : []);
    const prepared = await invoke('rules.prepare-apply');
    expect((await invoke('rules.revise', rule)).value).toMatchObject({ unchanged: true, saved: false });
    expect(adapter.revise).not.toHaveBeenCalled();
    await prepared.confirmation!.confirm();
    expect(execute).toHaveBeenCalledTimes(1);
  });
  it('pairs trial results with labeled input samples and warns when no rule ran', async () => {
    const { adapter, invoke } = fixture();
    adapter.catalog = (section) =>
      section === 'fields'
        ? [{ fieldName: 'total', title: '合计' }]
        : section === 'aggregateFields'
          ? [{ fieldName: 'lines.amount', title: '小计' }]
          : [];
    const sample = { sampleValues: { total: -1 }, sampleChildren: { lines: [{ amount: 18 }] } };
    const result = await invoke('rules.trial', sample);
    sample.sampleValues.total = 999;
    expect(result.presentation!.lines).toEqual(
      expect.arrayContaining([
        '输入 · 合计：-1',
        '第 1 行：小计：18',
        '结果 · 合计：18',
        '当前没有启用的计算或保存校验；本次不能证明预期规则有效。',
      ]),
    );
    expect(result.value).toMatchObject({ sample: { sampleValues: { total: -1 } } });
  });
  it('requires a human confirmation and expires it when the candidate changes', async () => {
    const { invoke, execute } = fixture();
    const first = await invoke('rules.prepare-apply');
    expect(execute).not.toHaveBeenCalled();
    await invoke('rules.revise', {
      code: 'sum',
      kind: 'CALCULATION',
      targetField: 'total',
      expression: 'SUM({lines.amount})',
      enabled: true,
    });
    await first.confirmation!.confirm();
    expect(execute).not.toHaveBeenCalled();
    const second = await invoke('rules.prepare-apply');
    await second.confirmation!.confirm();
    await second.confirmation!.confirm();
    expect(execute).toHaveBeenCalledTimes(1);
  });
  it('does not expose rule mutations or confirmations to read-only continuation', async () => {
    const { registry, adapter } = fixture();
    await expect(
      registry.invoke(
        { id: 'call', code: 'rules.prepare-apply', input: {} },
        registry.snapshot()!.token,
        undefined,
        { readOnly: true },
      ),
    ).rejects.toThrow();
    expect(adapter.prepareConfirmation).not.toHaveBeenCalled();
  });
});

it('rejects rule identifiers that the standard governance compiler cannot apply', () => {
  for (const code of ['amount_not_negative', 'AmountRule', 'amount-rule'])
    expect(() =>
      parseBusinessRuleProposal({ code, kind: 'VALIDATION', expression: '{amount} >= 0', enabled: true }),
    ).toThrow('小写字母');
});
