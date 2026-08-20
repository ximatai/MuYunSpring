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

it('delegates the flat standard editor to its isolated contribution surface', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageHost.vue'),
    'utf8',
  );

  assert.match(source, /if \(!mainFormValid\.value\) return;/);
  assert.match(source, /StandardFlatFormSurface/);
  assert.notMatch(source, /ModulePageFormContributionRenderer/);
  assert.match(source, /function flatManagementAllowsDetailEnhancement[\s\S]*editorMode\.value === 'view'/);
  assert.match(source, /flatManagementDetailActions[\s\S]*\.\.\.flatManagementEnhancementActions\.value/);
  assert.match(source, /function handleFlatManagementAction[\s\S]*runEnhancementAction/);
  assert.match(source, /@validity-change="updateMainFormValidity"/);
  assert.match(source, /localEditValid: localEditFormValid/);
  assert.match(source, /@validity-change="updateLocalEditFormValidity"/);
});
