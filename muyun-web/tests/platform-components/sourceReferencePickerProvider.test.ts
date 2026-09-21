import { describe, expect, it, vi } from 'vitest';
import {
  createSourceReferencePickerProvider,
  sourceReferencePickerReloadKey,
} from '@/platform-components/sourceReferencePickerProvider';
import { ReferencePickerReadError } from '@/platform-components/referencePickerReadError';

describe('source reference picker provider', () => {
  const reference = {
    targetModuleAlias: 'education.student',
    cardinality: 'ONE' as const,
    resolvePath: '/education/enrollment/references/studentId/resolve',
    candidateDependencies: [{ sourceField: 'classId', targetField: 'classId', required: true }],
  };

  it('keeps server pagination and source-bound QUERY/TRANSLATE semantics', async () => {
    const resolve = vi
      .fn()
      .mockResolvedValueOnce({
        options: [
          {
            id: 'student-2',
            title: '李明',
            projections: { studentNo: 'S2' },
            affectPatch: { studentNo: 'S2' },
          },
        ],
        total: 71,
      })
      .mockResolvedValueOnce({
        results: [
          { item: { id: 'student-1', title: '王华', projections: { studentNo: 'S1' } } },
          { item: { id: 'student-unrequested', title: '不应回显' } },
        ],
      });
    const provider = createSourceReferencePickerProvider({
      sourceModuleAlias: 'education.enrollment',
      fieldName: 'studentId',
      reference,
      resolver: () => ({ resolve }),
      formValues: () => ({ classId: 'class-1' }),
      source: () => ({ recordId: 'enrollment-1' }),
    });

    await expect(
      provider.searchPage({ keyword: '李', pageNum: 3, pageSize: 25, scope: { selections: [] } }),
    ).resolves.toEqual({
      records: [
        {
          id: 'student-2',
          title: '李明',
          projections: { studentNo: 'S2' },
          affectPatch: { studentNo: 'S2' },
        },
      ],
      total: 71,
    });
    await expect(provider.resolve(['student-1'])).resolves.toEqual([
      { id: 'student-1', title: '王华', projections: { studentNo: 'S1' }, affectPatch: undefined },
    ]);

    expect(provider.identity).toEqual({
      targetModuleAlias: 'education.student',
      source: {
        kind: 'sourceField',
        id: 'education.enrollment:studentId:/education/enrollment/references/studentId/resolve',
      },
    });
    expect(resolve).toHaveBeenNthCalledWith(1, 'studentId', {
      mode: 'QUERY',
      fuzzy: '李',
      page: { pageNum: 3, pageSize: 25 },
      formValues: { classId: 'class-1' },
      source: { recordId: 'enrollment-1' },
    });
    expect(resolve).toHaveBeenNthCalledWith(2, 'studentId', {
      mode: 'TRANSLATE',
      values: ['student-1'],
      formValues: { classId: 'class-1' },
      source: { recordId: 'enrollment-1' },
    });
  });

  it('marks a missing source title when the picker falls back to the identifier', async () => {
    const resolve = vi.fn().mockResolvedValue({ options: [{ id: 'student-internal' }], total: 1 });
    const provider = createSourceReferencePickerProvider({
      sourceModuleAlias: 'education.enrollment',
      fieldName: 'studentId',
      reference,
      resolver: () => ({ resolve }),
      formValues: () => ({ classId: 'class-1' }),
      source: () => undefined,
    });

    await expect(
      provider.searchPage({ keyword: '', pageNum: 1, pageSize: 20, scope: { selections: [] } }),
    ).resolves.toEqual({
      records: [
        {
          id: 'student-internal',
          title: 'student-internal',
          identifierFallback: true,
          projections: undefined,
          affectPatch: undefined,
        },
      ],
      total: 1,
    });
  });

  it('invalidates only record scope and declared candidate dependencies', () => {
    const base = { id: 'enrollment-1', tenantId: 'tenant-1', classId: 'class-1', note: 'old' };
    const sameScope = { ...base, note: 'edited without changing candidates' };
    const changedDependency = { ...base, classId: 'class-2' };

    expect(sourceReferencePickerReloadKey('education.enrollment', 'studentId', reference, base)).toBe(
      sourceReferencePickerReloadKey('education.enrollment', 'studentId', reference, sameScope),
    );
    expect(sourceReferencePickerReloadKey('education.enrollment', 'studentId', reference, base)).not.toBe(
      sourceReferencePickerReloadKey('education.enrollment', 'studentId', reference, changedDependency),
    );
  });

  it('adapts only an authorized TREE response into the complete picker tree', async () => {
    const resolve = vi.fn().mockResolvedValue({
      tree: [
        {
          record: { id: 'student-root', title: '高一年级' },
          children: [
            {
              record: {
                id: 'student-1',
                title: '王华',
                projections: { studentNo: 'S1' },
                affectPatch: { studentNo: 'S1' },
              },
              children: [],
            },
          ],
        },
      ],
    });
    const provider = createSourceReferencePickerProvider({
      sourceModuleAlias: 'education.enrollment',
      fieldName: 'studentId',
      reference: { ...reference, pickerMode: 'TREE' },
      resolver: () => ({ resolve }),
      formValues: () => ({ classId: 'class-1' }),
      source: () => ({ recordId: 'enrollment-1' }),
    });

    await expect(provider.loadTree!({ scope: { selections: [] } })).resolves.toEqual([
      {
        record: { id: 'student-root', title: '高一年级', projections: undefined, affectPatch: undefined },
        children: [
          {
            record: {
              id: 'student-1',
              title: '王华',
              projections: { studentNo: 'S1' },
              affectPatch: { studentNo: 'S1' },
            },
          },
        ],
      },
    ]);
    expect(resolve).toHaveBeenCalledWith('studentId', {
      mode: 'TREE',
      formValues: { classId: 'class-1' },
      source: { recordId: 'enrollment-1' },
    });
  });

  it('does not reinterpret source delivery as an unrestricted navigation source', async () => {
    const provider = createSourceReferencePickerProvider({
      sourceModuleAlias: 'education.enrollment',
      fieldName: 'studentId',
      reference,
      resolver: () => ({ resolve: vi.fn() }),
      formValues: () => ({}),
      source: () => undefined,
    });

    await expect(
      provider.searchPage({
        keyword: '',
        pageNum: 1,
        pageSize: 20,
        scope: { selections: [{ axisId: 'organization', itemId: 'org-1' }] },
      }),
    ).rejects.toMatchObject({
      kind: 'unsupportedConfiguration',
      retryable: false,
      message: '当前引用来源不支持范围导航',
    } satisfies Partial<ReferencePickerReadError>);
  });

  it('reports declared missing required dependencies before QUERY or TRANSLATE can look like an empty set', async () => {
    const resolve = vi.fn();
    const provider = createSourceReferencePickerProvider({
      sourceModuleAlias: 'education.enrollment',
      fieldName: 'studentId',
      reference,
      resolver: () => ({ resolve }),
      formValues: () => ({ classId: '  ' }),
      source: () => undefined,
    });

    await expect(
      provider.searchPage({ keyword: '', pageNum: 1, pageSize: 20, scope: { selections: [] } }),
    ).rejects.toMatchObject({
      kind: 'missingDependencies',
      retryable: false,
      missingDependencyFields: ['classId'],
    } satisfies Partial<ReferencePickerReadError>);
    await expect(provider.resolve(['student-1'])).rejects.toMatchObject({
      kind: 'missingDependencies',
      retryable: false,
      missingDependencyFields: ['classId'],
    } satisfies Partial<ReferencePickerReadError>);
    expect(resolve).not.toHaveBeenCalled();
  });
});
