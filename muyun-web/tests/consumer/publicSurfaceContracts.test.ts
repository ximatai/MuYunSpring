import { assert, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const sourceRoot = resolve(import.meta.dirname, '../../src');

function readSource(path: string) {
  return readFileSync(resolve(sourceRoot, path), 'utf8');
}

it('consumer surface exposes the stable app-shell feedback and lifecycle facades', () => {
  const consumerSource = readSource('consumer/index.ts');
  const runtimeSource = readSource('platform-admin-runtime/index.ts');

  assert.match(consumerSource, /import '\.\.\/vue-ui-antdv\/styles\.css';/);
  assert.match(consumerSource, /import '\.\.\/styles\.css';/);
  assert.match(
    consumerSource,
    /export \{ uploadedFileId \} from '\.\.\/platform-components\/fileReferenceTransfer';/,
  );
  assert.match(consumerSource, /presentPlatformInfo/);
  assert.match(consumerSource, /refreshModulePageList/);
  assert.match(runtimeSource, /connectAppRealtime,/);
  assert.match(runtimeSource, /disconnectAppRealtime,/);
  assert.match(runtimeSource, /AppRealtimeConnection/);
  assert.ok(!/createAppRealtimeClient,/.test(runtimeSource));
  assert.match(consumerSource, /AppWorkbenchShell/);
  assert.match(consumerSource, /AppWorkbenchNavigation/);
  assert.ok(!/workbenchRouteSync/.test(consumerSource));
  assert.ok(!/openDirectTab|openMenuTab|closeMenuTab/.test(consumerSource));
});

it('app realtime consumer contract accepts runtime connection configuration without exposing a raw client', () => {
  const realtimeSource = readSource('platform-admin-runtime/realtime.ts');

  assert.match(realtimeSource, /baseUrl\?: string;/);
  assert.match(realtimeSource, /token\?: string;/);
  assert.match(realtimeSource, /export interface AppRealtimeConnection \{\s+disconnect\(\): Promise<void>;/);
});
