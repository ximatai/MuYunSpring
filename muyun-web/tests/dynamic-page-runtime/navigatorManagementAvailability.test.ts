import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { navigatorItemOf } from '@/dynamic-page-runtime/pageNavigatorItemModel';
import type { ResolvedPageNavigatorLevelDescriptor } from '@muyun/web-contracts';

describe('navigator management availability', () => {
  it('keeps list-detail and tree navigation read-only when an unmanaged DSL level serializes management as null', () => {
    for (const template of ['LIST_DETAIL_CARD', 'TREE_MANAGEMENT'] as const) {
      const level = navigatorLevel(template, null);
      const item = navigatorItemOf(
        { id: 'source-1', title: '导航源' },
        undefined,
        level.management != null,
        () => [
          { key: 'edit', title: '编辑', iconName: 'edit' },
          { key: 'delete', title: '删除', iconName: 'delete' },
        ],
      );

      expect(item.actions, `${template} must not expose row management actions`).toEqual([]);
    }

    expect(modulePageHostSource()).toMatch(/function navigatorManagementAvailable[\s\S]*management != null/);
    expect(pageNavigatorExplorerSource()).toMatch(/descriptor\.management != null/);
  });

  it('keeps navigator management available when the DSL declares manageable()', () => {
    const level = navigatorLevel('LIST_DETAIL_CARD', {});
    const item = navigatorItemOf(
      { id: 'source-1', title: '导航源' },
      undefined,
      level.management != null,
      () => [
        { key: 'edit', title: '编辑', iconName: 'edit' },
        { key: 'delete', title: '删除', iconName: 'delete' },
      ],
    );

    expect(item.actions).toHaveLength(2);
  });
});

function modulePageHostSource() {
  return readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHost.vue'),
    'utf8',
  );
}

function pageNavigatorExplorerSource() {
  return readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/PageNavigatorExplorer.vue'),
    'utf8',
  );
}

function navigatorLevel(
  template: 'LIST_DETAIL_CARD' | 'TREE_MANAGEMENT',
  management: ResolvedPageNavigatorLevelDescriptor['management'],
): ResolvedPageNavigatorLevelDescriptor {
  return {
    key: `${template.toLowerCase()}-source`,
    kind: template === 'TREE_MANAGEMENT' ? 'TREE' : 'MICRO_LIST',
    sourceModuleAlias: 'demo.source',
    title: '导航源',
    searchPlaceholder: '搜索导航源',
    management,
  };
}
