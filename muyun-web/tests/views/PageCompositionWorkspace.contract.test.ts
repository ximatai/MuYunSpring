import { assert, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');

it('keeps management quick search as a fixed template node outside the persisted UI tree', () => {
  const workspaceSource = readSource('src/views/PageCompositionWorkspace.vue');
  const draftStateSource = readSource('src/views/pageCompositionDraftState.ts');

  assert.match(workspaceSource, /key: 'ui:template:list:quick-search'/);
  assert.match(workspaceSource, /title: '快速查询'/);
  assert.match(workspaceSource, /secondary: '模板内置 · 只读'/);
  assert.match(workspaceSource, /disabled: true/);
  assert.match(workspaceSource, /target\.kind === 'root' \|\| target\.kind === 'template'/);
  assert.match(
    workspaceSource,
    /if \(key === 'ui:template:list:quick-search'\) return \{ kind: 'template' \}/,
  );
  assert.notMatch(draftStateSource, /quick-search/);
});

function readSource(path: string) {
  return readFileSync(resolve(root, path), 'utf8');
}
