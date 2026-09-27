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
