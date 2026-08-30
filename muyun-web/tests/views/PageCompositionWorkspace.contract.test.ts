import { assert, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');

it('keeps management quick search as a constrained template component', () => {
  const workspaceSource = readSource('src/views/PageCompositionWorkspace.vue');
  const draftStateSource = readSource('src/views/pageCompositionDraftState.ts');

  assert.match(workspaceSource, /key: 'ui:template:list:quick-search'/);
  assert.match(workspaceSource, /title: '快速查询'/);
  assert.match(
    workspaceSource,
    /secondary: state\.quickSearchPlaceholder\.value \? '模板内置 · 已配置' : '模板内置 · 可配置'/,
  );
  assert.match(workspaceSource, /target\.kind === 'root' \|\| target\.kind === 'template'/);
  assert.match(
    workspaceSource,
    /if \(key === 'ui:template:list:quick-search'\) return \{ kind: 'template' \}/,
  );
  assert.match(draftStateSource, /quickSearchPlaceholder/);
  assert.match(draftStateSource, /list: \{ searchPlaceholder: quickSearchPlaceholder\.value \}/);
  assert.match(workspaceSource, /changes\.push\('修改快速查询占位提示'\)/);
  assert.match(
    workspaceSource,
    /state\.updateQuickSearchPlaceholder\([\s\S]*tree\.props\?\.list\?\.searchPlaceholder/,
  );
  assert.match(
    workspaceSource,
    /if \(!confirmed \|\| isMutating\.value\) return;[\s\S]*hydrateDraft\(revision\.value\)/,
  );
});

function readSource(path: string) {
  return readFileSync(resolve(root, path), 'utf8');
}
