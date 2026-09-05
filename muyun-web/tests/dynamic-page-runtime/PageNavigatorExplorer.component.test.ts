import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import PageNavigatorExplorer from '@/dynamic-page-runtime/PageNavigatorExplorer.vue';
import type { NavigatorLevelRuntime } from '@/dynamic-page-runtime/composables/useNavigatorRuntime';

function mountNavigator(tree = true, ready = true) {
  const canUseAsParent = (record: Readonly<Record<string, unknown>>) => record.kind === 'folder';
  const level = {
    descriptor: {
      key: 'category',
      kind: tree ? 'TREE' : 'MICRO_LIST',
      sourceModuleAlias: 'test.category',
      title: '类目',
      searchPlaceholder: '搜索类目',
    },
    context: { runtime: { snapshot: () => ({ sortPartitionFields: ['applicationAlias'] }) } },
    tree,
    sort: { visible: true, enabled: true },
  } as NavigatorLevelRuntime;
  const wrapper = shallowMount(PageNavigatorExplorer, {
    props: {
      level,
      ready,
      reloadKey: 0,
      keyword: '',
      navigatorHostModuleAlias: 'test.item',
      externalQueryValues: { applicationAlias: 'test' },
      sort: { visible: true, enabled: true, active: true },
      treeParentPolicy: { canUseAsParent, rejectionMessage: '请选择目录' },
    },
    global: {
      stubs: { RecordExplorerPanel: { template: '<div><slot name="actions" /><slot /></div>' } },
    },
  });
  return { wrapper, canUseAsParent };
}

describe('PageNavigatorExplorer', () => {
  it('passes the same navigation scope and parent admission to the standard tree', () => {
    const { wrapper, canUseAsParent } = mountNavigator();
    const tree = wrapper.findComponent({ name: 'TreeRecordExplorer' });
    expect(tree.props()).toMatchObject({
      sorting: true,
      sortPartitionFields: ['applicationAlias'],
      navigatorHostModuleAlias: 'test.item',
      navigatorTargetLevelKey: 'category',
      externalQueryValues: { applicationAlias: 'test' },
      canDropInside: canUseAsParent,
    });
    tree.vm.$emit('select', { id: 'folder' });
    expect(wrapper.emitted('select')).toEqual([[{ id: 'folder' }]]);
    wrapper.findComponent({ name: 'NavigatorPanelActions' }).vm.$emit('toggle-sorting');
    expect(wrapper.emitted('toggle-sorting')).toHaveLength(1);
  });

  it('does not instantiate a resource before its upstream scope is ready', () => {
    const { wrapper } = mountNavigator(true, false);
    expect(wrapper.findComponent({ name: 'TreeRecordExplorer' }).exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'CrudRecordListExplorer' }).exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordPanelState' }).props('description')).toBe('请先选择导航范围');
  });

  it('keeps an unmanaged list free of record management actions', () => {
    const { wrapper } = mountNavigator(false);
    expect(wrapper.findComponent({ name: 'CrudRecordListExplorer' }).props('actionsOf')).toBeUndefined();
    expect(wrapper.findComponent({ name: 'NavigatorPanelActions' }).props('createAvailable')).toBe(false);
  });
});
