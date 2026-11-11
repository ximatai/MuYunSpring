import { npmStage, stage } from './verification-stage.mjs';
import { pathToFileURL } from 'node:url';

export function runCheck(runNpm = npmStage) {
  return stage('complete verification', async () => {
    const commands = ['version:check', 'version:test', 'lint', 'format:check', 'test', 'build'];
    for (const [index, command] of commands.entries()) {
      try {
        await runNpm(command, ['run', command]);
      } catch (error) {
        console.error(`[verify] stopped at ${command}; no later stage was run.`);
        const remaining = commands.slice(index + 1);
        if (remaining.length)
          console.error(
            `[verify] remaining stages: ${remaining.map((name) => `npm run ${name}`).join(', ')}`,
          );
        throw error;
      }
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await runCheck();
