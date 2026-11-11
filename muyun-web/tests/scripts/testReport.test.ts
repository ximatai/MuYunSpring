import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, it, vi } from 'vitest';
// @ts-expect-error Native verification scripts do not publish TypeScript declarations.
import * as reporting from '../../scripts/test-report.mjs';
// @ts-expect-error Native verification scripts do not publish TypeScript declarations.
import * as verified from '../../scripts/run-verified.mjs';

const { createTestReport, printTestReport, testReportSummary } = reporting;
const roots: string[] = [];
afterEach(() => {
  vi.restoreAllMocks();
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true });
});
function root() {
  const path = mkdtempSync(join(tmpdir(), 'muyun-report-test-'));
  roots.push(path);
  return path;
}
function failedFile(directory: string, name: string, title?: string) {
  return {
    name: join(directory, name),
    status: 'failed',
    startTime: 1000,
    endTime: 1200,
    assertionResults: title ? [{ status: 'failed', title }] : [],
  };
}

it('uses a new report destination for every invocation without reusing old results', () => {
  const directory = root();
  const first = createTestReport(directory);
  const second = createTestReport(directory);
  expect(first).not.toBe(second);
  expect(existsSync(first)).toBe(false);
  expect(existsSync(second)).toBe(false);
});

it('ranks only finite test execution windows and does not suggest retries on success', () => {
  const directory = root();
  const summary: string[] = testReportSummary(
    {
      success: true,
      testResults: [100, 900, 300].map((duration) => ({
        name: join(directory, `tests/${duration}.test.ts`),
        status: 'passed',
        startTime: 1000,
        endTime: 1000 + duration,
      })),
    },
    directory,
  );
  expect(summary.slice(1)).toEqual([
    '  0.90s tests/900.test.ts',
    '  0.30s tests/300.test.ts',
    '  0.10s tests/100.test.ts',
  ]);
  expect(summary.join('\n')).not.toContain('rerun');
});

it.each([
  ['tests/model.test.ts', 'unit'],
  ['tests/model.component.test.ts', 'component'],
  ['tests/model.browser.test.ts', 'browser'],
])('selects the native %s project and escapes literal test names', (path, project) => {
  const directory = root();
  const summary = testReportSummary(
    {
      success: false,
      testResults: [failedFile(directory, path, "keeps user's ($value)")],
    },
    directory,
    'win32',
  ).join('\n');
  expect(summary).toContain(`npm run test:${project} -- '${path}'`);
  expect(summary).toContain("-t 'keeps user''s \\(\\$value\\)'");
});

it('suggests the file after collection errors and keeps packaged setup in the consumer command', () => {
  const directory = root();
  const summary = testReportSummary(
    {
      success: false,
      testResults: [
        failedFile(directory, 'tests/collection.test.ts'),
        failedFile(directory, 'tests/consumer/packaged/runtime.test.ts', 'reads package'),
        failedFile(directory, 'tests/consumer/packaged/runtime.component.test.ts', 'mounts package'),
      ],
    },
    directory,
    'win32',
  );
  expect(summary.filter((line: string) => line.includes('npm run'))).toEqual([
    "  npm run test:unit -- 'tests/collection.test.ts'",
    '  npm run verify:consumer',
  ]);
});

it('quotes embedded apostrophes for POSIX shells used in CI', () => {
  const directory = root();
  const summary = testReportSummary(
    {
      success: false,
      testResults: [failedFile(directory, 'tests/model.test.ts', "user's value")],
    },
    directory,
    'linux',
  ).join('\n');
  expect(summary).toContain("-t 'user'\\''s value'");
});

it('reports empty discovery without suggesting passWithNoTests or an automatic retry', () => {
  const summary = testReportSummary({ success: false, testResults: [] }, root()).join('\n');
  expect(summary).toContain('unmatched path filters');
  expect(summary).not.toContain('passWithNoTests');
  expect(summary).not.toContain('npm run');
});

it('reports a missing native report without throwing a replacement diagnostics error', () => {
  vi.spyOn(console, 'log').mockImplementation(() => {});
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  expect(() => printTestReport(join(root(), 'missing.json'), root())).not.toThrow();
  expect(warn).toHaveBeenCalledWith(expect.stringContaining('ENOENT'));
});

it.each([false, true])(
  'runs tests despite an unavailable report directory (runner fails: %s)',
  async (fails) => {
    const directory = root();
    mkdirSync(join(directory, 'test-results'));
    writeFileSync(join(directory, 'test-results/verification'), 'occupied by a file');
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const error = new Error('original test failure');
    const run = vi.fn(async () => {
      if (fails) throw error;
    });
    const result = verified.runVerified(
      'unit-tests',
      'node_modules/vitest/vitest.mjs',
      ['run', '--project', 'unit'],
      {
        root: directory,
        run,
      },
    );
    if (fails) await expect(result).rejects.toBe(error);
    else await expect(result).resolves.toBeUndefined();
    expect(run).toHaveBeenCalledWith(
      'unit-tests',
      process.execPath,
      ['node_modules/vitest/vitest.mjs', 'run', '--project', 'unit'],
      { env: expect.objectContaining({ MUYUN_TEST_REPORT_DISABLED: '1' }) },
    );
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('using default reporter'));
  },
);
