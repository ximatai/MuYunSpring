import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';
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

if (!existsSync(tarball)) {
  throw new Error(`缺少消费者 tarball：${tarball}`);
}

execFileSync('node', [join(webRoot, 'scripts', 'verify-consumer-declarations.mjs')], {
  cwd: webRoot,
  stdio: 'inherit',
});
const temporaryExampleRoot = mkdtempSync(join(tmpdir(), 'muyun-web-consumer-'));
try {
  cpSync(exampleRoot, temporaryExampleRoot, {
    recursive: true,
    filter: (source) => !source.includes('node_modules'),
  });
  const exampleTsConfigPath = join(temporaryExampleRoot, 'tsconfig.json');
  const exampleTsConfig = JSON.parse(readFileSync(exampleTsConfigPath, 'utf8'));
  exampleTsConfig.extends = join(webRoot, 'tsconfig.json');
  writeFileSync(exampleTsConfigPath, `${JSON.stringify(exampleTsConfig, null, 2)}\n`);
  execFileSync(
    process.execPath,
    [process.env.npm_execpath, 'ci', '--prefer-offline', '--no-audit', '--no-fund'],
    {
      cwd: temporaryExampleRoot,
      stdio: 'inherit',
    },
  );
  const installedPackageRoot = join(temporaryExampleRoot, 'node_modules', '@ximatai', 'muyun-web-app');
  rmSync(installedPackageRoot, { recursive: true, force: true });
  mkdirSync(installedPackageRoot, { recursive: true });
  execFileSync(
    process.platform === 'win32' ? join(process.env.SystemRoot, 'System32', 'tar.exe') : 'tar',
    ['-xzf', tarball, '--strip-components=1', '-C', installedPackageRoot],
    {
      cwd: temporaryExampleRoot,
      stdio: 'inherit',
    },
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
  // Keep the platform-owned client reachable in this disposable consumer build.
  // This probe is compiled only; it never sends a request.
  const consumerMain = join(temporaryExampleRoot, 'src', 'main.ts');
  writeFileSync(
    consumerMain,
    `${readFileSync(consumerMain, 'utf8')}\nimport { createBackendHttpClient } from '@ximatai/muyun-web-app';\nvoid createBackendHttpClient({ withAuth: false }).request({ path: '/consumer-contract' });\n`,
  );
  execFileSync(process.execPath, [process.env.npm_execpath, 'run', 'build'], {
    cwd: temporaryExampleRoot,
    env: { ...process.env, VITE_MUYUN_API_BASE_URL: '/consumer-api-contract' },
    stdio: 'inherit',
  });
  const hostJavascript = readdirSync(join(temporaryExampleRoot, 'dist', 'assets'))
    .filter((name) => name.endsWith('.js'))
    .map((name) => readFileSync(join(temporaryExampleRoot, 'dist', 'assets', name), 'utf8'))
    .join('\n');
  if (
    !hostJavascript.includes('/consumer-api-contract') ||
    hostJavascript.includes('import.meta.env.VITE_MUYUN_API_BASE_URL')
  ) {
    throw new Error('宿主构建没有解析平台组件的 API 地址配置');
  }
} finally {
  rmSync(temporaryExampleRoot, { recursive: true, force: true });
}
