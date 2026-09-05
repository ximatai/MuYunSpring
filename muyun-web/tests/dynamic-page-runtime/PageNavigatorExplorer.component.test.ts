import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { it } from 'vitest';

it('follows the navigator runtime ordering capability', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/PageNavigatorExplorer.vue'),
    'utf8',
  );

  assert.doesNotMatch(source, /const sorting = ref\(false\)/);
  assert.match(source, /import NavigatorPanelActions from '\.\/NavigatorPanelActions\.vue'/);
  assert.match(source, /<NavigatorPanelActions[\s\S]*:sort="sort"/);
  assert.match(source, /<CrudRecordListExplorer[\s\S]*?:sorting="sort\.active"/);
  assert.match(source, /<TreeRecordExplorer[\s\S]*?:sorting="sort\.active"/);
  assert.match(source, /<TreeRecordExplorer[\s\S]*?:can-drop-inside="treeParentPolicy\?\.canUseAsParent"/);
  assert.match(source, /const managementAvailable = computed\([\s\S]*descriptor\.management != null/);
  assert.match(source, /v-if="managementAvailable \|\| sort\.visible" #actions/);
  assert.match(source, /'toggle-sorting': \[\]/);
  assert.match(source, /:actions-of="managementAvailable \? actionsOf : undefined"/);
  assert.doesNotMatch(source, /sortPartitionOf/);
});
