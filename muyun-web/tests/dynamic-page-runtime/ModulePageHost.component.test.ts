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
