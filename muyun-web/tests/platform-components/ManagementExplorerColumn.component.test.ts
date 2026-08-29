import { mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ManagementExplorerColumn from '@/platform-components/ManagementExplorerColumn.vue';
import ManagementWorkspace from '@/platform-components/ManagementWorkspace.vue';
import RecordExplorerPanel from '@/platform-components/RecordExplorerPanel.vue';
import {
  collapsedExplorerTabHeight,
  MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT,
} from '@/platform-components/managementWorkspaceLayout.ts';

const WorkspaceHarness = defineComponent({
  components: { ManagementExplorerColumn, ManagementWorkspace, RecordExplorerPanel },
  data: () => ({ tenantSelected: true, organizationSelected: false }),
  template: `
    <ManagementWorkspace :explorer-count="2" list-surface>
      <ManagementExplorerColumn collapsible title="租户" :has-selection="tenantSelected">
        <RecordExplorerPanel title="租户"><div>租户内容</div></RecordExplorerPanel>
      </ManagementExplorerColumn>
      <ManagementExplorerColumn collapsible title="机构树" :has-selection="organizationSelected">
        <RecordExplorerPanel title="机构树"><div>机构内容</div></RecordExplorerPanel>
      </ManagementExplorerColumn>
      <main>职员管理</main>
    </ManagementWorkspace>
  `,
});

describe('ManagementExplorerColumn', () => {
  afterEach(() => vi.useRealTimers());

  it('exposes the composer layout preset without changing its explorer registration contract', () => {
    const wrapper = mount(ManagementWorkspace, {
      props: { layout: 'composer', explorerCount: 2 },
      slots: { default: '<aside>元数据</aside><aside>UI Tree</aside><main>预览</main>' },
    });

    expect(wrapper.classes()).toContain('management-workspace--composer');
    expect(wrapper.find('.management-workspace__grid').attributes('style')).toContain(
      '--muyun-management-explorer-count: 2',
    );
  });

  it('releases a collapsed micro list into the left rail and restores it from its dedicated expand action', async () => {
    vi.useFakeTimers();
    const wrapper = mount(WorkspaceHarness);
    const collapse = wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((button) => button.props('title') === '收起租户');

    expect(collapse).toBeDefined();
    await collapse!.trigger('click');
    expect(wrapper.find('.management-explorer-column--collapsing').exists()).toBe(true);
    expect(wrapper.find('.management-explorer-column-tab').exists()).toBe(true);
    vi.advanceTimersByTime(48);
    await nextTick();
    expect(wrapper.find('.management-explorer-column--collapse-leaving').exists()).toBe(true);
    vi.advanceTimersByTime(240);
    await nextTick();

    const workspace = wrapper.findComponent(ManagementWorkspace);
    expect(workspace.classes()).toContain('management-workspace--with-collapsed-explorer');
    expect(workspace.find('.management-workspace__grid').attributes('style')).toContain(
      '--muyun-management-explorer-count: 1',
    );
    expect(wrapper.find('.management-explorer-column-tab').classes()).toContain(
      'management-explorer-column-tab--selected',
    );
    await wrapper.find('.management-explorer-column--collapsed').trigger('pointerenter');
    expect(wrapper.find('.management-explorer-column--preview-open').exists()).toBe(true);
    await wrapper.find('.management-explorer-column-tab').trigger('click');
    expect(wrapper.find('.management-explorer-column-tab').exists()).toBe(true);
    const expand = wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((button) => button.props('title') === '展开租户');
    expect(expand).toBeDefined();
    await expand!.trigger('click');
    expect(wrapper.find('.management-explorer-column-tab').exists()).toBe(false);
    expect(wrapper.find('.management-explorer-column--preview-open').exists()).toBe(false);
    expect(workspace.classes()).not.toContain('management-workspace--with-collapsed-explorer');
    expect(wrapper.find('.management-explorer-column').attributes('style')).toContain(
      '--muyun-management-tab-transition-width: 36px',
    );
    expect(wrapper.find('.management-explorer-column--expanding').exists()).toBe(true);
    vi.advanceTimersByTime(48);
    await nextTick();
    expect(wrapper.find('.management-explorer-column--expanding').exists()).toBe(false);

    const collapseAgain = wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((button) => button.props('title') === '收起租户');
    await collapseAgain!.trigger('click');
    vi.advanceTimersByTime(288);
    await nextTick();
    expect(wrapper.find('.management-explorer-column--preview-open').exists()).toBe(false);

    const collapseOrganization = wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((button) => button.props('title') === '收起机构树');
    await collapseOrganization!.trigger('click');
    vi.advanceTimersByTime(288);
    await nextTick();
    const collapsedColumns = wrapper.findAll('.management-explorer-column--collapsed');
    expect(collapsedColumns).toHaveLength(2);
    expect(collapsedColumns[1]?.attributes('style')).toContain(
      `--muyun-management-collapsed-offset: ${collapsedExplorerTabHeight('租户') + MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.tabStackGap}px`,
    );
  });

  it('keeps the hover preview open while moving from its tab into the panel', async () => {
    vi.useFakeTimers();
    const wrapper = mount(WorkspaceHarness);
    const collapse = wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((button) => button.props('title') === '收起租户');
    await collapse!.trigger('click');
    vi.advanceTimersByTime(288);
    await nextTick();

    const column = wrapper.find('.management-explorer-column--collapsed');
    const tab = wrapper.find('.management-explorer-column-tab-trigger');
    await column.trigger('pointerenter');
    expect(wrapper.find('.management-explorer-column--preview-open').exists()).toBe(true);

    // The transparent bridge belongs to the same hover region, so a slow move
    // across the rail gap must not schedule a close.
    await tab.trigger('pointerleave');
    vi.advanceTimersByTime(160);
    await nextTick();

    expect(wrapper.find('.management-explorer-column--preview-open').exists()).toBe(true);

    await column.trigger('pointerleave');
    vi.advanceTimersByTime(160);
    await nextTick();
    expect(wrapper.find('.management-explorer-column--preview-open').exists()).toBe(false);
  });
});
