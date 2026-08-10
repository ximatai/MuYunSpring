import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';
import { execFileSync } from 'node:child_process';
import vue from '@vitejs/plugin-vue';
import { build } from 'vite';

const webRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const repositoryRoot = dirname(webRoot);
const outputDirectory = join(repositoryRoot, 'build', 'consumer-npm');
const stagingDirectory = join(outputDirectory, 'staging', 'web-app');
const consumerEntry = join(webRoot, 'src', 'consumer', 'index.ts');
const rootPackage = JSON.parse(readFileSync(join(webRoot, 'package.json'), 'utf8'));

assertVersionAlignment(rootPackage.version);

rmSync(outputDirectory, { recursive: true, force: true });
mkdirSync(stagingDirectory, { recursive: true });

writeFileSync(
  join(stagingDirectory, 'package.json'),
  `${JSON.stringify(
    {
      name: '@ximatai/muyun-web-app',
      version: rootPackage.version,
      description: 'MuYunSpring workbench and standard module runtime for business applications',
      type: 'module',
      files: ['dist'],
      main: './dist/index.js',
      types: './dist/types/consumer/index.d.ts',
      exports: {
        '.': { types: './dist/types/consumer/index.d.ts', import: './dist/index.js' },
        './style.css': './dist/index.css',
      },
      sideEffects: ['./dist/index.css'],
      peerDependencies: {
        vue: rootPackage.dependencies.vue,
        'ant-design-vue': rootPackage.dependencies['ant-design-vue'],
        '@ant-design/icons-vue': rootPackage.dependencies['@ant-design/icons-vue'],
      },
      license: 'Apache-2.0',
    },
    null,
    2,
  )}\n`,
);

if (!existsSync(join(webRoot, 'node_modules', 'vite'))) {
  throw new Error('请先在 muyun-web 执行 npm ci，再运行 pack:consumer。');
}

const aliases = {
  '@': join(webRoot, 'src'),
  '@muyun/vue-ui-antdv/styles.css': join(webRoot, 'src/vue-ui-antdv/styles.css'),
  '@muyun/web-contracts': join(webRoot, 'src/web-contracts/index.ts'),
  '@muyun/web-core': join(webRoot, 'src/web-core/index.ts'),
  '@muyun/vue-ui-antdv': join(webRoot, 'src/vue-ui-antdv/index.ts'),
  '@muyun/dynamic-page-runtime': join(webRoot, 'src/dynamic-page-runtime/index.ts'),
  '@muyun/platform-components': join(webRoot, 'src/platform-components/index.ts'),
  '@muyun/platform-workbench': join(webRoot, 'src/platform-workbench/index.ts'),
};

await build({
  configFile: false,
  plugins: [vue()],
  resolve: { alias: aliases },
  build: {
    emptyOutDir: true,
    outDir: join(stagingDirectory, 'dist'),
    lib: { entry: consumerEntry, formats: ['es'], fileName: 'index' },
    rollupOptions: {
      external: ['vue', 'ant-design-vue', '@ant-design/icons-vue'],
    },
  },
});

const vueTscArguments = [
  '--project',
  'tsconfig.build.json',
  '--noEmit',
  'false',
  '--declaration',
  '--emitDeclarationOnly',
  '--rootDir',
  'src',
  '--outDir',
  join(stagingDirectory, 'dist', 'types'),
  '--declarationMap',
  'false',
];

if (process.platform === 'win32') {
  execFileSync(process.execPath, [process.env.npm_execpath, 'exec', '--', 'vue-tsc', ...vueTscArguments], {
    cwd: webRoot,
    stdio: 'inherit',
  });
} else {
  execFileSync(join(webRoot, 'node_modules', '.bin', 'vue-tsc'), vueTscArguments, {
    cwd: webRoot,
    stdio: 'inherit',
  });
}

rewriteDeclarationAliases(join(stagingDirectory, 'dist', 'types'));

const npmPackArguments = ['pack', '--pack-destination', outputDirectory];

if (process.platform === 'win32') {
  execFileSync(process.execPath, [process.env.npm_execpath, ...npmPackArguments], {
    cwd: stagingDirectory,
    stdio: 'inherit',
  });
} else {
  execFileSync('npm', npmPackArguments, {
    cwd: stagingDirectory,
    stdio: 'inherit',
  });
}

function rewriteDeclarationAliases(typesDirectory) {
  const declarationFiles = collectFiles(typesDirectory).filter((file) => file.endsWith('.d.ts'));
  const declarationsByAlias = {
    '@muyun/web-contracts': join(typesDirectory, 'web-contracts', 'index'),
    '@muyun/web-core': join(typesDirectory, 'web-core', 'index'),
    '@muyun/vue-ui-antdv': join(typesDirectory, 'vue-ui-antdv', 'index'),
    '@muyun/dynamic-page-runtime': join(typesDirectory, 'dynamic-page-runtime', 'index'),
    '@muyun/platform-components': join(typesDirectory, 'platform-components', 'index'),
    '@muyun/platform-workbench': join(typesDirectory, 'platform-workbench', 'index'),
  };

  for (const declarationFile of declarationFiles) {
    const declaration = readFileSync(declarationFile, 'utf8');
    const rewritten = Object.entries(declarationsByAlias).reduce(
      (source, [alias, destination]) =>
        source.replaceAll(alias, relativeModuleSpecifier(dirname(declarationFile), destination)),
      declaration,
    );
    if (rewritten !== declaration) {
      writeFileSync(declarationFile, rewritten);
    }
  }
}

function collectFiles(directory) {
  return readdirSync(directory).flatMap((name) => {
    const file = join(directory, name);
    return statSync(file).isDirectory() ? collectFiles(file) : [file];
  });
}

function relativeModuleSpecifier(fromDirectory, destination) {
  const specifier = relative(fromDirectory, destination).replaceAll('\\', '/');
  return specifier.startsWith('.') ? specifier : `./${specifier}`;
}

function assertVersionAlignment(packageVersion) {
  const gradleProperties = readFileSync(join(repositoryRoot, 'gradle.properties'), 'utf8');
  const match = gradleProperties.match(/^muyunVersion=(.+)-SNAPSHOT$/m);
  if (!match || match[1] !== packageVersion) {
    throw new Error(
      `前端包版本 ${packageVersion} 必须与 gradle.properties 的 muyunVersion 正式版本保持一致。`,
    );
  }
}
