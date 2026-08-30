import { assert, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');

it('keeps management quick search as a constrained template component', () => {
  const workspaceSource = readSource('src/views/PageCompositionWorkspace.vue');
  const treeSource = readSource('src/views/PageCompositionTree.vue');
  const draftStateSource = readSource('src/views/pageCompositionDraftState.ts');

  assert.match(treeSource, /ui:template:list:quick-search/);
  assert.match(treeSource, /快速查询/);
  assert.match(treeSource, /可配置占位提示/);
  assert.match(workspaceSource, /selectedQuickSearch\.value \? '配置：快速查询占位提示'/);
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

it('keeps metadata quick addition explicit and keyboard-reachable', () => {
  const workspaceSource = readSource('src/views/PageCompositionWorkspace.vue');

  assert.match(workspaceSource, /aria-label="字段快速添加目标"/);
  assert.match(workspaceSource, /双击添加至/);
  assert.match(workspaceSource, /@click="selectQuickAddTarget\('list'\)"/);
  assert.match(workspaceSource, /@click="selectQuickAddTarget\('form'\)"/);
  assert.match(workspaceSource, /@click="addSelectedMetadataField\('list'\)"/);
  assert.match(workspaceSource, /@click="addSelectedMetadataField\('form'\)"/);
  assert.match(workspaceSource, /function addSelectedMetadataField\(slot: PageComposerSlot\)/);
  assert.match(workspaceSource, /当前双击目标为：\{\{ quickAddTargetLabel \}\}/);
});

it('keeps the last successful descriptor visibly stale and retries the current draft safely', () => {
  const workspaceSource = readSource('src/views/PageCompositionWorkspace.vue');

  assert.match(workspaceSource, /当前展示的是上一次成功解析结果，不代表当前草稿。/);
  assert.match(workspaceSource, /@click="retryPreviewDescriptor"/);
  assert.match(
    workspaceSource,
    /function retryPreviewDescriptor\(\) \{[\s\S]*?previewLoading\.value \|\| !variant\.value\?\.id \|\| !revision\.value\?\.id[\s\S]*?schedulePreviewDescriptor\(\);/,
  );
  assert.match(
    workspaceSource,
    /const uiTreeJson = currentUiTreeJson\.value;[\s\S]*?requestPreviewDescriptor\(requestSequence, variantId, revisionId, uiTreeJson\)/,
  );
});

function readSource(path: string) {
  return readFileSync(resolve(root, path), 'utf8');
}
