import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { commands } from 'vitest/browser';
import { createHttpClient, createModuleContext } from '@/web-core/index.ts';
import type { TreeRecordBase } from '@/platform-components/index.ts';
import TreeRecordExplorer from '@/platform-components/TreeRecordExplorer.vue';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';

declare const __TREE_SERVICE_ENABLED__: boolean;
declare const __TREE_SERVICE_TOKEN__: string;

// This opt-in test creates data. MUYUN_TREE_SERVICE_URL must point to a disposable development database.
it.skipIf(!__TREE_SERVICE_ENABLED__)(
  'persists reparenting and root moves through the real service and preserves the renderer and destination path',
  async () => {
    const http = createHttpClient({ baseUrl: '/api', token: __TREE_SERVICE_TOKEN__ });
    const tenantId = `tree_verify_${Date.now()}`;
    await http.request({
      method: 'POST',
      path: '/iam.tenant/insert',
      body: { alias: tenantId, title: 'UiTree 浏览器验证', enabled: true },
    });
    const context = createModuleContext<
      TreeRecordBase & { code?: string; tenantId?: string; parentId?: string; enabled?: boolean }
    >({ http, moduleAlias: 'iam.position_category' });
    await context.runtime.ready;
    const create = async (code: string, parentId = 'root') => {
      const result = await context.crud.insert({ title: code, code, parentId, tenantId, enabled: true });
      if (!result.record?.id) throw new Error('测试节点未创建');
      return String(result.record.id);
    };
    const left = await create('left');
    const child = await create('moving', left);
    const right = await create('right');
    const deep = await create('deep', right);
    const destination = await create('destination', deep);
    const wrapper = mount(TreeRecordExplorer, {
      attachTo: document.body,
      props: { context, sorting: true, searchMode: 'none', externalQueryValues: { tenantId } },
    });
    (wrapper.element as HTMLElement).style.width = '360px';
    await expect.poll(() => wrapper.findComponent(UiTree).exists()).toBe(true);
    const tree = wrapper.findComponent(UiTree);
    const renderer = tree.element;
    tree.vm.$emit('update:expandedKeys', [left, right, deep]);
    await expect.poll(() => wrapper.find(`[data-ui-tree-key="${destination}"]`).exists()).toBe(true);
    await commands.treeGesture(`[data-ui-tree-key="${child}"]`, `[data-ui-tree-key="${destination}"]`);
    await expect.poll(() => wrapper.emitted('sorted')?.length).toBe(1);
    expect(wrapper.findComponent(UiTree).element).toBe(renderer);
    await expect
      .poll(() => (renderer as HTMLElement).getAnimations({ subtree: true }).length)
      .toBeGreaterThan(0);
    expect(wrapper.findComponent(UiTree).props('expandedKeys')).toEqual(
      expect.arrayContaining([right, deep, destination]),
    );
    expect((await context.crud.view(child)).parentId).toBe(destination);
    await commands.treeGesture(`[data-ui-tree-key="${child}"]`, '[data-ui-drop-root]');
    await expect.poll(() => wrapper.emitted('sorted')?.length).toBe(2);
    expect((await context.crud.view(child)).parentId).toBe('root');
  },
  30000,
);
