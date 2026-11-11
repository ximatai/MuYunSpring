import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { runStage, stage } from './verification-stage.mjs';
import {
  consumerRoot,
  prepareDependencies,
  preparePackage,
  withPreparationLock,
  withStableInputs,
} from './consumer-preparation.mjs';
import {
  assertAlignedVersionState,
  consumerPackageVersion,
  readVersionState,
} from '../../scripts/version-lib.mjs';

const webRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const repositoryRoot = dirname(webRoot);
const developmentVersion = assertAlignedVersionState(readVersionState(repositoryRoot));
const version = consumerPackageVersion(developmentVersion, process.env.MUYUN_RELEASE_VERSION?.trim());
const tarball = join(repositoryRoot, 'build', 'consumer-npm', `ximatai-muyun-web-app-${version}.tgz`);
const exampleRoot = join(webRoot, 'examples', 'business-web');

await withPreparationLock(() =>
  stage('consumer verification', () =>
    withStableInputs(async (assertCurrentInputs) => {
      const options = { fresh: process.argv.includes('--fresh') };
      await preparePackage(options);
      await prepareDependencies(options);
      assertCurrentInputs();
      if (!existsSync(tarball)) {
        throw new Error(`缺少消费者 tarball：${tarball}`);
      }

      const temporaryExampleRoot = consumerRoot;
      // Keep only dependencies between runs; refresh the complete example and outputs.
      for (const name of readdirSync(temporaryExampleRoot)) {
        if (name !== 'node_modules')
          rmSync(join(temporaryExampleRoot, name), { recursive: true, force: true });
      }
      cpSync(exampleRoot, temporaryExampleRoot, {
        recursive: true,
        filter: (source) => !source.includes('node_modules'),
      });
      const exampleTsConfigPath = join(temporaryExampleRoot, 'tsconfig.json');
      const exampleTsConfig = JSON.parse(readFileSync(exampleTsConfigPath, 'utf8'));
      exampleTsConfig.extends = join(webRoot, 'tsconfig.json');
      writeFileSync(exampleTsConfigPath, `${JSON.stringify(exampleTsConfig, null, 2)}\n`);
      const installedPackageRoot = join(temporaryExampleRoot, 'node_modules', '@ximatai', 'muyun-web-app');
      rmSync(installedPackageRoot, { recursive: true, force: true });
      mkdirSync(installedPackageRoot, { recursive: true });
      await runStage(
        'install current tarball',
        process.platform === 'win32' ? join(process.env.SystemRoot, 'System32', 'tar.exe') : 'tar',
        ['-xzf', tarball, '--strip-components=1', '-C', installedPackageRoot],
        {
          cwd: temporaryExampleRoot,
        },
      );
      await runStage(
        'declaration boundary',
        process.execPath,
        [
          join(webRoot, 'scripts', 'verify-consumer-declarations.mjs'),
          join(installedPackageRoot, 'dist', 'types'),
        ],
        { cwd: webRoot },
      );
      const installedPackage = JSON.parse(readFileSync(join(installedPackageRoot, 'package.json'), 'utf8'));
      if (installedPackage.version !== version) {
        throw new Error(`消费者未安装当前 tarball：期望 ${version}，实际 ${installedPackage.version}`);
      }
      const libraryJavascript = readdirSync(join(installedPackageRoot, 'dist'))
        .filter((name) => name.endsWith('.js'))
        .map((name) => readFileSync(join(installedPackageRoot, 'dist', name), 'utf8'))
        .join('\n');
      for (const key of ['VITE_MUYUN_API_BASE_URL', 'VITE_MUYUN_AUTH_TOKEN', 'VITE_MUYUN_CREDENTIALS']) {
        if (!libraryJavascript.includes(`import.meta.env.${key}`)) {
          throw new Error(`消费者包提前固化了宿主配置：${key}`);
        }
      }
      const contractFixtures = join(webRoot, 'tests', 'consumer', 'packaged');
      cpSync(
        join(contractFixtures, 'ExplorerConsumer.vue'),
        join(temporaryExampleRoot, 'src', 'ExplorerConsumer.vue'),
      );
      cpSync(
        join(contractFixtures, 'recordRead.mjs'),
        join(temporaryExampleRoot, 'record-read-contract.mjs'),
      );
      cpSync(join(contractFixtures, 'host.mjs'), join(temporaryExampleRoot, 'src', 'consumer-contract.ts'));
      cpSync(join(contractFixtures, 'vite.config.ts'), join(temporaryExampleRoot, 'vite.contract.config.ts'));
      await runStage(
        'example typecheck and build',
        process.execPath,
        [process.env.npm_execpath, 'run', 'build'],
        {
          cwd: temporaryExampleRoot,
          env: { ...process.env, VITE_MUYUN_API_BASE_URL: '/consumer-api-contract' },
        },
      );
      await runStage(
        'consumer contract build',
        process.execPath,
        [
          join(temporaryExampleRoot, 'node_modules', 'vite', 'bin', 'vite.js'),
          'build',
          '--config',
          'vite.contract.config.ts',
        ],
        {
          cwd: temporaryExampleRoot,
          env: {
            ...process.env,
            VITE_MUYUN_API_BASE_URL: '/consumer-api-contract',
            VITE_MUYUN_AUTH_TOKEN: 'consumer-environment-token',
            VITE_MUYUN_CREDENTIALS: 'include',
          },
        },
      );
      await runStage(
        'consumer contract tests',
        process.execPath,
        [
          join(webRoot, 'scripts', 'run-verified.mjs'),
          'consumer-tests',
          join(webRoot, 'node_modules', 'vitest', 'vitest.mjs'),
          'run',
          '--config',
          'vitest.consumer.config.ts',
        ],
        {
          cwd: webRoot,
          env: {
            ...process.env,
            MUYUN_CONSUMER_CONTRACT_BUNDLE: join(
              temporaryExampleRoot,
              'contract-dist',
              'consumer-contract.js',
            ),
            MUYUN_CONSUMER_PROJECT_ROOT: temporaryExampleRoot,
          },
        },
      );
    }),
  ),
);
