import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const sourceRoots = ['src', 'examples/business-web/src'];
const allowedAntdvPrefix = 'src/vue-ui-antdv/';
// RecordDetailDrawer is the page-owned drawer boundary. It uses ADrawer directly
// so each business page can supply its own root DOM container without a global host.
const allowedAntdvFiles = new Set(['src/platform-components/RecordDetailDrawer.vue']);
const violations = [];
const packageViolations = [];
const layerViolations = [];
const adapterContractViolations = [];
const antdvTemplatePattern = /<\/?a-[a-z0-9-]+[\s>]/i;
const packageLayerRules = [
  {
    prefix: 'src/web-contracts/',
    forbidden: [
      '@muyun/web-core',
      '@muyun/vue-ui-antdv',
      '@muyun/dynamic-page-runtime',
      '@muyun/platform-components',
      '@muyun/platform-workbench',
    ],
  },
  {
    prefix: 'src/web-core/',
    forbidden: [
      '@muyun/vue-ui-antdv',
      '@muyun/dynamic-page-runtime',
      '@muyun/platform-components',
      '@muyun/platform-workbench',
    ],
  },
  {
    prefix: 'src/vue-ui-antdv/',
    forbidden: ['@muyun/dynamic-page-runtime', '@muyun/platform-components', '@muyun/platform-workbench'],
  },
  {
    prefix: 'src/dynamic-page-runtime/',
    forbidden: ['@muyun/vue-ui-antdv', '@muyun/platform-workbench'],
  },
  {
    prefix: 'src/platform-components/',
    forbidden: ['@muyun/dynamic-page-runtime', '@muyun/platform-workbench'],
  },
  {
    prefix: 'src/platform-workbench/',
    forbidden: ['@muyun/platform-components'],
  },
];

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return walk(path);
    }
    return [path];
  });
}

for (const sourceRoot of sourceRoots) {
  const absoluteRoot = join(root, sourceRoot);
  for (const file of walk(absoluteRoot)) {
    if (!/\.(ts|vue)$/.test(file)) {
      continue;
    }

    const projectPath = relative(root, file).replaceAll('\\', '/');
    const source = readFileSync(file, 'utf8');
    const usesAntdvPackage = source.includes('ant-design-vue') || source.includes('@ant-design/icons-vue');
    const usesAntdvTemplate = file.endsWith('.vue') && antdvTemplatePattern.test(source);

    if (
      projectPath.startsWith('src/vue-ui-antdv/components/Ui') &&
      file.endsWith('.vue') &&
      !source.includes('inheritAttrs: false')
    ) {
      adapterContractViolations.push(
        `${projectPath}: Ui adapter component must disable attribute fallthrough`,
      );
    }

    if (
      (usesAntdvPackage || usesAntdvTemplate) &&
      !projectPath.startsWith(allowedAntdvPrefix) &&
      !allowedAntdvFiles.has(projectPath)
    ) {
      violations.push(projectPath);
    }

    const layerRule = packageLayerRules.find((rule) => projectPath.startsWith(rule.prefix));
    if (layerRule) {
      for (const dependencyName of layerRule.forbidden) {
        if (source.includes(`'${dependencyName}'`) || source.includes(`"${dependencyName}"`)) {
          layerViolations.push(`${projectPath}: ${dependencyName}`);
        }
      }
    }
  }
}

for (const packagePath of ['examples/business-web/package.json']) {
  const absolutePath = join(root, packagePath);
  if (!existsSync(absolutePath)) {
    continue;
  }

  const packageJson = JSON.parse(readFileSync(absolutePath, 'utf8'));
  const directDependencies = {
    ...(packageJson.dependencies ?? {}),
    ...(packageJson.devDependencies ?? {}),
    ...(packageJson.peerDependencies ?? {}),
  };

  for (const dependencyName of ['ant-design-vue', '@ant-design/icons-vue']) {
    if (directDependencies[dependencyName]) {
      packageViolations.push(`${packagePath}: ${dependencyName}`);
    }
  }
}

if (
  violations.length > 0 ||
  packageViolations.length > 0 ||
  layerViolations.length > 0 ||
  adapterContractViolations.length > 0
) {
  if (violations.length > 0) {
    console.error(
      'Ant Design Vue imports or template tags are only allowed in the UI adapter or approved page-owned drawer:',
    );
    for (const violation of violations) {
      console.error(`- ${violation}`);
    }
  }

  if (packageViolations.length > 0) {
    console.error('Business examples must not declare direct Ant Design Vue dependencies:');
    for (const violation of packageViolations) {
      console.error(`- ${violation}`);
    }
  }

  if (layerViolations.length > 0) {
    console.error('Package layer dependency violations:');
    for (const violation of layerViolations) {
      console.error(`- ${violation}`);
    }
  }

  if (adapterContractViolations.length > 0) {
    console.error('Ui adapter components must expose explicit contracts instead of attribute fallthrough:');
    for (const violation of adapterContractViolations) {
      console.error(`- ${violation}`);
    }
  }

  process.exit(1);
}

console.log('Boundary check passed.');
