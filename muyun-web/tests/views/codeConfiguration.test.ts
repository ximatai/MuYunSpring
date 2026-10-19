import { describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import {
  codeTrialContext,
  codeTrialSources,
  createCodeConfigurationClient,
  newCodeRule,
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
      quantity: 12,
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
