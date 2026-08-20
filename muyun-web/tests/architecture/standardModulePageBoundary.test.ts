import { assert, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');
const source = (path: string) => readFileSync(join(root, path), 'utf8');
const legacySourceCompatibilityFiles = [
  'src/dynamic-page-runtime/DynamicModuleHost.vue',
  'src/platform-workbench/legacyPageHostTypeCompatibility.ts',
] as const;

it('keeps source-neutral host and registry free from dynamic-source terms', () => {
  for (const path of [
    'src/dynamic-page-runtime/ModulePageHost.vue',
    'src/platform-workbench/pageHostRegistry.ts',
  ]) {
    const content = source(path);
    assert.notMatch(content, /dynamic/i);
  }
  assert.match(source(legacySourceCompatibilityFiles[1]), /dynamic-module-host/);
  assert.match(source(legacySourceCompatibilityFiles[0]), /ModulePageHost/);
});

it('keeps the module CRUD client as web-core default and legacy names as deprecated aliases', () => {
  const abilities = source('src/web-core/module/abilities.ts');
  const client = source('src/web-core/module/staticModuleClient.ts');

  assert.match(abilities, /ModuleCrudClient<TRecord>/);
  assert.match(abilities, /ModuleTreeClient<TRecord>/);
  assert.notMatch(abilities, /StaticModuleCrudClient|StaticModuleTreeClient/);
  assert.match(
    client,
    /@deprecated Use ModuleCrudClient\. Remove after static-prefixed consumer imports migrate/,
  );
  assert.match(client, /export type StaticModuleCrudClient<TRecord> = ModuleCrudClient<TRecord>/);
});

it('exposes one implementation owner and keeps only explicit legacy host aliases', () => {
  const entries = readdirSync(join(root, 'src/dynamic-page-runtime'));
  assert.ok(entries.includes('ModulePageHost.vue'));
  assert.match(
    source(legacySourceCompatibilityFiles[0]),
    /import ModulePageHost from '\.\/ModulePageHost\.vue';/,
  );
});
