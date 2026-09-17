// Source-only composition contracts; runtime tenant and entry behavior is covered by
// DynamicModuleHost, ModulePageHostLifecycle, and ModulePageTenantScope component tests.
import { assert, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function readRuntimeSource() {
  return readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHostRuntime.vue'),
    'utf8',
  );
}

function readSessionSource() {
  return readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/useModulePageSession.ts'),
    'utf8',
  );
}

it('routes every standard card shell through the shared content and form surfaces', () => {
  const source = `${readRuntimeSource()}\n${readSessionSource()}`;

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

it('places tree sorting between the explorer search affordance and create action', () => {
  const source = `${readRuntimeSource()}\n${readSessionSource()}`;

  assert.match(source, /const mainTreeSorting = ref\(false\)/);
  assert.match(
    source,
    /<template #actions>[\s\S]*?<RecordPanelButton[\s\S]*?icon-name="swap-vertical"[\s\S]*?<ModuleActionButton/,
  );
  assert.match(source, /<TreeRecordExplorer[\s\S]*?:sorting="mainTreeSorting"/);
  assert.match(source, /mainTreeScopeReady && context\.can\('sort'\) === true/);
  assert.match(source, /treeSearchKeyword\.trim\(\)[\s\S]*清空搜索后可调整排序/);
});

it('exposes flat-list ordering only when the runtime declares sort capability', () => {
  const source = `${readRuntimeSource()}\n${readSessionSource()}`;

  assert.match(source, /const flatManagementSorting = ref\(false\)/);
  assert.match(
    source,
    /#explorer-actions>[\s\S]*?context\.can\('sort'\) === true[\s\S]*?icon-name="swap-vertical"/,
  );
  assert.match(source, /<CrudRecordListExplorer[\s\S]*?:sorting="flatManagementSorting"/);
});

it('exposes sortable navigator lists through the navigator module capability', () => {
  const source = `${readRuntimeSource()}\n${readSessionSource()}`;

  assert.match(source, /<PageNavigatorExplorer[\s\S]*:sort="navigatorSortState\(level\)"/);
  assert.match(
    source,
    /<PageNavigatorExplorer[\s\S]*:sort="navigatorSortState\(navigatorLevelAt\(index - tenantScopeExplorerCount\)!\)"/,
  );
});

it('declares cancellation destinations from the detail entry context', () => {
  const source = `${readRuntimeSource()}\n${readSessionSource()}`;

  assert.match(source, /cancelDestination: persistentTreeDetail\.value \? 'restore-view' : 'close'/);
  assert.match(source, /function editRecord\([\s\S]*cancelDestination: 'close' \| 'restore-view' = 'close'/);
  assert.match(source, /@edit="selectedRecord && editRecord\(selectedRecord, 'restore-view'\)"/);
  assert.match(
    source,
    /function cancelDetailEditing\(\)[\s\S]*detail\.cancelEdit\(\);\s*if \(!detailOpen\.value\) return;/,
  );
});

// Static DSL allows query scope independently of explorer display bindings.
it('installs explicit tree query scope even without explorer display bindings', () => {
  const source = readRuntimeSource();
  assert.match(
    source,
    /<TreeRecordExplorer[\s\S]*?:filter-option="\s*runtimePage\?\.explorer \|\| runtimePage\?\.quickSearchFields != null\s+\? matchesPageQuickSearch\s+: undefined/,
  );
});
