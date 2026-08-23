import { assert, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

it('keeps a single relation on the compact surface and lazy-loads multiple relations by tab', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageListRelationExpansions.vue'),
    'utf8',
  );

  assert.match(source, /v-if="entries\.length === 1"/);
  assert.match(source, /<RecordRelationTabs/);
  assert.match(source, /props\.entries\.filter\(\(entry\) => loadedRelationCodes\.value\.has/);
  assert.match(source, /@update:active-key="activate"/);
});
