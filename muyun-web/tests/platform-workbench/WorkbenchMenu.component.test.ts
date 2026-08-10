import { describe, expect, it } from 'vitest';
import { mount, shallowMount } from '@vue/test-utils';
import WorkbenchBrandControl from '@/platform-workbench/WorkbenchBrandControl.vue';
import WorkbenchMenu from '@/platform-workbench/WorkbenchMenu.vue';
import WorkbenchMenuTree from '@/platform-workbench/WorkbenchMenuTree.vue';
import { createWorkbenchMenuNodes, findWorkbenchMenuNodeById } from '@/platform-workbench/menuTreeModel.ts';

const menus = [
  {
    record: { id: 'platform', schemeId: 'default', title: '平台管理' },
    children: [
      {
        record: {
          id: 'metadata',
          schemeId: 'default',
          title: '元数据管理',
          openMode: 'tab' as const,
          moduleAlias: 'platform.metadata',
        },
        children: [],
      },
    ],
  },
];

const nestedMenus = [
  {
    record: { id: 'platform', schemeId: 'default', title: '平台管理' },
    children: [
      {
        record: { id: 'configuration', schemeId: 'default', title: '平台配置' },
        children: [
          {
            record: {
              id: 'application',
              schemeId: 'default',
              title: '应用管理',
              openMode: 'tab' as const,
              moduleAlias: 'platform.application',
            },
            children: [],
          },
          {
            record: {
              id: 'metadata',
              schemeId: 'default',
              title: '元数据管理',
              openMode: 'tab' as const,
              moduleAlias: 'platform.metadata',
            },
            children: [
              {
                record: {
                  id: 'field-spec',
                  schemeId: 'default',
                  title: '字段规格',
                  openMode: 'tab' as const,
                  moduleAlias: 'platform.field_spec',
                },
                children: [],
              },
            ],
          },
          {
            record: {
              id: 'disabled-entry',
              schemeId: 'default',
              title: '停用入口',
              openMode: 'tab' as const,
              moduleAlias: 'platform.disabled',
              enabled: false,
            },
            children: [],
          },
        ],
      },
    ],
  },
];

describe('WorkbenchMenu', () => {
  it('keeps the compact menu panel hidden until the tab-bar launcher opens it', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus, presentation: 'compact', compactOpen: false },
    });

    expect(wrapper.find('#workbench-compact-menu').exists()).toBe(false);

    await wrapper.setProps({ compactOpen: true });

    expect(wrapper.find('#workbench-compact-menu').exists()).toBe(true);
    expect(wrapper.find('.compact-menu-tools').exists()).toBe(true);
    expect(wrapper.find('.sidebar-footer').exists()).toBe(false);
    expect(wrapper.text()).toContain('平台管理');
    expect(wrapper.get('.root-menu').element.nextElementSibling).toBe(
      wrapper.get('.compact-menu-tools').element,
    );
    expect(wrapper.get('.workbench-menu').attributes('style')).toContain('--compact-menu-top: 54px');
  });

  it('uses the shell-provided top offset for compact presentation', () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus, presentation: 'compact', compactTop: 172 },
    });

    expect(wrapper.get('.workbench-menu').attributes('style')).toContain('--compact-menu-top: 172px');
  });

  it('keeps compact panel tools focused on menu search', () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus, presentation: 'compact', compactOpen: true },
    });

    expect(wrapper.get('.compact-menu-tools').get('[aria-label="搜索菜单"]').exists()).toBe(true);
    expect(wrapper.find('[aria-label="展开侧栏菜单"]').exists()).toBe(false);
  });

  it('requests an immediate compact-panel close when Escape is pressed', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus, presentation: 'compact', compactOpen: true },
    });

    await wrapper.trigger('keydown', { key: 'Escape' });

    expect(wrapper.emitted('compactMenuClose')).toHaveLength(1);
    expect(wrapper.emitted('compactMenuLeave')).toBeUndefined();
  });

  it('keeps the existing expanded sidebar available as the alternate presentation', () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus, presentation: 'expanded' },
    });

    expect(wrapper.find('.workbench-menu--expanded').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'WorkbenchBrandControl' }).props('presentation')).toBe('expanded');
  });

  it('renders nested sidebar levels only when selected for the expanded presentation', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded', expandedMenuDepth: 1 },
    });

    expect(wrapper.find('.sidebar-menu-level--2').exists()).toBe(false);

    await wrapper.setProps({ expandedMenuDepth: 3 });

    expect(wrapper.findAll('.sidebar-menu-level--2')).toHaveLength(1);
    expect(wrapper.findAll('.sidebar-menu-level--3')).toHaveLength(1);
    expect(wrapper.text()).toContain('应用管理');
  });

  it('marks the active mega-menu entry as the current page and its group as the selected path', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, selectedMenuId: 'application', presentation: 'expanded' },
    });

    await wrapper.get('.root-menu-item').trigger('mouseenter');

    expect(wrapper.get('.mega-group-title').classes()).toContain('selected-path');
    const currentEntry = wrapper.findAll('.mega-entry').find((entry) => entry.text() === '应用管理');
    expect(currentEntry?.classes()).toContain('selected');
    expect(currentEntry?.attributes('aria-current')).toBe('page');
  });

  it('keeps navigable descendants when search matches a non-navigable container', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded' },
    });

    wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', '平台配置');
    await wrapper.vm.$nextTick();
    await wrapper.get('.root-menu-item').trigger('mouseenter');

    expect(wrapper.findAll('.mega-entry').map((entry) => entry.text())).toEqual(['应用管理', '元数据管理']);
    expect(wrapper.text()).not.toContain('停用入口');
  });
});

describe('WorkbenchMenuTree', () => {
  it('marks the current deep leaf and keeps its ancestor as a weak selected path', () => {
    const nodes = createWorkbenchMenuNodes(nestedMenus);
    const metadata = findWorkbenchMenuNodeById(nodes, 'metadata');
    expect(metadata).toBeDefined();

    const wrapper = mount(WorkbenchMenuTree, {
      props: {
        node: metadata!,
        selectedMenuId: 'field-spec',
        selectedPathIds: ['platform', 'configuration', 'metadata', 'field-spec'],
      },
    });

    const buttons = wrapper.findAll('.deep-node-button');
    expect(buttons[0].classes()).toContain('selected-path');
    expect(buttons[0].attributes('aria-current')).toBeUndefined();
    expect(buttons[1].classes()).toContain('selected');
    expect(buttons[1].attributes('aria-current')).toBe('page');
  });
});

describe('WorkbenchBrandControl', () => {
  it('distinguishes pointer entry from deliberate compact-menu activation', async () => {
    const wrapper = mount(WorkbenchBrandControl, { props: { presentation: 'compact' } });
    const identity = wrapper.get('[aria-label="系统菜单"]');

    await identity.trigger('mouseenter');
    await identity.trigger('focus');
    await identity.trigger('click');

    expect(wrapper.emitted('openCompactMenu')).toEqual([['pointer'], ['focus'], ['click']]);
  });

  it('shows the expanded sidebar depth chooser beside the presentation toggle', async () => {
    const wrapper = mount(WorkbenchBrandControl, {
      props: { presentation: 'expanded', expandedMenuDepth: 1 },
    });

    expect(wrapper.findAll('.workbench-menu-depth-option')).toHaveLength(3);
    expect(wrapper.get('.workbench-menu-depth-option.selected').text()).toBe('1');

    await wrapper.get('.workbench-menu-depth-option:nth-child(3)').trigger('click');

    expect(wrapper.emitted('changeExpandedMenuDepth')).toEqual([[3]]);
  });
});
