import { existsSync, mkdtempSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
// The scripts are native ESM entry points shared with npm verification commands.
// @ts-expect-error Native verification scripts do not publish TypeScript declarations.
import * as preparation from '../../scripts/consumer-preparation.mjs';

const { createConsumerPreparation, dependencyFingerprint, fingerprint, packageFingerprint } = preparation;

let directory: string;
let root: string;
function write(name: string, value = name) {
  const path = join(root, name);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, value);
}

beforeEach(() => {
  directory = mkdtempSync(join(tmpdir(), 'muyun-preparation-test-'));
  root = join(directory, 'web');
  for (const name of [
    'src/entry.ts',
    'package.json',
    'package-lock.json',
    'tsconfig.build.json',
    'scripts/pack-consumer.mjs',
    'node_modules/.package-lock.json',
    'examples/business-web/package.json',
    'examples/business-web/package-lock.json',
  ])
    write(name);
});
afterEach(() => {
  vi.unstubAllEnvs();
  rmSync(directory, { recursive: true, force: true });
});

it.each([
  'src/entry.ts',
  'tsconfig.build.json',
  'package-lock.json',
  'scripts/pack-consumer.mjs',
  'node_modules/.package-lock.json',
  '../gradle.properties',
  '../scripts/version-lib.mjs',
  '.npmrc',
  '.env.production',
])('invalidates package preparation when %s changes', (name) => {
  const before = packageFingerprint(root);
  expect(packageFingerprint(root)).toBe(before);
  write(name, 'changed');
  expect(packageFingerprint(root)).not.toBe(before);
});

it('detects added, renamed and removed sources even with identical bytes', () => {
  const before = packageFingerprint(root);
  write('src/extra.ts', 'src/entry.ts');
  const added = packageFingerprint(root);
  expect(added).not.toBe(before);
  renameSync(join(root, 'src/extra.ts'), join(root, 'src/renamed.ts'));
  expect(packageFingerprint(root)).not.toBe(added);
  rmSync(join(root, 'src/renamed.ts'));
  expect(packageFingerprint(root)).toBe(before);
});

it.each(['examples/business-web/package.json', 'examples/business-web/package-lock.json'])(
  'invalidates dependencies when %s changes',
  (name) => {
    const before = dependencyFingerprint(root);
    write(name, 'changed');
    expect(dependencyFingerprint(root)).not.toBe(before);
  },
);

it('keeps the dependency cache reusable across platform source edits', () => {
  const before = dependencyFingerprint(root);
  write('src/entry.ts', 'changed');
  expect(dependencyFingerprint(root)).toBe(before);
});

it.each(['npm_config_user_agent', 'VITE_MUYUN_API_BASE_URL', 'MUYUN_RELEASE_VERSION', 'NODE_OPTIONS'])(
  'invalidates preparation when toolchain/environment input %s changes',
  (name) => {
    const before = packageFingerprint(root);
    vi.stubEnv(name, 'changed');
    expect(packageFingerprint(root)).not.toBe(before);
  },
);

it('detects deleted or corrupted output rather than trusting file existence', () => {
  write('artifact/package.tgz', 'original');
  const paths = [join(root, 'artifact')];
  const before = fingerprint(paths);
  write('artifact/package.tgz', 'corrupted');
  expect(fingerprint(paths)).not.toBe(before);
  rmSync(paths[0], { recursive: true });
  expect(fingerprint(paths)).not.toBe(before);
});

function lifecycle() {
  const cache = join(directory, 'build/consumer-verification');
  const output = join(directory, 'build/consumer-npm');
  const runNpm = vi.fn(async (_label: string, args: string[], options: { cwd: string }) => {
    if (args[0] === 'run') {
      expect(options.cwd).toBe(root);
      write('../build/consumer-npm/package.tgz', readFileSync(join(root, 'src/entry.ts'), 'utf8'));
      write('../build/consumer-npm/staging/dist/types/entry.d.ts', 'current declaration');
    } else {
      expect(args[0]).toBe('ci');
      expect(options.cwd).toBe(join(cache, 'project'));
      write('../build/consumer-verification/project/node_modules/.package-lock.json', 'installed lock');
    }
  });
  const instance = createConsumerPreparation(root, runNpm);
  const prepare = (name: 'preparePackage' | 'prepareDependencies', options = {}) =>
    instance.withPreparationLock(() => instance[name](options));
  return { ...instance, prepare, runNpm, cache, output };
}

it('reuses successful preparation but forces both commands when fresh is requested', async () => {
  const state = lifecycle();
  for (const name of ['preparePackage', 'prepareDependencies'] as const) {
    await state.prepare(name);
    await state.prepare(name);
  }
  expect(state.runNpm).toHaveBeenCalledTimes(2);
  await state.prepare('preparePackage', { fresh: true });
  await state.prepare('prepareDependencies', { fresh: true });
  expect(state.runNpm).toHaveBeenCalledTimes(4);
});

it.each(['package', 'dependencies'] as const)(
  'does not retain a successful %s manifest after a failed refresh',
  async (kind) => {
    const state = lifecycle();
    const method = kind === 'package' ? 'preparePackage' : 'prepareDependencies';
    await state.prepare(method);
    state.runNpm.mockRejectedValueOnce(new Error('npm failed after partial output'));
    await expect(state.prepare(method, { fresh: true })).rejects.toThrow('npm failed');
    expect(existsSync(join(state.cache, `${kind}.json`))).toBe(false);
    expect(existsSync(join(state.cache, 'lock'))).toBe(false);
    await state.prepare(method);
    expect(state.runNpm).toHaveBeenCalledTimes(3);
    expect(existsSync(join(state.cache, `${kind}.json`))).toBe(true);
  },
);

it.each(['package.tgz', 'staging/dist/types/entry.d.ts'])(
  'rebuilds when prepared artifact %s is corrupted',
  async (name) => {
    const state = lifecycle();
    await state.prepare('preparePackage');
    writeFileSync(join(state.output, name), 'corrupted');
    await state.prepare('preparePackage');
    expect(state.runNpm).toHaveBeenCalledTimes(2);
    expect(readFileSync(join(state.output, name), 'utf8')).not.toBe('corrupted');
  },
);

it('rebuilds a missing artifact directory and a corrupt preparation manifest', async () => {
  const state = lifecycle();
  await state.prepare('preparePackage');
  rmSync(state.output, { recursive: true });
  await state.prepare('preparePackage');
  writeFileSync(join(state.cache, 'package.json'), '{unfinished');
  await state.prepare('preparePackage');
  expect(state.runNpm).toHaveBeenCalledTimes(3);
});

it('rebuilds changed sources without reinstalling unchanged consumer dependencies', async () => {
  const state = lifecycle();
  await state.prepare('preparePackage');
  await state.prepare('prepareDependencies');
  write('src/entry.ts', 'new source');
  await state.prepare('preparePackage');
  await state.prepare('prepareDependencies');
  expect(state.runNpm).toHaveBeenCalledTimes(3);
  expect(readFileSync(join(state.output, 'package.tgz'), 'utf8')).toBe('new source');
});

it.each(['preparePackage', 'prepareDependencies'] as const)(
  'rejects an input change during %s without publishing a successful manifest',
  async (method) => {
    const state = lifecycle();
    const input = method === 'preparePackage' ? 'src/entry.ts' : 'examples/business-web/package-lock.json';
    state.runNpm.mockImplementationOnce(async () => {
      write(input, 'changed during npm');
    });
    await expect(state.prepare(method)).rejects.toThrow(/inputs changed/i);
    const manifest = method === 'preparePackage' ? 'package.json' : 'dependencies.json';
    expect(existsSync(join(state.cache, manifest))).toBe(false);
    expect(existsSync(join(state.cache, 'lock'))).toBe(false);
  },
);

it('reinstalls after a consumer lockfile change or missing installed dependencies', async () => {
  const state = lifecycle();
  await state.prepare('prepareDependencies');
  write('examples/business-web/package-lock.json', 'new consumer lock');
  await state.prepare('prepareDependencies');
  rmSync(join(state.cache, 'project/node_modules'), { recursive: true });
  await state.prepare('prepareDependencies');
  expect(state.runNpm).toHaveBeenCalledTimes(3);
});

it('rejects overlapping preparation without deleting the active owner lock', async () => {
  const state = lifecycle();
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const active = state.withPreparationLock(() => gate);
  const owner = readFileSync(join(state.cache, 'lock/owner.json'), 'utf8');
  try {
    await expect(state.withPreparationLock(() => undefined)).rejects.toThrow('already running');
    expect(readFileSync(join(state.cache, 'lock/owner.json'), 'utf8')).toBe(owner);
  } finally {
    release();
    await active;
  }
  await expect(state.withPreparationLock(() => 'next owner')).resolves.toBe('next owner');
  expect(existsSync(join(state.cache, 'lock'))).toBe(false);
});

it('rejects source edits during dependency installation before consuming the old tarball', async () => {
  const state = lifecycle();
  const install = state.runNpm.getMockImplementation()!;
  state.runNpm.mockImplementation(async (label: string, args: string[], options: { cwd: string }) => {
    await install(label, args, options);
    if (args[0] === 'ci') write('src/entry.ts', 'edited during dependency installation');
  });
  const consume = vi.fn();
  await expect(
    state.withPreparationLock(() =>
      state.withStableInputs(async (assertCurrentInputs: () => void) => {
        await state.preparePackage();
        await state.prepareDependencies();
        assertCurrentInputs();
        consume();
      }),
    ),
  ).rejects.toThrow('Consumer inputs changed');
  expect(consume).not.toHaveBeenCalled();
  expect(existsSync(join(state.cache, 'lock'))).toBe(false);

  await state.withPreparationLock(() =>
    state.withStableInputs(async () => {
      await state.preparePackage();
      await state.prepareDependencies();
    }),
  );
  expect(readFileSync(join(state.output, 'package.tgz'), 'utf8')).toBe(
    'edited during dependency installation',
  );
  // The successful installation is still reusable; only the stale package rebuilds.
  expect(state.runNpm).toHaveBeenCalledTimes(3);
});

it('rejects source changes after preparation instead of reporting verification success', async () => {
  const state = lifecycle();
  await expect(
    state.withPreparationLock(() =>
      state.withStableInputs(async (assertCurrentInputs: () => void) => {
        await state.preparePackage();
        await state.prepareDependencies();
        assertCurrentInputs();
        write('src/entry.ts', 'edited during contracts');
      }),
    ),
  ).rejects.toThrow('Consumer inputs changed');
});
