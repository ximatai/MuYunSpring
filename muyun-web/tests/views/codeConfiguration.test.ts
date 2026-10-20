import { describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import {
  codeRuleSupportsRenumbering,
  codeTrialContext,
  codeSourceTitle,
  codeTrialSources,
  createCodeConfigurationClient,
  newCodeRule,
  newCodeSegment,
  prepareCodeRule,
  type CodeTarget,
} from '@/views/codeConfiguration';
import { createLanguageContext } from '@muyun/web-core';
import type { HttpClient } from '@muyun/web-core';

const target: CodeTarget = {
  moduleAlias: 'sales.invoice',
  entityAlias: 'invoice',
  source: 'STATIC',
  title: '单据',
  fields: [{ fieldName: 'documentNo', title: '编码', role: 'PRIMARY', trigger: 'EXPLICIT' }],
  sources: [{ fieldName: 'quantity', title: '数量', type: 'INTEGER' }],
};
describe('business code configuration client', () => {
  it('switches shared field labels without changing qualified source identity or custom labels', () => {
    const language = createLanguageContext({ languageTag: 'en' });
    const source = {
      fieldName: 'purchase.version',
      title: '版本号',
      ownerTitle: '采购单',
      type: 'Integer',
      titleText: {
        source: {
          sourceKind: 'CODE' as const,
          sourceId: 'query/field/version/title',
          sourceVersion: 'v1',
          originalText: '版本号',
        },
        languages: {
          en: { text: 'Version', requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false },
        },
      },
    };
    expect(codeSourceTitle(source, language)).toBe('采购单 · Version');
    language.setLanguage('zh-CN');
    expect(codeSourceTitle(source, language)).toBe('采购单 · 版本号');
    expect(source.fieldName).toBe('purchase.version');
    expect(codeSourceTitle({ fieldName: 'subject', title: '自定义业务标题', type: 'String' }, language)).toBe(
      '自定义业务标题',
    );
  });
  it('leaves segment length unset so the sequence policy controls new rules', () => {
    const draft = newCodeRule(target, target.fields[0]!);
    draft.sequencePolicy!.sequenceLength = 6;
    expect(
      prepareCodeRule(draft).segments.find((segment) => segment.segmentType === 'SEQUENCE')!.length,
    ).toBeNull();
    expect(prepareCodeRule(draft).sequencePolicy!.sequenceLength).toBe(6);
    draft.segments[1]!.length = 8;
    expect(prepareCodeRule(draft).segments[1]!.length).toBe(8);
  });
  it('only submits renumbering for automatic save rules with source dependencies', () => {
    const draft = newCodeRule(target, { ...target.fields[0]!, trigger: 'SAVE' });
    draft.linkedUpdate = true;
    expect(codeRuleSupportsRenumbering(draft)).toBe(false);
    expect(prepareCodeRule(draft).linkedUpdate).toBe(false);
    for (const segmentType of ['FIELD_VALUE', 'VALUE_MAPPING', 'CONTEXT_VAR', 'FORMULA'] as const) {
      draft.segments[0] = { ...newCodeSegment(segmentType), sourceRef: 'quantity' };
      expect(codeRuleSupportsRenumbering(draft)).toBe(true);
      expect(prepareCodeRule(draft).linkedUpdate).toBe(true);
    }
    draft.fieldTrigger = 'EXPLICIT';
    expect(codeRuleSupportsRenumbering(draft)).toBe(false);
    expect(prepareCodeRule(draft).linkedUpdate).toBe(false);
    draft.fieldTrigger = 'SAVE';
    draft.mode = 'MANUAL';
    expect(codeRuleSupportsRenumbering(draft)).toBe(false);
    expect(prepareCodeRule(draft).linkedUpdate).toBe(false);
    expect(draft.linkedUpdate).toBe(true);
  });
  it('collects only direct dependencies, deduplicates them and preserves their declared input types', () => {
    const draft = newCodeRule(target, target.fields[0]!);
    draft.segments = [
      { ...newCodeSegment('FIELD_VALUE'), sourceRef: 'quantity' },
      { ...newCodeSegment('VALUE_MAPPING'), sourceRef: 'quantity' },
      { ...newCodeSegment('FORMULA'), formulaExpr: '{quantity} + 1' },
    ];
    const catalog = {
      ...target,
      sources: [...target.sources, { fieldName: 'version', title: 'Version', type: 'LONG' }],
    };
    expect(codeTrialSources(catalog, draft)).toEqual([target.sources[0]]);
    expect(codeTrialContext(codeTrialSources(catalog, draft), { quantity: '12', version: '99' })).toEqual({
      quantity: 12,
    });
    expect(codeTrialSources(catalog, newCodeRule(target, target.fields[0]!))).toEqual([]);
  });

  it('keeps exact numeric trial values on the shared field wire through JSON transport', async () => {
    const sources = [
      { fieldName: 'serial', title: '序号', type: 'LONG' },
      { fieldName: 'amount', title: '金额', type: 'BigDecimal' },
      { fieldName: 'rate', title: '比率', type: 'DECIMAL' },
      { fieldName: 'quantity', title: '数量', type: 'INTEGER' },
    ];
    const context = codeTrialContext(sources, {
      serial: '9007199254740993',
      amount: '123456789012345678.12',
      rate: '1.00',
      quantity: '12',
    });
    expect(context).toEqual({
      serial: '9007199254740993',
      amount: '123456789012345678.12',
      rate: '1.00',
      quantity: 12,
    });
    const request = vi.fn().mockResolvedValue({ value: 'preview', segments: [] });
    await createCodeConfigurationClient({ request } as HttpClient).preview(
      newCodeRule(target, target.fields[0]!),
      context,
      1,
    );
    expect(JSON.parse(JSON.stringify(request.mock.calls[0]![0].body)).context).toEqual(context);
    expect(() => codeTrialContext(sources, { serial: '1.5' })).toThrow();
    expect(() => codeTrialContext(sources, { amount: 'NaN' })).toThrow();
    expect(() => codeTrialContext(sources, { quantity: '9007199254740993' })).toThrow();
  });

  it('serializes reactive editor state with versions, ordered segments and tenant-independent target identities', async () => {
    const request = vi.fn().mockResolvedValue({ id: 'saved' });
    const client = createCodeConfigurationClient({ request } as HttpClient);
    const draft = reactive(newCodeRule(target, target.fields[0]!));
    draft.id = 'rule';
    draft.version = 3;
    draft.segments.reverse();
    draft.orgScopeId = 'stale-organization';
    await client.save(draft);
    const body = request.mock.calls[0]![0].body;
    expect(body).toMatchObject({
      id: 'rule',
      version: 3,
      orgScopeId: null,
      fieldName: 'documentNo',
      fieldRole: 'PRIMARY',
    });
    expect(body.segments.map((segment: { sortOrder: number }) => segment.sortOrder)).toEqual([0, 1]);
    expect(draft.orgScopeId).toBe('stale-organization');
  });
  it('removes automatic policy when changing to manual and preserves code binding role', () => {
    const draft = newCodeRule(target, target.fields[0]!);
    draft.mode = 'MANUAL';
    draft.linkedUpdate = true;
    expect(prepareCodeRule(draft)).toMatchObject({
      mode: 'MANUAL',
      segments: [],
      sequencePolicy: null,
      linkedUpdate: false,
      fieldRole: 'PRIMARY',
    });
  });
  it('uses non-consuming draft trial and gives explicit action variables their own sample inputs', async () => {
    const request = vi.fn().mockResolvedValue({ value: 'CANDIDATE' });
    const draft = newCodeRule(target, target.fields[0]!);
    draft.segments[0]!.segmentType = 'CONTEXT_VAR';
    draft.segments[0]!.sourceRef = 'fiscalYear';
    const sources = codeTrialSources(target, draft);
    expect(codeTrialContext(sources, { quantity: '12', fiscalYear: 'FY2026' })).toEqual({
      fiscalYear: 'FY2026',
    });
    await createCodeConfigurationClient({ request } as HttpClient).preview(
      draft,
      { fiscalYear: 'FY2026' },
      7,
    );
    expect(request.mock.calls[0]![0]).toMatchObject({
      path: '/platform.code_rule/preview',
      body: { sequenceValue: 7 },
    });
    expect(request).toHaveBeenCalledTimes(1);
    expect(draft).not.toHaveProperty('value');
  });
});
