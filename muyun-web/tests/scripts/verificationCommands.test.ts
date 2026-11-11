import { afterEach, beforeEach, expect, it, vi } from 'vitest';
// @ts-expect-error Native verification scripts do not publish TypeScript declarations.
import * as verification from '../../scripts/check.mjs';

const { runCheck } = verification;
beforeEach(() => vi.spyOn(console, 'log').mockImplementation(() => {}));
afterEach(() => vi.restoreAllMocks());

it('stops after the failed stage, preserves the failure and identifies unexecuted stages', async () => {
  const error = new Error('version test failed');
  const runNpm = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(error);
  const stderr = vi.spyOn(console, 'error').mockImplementation(() => {});
  await expect(runCheck(runNpm)).rejects.toBe(error);
  expect(runNpm.mock.calls).toEqual([
    ['version:check', ['run', 'version:check']],
    ['version:test', ['run', 'version:test']],
  ]);
  expect(stderr).toHaveBeenCalledWith(expect.stringContaining('stopped at version:test'));
  expect(stderr).toHaveBeenCalledWith(
    '[verify] remaining stages: npm run lint, npm run format:check, npm run test, npm run build',
  );
});

it('retains every complete-verification stage and reports success only after build', async () => {
  const runNpm = vi.fn().mockResolvedValue(undefined);
  await runCheck(runNpm);
  expect(runNpm.mock.calls.map(([name]) => name)).toEqual([
    'version:check',
    'version:test',
    'lint',
    'format:check',
    'test',
    'build',
  ]);
  expect(console.log).toHaveBeenCalledWith(expect.stringContaining('complete verification: passed'));
});
