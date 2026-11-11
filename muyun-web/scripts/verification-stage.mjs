import { spawn } from 'node:child_process';
import { performance } from 'node:perf_hooks';

export async function stage(label, action) {
  const started = performance.now();
  console.log(`[verify] ${label}: started`);
  const progress = setInterval(() => {
    console.log(`[verify] ${label}: running (${seconds()}s)`);
  }, 30_000);
  function seconds() {
    return ((performance.now() - started) / 1000).toFixed(2);
  }
  try {
    const result = await action();
    console.log(`[verify] ${label}: passed (${seconds()}s)`);
    return result;
  } catch (error) {
    console.error(`[verify] ${label}: failed (${seconds()}s)`);
    throw error;
  } finally {
    clearInterval(progress);
  }
}

export function runStage(label, command, args, options = {}) {
  return stage(
    label,
    () =>
      new Promise((resolve, reject) => {
        const child = spawn(command, args, { ...options, stdio: ['ignore', 'inherit', 'inherit'] });
        child.once('error', reject);
        child.once('exit', (code, signal) => {
          if (code === 0) resolve();
          else reject(new Error(`${label} exited with ${signal ?? code}`));
        });
      }),
  );
}

export function npmStage(label, args, options) {
  if (!process.env.npm_execpath) throw new Error('Run verification through npm run.');
  return runStage(label, process.execPath, [process.env.npm_execpath, ...args], options);
}
