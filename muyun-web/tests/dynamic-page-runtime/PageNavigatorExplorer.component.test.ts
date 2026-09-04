import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { it } from 'vitest';

it('keeps navigator projections read-only for ordering', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/PageNavigatorExplorer.vue'),
    'utf8',
  );

  assert.doesNotMatch(source, /const sorting = ref\(false\)/);
  assert.doesNotMatch(source, /icon-name="swap-vertical"/);
  assert.match(source, /<CrudRecordListExplorer[\s\S]*?:sorting="false"/);
  assert.match(source, /const managementAvailable = computed\([\s\S]*descriptor\.management != null/);
  assert.match(source, /v-if="managementAvailable" #actions/);
  assert.match(source, /:actions-of="managementAvailable \? actionsOf : undefined"/);
  assert.doesNotMatch(source, /sortPartitionOf/);
});
