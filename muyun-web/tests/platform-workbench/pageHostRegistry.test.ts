import { assert, it } from 'vitest';
import { resolvePageHostComponentName } from '@/platform-workbench/pageHostRegistry.ts';

it('resolvePageHostComponentName maps platform route hosts to PlatformRouteHost', () => {
  assert.equal(resolvePageHostComponentName('platform-route-host'), 'PlatformRouteHost');
});

it('resolvePageHostComponentName maps business route hosts to BusinessRouteHost', () => {
  assert.equal(resolvePageHostComponentName('business-route-host'), 'BusinessRouteHost');
});

it('resolvePageHostComponentName maps dynamic and external hosts', () => {
  assert.equal(resolvePageHostComponentName('dynamic-module-host'), 'DynamicModuleHost');
  assert.equal(resolvePageHostComponentName('external-page-host'), 'ExternalPageHost');
});
