import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { expect, it } from 'vitest';

it('uses the public record-read subpath in native Node without UI dependencies', () => {
  const projectRoot = process.env.MUYUN_CONSUMER_PROJECT_ROOT;
  if (!projectRoot) throw new Error('Run this contract through npm run verify:consumer.');
  // Source tests cover session edge cases; this contract verifies native package loading and use.
  // Bound the child lifetime so a leaked handle fails instead of hanging consumer verification.
  const result = execFileSync(process.execPath, [join(projectRoot, 'record-read-contract.mjs')], {
    cwd: projectRoot,
    encoding: 'utf8',
    timeout: 10_000,
  });
  expect(JSON.parse(result)).toEqual({
    recordId: 'record-1',
    requestAborted: true,
    browserGlobalsAbsent: true,
    entryView: 'current',
  });
}, 15_000);
