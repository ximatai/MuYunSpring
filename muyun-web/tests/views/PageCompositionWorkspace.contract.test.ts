import { assert, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');

it('keeps management quick search as a constrained template component', () => {
  const workspaceSource = readSource('src/views/PageCompositionWorkspace.vue');
  const treeSource = readSource('src/views/PageCompositionTree.vue');
  const draftStateSource = readSource('src/views/pageCompositionDraftState.ts');

  assert.match(treeSource, /ui:template:list:quick-search/);
  assert.match(treeSource, /<UiTree/);
  assert.ok(!/VueDraggable|SortableEvent|vue-draggable-plus/.test(treeSource));
  assert.match(treeSource, /快速查询/);
  assert.match(treeSource, /双击编辑占位提示/);
  assert.match(workspaceSource, /selectedQuickSearch\.value\s*\? '配置：快速查询占位提示'/);
  assert.match(
    workspaceSource,
    /if \(key === 'ui:template:list:quick-search'\) return \{ kind: 'template' \}/,
  );
  assert.match(draftStateSource, /quickSearchPlaceholder/);
  assert.match(draftStateSource, /list: \{ searchPlaceholder: quickSearchPlaceholder\.value \}/);
  assert.match(
    workspaceSource,
    /state\.updateQuickSearchPlaceholder\([\s\S]*tree\.props\?\.list\?\.searchPlaceholder/,
  );
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

it('keeps dictionary presentation separate from record-picker configuration and derives aliases by cardinality', () => {
  const workspaceSource = readSource('src/views/PageCompositionWorkspace.vue');
  const draftStateSource = readSource('src/views/pageCompositionDraftState.ts');

  assert.match(workspaceSource, /selectedDirectDictionaryFormField/);
  assert.match(workspaceSource, /field\.optionSourceType !== 'dictionary'/);
  assert.match(workspaceSource, /dictionary_multi_dropdown/);
  assert.match(workspaceSource, /dictionary_multi_dialog/);
  assert.match(workspaceSource, /value === 'RADIO' && field\.optionSelectionMode === 'SINGLE'/);
  assert.match(workspaceSource, /字典展示形式/);
  assert.notMatch(workspaceSource, /dictionary_[\w_]+[\s\S]{0,60}record_picker/);
  assert.match(draftStateSource, /optionSourceType\?: string/);
  assert.match(draftStateSource, /optionSelectionMode\?: 'SINGLE' \| 'MULTIPLE'/);
});

it('preflights dictionary radio eligibility in composition instead of deferring hierarchy errors to the business form', () => {
  const workspaceSource = readSource('src/views/PageCompositionWorkspace.vue');

  assert.match(
    workspaceSource,
    /loadOptionFieldItems\(moduleContext, fieldName, undefined, props\.moduleAlias, true\)/,
  );
  assert.match(workspaceSource, /hasOptionHierarchy\(items\)/);
  assert.match(workspaceSource, /!path[\s\S]{0,120}dictionaryRadioMaxOptions/);
  assert.match(workspaceSource, /dictionaryRadioEligibilityIssue\(/);
  assert.match(workspaceSource, /dictionaryRadioIssues/);
  assert.match(workspaceSource, /dictionaryRadioIssues\.length > 0/);
  assert.match(workspaceSource, /dictionaryRadioFactRequestEpoch\.invalidate\(\);/);
  assert.match(
    workspaceSource,
    /async function loadMetadataTree[\s\S]*?referenceDirectoryEpoch \+= 1;[\s\S]*?invalidateDictionaryRadioFacts\(\);[\s\S]*?await Promise\.all/,
  );
  assert.match(
    workspaceSource,
    /function invalidateDictionaryRadioFacts\(\) \{[\s\S]*?dictionaryRadioFacts\.value = \{};[\s\S]*?dictionaryRadioFactErrors\.value = \{};[\s\S]*?dictionaryRadioFactLoading\.value = new Set\(\);/,
  );
  assert.match(
    workspaceSource,
    /const requestGeneration = dictionaryRadioFactRequestEpoch\.capture\(\);[\s\S]*?isCurrent\(requestGeneration\)/,
  );
  assert.match(
    workspaceSource,
    /if \(dictionaryRadioIssues\.value\.length\) \{\s*previewError\.value = undefined;/,
  );
});

function readSource(path: string) {
  return readFileSync(resolve(root, path), 'utf8');
}
