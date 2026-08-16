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

  it('does not expose server-resolved session values as browser query criteria', () => {
    expect(externalPageContextCriteriaKeys([...bindings], 'LIST_QUERY')).toEqual([]);
    expect(
      externalPageContextCriteriaKeys(
        [
          ...bindings,
          { source: 'NAVIGATOR', sourceKey: 'organization', target: 'LIST_QUERY', targetKey: 'organizationId' },
        ],
        'LIST_QUERY',
      ),
    ).toEqual(['organizationId']);
    expect(externalPageContextCriteriaKeys([...bindings], 'NAVIGATOR_QUERY')).toEqual(['tenantId']);
  });
});
