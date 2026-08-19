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
