import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { config, mount, shallowMount } from '@vue/test-utils';
import Workbench from '@/platform-workbench/Workbench.vue';
import WorkbenchBrandControl from '@/platform-workbench/WorkbenchBrandControl.vue';
import WorkbenchMenu from '@/platform-workbench/WorkbenchMenu.vue';
import WorkbenchMenuTree from '@/platform-workbench/WorkbenchMenuTree.vue';
import { createWorkbenchMenuNodes, findWorkbenchMenuNodeById } from '@/platform-workbench/menuTreeModel.ts';
import { userPreferences } from '@/web-core';

config.global.stubs = { ...config.global.stubs, WorkbenchSidebarMenuEntry: false };

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  window.localStorage.clear();
});

function stubHoverCapability(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockReturnValue({ matches, addEventListener: vi.fn(), removeEventListener: vi.fn() }),
  );
}

const menus = [
  {
    record: { id: 'platform', schemeId: 'default', title: '平台管理' },
    children: [
      {
        record: {
          id: 'metadata',
          schemeId: 'default',
          title: '元数据管理',
          entryType: 'module' as const,
          openMode: 'tab' as const,
          moduleAlias: 'platform.metadata',
        },
        children: [],
      },
    ],
  },
];

const mixedRootMenus = [
  ...menus,
  {
    record: {
      id: 'runtime',
      schemeId: 'default',
      title: '动态运行态',
      entryType: 'module' as const,
      openMode: 'tab' as const,
      moduleAlias: 'platform.runtime',
    },
    children: [],
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
              entryType: 'module' as const,
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
              entryType: 'module' as const,
              openMode: 'tab' as const,
              moduleAlias: 'platform.metadata',
            },
            children: [
              {
                record: {
                  id: 'field-spec',
                  schemeId: 'default',
                  title: '字段规格',
                  entryType: 'module' as const,
                  openMode: 'tab' as const,
                  moduleAlias: 'platform.field_spec',
                },
                children: [
                  {
                    record: {
                      id: 'field-validation',
                      schemeId: 'default',
                      title: '字段校验规则',
                      entryType: 'module' as const,
                      openMode: 'tab' as const,
                      moduleAlias: 'platform.application',
                    },
                    children: [],
                  },
                ],
              },
            ],
          },
          {
            record: {
              id: 'disabled-entry',
              schemeId: 'default',
              title: '停用入口',
              entryType: 'module' as const,
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

const navigableSecondLevelMenus = [
  {
    ...nestedMenus[0],
    children: [
      {
        ...nestedMenus[0].children[0],
        record: {
          ...nestedMenus[0].children[0].record,
          entryType: 'module' as const,
          openMode: 'tab' as const,
          moduleAlias: 'platform.configuration',
        },
      },
    ],
  },
];

const navigableRootBranchMenus = [
  {
    ...nestedMenus[0],
    record: {
      ...nestedMenus[0].record,
      entryType: 'module' as const,
      openMode: 'tab' as const,
      moduleAlias: 'platform.root',
    },
  },
];

const siblingRootMenus = [
  nestedMenus[0],
  {
    ...nestedMenus[0],
    record: { id: 'operations', schemeId: 'default', title: '运营管理' },
  },
];

const sidebarAimMenus = [
  {
    record: { id: 'root', schemeId: 'default', title: '根菜单' },
    children: [
      {
        record: { id: 'group-a', schemeId: 'default', title: '分组 A' },
        children: [
          {
            record: { id: 'entry-a1', schemeId: 'default', title: '入口 A1' },
            children: [
              {
                record: { id: 'leaf-a1', schemeId: 'default', title: '叶子 A1' },
                children: [],
              },
            ],
          },
          {
            record: { id: 'entry-a2', schemeId: 'default', title: '入口 A2' },
            children: [
              {
                record: { id: 'leaf-a2', schemeId: 'default', title: '叶子 A2' },
                children: [],
              },
            ],
          },
        ],
      },
      {
        record: { id: 'group-b', schemeId: 'default', title: '分组 B' },
        children: [
          {
            record: { id: 'entry-b1', schemeId: 'default', title: '入口 B1' },
            children: [
              {
                record: { id: 'leaf-b1', schemeId: 'default', title: '叶子 B1' },
                children: [],
              },
            ],
          },
        ],
      },
    ],
  },
];

function pointerMoveEvent(clientX: number, clientY: number): Event {
  const event = new Event('pointermove');
  Object.defineProperties(event, {
    clientX: { value: clientX },
    clientY: { value: clientY },
  });
  return event;
}

function mouseEnterEvent(clientX: number, clientY: number): MouseEvent {
  return new MouseEvent('mouseenter', { clientX, clientY });
}

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

    expect(wrapper.get('.compact-menu-tools').find('[aria-label="搜索菜单"]').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'UiInput' }).props('placeholder')).toBe('');
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
      props: { menus, presentation: 'expanded', logoSrc: 'data:image/png;base64,bG9nbw==' },
    });

    expect(wrapper.find('.workbench-menu--expanded').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'WorkbenchBrandControl' }).props('presentation')).toBe('expanded');
    expect(wrapper.findComponent({ name: 'WorkbenchBrandControl' }).props('logoSrc')).toBe(
      'data:image/png;base64,bG9nbw==',
    );
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
    expect(wrapper.get('.root-menu-item').attributes('aria-expanded')).toBeUndefined();
    expect(wrapper.get('.root-menu-item').attributes('aria-controls')).toBeUndefined();
  });

  it('shows only the next level when a second-level entry opens a flyout', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded', expandedMenuDepth: 2 },
    });

    expect(wrapper.find('.sidebar-menu-level--3').exists()).toBe(false);

    await wrapper
      .findAll('.sidebar-menu-entry')
      .find((entry) => entry.text() === '平台配置')
      ?.trigger('mouseenter');

    expect(wrapper.get('.sidebar-submenu-panel').attributes('aria-label')).toBe('下级菜单');
    expect(wrapper.find('.sidebar-submenu-panel header').exists()).toBe(false);
    expect(wrapper.find('.sidebar-submenu-outline--shadow').exists()).toBe(true);
    expect(wrapper.find('.sidebar-submenu-outline--stroke path').attributes('d')).toBeTruthy();
    expect(
      wrapper
        .findAll('.sidebar-menu-entry')
        .find((entry) => entry.text() === '平台配置')
        ?.find('.sidebar-menu-entry-indicator')
        .exists(),
    ).toBe(true);
  });

  it('shows only the next level when a third-level branch opens a flyout', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded', expandedMenuDepth: 3 },
    });

    await wrapper
      .findAll('.sidebar-menu-entry')
      .find((entry) => entry.text() === '元数据管理')
      ?.trigger('mouseenter');

    expect(wrapper.find('.sidebar-submenu-panel header').exists()).toBe(false);
  });

  it('closes an open flyout when the sidebar display depth changes', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded', expandedMenuDepth: 2 },
    });

    await wrapper
      .findAll('.sidebar-menu-entry')
      .find((entry) => entry.text() === '平台配置')
      ?.trigger('mouseenter');
    expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(true);

    await wrapper.setProps({ expandedMenuDepth: 3 });

    expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(false);
  });

  it('closes open navigation layers when responsive layout changes the presentation', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded', expandedMenuDepth: 1 },
    });

    await wrapper.get('.root-menu-item').trigger('mouseenter');
    expect(wrapper.find('.mega-panel').exists()).toBe(true);
    expect(wrapper.find('.mega-outline--shadow').exists()).toBe(true);
    expect(wrapper.find('.mega-outline--stroke path').attributes('d')).toBeTruthy();

    await wrapper.setProps({ presentation: 'compact', compactOpen: false });

    expect(wrapper.find('.mega-panel').exists()).toBe(false);
    expect(wrapper.find('.mega-outline--shadow').exists()).toBe(false);
  });

  it('opens Mega only for root branches and keeps navigable root leaves direct', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: mixedRootMenus, presentation: 'compact', compactOpen: true },
    });
    const [branch, leaf] = wrapper.findAll('.root-menu-item');

    expect(branch.classes()).toContain('branch');
    expect(branch.find('.root-menu-branch-indicator').exists()).toBe(true);
    expect(branch.attributes('aria-expanded')).toBe('false');
    expect(leaf.classes()).not.toContain('branch');
    expect(leaf.find('.root-menu-branch-indicator').exists()).toBe(false);
    expect(leaf.attributes('aria-expanded')).toBeUndefined();

    await branch.trigger('mouseenter');

    expect(branch.attributes('aria-expanded')).toBe('true');
    expect(wrapper.find('.mega-panel').exists()).toBe(true);
    expect(wrapper.get('.mega-group-title').text()).toBe('元数据管理');

    await leaf.trigger('mouseenter');

    expect(leaf.classes()).not.toContain('active');
    expect(leaf.attributes('aria-controls')).toBeUndefined();
    expect(wrapper.find('.mega-panel').exists()).toBe(false);

    await leaf.trigger('click');

    expect(wrapper.emitted('selectMenu')?.[0]?.[0]).toMatchObject({ id: 'runtime' });
  });

  it('keeps compact navigation content above its single sidebar panel', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: mixedRootMenus, presentation: 'compact', compactOpen: true },
    });
    const branch = wrapper.get('.root-menu-item');
    (branch.element as HTMLElement).getBoundingClientRect = () =>
      ({ left: 8, right: 176, top: 64, bottom: 98, width: 168, height: 34 }) as DOMRect;

    await branch.trigger('mouseenter');

    expect(wrapper.get('.mega-panel').text()).toContain('元数据管理');
    expect(wrapper.find('.compact-mega-outline').exists()).toBe(false);
    expect(wrapper.get('.workbench-menu').classes()).toContain('mega-open');
  });

  it('keeps the compact Mega panel mounted while the shared hover session is closing', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: mixedRootMenus, presentation: 'compact', compactOpen: true },
    });
    const branch = wrapper.get('.root-menu-item');

    await branch.trigger('mouseenter');
    await wrapper.trigger('mouseleave', { clientX: 1000, clientY: 100 });

    expect(wrapper.find('.mega-panel').exists()).toBe(true);
    expect(wrapper.emitted('compactMenuLeave')).toHaveLength(1);
  });

  it('separates root navigation from child expansion without relying on hover', async () => {
    stubHoverCapability(false);
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: navigableRootBranchMenus, presentation: 'compact', compactOpen: true },
    });
    const entry = wrapper.get('.root-menu-item--split');
    const main = entry.get('.root-menu-item-main');
    const trigger = entry.get('.root-menu-item-trigger');

    expect(trigger.attributes('aria-expanded')).toBe('false');
    await entry.trigger('mouseenter');
    expect(wrapper.find('.mega-panel').exists()).toBe(false);

    await trigger.trigger('click');

    expect(trigger.attributes('aria-expanded')).toBe('true');
    expect(wrapper.find('.mega-panel').exists()).toBe(true);
    expect(wrapper.emitted('selectMenu')).toBeUndefined();

    await main.trigger('click');

    expect(wrapper.emitted('selectMenu')?.[0]?.[0]).toMatchObject({ id: 'platform' });
    expect(wrapper.find('.mega-panel').exists()).toBe(false);
  });

  it('keeps the root branch open when its first desktop click immediately follows hover', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-12T00:00:00Z'));
    stubHoverCapability(true);
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: navigableRootBranchMenus, presentation: 'compact', compactOpen: true },
    });
    const entry = wrapper.get('.root-menu-item--split');
    const trigger = entry.get('.root-menu-item-trigger');

    await entry.trigger('mouseenter');
    expect(wrapper.find('.mega-panel').exists()).toBe(true);

    await trigger.trigger('click');
    expect(wrapper.find('.mega-panel').exists()).toBe(true);

    vi.advanceTimersByTime(241);
    await trigger.trigger('click');
    expect(wrapper.find('.mega-panel').exists()).toBe(false);
  });

  it('keeps the Mega panel open while the pointer follows the diagonal safe corridor', async () => {
    vi.useFakeTimers();
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded' },
    });

    await wrapper.get('.root-menu-item').trigger('mouseenter');
    const megaPanel = wrapper.get('.mega-panel').element as HTMLElement;
    megaPanel.getBoundingClientRect = () =>
      ({ left: 180, right: 760, top: 80, bottom: 260, width: 580, height: 180 }) as DOMRect;

    await wrapper.get('.workbench-menu').trigger('mouseleave', { clientX: 160, clientY: 100 });
    window.dispatchEvent(pointerMoveEvent(170, 140));
    await wrapper.vm.$nextTick();

    expect(wrapper.find('.mega-panel').exists()).toBe(true);

    window.dispatchEvent(pointerMoveEvent(150, 140));
    await wrapper.vm.$nextTick();

    expect(wrapper.find('.mega-panel').exists()).toBe(true);

    window.dispatchEvent(pointerMoveEvent(150, 140));
    await wrapper.vm.$nextTick();

    expect(wrapper.find('.mega-panel').exists()).toBe(false);
  });

  it('extends Mega pointer aim while the pointer keeps moving towards the panel', async () => {
    vi.useFakeTimers();
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded' },
    });

    await wrapper.get('.root-menu-item').trigger('mouseenter');
    const megaPanel = wrapper.get('.mega-panel').element as HTMLElement;
    megaPanel.getBoundingClientRect = () =>
      ({ left: 180, right: 760, top: 80, bottom: 260, width: 580, height: 180 }) as DOMRect;

    await wrapper.get('.workbench-menu').trigger('mouseleave', { clientX: 160, clientY: 100 });
    vi.advanceTimersByTime(360);
    window.dispatchEvent(pointerMoveEvent(170, 140));
    vi.advanceTimersByTime(360);
    await wrapper.vm.$nextTick();

    expect(wrapper.find('.mega-panel').exists()).toBe(true);

    vi.advanceTimersByTime(141);
    await wrapper.vm.$nextTick();

    expect(wrapper.find('.mega-panel').exists()).toBe(false);
  });

  it('delays sibling root hover while the pointer is heading diagonally into the active Mega panel', async () => {
    vi.useFakeTimers();
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: siblingRootMenus, presentation: 'expanded' },
    });
    const rootItems = wrapper.findAll('.root-menu-item');

    await rootItems[0].trigger('mouseenter');
    const megaPanel = wrapper.get('.mega-panel');
    megaPanel.element.getBoundingClientRect = () =>
      ({ left: 180, right: 760, top: 80, bottom: 260, width: 580, height: 180 }) as DOMRect;

    wrapper.get('.workbench-menu').element.dispatchEvent(pointerMoveEvent(150, 100));
    rootItems[1].element.dispatchEvent(mouseEnterEvent(165, 140));
    await wrapper.vm.$nextTick();

    expect(wrapper.get('.root-menu-item.active').text()).toBe('平台管理');

    await megaPanel.trigger('mouseenter');
    vi.advanceTimersByTime(400);
    await wrapper.vm.$nextTick();

    expect(wrapper.get('.root-menu-item.active').text()).toBe('平台管理');

    wrapper.get('.workbench-menu').element.dispatchEvent(pointerMoveEvent(150, 100));
    rootItems[1].element.dispatchEvent(mouseEnterEvent(165, 140));
    await wrapper.vm.$nextTick();
    vi.advanceTimersByTime(320);
    await wrapper.vm.$nextTick();

    expect(wrapper.get('.root-menu-item.active').text()).toBe('运营管理');
  });

  it.each([
    {
      depth: 2 as const,
      selector: '.sidebar-menu-level--2 > .sidebar-menu-entry',
      activeTitle: '分组 A',
      siblingTitle: '分组 B',
    },
    {
      depth: 3 as const,
      selector: '.sidebar-menu-level--3 > .sidebar-menu-entry',
      activeTitle: '入口 A1',
      siblingTitle: '入口 A2',
    },
  ])(
    'protects the pointer path across sidebar siblings when $depth levels are expanded',
    async ({ depth, selector, activeTitle, siblingTitle }) => {
      vi.useFakeTimers();
      const wrapper = shallowMount(WorkbenchMenu, {
        props: { menus: sidebarAimMenus, presentation: 'expanded', expandedMenuDepth: depth },
      });
      const entries = wrapper.findAll(selector);
      const activeEntry = entries.find((entry) => entry.text() === activeTitle);
      const siblingEntry = entries.find((entry) => entry.text() === siblingTitle);

      await activeEntry?.trigger('mouseenter');
      const panel = wrapper.get('.sidebar-submenu-panel');
      panel.element.getBoundingClientRect = () =>
        ({ left: 180, right: 480, top: 80, bottom: 260, width: 300, height: 180 }) as DOMRect;

      wrapper.get('.workbench-menu').element.dispatchEvent(pointerMoveEvent(150, 100));
      siblingEntry?.element.dispatchEvent(mouseEnterEvent(165, 140));
      await wrapper.vm.$nextTick();

      expect(wrapper.get('.sidebar-menu-entry.active').text()).toBe(activeTitle);

      await panel.trigger('mouseenter');
      vi.advanceTimersByTime(400);
      await wrapper.vm.$nextTick();

      expect(wrapper.get('.sidebar-menu-entry.active').text()).toBe(activeTitle);

      wrapper.get('.workbench-menu').element.dispatchEvent(pointerMoveEvent(150, 100));
      siblingEntry?.element.dispatchEvent(mouseEnterEvent(165, 140));
      vi.advanceTimersByTime(320);
      await wrapper.vm.$nextTick();

      expect(wrapper.get('.sidebar-menu-entry.active').text()).toBe(siblingTitle);
    },
  );

  it('keeps a second-level flyout open when the diagonal path crosses a root row', async () => {
    vi.useFakeTimers();
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: sidebarAimMenus, presentation: 'expanded', expandedMenuDepth: 2 },
    });
    const group = wrapper
      .findAll('.sidebar-menu-level--2 > .sidebar-menu-entry')
      .find((entry) => entry.text() === '分组 A');

    await group?.trigger('mouseenter');
    const panel = wrapper.get('.sidebar-submenu-panel');
    panel.element.getBoundingClientRect = () =>
      ({ left: 180, right: 480, top: 80, bottom: 260, width: 300, height: 180 }) as DOMRect;

    wrapper.get('.workbench-menu').element.dispatchEvent(pointerMoveEvent(150, 100));
    wrapper.get('.root-menu-item').element.dispatchEvent(mouseEnterEvent(165, 140));
    await wrapper.vm.$nextTick();

    expect(wrapper.get('.sidebar-menu-entry.active').text()).toBe('分组 A');

    await panel.trigger('mouseenter');
    vi.advanceTimersByTime(400);
    await wrapper.vm.$nextTick();

    expect(wrapper.get('.sidebar-menu-entry.active').text()).toBe('分组 A');
  });

  it('keeps the hovered second-level ancestor visually active with its flyout', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: {
        menus: nestedMenus,
        selectedMenuId: 'application',
        presentation: 'expanded',
        expandedMenuDepth: 2,
      },
    });
    const group = wrapper
      .findAll('.sidebar-menu-level--2 > .sidebar-menu-entry')
      .find((entry) => entry.text() === '平台配置');

    await group?.trigger('mouseenter');

    expect(group?.classes()).toEqual(expect.arrayContaining(['active', 'selected-path']));
    expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(true);
  });

  it('switches sidebar flyouts immediately on a deliberate click', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: sidebarAimMenus, presentation: 'expanded', expandedMenuDepth: 2 },
    });
    const groups = wrapper.findAll('.sidebar-menu-level--2 > .sidebar-menu-entry');

    await groups[0].trigger('mouseenter');
    const panel = wrapper.get('.sidebar-submenu-panel');
    panel.element.getBoundingClientRect = () =>
      ({ left: 180, right: 480, top: 80, bottom: 260, width: 300, height: 180 }) as DOMRect;
    wrapper.get('.workbench-menu').element.dispatchEvent(pointerMoveEvent(150, 100));

    await groups[1].trigger('click', { clientX: 165, clientY: 140 });

    expect(wrapper.get('.sidebar-menu-entry.active').text()).toBe('分组 B');
  });

  it('does not delay a sibling switch after the pointer has turned away from the active panel', async () => {
    vi.useFakeTimers();
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: siblingRootMenus, presentation: 'expanded' },
    });
    const rootItems = wrapper.findAll('.root-menu-item');

    await rootItems[0].trigger('mouseenter');
    const megaPanel = wrapper.get('.mega-panel');
    megaPanel.element.getBoundingClientRect = () =>
      ({ left: 180, right: 760, top: 80, bottom: 260, width: 580, height: 180 }) as DOMRect;

    wrapper.get('.workbench-menu').element.dispatchEvent(pointerMoveEvent(150, 100));
    wrapper.get('.workbench-menu').element.dispatchEvent(pointerMoveEvent(170, 120));
    rootItems[1].element.dispatchEvent(mouseEnterEvent(165, 140));
    await wrapper.vm.$nextTick();

    expect(wrapper.get('.root-menu-item.active').text()).toBe('运营管理');
  });

  it.each([
    { depth: 2 as const, menus: navigableSecondLevelMenus, title: '平台配置' },
    { depth: 3 as const, menus: nestedMenus, title: '元数据管理' },
  ])(
    'splits module navigation from child expansion at sidebar depth $depth',
    async ({ depth, menus, title }) => {
      const wrapper = shallowMount(WorkbenchMenu, {
        props: { menus, presentation: 'expanded', expandedMenuDepth: depth },
      });
      const entry = wrapper
        .findAll('.sidebar-menu-entry--split')
        .find((candidate) => candidate.text() === title);
      const main = entry?.get('.sidebar-menu-entry-main');
      const trigger = entry?.get('.sidebar-menu-entry-trigger');

      expect(trigger?.attributes('aria-expanded')).toBe('false');
      await trigger?.trigger('click');

      expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(true);
      expect(trigger?.attributes('aria-expanded')).toBe('true');
      expect(wrapper.emitted('selectMenu')).toBeUndefined();

      await trigger?.trigger('click');
      expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(false);

      await main?.trigger('click');
      expect(wrapper.emitted('selectMenu')?.[0]?.[0]).toMatchObject({ title });
    },
  );

  it.each([
    { depth: 2 as const, menus: navigableSecondLevelMenus, title: '平台配置' },
    { depth: 3 as const, menus: nestedMenus, title: '元数据管理' },
  ])(
    'keeps the sidebar branch open when its first desktop click immediately follows hover at depth $depth',
    async ({ depth, menus, title }) => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date('2026-08-12T00:00:00Z'));
      stubHoverCapability(true);
      const wrapper = shallowMount(WorkbenchMenu, {
        props: { menus, presentation: 'expanded', expandedMenuDepth: depth },
      });
      const entry = wrapper
        .findAll('.sidebar-menu-entry--split')
        .find((candidate) => candidate.text() === title);
      const trigger = entry?.get('.sidebar-menu-entry-trigger');

      await entry?.trigger('mouseenter');
      expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(true);

      await trigger?.trigger('click');
      expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(true);

      vi.advanceTimersByTime(241);
      await trigger?.trigger('click');
      expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(false);
    },
  );

  it('ignores synthesized sidebar hover on touch-only input and opens from the explicit trigger', async () => {
    stubHoverCapability(false);
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: navigableSecondLevelMenus, presentation: 'expanded', expandedMenuDepth: 2 },
    });
    const entry = wrapper
      .findAll('.sidebar-menu-entry--split')
      .find((candidate) => candidate.text() === '平台配置');
    const trigger = entry?.get('.sidebar-menu-entry-trigger');

    await entry?.trigger('mouseenter');
    expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(false);

    await trigger?.trigger('click');
    expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(true);
  });

  it('keeps second-level entries structural rather than clickable when the sidebar shows three levels', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded', expandedMenuDepth: 3 },
    });
    const group = wrapper.get('.sidebar-menu-entry--group');

    expect(group.element.tagName).toBe('DIV');

    await group.trigger('click');

    expect(wrapper.emitted('selectMenu')).toBeUndefined();
  });

  it('keeps a module-backed second-level entry clickable without opening a flyout at three levels', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: navigableSecondLevelMenus, presentation: 'expanded', expandedMenuDepth: 3 },
    });
    const group = wrapper.get('.sidebar-menu-level--2 .sidebar-menu-entry');

    expect(group.element.tagName).toBe('BUTTON');

    await group.trigger('click');

    expect(wrapper.emitted('selectMenu')).toHaveLength(1);
    expect(wrapper.find('.sidebar-submenu-panel').exists()).toBe(false);
  });

  it('marks the active mega-menu entry as the current page and its group as the selected path', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, selectedMenuId: 'application', presentation: 'expanded' },
    });

    await wrapper.get('.root-menu-item').trigger('mouseenter');

    expect(wrapper.get('.mega-group-title').classes()).toContain('selected-path');
    const currentEntry = wrapper.findAll('.mega-entry').find((entry) => entry.text() === '应用管理');
    expect(currentEntry?.classes()).toContain('selected');
    expect(currentEntry?.find('.mega-entry-main').attributes('aria-current')).toBe('page');
  });

  it('distinguishes the current entry from its lighter ancestor path in the sidebar', () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, selectedMenuId: 'application', presentation: 'expanded' },
    });

    const root = wrapper.get('.root-menu-item');

    expect(root.classes()).toContain('selected-path');
    expect(root.classes()).not.toContain('selected');
  });

  it('uses a separate deep trigger when a branch can also navigate', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded' },
    });

    await wrapper.get('.root-menu-item').trigger('mouseenter');

    const metadataEntry = wrapper.findAll('.mega-entry').find((entry) => entry.text() === '元数据管理');
    const trigger = metadataEntry?.get('.mega-entry-trigger');
    expect(wrapper.find('.mega-deep-panel').exists()).toBe(false);
    expect(trigger?.attributes('aria-expanded')).toBe('false');

    await metadataEntry?.trigger('mouseenter');

    expect(wrapper.find('.mega-deep-panel').exists()).toBe(false);

    await trigger?.trigger('click');

    expect(metadataEntry?.classes()).toContain('active');
    expect(trigger?.attributes('aria-expanded')).toBe('true');
    expect(wrapper.find('.mega-deep-panel header').exists()).toBe(false);
    expect(wrapper.get('.mega-deep-tree').attributes('aria-label')).toBe('元数据管理下级菜单');
    expect(wrapper.getComponent(WorkbenchMenuTree).props('node').record.id).toBe('field-spec');
    expect(wrapper.get('.mega-panel').element.contains(wrapper.get('.mega-deep-panel').element)).toBe(true);

    await trigger?.trigger('keydown', { key: 'Escape' });

    expect(wrapper.find('.mega-deep-panel').exists()).toBe(false);

    await trigger?.trigger('keydown', { key: 'ArrowRight' });
    expect(wrapper.find('.mega-deep-panel').exists()).toBe(true);

    await trigger?.trigger('keydown', { key: 'ArrowLeft' });
    expect(wrapper.find('.mega-deep-panel').exists()).toBe(false);
  });

  it('keeps the Mega panel available while a deep dock opens and closes', async () => {
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: nestedMenus, presentation: 'expanded' },
    });

    await wrapper.get('.root-menu-item').trigger('mouseenter');
    const panel = wrapper.get('.mega-panel');
    const trigger = wrapper
      .findAll('.mega-entry')
      .find((entry) => entry.text() === '元数据管理')
      ?.get('.mega-entry-trigger');
    const deepTransition = wrapper
      .findAllComponents({ name: 'Transition' })
      .find((transition) => transition.props('name') === 'mega-deep-dock');
    if (!trigger || !deepTransition) {
      throw new Error('Expected the navigable deep menu trigger and its transition.');
    }

    await trigger.trigger('click');
    deepTransition.vm.$emit('after-enter');
    await wrapper.vm.$nextTick();
    expect(wrapper.find('.mega-panel').exists()).toBe(true);

    await trigger.trigger('click');
    deepTransition.vm.$emit('after-leave');
    await wrapper.vm.$nextTick();

    expect(wrapper.element.contains(panel.element)).toBe(true);
  });

  it('uses the whole row to toggle a structural branch without a navigation target', async () => {
    const structuralMenus = [
      {
        record: { id: 'root', schemeId: 'default', title: '根菜单' },
        children: [
          {
            record: { id: 'group', schemeId: 'default', title: '菜单分组' },
            children: [
              {
                record: { id: 'branch', schemeId: 'default', title: '结构分支' },
                children: [
                  {
                    record: {
                      id: 'leaf',
                      schemeId: 'default',
                      title: '叶子菜单',
                      openMode: 'tab' as const,
                      moduleAlias: 'platform.leaf',
                    },
                    children: [],
                  },
                ],
              },
            ],
          },
        ],
      },
    ];
    const wrapper = shallowMount(WorkbenchMenu, {
      props: { menus: structuralMenus, presentation: 'expanded' },
    });

    await wrapper.get('.root-menu-item').trigger('mouseenter');

    const entry = wrapper.get('.mega-entry');
    const main = entry.get('.mega-entry-main');
    expect(entry.find('.mega-entry-trigger').exists()).toBe(false);
    expect(main.attributes('disabled')).toBeUndefined();
    expect(main.attributes('aria-expanded')).toBe('false');

    await main.trigger('click');

    expect(entry.classes()).toContain('active');
    expect(main.attributes('aria-expanded')).toBe('true');
    expect(wrapper.get('.mega-deep-tree').attributes('aria-label')).toBe('结构分支下级菜单');
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

describe('Workbench compact menu', () => {
  it('does not expose the deferred notification entry in the global toolbar', () => {
    const wrapper = shallowMount(Workbench);

    expect(wrapper.find('[aria-label="搜索"]').exists()).toBe(false);
    expect(wrapper.find('[aria-label="通知"]').exists()).toBe(false);
    expect(wrapper.find('[aria-label="刷新当前页"]').exists()).toBe(true);
    expect(wrapper.find('[aria-label="皮肤切换"]').exists()).toBe(true);
    expect(wrapper.find('[aria-label="设置"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'UiDropdown' }).props('items')).not.toContainEqual(
      expect.objectContaining({ key: 'settings' }),
    );
  });

  it('keeps skin and user actions within the same topbar action region', () => {
    const wrapper = shallowMount(Workbench);
    const actions = wrapper.get('.topbar-actions');

    expect(actions.find('[aria-label="皮肤切换"]').exists()).toBe(true);
    expect(actions.findComponent({ name: 'UiDropdown' }).exists()).toBe(true);
  });

  it('keeps the compact topbar on one line and compresses secondary identity text only on phone widths', () => {
    const source = readFileSync(
      resolve(import.meta.dirname, '../../src/platform-workbench/Workbench.vue'),
      'utf8',
    );

    expect(source).toMatch(/@media \(max-width: 720px\)[\s\S]*flex-wrap: nowrap/);
    expect(source).toMatch(/@media \(max-width: 480px\)[\s\S]*\.topbar-title span,[\s\S]*\.user-meta/);
  });

  it('opens the shared theme skin preferences from the global toolbar', async () => {
    const wrapper = shallowMount(Workbench);

    await wrapper.get('[aria-label="皮肤切换"]').trigger('click');

    expect(wrapper.emitted('userCommand')).toEqual([['themeSkin']]);
  });

  it('requests a refresh for only the active page when the topbar action is requested', async () => {
    const wrapper = shallowMount(Workbench, {
      props: {
        startup: {
          session: { currentUser: { userId: 'user-1', tenantId: 'tenant-a', system: false } },
          menus: [],
          tabs: [
            {
              instanceKey: 'application',
              key: 'application',
              title: '应用管理',
              fullPath: '/platform/application?InstanceKey=application',
            },
          ],
          activeTabKey: 'application',
        },
      },
    });
    expect(wrapper.get('[aria-label="刷新当前页"]').attributes('disabled')).not.toBe('true');
    expect(wrapper.get('[aria-label="刷新当前页"]').attributes('title')).toBe('刷新当前页');

    await wrapper.get('[aria-label="刷新当前页"]').trigger('click');

    expect(wrapper.emitted('refreshPage')).toEqual([['application']]);
  });

  it('presents the shared module host without leaking its legacy dynamic route name', () => {
    const wrapper = shallowMount(Workbench, {
      props: {
        startup: {
          session: { currentUser: { userId: 'user-1', system: true } },
          menus: [],
          tabs: [
            {
              key: 'field-ui-controls',
              title: '字段 UI 控件',
              pageDescriptor: {
                pageType: 'dynamic-module',
                openMode: 'dynamic-runner',
                hostType: 'dynamic-module-host',
                target: { moduleAlias: 'platform.field_ui_control', pageMode: 'LIST' },
                tabPolicy: { identity: 'by-menu' },
              },
            },
          ],
          activeTabKey: 'field-ui-controls',
        },
      },
    });

    expect(wrapper.text()).toContain('标准模块 / platform.field_ui_control');
    expect(wrapper.text()).not.toContain('动态模块 / platform.field_ui_control');
  });

  it('selects a dark tenant logo first and falls back to the default logo in light mode', () => {
    const startup = {
      session: {
        currentUser: { userId: 'user-1', tenantId: 'tenant-a', system: false },
        tenantBranding: {
          lightLogo: 'data:image/png;base64,bGlnaHQ=',
          darkLogo: 'data:image/png;base64,ZGFyaw==',
        },
      },
      menus: [],
    };
    const dark = shallowMount(Workbench, { props: { startup, themeAppearance: 'dark' } });
    const light = shallowMount(Workbench, { props: { startup, themeAppearance: 'light' } });

    expect(dark.findComponent(WorkbenchBrandControl).props('logoSrc')).toBe('data:image/png;base64,ZGFyaw==');
    expect(light.findComponent(WorkbenchBrandControl).props('logoSrc')).toBe(
      'data:image/png;base64,bGlnaHQ=',
    );
  });

  it('uses the same tenant logo for the expanded sidebar brand', async () => {
    await userPreferences.set('workbench.menu-presentation', 'expanded');
    const wrapper = shallowMount(Workbench, {
      props: {
        startup: {
          session: {
            currentUser: { userId: 'user-1', tenantId: 'tenant-a', system: false },
            tenantBranding: { lightLogo: 'data:image/png;base64,bGlnaHQ=' },
          },
          menus: [],
        },
      },
    });

    expect(wrapper.findComponent(WorkbenchMenu).props('logoSrc')).toBe('data:image/png;base64,bGlnaHQ=');
  });

  it('passes logo-only branding mode to both workbench brand presentations', () => {
    const wrapper = shallowMount(Workbench, {
      props: {
        startup: {
          session: {
            currentUser: { userId: 'user-1', tenantId: 'tenant-a', system: false },
            tenantBranding: { mode: 'logoOnly', title: '不应显示', subtitle: '不应显示' },
          },
          menus: [],
        },
      },
    });

    expect(wrapper.findComponent(WorkbenchBrandControl).props()).toMatchObject({
      showTitleArea: false,
      brandTitle: '不应显示',
      brandSubtitle: '不应显示',
    });
    expect(wrapper.findComponent(WorkbenchMenu).props()).toMatchObject({
      showTitleArea: false,
      brandTitle: '不应显示',
      brandSubtitle: '不应显示',
    });
  });

  it('joins the active tab and its page body in one Mega surface', () => {
    const wrapper = shallowMount(Workbench);

    expect(wrapper.find('.workbench-mega-surface').exists()).toBe(true);
    expect(wrapper.find('.workbench-mega-surface > .tab-strip + .app-content').exists()).toBe(true);
  });

  it('restores and persists the selected menu presentation', async () => {
    await userPreferences.set('workbench.menu-presentation', 'expanded');
    const wrapper = shallowMount(Workbench);
    const menu = wrapper.findComponent(WorkbenchMenu);

    expect(menu.props('presentation')).toBe('expanded');

    menu.vm.$emit('changePresentation', 'compact');
    await wrapper.vm.$nextTick();

    expect(userPreferences.get('workbench.menu-presentation', 'expanded')).toBe('compact');
  });

  it('pins a deliberate click open and closes on the second click', async () => {
    vi.useFakeTimers();
    const wrapper = shallowMount(Workbench);
    const brand = wrapper.findComponent(WorkbenchBrandControl);
    const menu = wrapper.findComponent(WorkbenchMenu);
    const anchor = { left: 8, top: 8, right: 120, bottom: 42 };

    brand.vm.$emit('openCompactMenu', 'pointer', anchor);
    brand.vm.$emit('openCompactMenu', 'click', anchor);
    await wrapper.vm.$nextTick();
    menu.vm.$emit('compactMenuLeave');
    vi.advanceTimersByTime(300);
    await wrapper.vm.$nextTick();

    expect(menu.props('compactOpen')).toBe(true);

    brand.vm.$emit('openCompactMenu', 'click', anchor);
    await wrapper.vm.$nextTick();

    expect(menu.props('compactOpen')).toBe(false);
  });

  it('keeps the compact Mega surface open when the pointer moves from the brand into the menu', async () => {
    vi.useFakeTimers();
    const wrapper = shallowMount(Workbench);
    const brand = wrapper.findComponent(WorkbenchBrandControl);
    const menu = wrapper.findComponent(WorkbenchMenu);
    const anchor = { left: 8, top: 8, right: 120, bottom: 42 };

    brand.vm.$emit('openCompactMenu', 'pointer', anchor);
    brand.vm.$emit('scheduleCompactMenuClose');
    menu.vm.$emit('compactMenuEnter');
    vi.advanceTimersByTime(300);
    await wrapper.vm.$nextTick();

    expect(menu.props('compactOpen')).toBe(true);
    expect(menu.props('compactAnchor')).toEqual(anchor);
  });

  it('keeps the compact Mega surface open when the pointer returns from the menu to the brand', async () => {
    vi.useFakeTimers();
    const wrapper = shallowMount(Workbench);
    const brand = wrapper.findComponent(WorkbenchBrandControl);
    const menu = wrapper.findComponent(WorkbenchMenu);
    const anchor = { left: 8, top: 8, right: 120, bottom: 42 };

    brand.vm.$emit('openCompactMenu', 'pointer', anchor);
    menu.vm.$emit('compactMenuEnter');
    menu.vm.$emit('compactMenuLeave');
    brand.vm.$emit('openCompactMenu', 'pointer', anchor);
    vi.advanceTimersByTime(300);
    await wrapper.vm.$nextTick();

    expect(menu.props('compactOpen')).toBe(true);
    expect(menu.props('compactAnchor')).toEqual(anchor);
  });

  it('closes a pinned compact menu when interaction moves outside the menu', async () => {
    const wrapper = shallowMount(Workbench, { attachTo: document.body });
    const brand = wrapper.findComponent(WorkbenchBrandControl);
    const menu = wrapper.findComponent(WorkbenchMenu);
    const anchor = { left: 8, top: 8, right: 120, bottom: 42 };

    brand.vm.$emit('openCompactMenu', 'click', anchor);
    await wrapper.vm.$nextTick();
    expect(menu.props('compactOpen')).toBe(true);

    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true, composed: true }));
    await wrapper.vm.$nextTick();

    expect(menu.props('compactOpen')).toBe(false);
  });
});

describe('WorkbenchMenuTree', () => {
  it('renders a five-level path, marks the current deepest leaf, and selects it', async () => {
    const nodes = createWorkbenchMenuNodes(nestedMenus);
    const metadata = findWorkbenchMenuNodeById(nodes, 'metadata');
    expect(metadata).toBeDefined();

    const wrapper = mount(WorkbenchMenuTree, {
      props: {
        node: metadata!,
        selectedMenuId: 'field-validation',
        selectedPathIds: ['platform', 'configuration', 'metadata', 'field-spec', 'field-validation'],
      },
    });

    const entries = wrapper.findAll('.deep-node-button');
    expect(entries[0].classes()).toContain('selected-path');
    expect(entries[0].attributes('aria-current')).toBeUndefined();
    expect(entries[1].classes()).toContain('selected-path');
    expect(entries[1].element.tagName).toBe('BUTTON');
    expect(entries[2].classes()).toContain('selected');
    expect(entries[2].attributes('aria-current')).toBe('page');
    expect(entries.map((entry) => entry.text())).toEqual(['元数据管理', '字段规格', '字段校验规则']);

    await entries[2].trigger('click');

    expect(wrapper.emitted('selectMenu')?.[0][0]).toMatchObject({ id: 'field-validation' });
  });

  it('renders structural deep nodes as non-interactive hierarchy labels', () => {
    const [structuralRoot] = createWorkbenchMenuNodes([
      {
        record: { id: 'structural-root', schemeId: 'default', title: '结构根节点' },
        children: [
          {
            record: { id: 'structural-group', schemeId: 'default', title: '结构分组' },
            children: [
              {
                record: {
                  id: 'module-leaf',
                  schemeId: 'default',
                  title: '模块叶子',
                  entryType: 'module' as const,
                  openMode: 'tab' as const,
                  moduleAlias: 'platform.application',
                },
                children: [],
              },
            ],
          },
        ],
      },
    ]);
    const wrapper = mount(WorkbenchMenuTree, { props: { node: structuralRoot } });

    expect(wrapper.findAll('.deep-node-button').map((entry) => entry.element.tagName)).toEqual([
      'DIV',
      'DIV',
      'BUTTON',
    ]);
  });
});

describe('WorkbenchBrandControl', () => {
  it('distinguishes pointer entry from deliberate compact-menu activation', async () => {
    const wrapper = mount(WorkbenchBrandControl, { props: { presentation: 'compact' } });
    const identity = wrapper.get('[aria-label="系统菜单"]');

    await identity.trigger('mouseenter');
    await identity.trigger('focus');
    await identity.trigger('click');

    const menuOpenEvents = wrapper.emitted('openCompactMenu') ?? [];
    expect(menuOpenEvents.map(([source]) => source)).toEqual(['pointer', 'focus', 'click']);
    expect(menuOpenEvents[0]?.[1]).toEqual(
      expect.objectContaining({
        left: expect.any(Number),
        top: expect.any(Number),
        right: expect.any(Number),
        bottom: expect.any(Number),
      }),
    );
  });

  it('uses the full brand control, including the presentation toggle, as the compact-menu anchor', async () => {
    const wrapper = mount(WorkbenchBrandControl, { props: { presentation: 'compact' } });
    const control = wrapper.get('.workbench-brand-control').element;
    const identity = wrapper.get('[aria-label="系统菜单"]');
    vi.spyOn(control, 'getBoundingClientRect').mockReturnValue({
      left: 12,
      top: 8,
      right: 144,
      bottom: 44,
      width: 132,
      height: 36,
      x: 12,
      y: 8,
      toJSON: () => ({}),
    });

    await identity.trigger('click');

    expect(wrapper.emitted('openCompactMenu')?.at(-1)).toEqual([
      'click',
      {
        left: 12,
        top: 8,
        right: 144,
        bottom: 44,
      },
    ]);
  });

  it('marks the open compact brand anchor so it can share the Mega-menu surface', () => {
    const wrapper = mount(WorkbenchBrandControl, {
      props: { presentation: 'compact', compactOpen: true },
    });

    expect(wrapper.get('.workbench-brand-control').classes()).toContain(
      'workbench-brand-control--compact-open',
    );
  });

  it('stacks the expanded sidebar depth chooser above the presentation toggle', async () => {
    const wrapper = mount(WorkbenchBrandControl, {
      props: { presentation: 'expanded', expandedMenuDepth: 1 },
    });

    expect(wrapper.findAll('.workbench-menu-depth-option')).toHaveLength(3);
    expect(wrapper.get('[aria-label="侧栏菜单层级"]').text()).toBe('123');
    expect(wrapper.get('.workbench-menu-depth-option.selected').text()).toBe('1');
    const actions = wrapper.get('.workbench-brand-actions');
    expect(actions.element.firstElementChild).toBe(wrapper.get('[aria-label="侧栏菜单层级"]').element);
    expect(actions.element.lastElementChild).toBe(
      wrapper.get('.workbench-brand-presentation-toggle').element,
    );

    await wrapper.findAll('.workbench-menu-depth-option')[2].trigger('click');

    expect(wrapper.emitted('changeExpandedMenuDepth')).toEqual([[3]]);
  });

  it('can hide the presentation toggle when responsive layout owns the presentation', () => {
    const wrapper = mount(WorkbenchBrandControl, {
      props: { presentation: 'compact', presentationToggleVisible: false },
    });

    expect(wrapper.find('.workbench-brand-presentation-toggle').exists()).toBe(false);
    expect(wrapper.get('[aria-label="系统菜单"]').attributes('aria-label')).toBe('系统菜单');
  });

  it('renders a tenant-provided logo and title without a subtitle in compact mode', () => {
    const wrapper = mount(WorkbenchBrandControl, {
      props: {
        presentation: 'compact',
        logoSrc: 'data:image/png;base64,bG9nbw==',
        brandTitle: '木云工作台',
        brandSubtitle: '租户 A',
      },
    });

    expect(wrapper.get('.workbench-brand-logo').attributes('src')).toBe('data:image/png;base64,bG9nbw==');
    expect(wrapper.find('.workbench-brand-mark').exists()).toBe(false);
    expect(wrapper.get('.workbench-brand-copy').text()).toContain('木云工作台');
    expect(wrapper.find('.workbench-brand-copy small').exists()).toBe(false);
  });

  it('renders the configured subtitle in expanded mode', () => {
    const wrapper = mount(WorkbenchBrandControl, {
      props: {
        presentation: 'expanded',
        brandTitle: '木云工作台',
        brandSubtitle: '租户 A',
      },
    });

    expect(wrapper.get('.workbench-brand-copy').text()).toContain('木云工作台');
    expect(wrapper.get('.workbench-brand-copy small').text()).toBe('租户 A');
  });

  it('keeps the brand mark but hides both titles when the tenant disables the title area', () => {
    const wrapper = mount(WorkbenchBrandControl, {
      props: { presentation: 'compact', showTitleArea: false },
    });

    expect(wrapper.find('.workbench-brand-mark').exists()).toBe(true);
    expect(wrapper.find('.workbench-brand-copy').exists()).toBe(false);
  });

  it('gives the brand mark additional visual weight when it accompanies a title', () => {
    const wrapper = mount(WorkbenchBrandControl, {
      props: { presentation: 'compact', showTitleArea: true },
    });

    expect(wrapper.get('.workbench-brand-identity').classes()).toContain(
      'workbench-brand-identity--with-title',
    );
  });
});
