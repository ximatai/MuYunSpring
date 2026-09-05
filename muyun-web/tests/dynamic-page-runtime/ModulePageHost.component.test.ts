import { assert, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

it('is the implementation owner for neutral module page descriptors', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHost.vue'),
    'utf8',
  );

  assert.match(source, /defineOptions\(\{ name: 'ModulePageHost' \}\)/);
  assert.match(source, /descriptor: StandardModulePageDescriptor/);
});

it('routes every standard card shell through the shared content and form surfaces', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHost.vue'),
    'utf8',
  );

  assert.match(
    source,
    /if \(!mainFormValid\.value \|\| !relationDraftValid\.value\) \{[\s\S]*formValidationRequestKey\.value \+= 1;[\s\S]*return;/,
  );
  assert.match(source, /ModulePageRecordContent/g);
  assert.notMatch(source, /StandardFlatFormSurface/);
  assert.notMatch(source, /ModulePageFormContributionRenderer/);
  assert.match(source, /function flatManagementAllowsDetailEnhancement[\s\S]*editorMode\.value === 'view'/);
  assert.match(source, /flatManagementDetailActions[\s\S]*\.\.\.flatManagementEnhancementActions\.value/);
  assert.match(source, /function handleFlatManagementAction[\s\S]*runEnhancementAction/);
  assert.match(source, /@validity-change="updateMainFormValidity"/);
  assert.match(source, /navigatorManagementPageEnhancement[\s\S]*level\.context\.moduleAlias/);
  assert.match(source, /:contributions="navigatorManagementFormContributions"/);
  assert.match(source, /:field-policies="navigatorManagementFormFieldPolicies"/);
  assert.match(source, /@validity-change="navigatorManagementFormValid = \$event\.valid"/);
  assert.match(source, /:file-transfer-context="context"/);
  assert.match(source, /localEditValid: localEditFormValid/);
  assert.match(source, /@validity-change="updateLocalEditFormValidity"/);
});

it('uses the declared tree resource or page title as the tree panel title without synthesizing a suffix', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHost.vue'),
    'utf8',
  );

  assert.match(
    source,
    /const treePanelTitle = computed\([\s\S]*treeResource\?\.title \?\? modulePageTitle\.value/,
  );
  assert.notMatch(source, /\$\{modulePageTitle\.value\}树/);
  assert.match(
    source,
    /<RecordExplorerPanel[\s\S]*?:title="treePanelTitle"[\s\S]*?:refresh-title="`刷新\$\{treePanelTitle\}`"/,
  );
});

it('places tree sorting between the explorer search affordance and create action', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHost.vue'),
    'utf8',
  );

  assert.match(source, /const mainTreeSorting = ref\(false\)/);
  assert.match(
    source,
    /<template #actions>[\s\S]*?<RecordPanelButton[\s\S]*?icon-name="swap-vertical"[\s\S]*?<ModuleActionButton/,
  );
  assert.match(source, /<TreeRecordExplorer[\s\S]*?:sorting="mainTreeSorting"/);
  assert.match(
    source,
    /<TreeRecordExplorer[\s\S]*?:sort-partition-fields="runtimePage\?\.treeResource\?\.sortPartitionFields"/,
  );
  assert.match(source, /mainTreeScopeReady && context\.can\('sort'\) === true/);
  assert.match(source, /treeSearchKeyword\.trim\(\)[\s\S]*清空搜索后可调整排序/);
});

it('exposes flat-list ordering only when the runtime declares sort capability', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHost.vue'),
    'utf8',
  );

  assert.match(source, /const flatManagementSorting = ref\(false\)/);
  assert.match(
    source,
    /#explorer-actions>[\s\S]*?context\.can\('sort'\) === true[\s\S]*?icon-name="swap-vertical"/,
  );
  assert.match(source, /<CrudRecordListExplorer[\s\S]*?:sorting="flatManagementSorting"/);
});

it('exposes sortable navigator lists through the navigator module capability', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHost.vue'),
    'utf8',
  );

  assert.match(source, /import NavigatorPanelActions from '\.\/NavigatorPanelActions\.vue'/);
  assert.match(source, /<NavigatorPanelActions[\s\S]*:sort="navigatorSortState\(level\)"/);
  assert.match(source, /<CrudRecordListExplorer[\s\S]*?:sorting="navigatorSorting\(level\)"/);
  assert.match(source, /:sorting="navigatorSorting\(navigatorLevelAt\(index\)!\)"/);
  assert.match(source, /navigatorLevelAt\(index\)!\.sort\.visible/);
});

it('declares cancellation destinations from the detail entry context', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHost.vue'),
    'utf8',
  );

  assert.match(source, /cancelDestination: persistentTreeDetail\.value \? 'restore-view' : 'close'/);
  assert.match(source, /function editRecord\([\s\S]*cancelDestination: 'close' \| 'restore-view' = 'close'/);
  assert.match(source, /@edit="selectedRecord && editRecord\(selectedRecord, 'restore-view'\)"/);
  assert.match(
    source,
    /function cancelDetailEditing\(\)[\s\S]*detail\.cancelEdit\(\);\s*if \(!detailOpen\.value\) return;/,
  );
});
