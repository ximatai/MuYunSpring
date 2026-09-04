import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { it } from 'vitest';

it('provides managed navigator lists with the standard drag-order action', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/PageNavigatorExplorer.vue'),
    'utf8',
  );

  assert.match(source, /const sorting = ref\(false\)/);
  assert.match(
    source,
    /<template v-if="sortingAvailable" #utility-actions>[\s\S]*?icon-name="swap-vertical"/,
  );
  assert.match(source, /<CrudRecordListExplorer[\s\S]*?:sorting="sorting"/);
  assert.match(source, /!props\.level\.sortingDisabled[\s\S]*props\.level\.context\.can\('sort'\) === true/);
  assert.match(source, /const managementAvailable = computed\([\s\S]*descriptor\.management != null/);
  assert.match(source, /v-if="managementAvailable" #actions/);
  assert.match(source, /:actions-of="managementAvailable \? actionsOf : undefined"/);
  assert.doesNotMatch(source, /sortPartitionOf/);
});
