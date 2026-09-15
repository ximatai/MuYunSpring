import { describe, expect, it, vi } from 'vitest';
import { createSourceReferencePickerConfigAssembler } from '@/dynamic-page-runtime/sourceReferencePickerConfig';
import type { ResolvedReferenceFieldDescriptor } from '@muyun/web-contracts';
import type { ReferenceResolveClient } from '@muyun/web-core';

const reference: ResolvedReferenceFieldDescriptor = {
  targetModuleAlias: 'education.student',
  cardinality: 'ONE',
  resolvePath: '/education.enrollment/references/studentId/resolve',
  candidateDependencies: [{ sourceField: 'classId', targetField: 'classId', required: true }],
};

describe('source reference picker config', () => {
  it('retains the source-authorized provider while reading the current draft and limiting legacy loaders', async () => {
    const firstResolve = vi.fn().mockResolvedValue({
      options: [{ id: 'student-1', title: '王华' }],
      results: [{ item: { id: 'student-1', title: '王华' } }],
      total: 1,
    });
    const nextResolve = vi.fn().mockResolvedValue({
      options: [{ id: 'student-1', title: '王华' }],
      results: [{ item: { id: 'student-1', title: '王华' } }],
      total: 1,
    });
    let activeResolve = firstResolve;
    const resolver = () => ({ resolve: activeResolve }) as unknown as ReferenceResolveClient;
    let draft: Record<string, unknown> = { id: 'enrollment-1', classId: 'class-1' };
    const assemble = createSourceReferencePickerConfigAssembler();
    const options = {
      providerScopeKey: 'main',
      sourceModuleAlias: 'education.enrollment',
      reference,
      pickerFieldName: 'studentId',
      referenceResolver: resolver,
      formValues: () => draft,
      reloadRecord: () => draft,
      source: () => ({ recordId: String(draft.id) }),
    };

    const first = assemble(options);
    expect(first.loadOptions).toBeUndefined();
    expect(first.loadTree).toBeUndefined();
    expect(first.resolveOptions).toBeUndefined();
    await first.provider!.searchPage({ keyword: '王', pageNum: 1, pageSize: 20, scope: { selections: [] } });

    draft = { id: 'enrollment-1', classId: 'class-2' };
    activeResolve = nextResolve;
    const next = assemble(options);
    expect(next.provider).toBe(first.provider);
    expect(next.reloadKey).not.toBe(first.reloadKey);
    await next.provider!.resolve(['student-1']);

    expect(firstResolve).toHaveBeenCalledWith('studentId', {
      mode: 'QUERY',
      fuzzy: '王',
      page: { pageNum: 1, pageSize: 20 },
      formValues: { id: 'enrollment-1', classId: 'class-1' },
      source: { recordId: 'enrollment-1' },
    });
    expect(nextResolve).toHaveBeenCalledWith('studentId', {
      mode: 'TRANSLATE',
      values: ['student-1'],
      formValues: { id: 'enrollment-1', classId: 'class-2' },
      source: { recordId: 'enrollment-1' },
    });
  });

  it('keeps legacy source loaders only for the TREE picker branch', async () => {
    const resolve = vi.fn().mockResolvedValue({
      options: [{ id: 'student-1', title: '王华', projections: { studentNo: 'S1' } }],
      results: [{ item: { id: 'student-1', title: '王华', projections: { studentNo: 'S1' } } }],
      tree: [],
      total: 1,
    });
    const config = createSourceReferencePickerConfigAssembler()({
      providerScopeKey: 'child:1',
      sourceModuleAlias: 'education.enrollment_item',
      reference: { ...reference, pickerMode: 'TREE' },
      pickerFieldName: 'studentId',
      referenceResolver: () => ({ resolve }) as unknown as ReferenceResolveClient,
      formValues: () => ({ classId: 'class-1' }),
      reloadRecord: () => ({ classId: 'class-1' }),
      source: () => undefined,
    });

    await expect(config.loadOptions!('王')).resolves.toEqual([
      {
        id: 'student-1',
        title: '王华',
        studentNo: 'S1',
        projections: { studentNo: 'S1' },
        affectPatch: undefined,
      },
    ]);
    await expect(config.loadTree!()).resolves.toEqual([]);
    await expect(config.resolveOptions!(['student-1'])).resolves.toEqual([
      {
        id: 'student-1',
        title: '王华',
        studentNo: 'S1',
        projections: { studentNo: 'S1' },
        affectPatch: undefined,
      },
    ]);
    expect(resolve.mock.calls.map(([, request]) => request.mode)).toEqual(['QUERY', 'TREE', 'TRANSLATE']);
  });
});
