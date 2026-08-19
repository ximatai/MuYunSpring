import { describe, expect, it } from 'vitest';
import {
  externalPageContextCriteriaKeys,
  resolvePageContextTargetValues,
} from '@/dynamic-page-runtime/pageContextRuntime';

describe('resolvePageContextTargetValues', () => {
  const bindings = [
    { source: 'SESSION', sourceKey: 'tenantId', target: 'LIST_QUERY', targetKey: 'tenantId' },
    {
      source: 'NAVIGATOR',
      sourceKey: 'tenant',
      target: 'NAVIGATOR_QUERY',
      targetKey: 'tenantId',
      targetNavigatorLevelKey: 'category',
    },
    { source: 'NAVIGATOR', sourceKey: 'category', target: 'FORM_DEFAULT', targetKey: 'categoryId' },
  ] as const;

  it('combines values from every declared source for a target', () => {
    expect(
      resolvePageContextTargetValues([...bindings], 'LIST_QUERY', {
        SESSION: { tenantId: 'xcmg' },
        NAVIGATOR: { tenant: 'xcmg', category: 'category-1' },
      }),
    ).toEqual({ tenantId: 'xcmg' });
  });

  it('keeps navigator query flows scoped to the declared downstream level', () => {
    expect(
      resolvePageContextTargetValues(
        [...bindings],
        'NAVIGATOR_QUERY',
        {
          NAVIGATOR: { tenant: 'xcmg' },
        },
        'category',
      ),
    ).toEqual({ tenantId: 'xcmg' });
    expect(
      resolvePageContextTargetValues(
        [...bindings],
        'NAVIGATOR_QUERY',
        {
          NAVIGATOR: { tenant: 'xcmg' },
        },
        'other',
      ),
    ).toBeUndefined();
  });

  it('uses an application navigator as module-tree scope and root-form default without making it a tree node', () => {
    const moduleTreeBindings = [
      {
        source: 'NAVIGATOR',
        sourceKey: 'application',
        target: 'NAVIGATOR_QUERY',
        targetKey: 'applicationAlias',
        targetNavigatorLevelKey: 'module',
      },
      {
        source: 'NAVIGATOR',
        sourceKey: 'application',
        target: 'FORM_DEFAULT',
        targetKey: 'applicationAlias',
      },
      {
        source: 'NAVIGATOR',
        sourceKey: 'application',
        target: 'PICKER_QUERY',
        targetKey: 'applicationAlias',
        targetPickerFieldKey: 'parentId',
      },
    ] as const;

    expect(
      resolvePageContextTargetValues(
        moduleTreeBindings,
        'NAVIGATOR_QUERY',
        {
          NAVIGATOR: { application: 'platform' },
        },
        'module',
      ),
    ).toEqual({ applicationAlias: 'platform' });
    expect(
      resolvePageContextTargetValues(moduleTreeBindings, 'FORM_DEFAULT', {
        NAVIGATOR: { application: 'platform' },
      }),
    ).toEqual({ applicationAlias: 'platform' });
    expect(
      resolvePageContextTargetValues(moduleTreeBindings, 'PICKER_QUERY', {
        NAVIGATOR: { application: 'platform' },
      }),
    ).toEqual({ applicationAlias: 'platform' });
  });

  it('does not expose server-resolved session values as browser query criteria', () => {
    expect(externalPageContextCriteriaKeys([...bindings], 'LIST_QUERY')).toEqual([]);
    expect(
      externalPageContextCriteriaKeys(
        [
          ...bindings,
          {
            source: 'NAVIGATOR',
            sourceKey: 'organization',
            target: 'LIST_QUERY',
            targetKey: 'organizationId',
          },
        ],
        'LIST_QUERY',
      ),
    ).toEqual(['organizationId']);
    expect(externalPageContextCriteriaKeys([...bindings], 'NAVIGATOR_QUERY')).toEqual(['tenantId']);
  });

  it('keeps required navigator-query criteria isolated to their target level', () => {
    const multiLevelBindings = [
      {
        source: 'NAVIGATOR',
        sourceKey: 'tenant',
        target: 'NAVIGATOR_QUERY',
        targetKey: 'tenantId',
        targetNavigatorLevelKey: 'organization',
      },
      {
        source: 'NAVIGATOR',
        sourceKey: 'organization',
        target: 'NAVIGATOR_QUERY',
        targetKey: 'organizationId',
        targetNavigatorLevelKey: 'department',
      },
    ] as const;

    expect(externalPageContextCriteriaKeys(multiLevelBindings, 'NAVIGATOR_QUERY', 'organization')).toEqual([
      'tenantId',
    ]);
    expect(externalPageContextCriteriaKeys(multiLevelBindings, 'NAVIGATOR_QUERY', 'department')).toEqual([
      'organizationId',
    ]);
  });
});
