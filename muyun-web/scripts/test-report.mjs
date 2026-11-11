import { mkdirSync, mkdtempSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';

export function createTestReport(root) {
  const directory = join(root, 'test-results', 'verification');
  mkdirSync(directory, { recursive: true });
  return join(mkdtempSync(join(directory, 'run-')), 'vitest.json');
}

function quote(value, platform) {
  return `'${value.replaceAll("'", platform === 'win32' ? "''" : "'\\''")}'`;
}

function retryCommand(file, title, platform) {
  // Packaged contracts need their installed consumer and generated bundle.
  if (file.startsWith('tests/consumer/packaged/')) return 'npm run verify:consumer';
  const project = file.endsWith('.browser.test.ts')
    ? 'browser'
    : file.endsWith('.component.test.ts')
      ? 'component'
      : 'unit';
  let command = `npm run test:${project} -- ${quote(file, platform)}`;
  if (title) {
    const pattern = title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    command += ` -t ${quote(pattern, platform)}`;
  }
  return command;
}

export function testReportSummary(report, root, platform = process.platform) {
  const files = report.testResults ?? [];
  const lines = [];
  const slow = files
    .map((file) => ({ ...file, duration: file.endTime - file.startTime }))
    .filter((file) => Number.isFinite(file.duration) && file.duration > 0)
    .sort((a, b) => b.duration - a.duration)
    .slice(0, 5);
  if (slow.length) {
    lines.push('[verify] slow file execution windows (excluding import/setup):');
    for (const file of slow) {
      lines.push(
        `  ${(file.duration / 1000).toFixed(2)}s ${relative(root, file.name).replaceAll('\\', '/')}`,
      );
    }
  }
  const commands = new Set();
  for (const file of files.filter((file) => file.status === 'failed')) {
    const path = relative(root, file.name).replaceAll('\\', '/');
    const failed = (file.assertionResults ?? []).filter((test) => test.status === 'failed');
    if (!failed.length) commands.add(retryCommand(path, undefined, platform));
    for (const test of failed) commands.add(retryCommand(path, test.title, platform));
  }
  if (commands.size) {
    lines.push('[verify] after fixing the failure, rerun from muyun-web (no automatic retry):');
    lines.push(...[...commands].slice(0, 10).map((command) => `  ${command}`));
    if (commands.size > 10) lines.push('  Further failures are listed in the JSON report.');
  } else if (!report.success) {
    lines.push(
      '[verify] no failed case recorded; inspect Vitest collection errors or unmatched path filters.',
    );
  }
  return lines;
}

export function printTestReport(path, root) {
  console.log(`[verify] test report: ${relative(root, path).replaceAll('\\', '/')}`);
  try {
    const report = JSON.parse(readFileSync(path, 'utf8'));
    for (const line of testReportSummary(report, root)) console.log(line);
  } catch (error) {
    // Diagnostics must not replace the original runner's success/failure result.
    console.warn(
      `[verify] test report unavailable (${error.code ?? error.name}); inspect native Vitest output.`,
    );
  }
}
