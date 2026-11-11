import { runStage } from './verification-stage.mjs';
import { createTestReport, printTestReport } from './test-report.mjs';
import { pathToFileURL } from 'node:url';

export async function runVerified(label, executable, args, { root = process.cwd(), run = runStage } = {}) {
  if (!label || !executable) throw new Error('Expected stage label and Node executable path.');
  const isTestRun = executable.replaceAll('\\', '/').endsWith('/vitest.mjs') && args[0] === 'run';
  let report;
  if (isTestRun) {
    try {
      report = createTestReport(root);
    } catch (error) {
      console.warn(`[verify] test report unavailable (${error.code ?? error.name}); using default reporter.`);
    }
  }
  try {
    await run(label, process.execPath, [executable, ...args], {
      env: {
        ...process.env,
        ...(isTestRun ? { MUYUN_TEST_REPORT_DISABLED: report ? '0' : '1' } : {}),
        ...(report ? { MUYUN_TEST_REPORT_PATH: report } : {}),
      },
    });
  } finally {
    if (report) printTestReport(report, root);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [label, executable, ...args] = process.argv.slice(2);
  await runVerified(label, executable, args);
}
