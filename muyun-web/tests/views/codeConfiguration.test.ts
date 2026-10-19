import { describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import {
  codeTrialContext,
  codeTrialSources,
  createCodeConfigurationClient,
  newCodeRule,
  newCodeSegment,
  prepareCodeRule,
  type CodeTarget,
} from '@/views/codeConfiguration';
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
