import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { npmStage, stage } from './verification-stage.mjs';

export const webRoot = dirname(dirname(fileURLToPath(import.meta.url)));
export const repositoryRoot = dirname(webRoot);
export const outputDirectory = join(repositoryRoot, 'build', 'consumer-npm');
export const cacheDirectory = join(repositoryRoot, 'build', 'consumer-verification');
export const consumerRoot = join(cacheDirectory, 'project');

// Hash names as well as bytes: additions, removals and renames must invalidate reuse.
export function fingerprint(paths, context = '') {
  const hash = createHash('sha256').update(context);
  function visit(path) {
    hash.update(JSON.stringify(path));
    if (!existsSync(path)) {
      hash.update('missing');
      return;
    }
    const entries = readdirOrFile(path);
    if (entries) {
      for (const name of entries.sort()) visit(join(path, name));
    } else {
      const content = readFileSync(path);
      hash.update(String(content.length)).update(':').update(content);
    }
  }
  paths.forEach(visit);
  return hash.digest('hex');
}

function readdirOrFile(path) {
  try {
    return readdirSync(path);
  } catch (error) {
    if (error.code === 'ENOTDIR') return null;
    throw error;
  }
}

function toolContext() {
  return JSON.stringify({
    node: process.version,
    platform: process.platform,
    arch: process.arch,
    npm: process.env.npm_config_user_agent,
    environment: Object.entries(process.env)
      .filter(([key]) => /^(VITE_|NODE_|NPM_CONFIG_|MUYUN_RELEASE_VERSION$)/i.test(key))
      .sort(([a], [b]) => a.localeCompare(b)),
  });
}

function npmInputs(root = webRoot) {
  return [
    join(root, '.npmrc'),
    join(dirname(root), '.npmrc'),
    join(process.env.USERPROFILE ?? process.env.HOME ?? webRoot, '.npmrc'),
    ...(process.env.npm_config_userconfig ? [process.env.npm_config_userconfig] : []),
    ...(process.env.npm_config_globalconfig ? [process.env.npm_config_globalconfig] : []),
    ...(process.env.npm_execpath ? [process.env.npm_execpath] : []),
  ];
}

export function packageFingerprint(root = webRoot) {
  const configFiles = readdirSync(root).filter((name) =>
    /^(package.*\.json|tsconfig.*\.json|.*config\.[cm]?[jt]s|\.env.*)$/.test(name),
  );
  return fingerprint(
    [
      ...['src', 'public', 'scripts', ...configFiles].map((name) => join(root, name)),
      join(root, 'node_modules', '.package-lock.json'),
      ...['vite', '@vitejs/plugin-vue', 'vue-tsc', 'typescript', 'vue'].map((name) =>
        join(root, 'node_modules', name, 'package.json'),
      ),
      join(dirname(root), 'gradle.properties'),
      join(dirname(root), 'scripts', 'version-lib.mjs'),
      ...npmInputs(root),
    ],
    toolContext(),
  );
}

export function dependencyFingerprint(root = webRoot) {
  return fingerprint(
    [
      join(root, 'examples/business-web/package.json'),
      join(root, 'examples/business-web/package-lock.json'),
      join(root, 'examples/business-web/.npmrc'),
      ...npmInputs(root),
    ],
    toolContext(),
  );
}

// Each instance owns one workspace's artifacts and lock. Tests replace only the
// external npm command, keeping fingerprints, manifests and file operations real.
export function createConsumerPreparation(root = webRoot, runNpm = npmStage) {
  const cacheDirectory = join(dirname(root), 'build', 'consumer-verification');
  const outputDirectory = join(dirname(root), 'build', 'consumer-npm');
  const consumerRoot = join(cacheDirectory, 'project');

  async function withStableInputs(action) {
    const inputs = [packageFingerprint(root), dependencyFingerprint(root)];
    function assertCurrentInputs() {
      if (inputs[0] !== packageFingerprint(root) || inputs[1] !== dependencyFingerprint(root)) {
        throw new Error(
          'Consumer inputs changed during verification; this result is not valid for the current sources.',
        );
      }
    }
    const result = await action(assertCurrentInputs);
    assertCurrentInputs();
    return result;
  }

  function readManifest(name) {
    try {
      return JSON.parse(readFileSync(join(cacheDirectory, name), 'utf8'));
    } catch (error) {
      if (error.code === 'ENOENT' || error instanceof SyntaxError) return null;
      throw error;
    }
  }

  async function withPreparationLock(action) {
    mkdirSync(cacheDirectory, { recursive: true });
    const lock = join(cacheDirectory, 'lock');
    try {
      mkdirSync(lock);
    } catch (error) {
      if (error.code === 'EEXIST') {
        throw new Error(
          `Consumer verification is already running (${lock}). If interrupted, confirm its process exited before removing this lock.`,
          { cause: error },
        );
      }
      throw error;
    }
    try {
      writeFileSync(join(lock, 'owner.json'), JSON.stringify({ pid: process.pid, started: new Date() }));
      return await action();
    } finally {
      rmSync(lock, { recursive: true, force: true });
    }
  }

  async function preparePackage({ fresh = false } = {}) {
    return stage('consumer package preparation', async () => {
      const input = packageFingerprint(root);
      const previous = readManifest('package.json');
      if (!fresh && previous?.input === input && previous.output === fingerprint([outputDirectory])) {
        console.log('[verify] package cache hit (inputs and artifact contents verified)');
        return;
      }
      console.log('[verify] package cache miss');
      rmSync(join(cacheDirectory, 'package.json'), { force: true });
      await runNpm('package build', ['run', 'pack:consumer'], { cwd: root });
      if (input !== packageFingerprint(root))
        throw new Error('Package inputs changed during preparation; run again after edits finish.');
      writeFileSync(
        join(cacheDirectory, 'package.json'),
        JSON.stringify({ input, output: fingerprint([outputDirectory]) }),
      );
    });
  }

  async function prepareDependencies({ fresh = false } = {}) {
    return stage('consumer dependency preparation', async () => {
      const input = dependencyFingerprint(root);
      const previous = readManifest('dependencies.json');
      const installedLock = join(consumerRoot, 'node_modules', '.package-lock.json');
      if (
        !fresh &&
        previous?.input === input &&
        existsSync(installedLock) &&
        previous.installed === fingerprint([installedLock])
      ) {
        console.log('[verify] dependency cache hit (lockfile and toolchain verified)');
        return;
      }
      console.log('[verify] dependency cache miss');
      rmSync(join(cacheDirectory, 'dependencies.json'), { force: true });
      mkdirSync(consumerRoot, { recursive: true });
      for (const name of ['package.json', 'package-lock.json']) {
        writeFileSync(join(consumerRoot, name), readFileSync(join(root, 'examples/business-web', name)));
      }
      const npmrc = join(root, 'examples/business-web/.npmrc');
      if (existsSync(npmrc)) writeFileSync(join(consumerRoot, '.npmrc'), readFileSync(npmrc));
      else rmSync(join(consumerRoot, '.npmrc'), { force: true });
      await runNpm('consumer install', ['ci', '--prefer-offline', '--no-audit', '--no-fund'], {
        cwd: consumerRoot,
      });
      if (input !== dependencyFingerprint(root))
        throw new Error('Consumer dependency inputs changed during installation.');
      writeFileSync(
        join(cacheDirectory, 'dependencies.json'),
        JSON.stringify({ input, installed: fingerprint([installedLock]) }),
      );
    });
  }

  return { withPreparationLock, withStableInputs, preparePackage, prepareDependencies };
}

export const { withPreparationLock, withStableInputs, preparePackage, prepareDependencies } =
  createConsumerPreparation();
