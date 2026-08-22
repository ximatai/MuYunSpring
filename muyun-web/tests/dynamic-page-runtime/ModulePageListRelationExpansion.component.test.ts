import { assert, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

it('loads a declared list expansion through its narrow aggregate-relation endpoint', () => {
  const source = readFileSync(
    resolve(import.meta.dirname, '../../src/dynamic-page-runtime/ModulePageListRelationExpansion.vue'),
    'utf8',
  );

  assert.match(source, /relations\/\$\{encodeURIComponent\(props\.relation\.code\)\}\/expansion/);
  assert.match(source, /WebListResponse<QueryListRecord>/);
  assert.notMatch(source, /sourceContext\.crud\.view\(id\)/);
});
