import { assert, it } from 'vitest';
import { normalizeWorkbenchBase } from '../vite.config.ts';

it('normalizes the configurable workbench mount point and defaults to /app/', () => {
  assert.equal(normalizeWorkbenchBase(undefined), '/app/');
  assert.equal(normalizeWorkbenchBase('console'), '/console/');
  assert.equal(normalizeWorkbenchBase('/tenant-a/workbench/'), '/tenant-a/workbench/');
  assert.equal(normalizeWorkbenchBase('/'), '/');
});
