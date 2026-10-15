import { expect, it } from 'vitest';
import { navigatorItemOf } from '@/dynamic-page-runtime/pageNavigatorItemModel';

it('hides source-record actions from a read-only navigator', () => {
  const item = navigatorItemOf({ id: 'platform', title: '平台能力' }, undefined, false, () => [
    { key: 'edit', title: '编辑', iconName: 'edit' },
  ]);

  expect(item.actions).toEqual([]);
});

it('keeps page-declared actions for a manageable navigator', () => {
  const item = navigatorItemOf({ id: 'category-1', title: '字典类目' }, undefined, true, () => [
    { key: 'edit', title: '编辑', iconName: 'edit' },
  ]);

  expect(item.actions).toEqual([{ key: 'edit', title: '编辑', iconName: 'edit' }]);
});

it('uses the navigator-declared secondary field instead of a technical alias', () => {
  const item = navigatorItemOf(
    { id: 'tenant-scheme', title: '租户管理', alias: 'tenant_admin', scopeId: 'demo' } as {
      id: string;
      title: string;
      alias: string;
      scopeId: string;
    },
    'scopeId',
    false,
    undefined,
  );

  expect(item.secondary).toBe('demo');
});

it('uses the same presentation resource for secondary navigator labels', async () => {
  const { createLanguageContext } = await import('@/web-core');
  const language = createLanguageContext({ languageTag: 'en' });
  const record = {
    id: 'scheme',
    title: 'Custom',
    scope: '租户',
    $texts: {
      scope: {
        source: { sourceKind: 'CODE', sourceId: 'scope.tenant', sourceVersion: 'v1', originalText: '租户' },
        languages: {
          en: { text: 'Tenant', requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false },
        },
      },
    },
  };
  expect(navigatorItemOf(record, 'scope', false, undefined, language).secondary).toBe('Tenant');
  language.setLanguage('zh-CN');
  expect(navigatorItemOf(record, 'scope', false, undefined, language).secondary).toBe('租户');
});
