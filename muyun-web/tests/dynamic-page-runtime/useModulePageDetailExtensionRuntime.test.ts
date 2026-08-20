import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { useModulePageDetailExtensionRuntime } from '@/dynamic-page-runtime/composables/useModulePageDetailExtensionRuntime.ts';

describe('module page detail extension runtime', () => {
  it('keeps the platform drawer mounted while a contributed operation blocks dismissal', () => {
    const runtime = useModulePageDetailExtensionRuntime({
      module: {} as never,
      scope: () => undefined,
      refreshList: () => undefined,
      reload: () => undefined,
      closeDetail: () => undefined,
    });

    runtime.openDrawer({ title: '配置', component: {} }, { id: 'tenant-a' });
    runtime.drawer.value!.context.setCloseBlocked(true);
    runtime.closeDrawer();
    expect(runtime.drawer.value).toBeDefined();

    runtime.drawer.value!.context.setCloseBlocked(false);
    runtime.closeDrawer();
    expect(runtime.drawer.value).toBeUndefined();
  });

  it('is the shared close authority for the dynamic workspace detail shell', () => {
    const source = readFileSync(
      resolve(import.meta.dirname, '../../src/dynamic-page-runtime/DynamicModuleWorkspaceDetailView.vue'),
      'utf8',
    );

    expect(source).toMatch(/useModulePageDetailExtensionRuntime/);
    expect(source).toMatch(/@close="closeEnhancementDrawer"/);
    expect(source).toMatch(/:context="sectionContext\(record\)"/);
  });

  it('publishes a monotonic section refresh fact for both static and dynamic detail shells', () => {
    const runtime = useModulePageDetailExtensionRuntime({
      module: {} as never,
      scope: () => undefined,
      refreshList: () => undefined,
      reload: () => undefined,
      closeDetail: () => undefined,
    });

    expect(runtime.sectionContext({ id: 'record-a' }).refreshKey).toBe(0);
    runtime.refreshDetailExtensions();
    expect(runtime.sectionContext({ id: 'record-a' }).refreshKey).toBe(1);
  });

  it('does not advertise action-drawer chrome controls to a record-view body', () => {
    const runtime = useModulePageDetailExtensionRuntime({
      module: {} as never,
      scope: () => undefined,
      refreshList: () => undefined,
      reload: () => undefined,
      closeDetail: () => undefined,
    });

    const context = runtime.recordViewContext({ id: 'record-a' });
    expect('setCloseBlocked' in context).toBe(false);
    expect('setTitleActions' in context).toBe(false);
    expect(context.close).toBeTypeOf('function');
    expect(context.refreshDetailExtensions).toBeTypeOf('function');
  });
});
