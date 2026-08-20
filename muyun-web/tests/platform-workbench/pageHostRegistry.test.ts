import { assert, it } from 'vitest';
import { resolvePageHostComponentName } from '@/platform-workbench/pageHostRegistry.ts';

it('resolvePageHostComponentName maps platform route hosts to PlatformRouteHost', () => {
  assert.equal(resolvePageHostComponentName('platform-route-host'), 'PlatformRouteHost');
});

it('resolvePageHostComponentName maps business route hosts to BusinessRouteHost', () => {
  assert.equal(resolvePageHostComponentName('business-route-host'), 'BusinessRouteHost');
});

it('resolvePageHostComponentName maps neutral and persisted module hosts to the same public host', () => {
  assert.equal(resolvePageHostComponentName('module-page-host'), 'ModulePageHost');
  assert.equal(resolvePageHostComponentName('dynamic-module-host'), 'ModulePageHost');
  assert.equal(resolvePageHostComponentName('external-page-host'), 'ExternalPageHost');
});
