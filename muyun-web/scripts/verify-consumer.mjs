import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';

const webRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const repositoryRoot = dirname(webRoot);
const version = JSON.parse(readFileSync(join(webRoot, 'package.json'), 'utf8')).version;
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
  execFileSync(process.execPath, [process.env.npm_execpath, 'run', 'build'], {
    cwd: temporaryExampleRoot,
    stdio: 'inherit',
  });
} finally {
  rmSync(temporaryExampleRoot, { recursive: true, force: true });
}
