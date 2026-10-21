import { describe, expect, it, vi } from 'vitest';

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { useModulePageDetailExtensionRuntime } from '@/dynamic-page-runtime/composables/useModulePageDetailExtensionRuntime.ts';

describe('module page detail extension runtime', () => {
  it('keeps drawer content mounted until its closing transition completes', () => {
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
    expect(runtime.drawerOpen.value).toBe(false);
    expect(runtime.drawer.value).toBeDefined();

    runtime.disposeDrawer();
    expect(runtime.drawer.value).toBeUndefined();
  });

  it('is the shared close authority for the dynamic workspace detail shell', () => {
    const source = readFileSync(
      resolve(import.meta.dirname, '../../src/dynamic-page-runtime/DynamicModuleWorkspaceDetailView.vue'),
      'utf8',
    );

    expect(source).toMatch(/useModulePageDetailExtensionRuntime/);
    expect(source).toMatch(/@close="closeEnhancementDrawer"/);
    expect(source).toMatch(/drawerOpen: enhancementDrawerOpen/);
    expect(source).toMatch(/:open="enhancementDrawerOpen"/);
    expect(source).toMatch(/@after-close="disposeEnhancementDrawer"/);
    expect(source).toMatch(/:context="sectionContext\(record\)"/);
    expect(source).toMatch(/<RecordDetailFields[\s\S]*:file-transfer-context="context"/);
  });

  it('confirms dirty drawer closing once and never closes a replacement drawer after a delayed answer', async () => {
    const runtime = useModulePageDetailExtensionRuntime({
      module: {} as never,
      scope: () => undefined,
      refreshList: () => {},
      reload: () => {},
      closeDetail: () => {},
    });
    runtime.openDrawer({ title: '配置', component: {} });
    let finish!: (allowed: boolean) => void;
    const guard = vi.fn(
      () =>
        new Promise<boolean>((resolve) => {
          finish = resolve;
        }),
    );
    const context = runtime.drawer.value!.context;
    context.setBeforeClose(guard);
    runtime.closeDrawer();
    runtime.closeDrawer();
    await Promise.resolve();
    expect(guard).toHaveBeenCalledOnce();
    finish(false);
    await new Promise((resolve) => setImmediate(resolve));
    expect(runtime.drawerOpen.value).toBe(true);
    runtime.closeDrawer();
    await Promise.resolve();
    runtime.openDrawer({ title: '另一抽屉', component: {} });
    context.setBeforeClose(() => true);
    finish(true);
    await new Promise((resolve) => setImmediate(resolve));
    expect(runtime.drawerOpen.value).toBe(true);
    expect(runtime.drawer.value!.definition.title).toBe('另一抽屉');
    const next = runtime.drawer.value!.context;
    next.setBeforeClose(() => true);
    runtime.closeDrawer();
    await new Promise((resolve) => setImmediate(resolve));
    expect(runtime.drawerOpen.value).toBe(false);
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

  it('lets an action drawer contribute presentation-only subtitle and fixed operation facts', () => {
    const runtime = useModulePageDetailExtensionRuntime({
      module: {} as never,
      scope: () => undefined,
      refreshList: () => undefined,
      reload: () => undefined,
      closeDetail: () => undefined,
    });

    runtime.openDrawer({ title: '配置', component: {} }, { id: 'tenant-a' });
    const context = runtime.drawer.value!.context;
    context.setSubtitle('租户：示例');
    context.setOperation({
      summary: '已选 1 项',
      actions: [{ key: 'confirm', label: '确定', run: () => undefined }],
    });

    expect(runtime.drawer.value!.subtitle).toBe('租户：示例');
    expect(runtime.drawer.value!.operation?.summary).toBe('已选 1 项');
    expect(runtime.drawer.value!.operation?.actions).toHaveLength(1);
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
    expect('setBeforeClose' in context).toBe(false);
    expect('setTitleActions' in context).toBe(false);
    expect('setOperation' in context).toBe(false);
    expect('setSubtitle' in context).toBe(false);
    expect(context.close).toBeTypeOf('function');
    expect(context.refreshDetailExtensions).toBeTypeOf('function');
  });
});
