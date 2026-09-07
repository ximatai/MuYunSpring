import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page, commands, userEvent } from 'vitest/browser';
import { configureModuleContext, type HttpClient, type HttpRequestOptions } from '@/web-core';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import PageCompositionWorkspace from '@/views/PageCompositionWorkspace.vue';
import PageCompositionTree from '@/views/PageCompositionTree.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

const PlacementHost = defineComponent({
  props: { height: { type: Number, required: true } },
  setup(props) {
    providePageLayout('workspace');
    return () =>
      h('div', { style: `height:${props.height}px;margin:10px` }, [
        h(PageCompositionWorkspace, { moduleAlias: 'education.placement' }),
      ]);
  },
});

it.each([760, 480])('uses one tree scrollport and reaches both ends at %ipx height', async (height) => {
  await page.viewport(1082, 814);
  configureModuleContext({ http: placementHttp([], true, 'LIST_CARD', true, 20) });
  const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height } });
  const selector = '.page-composition-tree__ui-tree';
  try {
    await expect.poll(() => wrapper.find('[data-ui-tree-key="ui:field:form:t"]').exists()).toBe(true);
    const tree = wrapper.get(selector).element as HTMLElement;
    const field = wrapper.get('[data-ui-tree-key="ui:field:form:a"]').element;
    const scrollports = [];
    for (let parent = field.parentElement; parent; parent = parent.parentElement) {
      if (
        /(auto|scroll)/.test(getComputedStyle(parent).overflowY) &&
        parent.scrollHeight > parent.clientHeight
      ) {
        scrollports.push(parent);
      }
    }
    expect(scrollports).toEqual([tree]);
    const header = wrapper.get('[data-testid="page-composer-ui-tree"]').element.parentElement!
      .previousElementSibling!;
    const headerTop = header.getBoundingClientRect().top;
    await commands.treeWheel(selector, 5000);
    await expect.poll(() => tree.scrollTop).toBeGreaterThan(0);
    const last = wrapper.findAll(`${selector} [data-ui-tree-key]`).at(-1)!.element;
    await expect
      .poll(() => last.getBoundingClientRect().bottom <= tree.getBoundingClientRect().bottom + 1)
      .toBe(true);
    await commands.treeWheel(selector, -5000);
    await expect.poll(() => tree.scrollTop).toBe(0);
    expect(header.getBoundingClientRect().top).toBe(headerTop);
    await page.elementLocator(field).click();
    const before = tree.scrollTop;
    await commands.treeScrollGesture('[data-ui-tree-key="ui:field:form:a"]', selector);
    await expect.poll(() => tree.scrollTop).toBeGreaterThan(before);
    await userEvent.keyboard('{Escape}');
    await commands.treeRelease();
  } finally {
    wrapper.unmount();
  }
});

it.each([1440, 980])(
  'places A into B/C precisely and reorders C without changing field facts at %ipx',
  async (width) => {
    await page.viewport(width, 950);
    const requests: HttpRequestOptions[] = [];
    configureModuleContext({ http: placementHttp(requests) });
    const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 900 } });
    const source = (name: string) => `[data-ui-tree-key="metadata:field:${name}"]`;
    const header = (name: string) => `[data-page-composition-layout-key="list:header:${name}"]`;
    const handle = (key: string) => `[data-composer-drag="${key}"]`;
    const target = (key: string) => `[data-composer-target="${key}"][tabindex]`;
    const model = () => wrapper.findComponent(PageCompositionTree);
    const ready = async () => {
      await expect
        .element(page.elementLocator(wrapper.get(handle('list:header:a')).element))
        .toHaveAttribute('aria-disabled', 'false');
      await expect
        .poll(
          () =>
            wrapper
              .get('[data-testid="page-composer-list-preview"]')
              .element.getAnimations({ subtree: true })
              .filter((animation: Animation) => animation.playState === 'running').length,
        )
        .toBe(0);
    };
    try {
      await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
      await expect.poll(() => wrapper.find(header('a')).exists()).toBe(true);
      await ready();
      // A -> C: drop at the left edge of the first column, not the canvas root.
      await commands.treeGesture(source('b'), header('a'), 0.5, 'hold', 0.1);
      await expect.poll(() => wrapper.find('.page-composer-drop-indicator').exists()).toBe(false);
      await commands.treeRelease();
      await expect
        .poll(() =>
          model()
            .props('listFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['b', 'a']);
      await ready();
      // A -> B retains the same before/after contract.
      await commands.treeGesture(source('c'), '[data-ui-tree-key="ui:field:list:a"]', 0.1);
      await expect
        .poll(() =>
          model()
            .props('listFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['b', 'c', 'a']);
      await ready();
      // C -> C from a real handle. Drop to the right of a later column; remove-before-insert matters.
      await commands.treeGesture(handle('list:header:b'), header('a'), 0.5, 'drop', 0.9);
      await expect
        .poll(() =>
          model()
            .props('listFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['c', 'a', 'b']);
      await ready();
      await commands.treeGesture(handle('list:header:c'), header('b'), 0.5, 'escape', 0.9);
      expect(
        model()
          .props('listFields')
          .map((field: { id: string }) => field.id),
      ).toEqual(['c', 'a', 'b']);
      // Blank preview background is not an implicit append target.
      await commands.treeGesture(source('b'), '[data-testid="page-composer-list-preview"]', 0.98);
      expect(
        model()
          .props('listFields')
          .map((field: { id: string }) => field.id),
      ).toEqual(['c', 'a', 'b']);
      await page.getByText('表单', { exact: true }).click();
      await expect
        .element(page.elementLocator(wrapper.get(handle('edit:field:a')).element))
        .toHaveAttribute('aria-disabled', 'false');
      // A -> exact empty group; then C -> root form after an existing field.
      await commands.treeGesture(source('c'), target('edit:container:group:empty'), 0.5);
      await expect
        .poll(() =>
          model()
            .props('formGroups')[1]
            .fields.map((field: { id: string }) => field.id),
        )
        .toEqual(['c']);
      await expect
        .element(page.elementLocator(wrapper.get(handle('edit:field:b')).element))
        .toHaveAttribute('aria-disabled', 'false');
      await commands.treeGesture(
        handle('edit:field:b'),
        '[data-page-composition-layout-key="edit:field:a"]',
        0.5,
        'drop',
        0.9,
      );
      await expect
        .poll(() =>
          model()
            .props('formFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['a', 'b']);
      expect(model().props('formFields')[1].properties).toEqual({ label: '自定义乙', readOnly: true });
      await expect
        .element(page.elementLocator(wrapper.get(handle('edit:group:empty')).element))
        .toHaveAttribute('aria-disabled', 'false');
      await expect
        .poll(
          () =>
            wrapper.element
              .getAnimations({ subtree: true })
              .filter((animation: Animation) => animation.playState === 'running').length,
        )
        .toBe(0);
      await commands.treeGesture(handle('edit:group:empty'), target('edit:group:basic'), 0.1);
      await expect
        .poll(() =>
          model()
            .props('formGroups')
            .map((group: { id: string }) => group.id),
        )
        .toEqual(['empty', 'basic']);
      await expect
        .element(page.elementLocator(wrapper.get(handle('edit:field:a')).element))
        .toHaveAttribute('aria-disabled', 'false');
      // Input values remain editable; dragging uses a dedicated grip.
      await page.getByRole('textbox', { name: '字段甲', exact: true }).fill('示例输入');
      expect(model().props('formFields')[0].fieldName).toBe('a');
      await page.getByRole('button', { name: '保存草稿', exact: true }).click();
      await expect.element(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled();
      await page.getByRole('button', { name: '刷新：页面结构', exact: true }).click();
      await expect
        .poll(() =>
          model()
            .props('listFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['c', 'a', 'b']);
      expect(
        model()
          .props('formGroups')
          .map((group: { id: string }) => group.id),
      ).toEqual(['empty', 'basic']);
      expect(requests.filter((request) => /\/fields\/(insert|update|delete)/.test(request.path))).toEqual([]);
    } finally {
      wrapper.unmount();
    }
  },
);

it('targets child-table columns precisely, rejects another relation and moves whole child sections', async () => {
  await page.viewport(1440, 1200);
  configureModuleContext({ http: placementHttp([], true) });
  const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 1120 } });
  const model = () => wrapper.findComponent(PageCompositionTree);
  const ids = () =>
    model()
      .props('formRelations')
      .find((relation: { id: string }) => relation.id === 'child')
      ?.fields.map((field: { id: string }) => field.id);
  const grip = '[data-composer-drag="edit:relation:child:header:x"]';
  const ready = async () => {
    await expect
      .element(page.elementLocator(wrapper.get(grip).element))
      .toHaveAttribute('aria-disabled', 'false');
    await expect
      .poll(
        () =>
          wrapper
            .get('[data-testid="page-composer-edit-preview"]')
            .element.getAnimations({ subtree: true })
            .filter((animation: Animation) => animation.playState === 'running').length,
      )
      .toBe(0);
  };
  try {
    await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
    await page.getByText('表单', { exact: true }).click();
    await expect.poll(() => wrapper.find(grip).exists()).toBe(true);
    await ready();
    const switcher = wrapper
      .get('[data-ui-tree-key="metadata:relation:child"]')
      .element.closest('.ant-tree-treenode')!
      .querySelector('.ant-tree-switcher')!;
    await page.elementLocator(switcher).click();
    const source = '[data-ui-tree-key="metadata:relation-field:child:y"]';
    await commands.treeGesture(source, '[data-composer-target="edit:relation:other"]', 0.5, 'hold');
    expect(wrapper.find('.page-composer-drop-indicator--rejected').exists()).toBe(true);
    await commands.treeRelease();
    expect(ids()).toEqual(['x']);
    await commands.treeGesture(
      source,
      '[data-page-composition-layout-key="edit:relation:child:header:x"]',
      0.5,
      'drop',
      0.1,
    );
    await expect.poll(ids).toEqual(['y', 'x']);
    await ready();
    await commands.treeGesture(
      grip,
      '[data-page-composition-layout-key="edit:relation:child:header:y"]',
      0.5,
      'drop',
      0.1,
    );
    await expect.poll(ids).toEqual(['x', 'y']);
    await ready();
    await commands.treeGesture(
      '[data-composer-drag="edit:relation:other"]',
      '[data-composer-target="edit:relation:child"]',
      0.1,
    );
    await expect
      .poll(() =>
        model()
          .props('formRelations')
          .map((relation: { id: string }) => relation.id),
      )
      .toEqual(['other', 'child']);
    await expect.element(page.getByRole('radio', { name: '表单', exact: true })).toBeChecked();
  } finally {
    wrapper.unmount();
  }
});

it('keeps cross-row and cross-column form placement stable for existing and new fields', async () => {
  await page.viewport(1440, 980);
  configureModuleContext({ http: placementHttp([], false, 'LIST_CARD', true) });
  const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 920 } });
  const model = () => wrapper.findComponent(PageCompositionTree);
  const formIds = () =>
    model()
      .props('formFields')
      .map((field: { id: string }) => field.id);
  const handle = (field: string) => `[data-composer-drag="edit:field:${field}"]`;
  const field = (fieldName: string) => `[data-page-composition-layout-key="edit:field:${fieldName}"]`;
  const ready = async (fieldName: string) => {
    await expect
      .element(page.elementLocator(wrapper.get(handle(fieldName)).element))
      .toHaveAttribute('aria-disabled', 'false');
    await expect
      .poll(
        () =>
          wrapper
            .get('[data-testid="page-composer-edit-preview"]')
            .element.getAnimations({ subtree: true })
            .filter((animation: Animation) => animation.playState === 'running').length,
      )
      .toBe(0);
  };
  try {
    await page.getByText('表单', { exact: true }).click();
    await expect.poll(() => wrapper.find(handle('c')).exists()).toBe(true);
    await ready('c');

    // The common adjacent case: A enters B's centre and takes B's former position.
    await commands.treeGesture(handle('a'), field('b'), 0.5, 'drop', 0.5);
    await expect.poll(formIds).toEqual(['b', 'a', 'c', 'd']);
    await ready('c');

    // c starts on the second row. Crossing rows to B's left edge must make it the first field.
    await commands.treeGesture(handle('c'), field('b'), 0.5, 'drop', 0.1);
    await expect.poll(formIds).toEqual(['c', 'b', 'a', 'd']);
    await ready('a');

    // A moves from the second row to C's right edge: an explicit cross-column "after".
    await commands.treeGesture(handle('a'), field('c'), 0.5, 'drop', 0.9);
    await expect.poll(formIds).toEqual(['c', 'a', 'b', 'd']);
    await ready('c');

    // A new metadata field held over C's centre remains before C rather than becoming second
    // because the pointer happens to be over the input control inside the field shell.
    await commands.treeGesture('[data-ui-tree-key="metadata:field:e"]', field('c'), 0.5, 'hold', 0.5);
    await expect
      .poll(() => wrapper.find('.page-composer-external-field-preview--dragging').exists())
      .toBe(true);
    await commands.treeRelease();
    await expect.poll(formIds).toEqual(['e', 'c', 'a', 'b', 'd']);
  } finally {
    wrapper.unmount();
  }
});

it.each([
  { delay: 0, source: 'a' },
  { delay: 400, source: 'a' },
  { delay: 0, source: 'd' },
  { delay: 0, source: 'e' },
  { delay: 400, source: 'e' },
])('moves $source through fixed grid slots with $delay ms between moves', async ({ delay, source }) => {
  await page.viewport(1082, 814);
  configureModuleContext({ http: placementHttp([], false, 'LIST_CARD', true) });
  const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 790 } });
  const markers: HTMLElement[] = [];
  const external = source === 'e';
  const handle = external
    ? '[data-ui-tree-key="metadata:field:e"]'
    : `[data-composer-drag="edit:field:${source}"]`;
  const readyHandle = '[data-composer-drag="edit:field:a"]';
  const selector = '[data-page-composition-layout-key^="edit:field:"]';
  const order = () =>
    wrapper
      .findAll(selector)
      .map((element) => element.attributes('data-page-composition-layout-key')?.split(':').at(-1));
  const draft = () =>
    wrapper
      .findComponent(PageCompositionTree)
      .props('formFields')
      .map((field: { id: string }) => field.id);
  try {
    await page.getByText('表单', { exact: true }).click();
    await expect.poll(() => wrapper.find(readyHandle).exists()).toBe(true);
    await expect
      .element(page.elementLocator(wrapper.get(readyHandle).element))
      .toHaveAttribute('aria-disabled', 'false');
    // Fixed viewport coordinates, independent of the field that animates through each slot.
    wrapper.findAll(selector).forEach((field, index) => {
      const rect = field.element.getBoundingClientRect();
      const marker = document.createElement('span');
      marker.id = `placement-slot-${index}`;
      marker.style.cssText = `position:fixed;pointer-events:none;left:${rect.x + rect.width / 2}px;top:${rect.y + rect.height / 2}px;width:1px;height:1px`;
      document.body.append(marker);
      markers.push(marker);
    });
    const [left, right] = wrapper
      .findAll(selector)
      .slice(0, 2)
      .map((field) => field.element.getBoundingClientRect());
    const gutter = document.createElement('span');
    gutter.id = 'placement-gutter';
    gutter.style.cssText = `position:fixed;pointer-events:none;left:${(left.right + right.left) / 2}px;top:${left.y + left.height / 2}px;width:1px;height:1px`;
    document.body.append(gutter);
    markers.push(gutter);
    await commands.treeGesture(handle, '#placement-slot-0', 0.5, 'hold');
    await commands.treeMove('#placement-gutter');
    await expect.poll(() => order().indexOf(source)).toBeLessThan(2);

    for (const slot of [0, 1, 2, 3, 3, 2, 1, 0]) {
      await commands.treeMove(`#placement-slot-${slot}`);
      if (delay) await new Promise((resolve) => setTimeout(resolve, delay));
      const expected = ['a', 'b', 'c', 'd'].filter((field) => field !== source);
      expected.splice(slot, 0, source);
      await expect.poll(order).toEqual(expected);
      expect(draft()).toEqual(['a', 'b', 'c', 'd']);
    }
    // Cancel and then commit a separate move: both must agree with the last visible candidate.
    await userEvent.keyboard('{Escape}');
    await commands.treeRelease();
    await expect.poll(order).toEqual(['a', 'b', 'c', 'd']);
    await expect
      .poll(
        () =>
          wrapper.element
            .getAnimations({ subtree: true })
            .filter((animation: Animation) => animation.playState === 'running').length,
      )
      .toBe(0);
    if (external) {
      await commands.treeGesture(handle, '#placement-slot-0', 0.5, 'hold');
      await expect.poll(order).toEqual(['e', 'a', 'b', 'c', 'd']);
      await commands.treeMove(handle);
      await expect.poll(order).toEqual(['a', 'b', 'c', 'd']);
      expect(draft()).toEqual(['a', 'b', 'c', 'd']);
      await commands.treeRelease();
      await expect
        .poll(
          () =>
            wrapper.element
              .getAnimations({ subtree: true })
              .filter((animation: Animation) => animation.playState === 'running').length,
        )
        .toBe(0);
    }
    await commands.treeGesture(handle, '#placement-slot-3', 0.5, 'hold');
    const committed = ['a', 'b', 'c', 'd'].filter((field) => field !== source);
    committed.splice(3, 0, source);
    await expect.poll(order).toEqual(committed);
    await commands.treeRelease();
    await expect.poll(draft).toEqual(committed);
  } finally {
    await userEvent.keyboard('{Escape}');
    await commands.treeRelease();
    markers.forEach((marker) => marker.remove());
    wrapper.unmount();
  }
});

it.each([3, 4, 10])('keeps a new field at the trailing slot after a %i-field grid expands', async (count) => {
  await page.viewport(1082, 980);
  configureModuleContext({ http: placementHttp([], false, 'LIST_CARD', true, count) });
  const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 940 } });
  const names = Array.from({ length: count }, (_, index) => String.fromCharCode(97 + index));
  const added = String.fromCharCode(97 + count);
  const key = (name: string) => `[data-page-composition-layout-key="edit:field:${name}"]`;
  const markers: HTMLElement[] = [];
  const mark = (id: string, rect: DOMRect) => {
    const marker = document.createElement('span');
    marker.id = id;
    marker.style.cssText = `position:fixed;pointer-events:none;left:${rect.x + rect.width / 2}px;top:${rect.y + rect.height / 2}px;width:1px;height:1px`;
    document.body.append(marker);
    markers.push(marker);
    return `#${id}`;
  };
  const order = () =>
    wrapper
      .findAll('[data-page-composition-layout-key^="edit:field:"]')
      .map((field) => field.attributes('data-page-composition-layout-key')?.split(':').at(-1));
  const settled = async () =>
    expect
      .poll(
        () =>
          wrapper.element
            .getAnimations({ subtree: true })
            .filter((animation: Animation) => animation.playState === 'running').length,
      )
      .toBe(0);
  try {
    await page.getByText('表单', { exact: true }).click();
    await expect.poll(() => wrapper.find(key(names[0])).exists()).toBe(true);
    await expect
      .element(page.elementLocator(wrapper.get('[data-composer-drag="edit:field:a"]').element))
      .toHaveAttribute('aria-disabled', 'false');
    const last = names.at(-1)!;
    const originalLast = mark('original-last-slot', wrapper.get(key(last)).element.getBoundingClientRect());
    await commands.treeGesture(`[data-ui-tree-key="metadata:field:${added}"]`, originalLast, 0.5, 'hold');
    await expect.poll(order).toEqual([...names.slice(0, -1), added, last]);
    await settled();
    // Inserting before the last field creates one extra visible slot. That slot must accept
    // the new field at its centre and stay stable when the pointer is over the new card itself.
    const tail = mark('expanded-last-slot', wrapper.get(key(last)).element.getBoundingClientRect());
    for (const destination of [tail, tail, originalLast, tail]) {
      await commands.treeMove(destination);
      await expect
        .poll(order)
        .toEqual(destination === tail ? [...names, added] : [...names.slice(0, -1), added, last]);
    }
    expect(
      wrapper
        .findComponent(PageCompositionTree)
        .props('formFields')
        .map((field: { id: string }) => field.id),
    ).toEqual(names);
    await commands.treeRelease();
    await expect
      .poll(() =>
        wrapper
          .findComponent(PageCompositionTree)
          .props('formFields')
          .map((field: { id: string }) => field.id),
      )
      .toEqual([...names, added]);
  } finally {
    await userEvent.keyboard('{Escape}');
    await commands.treeRelease();
    markers.forEach((marker) => marker.remove());
    wrapper.unmount();
  }
});

it('interleaves groups and fields through a held preview gesture and persists the same tree order', async () => {
  await page.viewport(1082, 980);
  const requests: HttpRequestOptions[] = [];
  configureModuleContext({ http: placementHttp(requests) });
  const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 940 } });
  const model = () => wrapper.findComponent(PageCompositionTree);
  const grip = (key: string) => `[data-composer-drag="edit:${key}"]`;
  const order = () =>
    model()
      .props('formOrder')!
      .map((item: { kind: string; id: string }) => `${item.kind}:${item.id}`);
  const visibleOrder = () =>
    wrapper
      .findAll(
        '.record-form-grid > header[data-page-composition-layout-key], .record-form-grid > .record-form-field-host[data-page-composition-layout-key]',
      )
      .map((node) => node.attributes('data-page-composition-layout-key'));
  const markers: HTMLElement[] = [];
  try {
    await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
    await page.getByText('表单', { exact: true }).click();
    await expect.poll(() => wrapper.find(grip('group:empty')).exists()).toBe(true);
    await expect
      .element(page.elementLocator(wrapper.get(grip('group:empty')).element))
      .toHaveAttribute('aria-disabled', 'false');
    expect(model().text()).not.toContain('表单分组');
    for (const key of ['field:a', 'group:basic']) {
      const rect = wrapper
        .get(`[data-page-composition-layout-key="edit:${key}"]`)
        .element.getBoundingClientRect();
      const marker = document.createElement('div');
      marker.id = `mixed-${markers.length}`;
      marker.style.cssText = `position:fixed;pointer-events:none;left:${rect.left}px;top:${rect.top}px;width:${rect.width}px;height:${rect.height}px`;
      document.body.append(marker);
      markers.push(marker);
    }
    await commands.treeGesture(grip('group:empty'), '#mixed-0', 0.5, 'hold', 0.1);
    await expect
      .poll(visibleOrder)
      .toEqual(['edit:group:empty', 'edit:field:a', 'edit:group:basic', 'edit:field:b']);
    expect(order()).toEqual(['field:a', 'group:basic', 'group:empty']);
    await commands.treeMove('#mixed-1', 0.1);
    await expect
      .poll(visibleOrder)
      .toEqual(['edit:field:a', 'edit:group:empty', 'edit:group:basic', 'edit:field:b']);
    await commands.treeMove('#mixed-0', 0.5, 0.1);
    await expect
      .poll(visibleOrder)
      .toEqual(['edit:group:empty', 'edit:field:a', 'edit:group:basic', 'edit:field:b']);
    await commands.treeRelease();
    await expect.poll(order).toEqual(['group:empty', 'field:a', 'group:basic']);
    await expect
      .element(page.elementLocator(wrapper.get(grip('field:a')).element))
      .toHaveAttribute('aria-disabled', 'false');
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect.element(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: '刷新：页面结构', exact: true }).click();
    await expect.poll(order).toEqual(['group:empty', 'field:a', 'group:basic']);
    await expect
      .poll(visibleOrder)
      .toEqual(['edit:group:empty', 'edit:field:a', 'edit:group:basic', 'edit:field:b']);
  } finally {
    await commands.treeRelease();
    markers.forEach((marker) => marker.remove());
    wrapper.unmount();
  }
});

it('keeps a palette field in the extra root cell before a group while crossing into and out of that group', async () => {
  await page.viewport(1082, 980);
  configureModuleContext({ http: placementHttp([]) });
  const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 940 } });
  const markers: HTMLElement[] = [];
  const marker = (selector: string) => {
    const rect = wrapper.get(selector).element.getBoundingClientRect();
    const node = document.createElement('div');
    node.id = `root-tail-${markers.length}`;
    node.style.cssText = `position:fixed;pointer-events:none;left:${rect.left}px;top:${rect.top}px;width:${rect.width}px;height:${rect.height}px`;
    document.body.append(node);
    markers.push(node);
    return `#${node.id}`;
  };
  const visible = () =>
    wrapper
      .findAll('.record-form-field-host[data-page-composition-layout-key]')
      .map((node) => node.attributes('data-page-composition-layout-key'));
  try {
    await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
    await page.getByText('表单', { exact: true }).click();
    await expect.poll(() => wrapper.find('[data-composer-drag="edit:field:a"]').exists()).toBe(true);
    await expect
      .element(page.elementLocator(wrapper.get('[data-composer-drag="edit:field:a"]').element))
      .toHaveAttribute('aria-disabled', 'false');
    const first = marker('.record-form-field-host[data-page-composition-layout-key="edit:field:a"]');
    const group = marker('[data-composer-target="edit:group:basic"]');
    await commands.treeGesture('[data-ui-tree-key="metadata:field:c"]', first, 0.5, 'hold');
    await expect.poll(visible).toEqual(['edit:field:c', 'edit:field:a', 'edit:field:b']);
    await expect
      .poll(
        () =>
          wrapper.element
            .getAnimations({ subtree: true })
            .filter((animation: Animation) => animation.playState === 'running').length,
      )
      .toBe(0);
    const tail = marker('.record-form-field-host[data-page-composition-layout-key="edit:field:a"]');
    await commands.treeMove(tail);
    await expect.poll(visible).toEqual(['edit:field:a', 'edit:field:c', 'edit:field:b']);
    await commands.treeMove(group);
    await expect.poll(visible).toEqual(['edit:field:a', 'edit:field:b', 'edit:field:c']);
    await commands.treeMove(tail);
    await expect.poll(visible).toEqual(['edit:field:a', 'edit:field:c', 'edit:field:b']);
    await commands.treeRelease();
    await expect
      .poll(() =>
        wrapper
          .findComponent(PageCompositionTree)
          .props('formFields')
          .map((field: { id: string }) => field.id),
      )
      .toEqual(['a', 'c']);
  } finally {
    await commands.treeRelease();
    markers.forEach((node) => node.remove());
    wrapper.unmount();
  }
});

it.each([
  { targetKind: 'heading', sourceId: 'a', roots: ['a'] },
  { targetKind: 'field', sourceId: 'a', roots: ['a'] },
  { targetKind: 'space', sourceId: 'a', roots: ['a'] },
  { targetKind: 'empty', sourceId: 'a', roots: ['a'] },
  { targetKind: 'follow', sourceId: 'a', roots: ['a'] },
  { targetKind: 'gap', sourceId: 'a', roots: ['a'] },
  { targetKind: 'empty', sourceId: 'b', roots: ['a'] },
  { targetKind: 'empty', sourceId: 'k', roots: ['a', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k'] },
])(
  'holds existing preview field $sourceId inside a $targetKind group with $roots without switching placement',
  async ({ targetKind, sourceId, roots }) => {
    await page.viewport(1082, 980);
    configureModuleContext({ http: placementHttp([], targetKind === 'gap', 'LIST_CARD', false, 10, roots) });
    const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 940 } });
    const markers: HTMLElement[] = [];
    const mark = (selector: string, emptyColumn = false, below = false) => {
      const rect = wrapper.get(selector).element.getBoundingClientRect();
      if (emptyColumn) {
        const heading = wrapper
          .get('[data-composer-target="edit:group:basic"]')
          .element.getBoundingClientRect();
        rect.x = heading.right - rect.width;
      }
      if (below) rect.y += rect.height + 8;
      const node = document.createElement('div');
      node.id = `existing-group-${markers.length}`;
      node.style.cssText = `position:fixed;pointer-events:none;left:${rect.left}px;top:${rect.top}px;width:${rect.width}px;height:${rect.height}px`;
      document.body.append(node);
      markers.push(node);
      return `#${node.id}`;
    };
    const model = () => wrapper.findComponent(PageCompositionTree);
    const previewFields = () =>
      wrapper
        .findComponent({ name: 'PageCompositionDescriptorPreview' })
        .findComponent({ name: 'RecordFormFields' })
        .props('fields');
    try {
      await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
      await page.getByText('表单', { exact: true }).click();
      const grip = `[data-composer-drag="edit:field:${sourceId}"]`;
      await expect.poll(() => wrapper.find(grip).exists()).toBe(true);
      await expect
        .element(page.elementLocator(wrapper.get(grip).element))
        .toHaveAttribute('aria-disabled', 'false');
      const original = mark(
        `.record-form-field-host[data-page-composition-layout-key="edit:field:${sourceId}"]`,
      );
      const target = mark(
        targetKind === 'heading'
          ? '[data-composer-target="edit:group:basic"]'
          : targetKind === 'field' || targetKind === 'space'
            ? '.record-form-field-host[data-page-composition-layout-key="edit:field:b"]'
            : '[data-composer-target="edit:container:group:empty"]',
        targetKind === 'space',
        targetKind === 'gap',
      );
      const groupId = ['empty', 'follow', 'gap'].includes(targetKind) ? 'empty' : 'basic';
      await commands.treeGesture(grip, target, 0.5, 'hold');
      await expect.poll(() => previewFields().get(sourceId)?.formGroup?.groupCode).toBe(groupId);
      for (let n = 0; n < 3; n++) {
        if (targetKind === 'follow')
          await expect
            .poll(
              () =>
                wrapper.element
                  .getAnimations({ subtree: true })
                  .filter((animation: Animation) => animation.playState === 'running').length,
            )
            .toBe(0);
        await commands.treeMove(
          targetKind === 'follow'
            ? `.record-form-field-host[data-page-composition-layout-key="edit:field:${sourceId}"]`
            : target,
          0.5,
          0.48 + n * 0.02,
        );
        await expect.poll(() => previewFields().get(sourceId)?.formGroup?.groupCode).toBe(groupId);
      }
      expect(
        model()
          .props('formFields')
          .map((field: { id: string }) => field.id),
      ).toEqual(roots);
      await commands.treeMove(original);
      await expect
        .poll(() => previewFields().get(sourceId)?.formGroup?.groupCode)
        .toBe(sourceId === 'b' ? 'basic' : undefined);
      await commands.treeMove(target);
      await expect.poll(() => previewFields().get(sourceId)?.formGroup?.groupCode).toBe(groupId);
      await commands.treeRelease();
      await expect
        .poll(() =>
          model()
            .props('formGroups')
            .find((group: { id: string }) => group.id === groupId)
            ?.fields.some((field: { id: string }) => field.id === sourceId),
        )
        .toBe(true);
      expect(
        model()
          .props('formFields')
          .map((field: { id: string }) => field.id),
      ).toEqual(roots.filter((id) => id !== sourceId));
    } finally {
      await commands.treeRelease();
      markers.forEach((node) => node.remove());
      wrapper.unmount();
    }
  },
);

it.each([false, true])(
  'previews a whole group through four group bodies and back with settled motion %s',
  async (settled) => {
    await page.viewport(1082, 1400);
    configureModuleContext({ http: placementHttp([], false, 'LIST_CARD', false, 4, undefined, true) });
    const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 1360 } });
    const markers: HTMLElement[] = [];
    const groups = ['basic', 'empty', 'third', 'last'];
    const visible = () =>
      wrapper
        .findAll('.record-form-grid > header[data-page-composition-layout-key]')
        .map((node) => node.attributes('data-page-composition-layout-key'));
    const model = () => wrapper.findComponent(PageCompositionTree);
    try {
      await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
      await page.getByText('表单', { exact: true }).click();
      const grip = '[data-composer-drag="edit:group:basic"]';
      await expect.poll(() => wrapper.find(grip).exists()).toBe(true);
      for (const id of groups) {
        const header = wrapper
          .get(`[data-composer-target="edit:group:${id}"]`)
          .element.getBoundingClientRect();
        const body = wrapper
          .get(
            id === 'basic'
              ? '.record-form-field-host[data-page-composition-layout-key="edit:field:b"]'
              : id === 'third'
                ? '.record-form-field-host[data-page-composition-layout-key="edit:field:d"]'
                : `[data-composer-target="edit:container:group:${id}"]`,
          )
          .element.getBoundingClientRect();
        const marker = document.createElement('div');
        marker.id = `group-body-${id}`;
        marker.style.cssText = `position:fixed;pointer-events:none;left:${header.left}px;top:${body.top}px;width:${header.width}px;height:${body.height}px`;
        document.body.append(marker);
        markers.push(marker);
      }
      await commands.treeGesture(grip, '#group-body-empty', 0.5, 'hold');
      for (const index of [1, 2, 3, 3, 2, 1, 0]) {
        if (settled)
          await expect
            .poll(
              () =>
                wrapper.element
                  .getAnimations({ subtree: true })
                  .filter((animation: Animation) => animation.playState === 'running').length,
            )
            .toBe(0);
        await commands.treeMove(`#group-body-${groups[index]}`);
        const expected = groups.filter((id) => id !== 'basic');
        expected.splice(index, 0, 'basic');
        await expect.poll(visible).toEqual(expected.map((id) => `edit:group:${id}`));
        expect(
          model()
            .props('formGroups')
            .map((group: { id: string }) => group.id),
        ).toEqual(groups);
      }
      await commands.treeMove('#group-body-last');
      await commands.treeRelease();
      await expect
        .poll(() =>
          model()
            .props('formGroups')
            .map((group: { id: string }) => group.id),
        )
        .toEqual(['empty', 'third', 'last', 'basic']);
      expect(
        model()
          .props('formGroups')
          .find((group: { id: string }) => group.id === 'basic')
          ?.fields.map((field: { id: string }) => field.id),
      ).toEqual(['b']);
      await expect.element(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled();
      await page.getByRole('button', { name: '保存草稿', exact: true }).click();
      await expect.element(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled();
      await page.getByRole('button', { name: '刷新：页面结构', exact: true }).click();
      await expect.poll(visible).toEqual(['empty', 'third', 'last', 'basic'].map((id) => `edit:group:${id}`));
    } finally {
      await commands.treeRelease();
      markers.forEach((marker) => marker.remove());
      wrapper.unmount();
    }
  },
);

it('moves a group field after its group through the structure tree and persists the mixed order', async () => {
  await page.viewport(1082, 1000);
  configureModuleContext({ http: placementHttp([]) });
  const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 960 } });
  const node = (key: string) => `[data-ui-tree-key="${key}"]`;
  const model = () => wrapper.findComponent(PageCompositionTree);
  const order = () =>
    model()
      .props('formOrder')!
      .map((item: { kind: string; id: string }) => `${item.kind}:${item.id}`);
  try {
    await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
    await page.getByText('表单', { exact: true }).click();
    await expect.poll(() => wrapper.find(node('ui:group-field:form:basic:b')).exists()).toBe(true);
    await commands.treeGesture(node('ui:group-field:form:basic:b'), node('ui:group:form:basic'), 0.95);
    await expect.poll(order).toEqual(['field:a', 'group:basic', 'field:b', 'group:empty']);
    expect(model().props('formGroups')[0].fields).toEqual([]);
    await expect
      .poll(() => {
        const fields = wrapper
          .findComponent({ name: 'PageCompositionDescriptorPreview' })
          .findComponent({ name: 'RecordFormFields' })
          .props('fields');
        return fields.has('b') && !fields.get('b')?.formGroup;
      })
      .toBe(true);
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect.element(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: '刷新：页面结构', exact: true }).click();
    await expect.poll(order).toEqual(['field:a', 'group:basic', 'field:b', 'group:empty']);
    await commands.treeGesture(node('ui:field:form:b'), node('ui:group:form:basic'), 0.5);
    await expect
      .poll(() =>
        model()
          .props('formGroups')[0]
          .fields.map((field: { id: string }) => field.id),
      )
      .toEqual(['b']);
  } finally {
    await commands.treeRelease();
    wrapper.unmount();
  }
});

function placementHttp(
  requests: HttpRequestOptions[],
  withRelations = false,
  mode = 'LIST_CARD',
  flatForm = false,
  formFieldCount = 4,
  rootFields?: string[],
  moreGroups = false,
): HttpClient {
  const fields = Array.from({ length: Math.max(5, formFieldCount + 1) }, (_, index) =>
    String.fromCharCode(97 + index),
  ).map((id, index) => ({
    id,
    fieldName: id,
    title: ['字段甲', '字段乙', '字段丙', '字段丁', '字段戊'][index] ?? `字段${id}`,
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
  }));
  const children = ['x', 'y', 'z'].map((id) => ({
    id,
    fieldName: id,
    title: `子字段${id}`,
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
  }));
  let tree = {
    template: 'management',
    templateVersion: 1,
    nodes: [
      { slot: 'list', fields: ['a'] },
      {
        slot: 'form',
        fields: rootFields ?? (flatForm ? fields.slice(0, formFieldCount).map((field) => field.id) : ['a']),
        relations: withRelations
          ? [
              { relation: 'child', title: '子表一', fields: ['x'] },
              { relation: 'other', title: '子表二', fields: ['z'] },
            ]
          : [],
        groups: flatForm
          ? []
          : [
              {
                group: 'basic',
                title: '基本分组',
                fields: [{ field: 'b', props: { label: '自定义乙', readOnly: true } }],
              },
              { group: 'empty', title: '空分组', fields: [] },
              ...(moreGroups
                ? [
                    {
                      group: 'third',
                      title: '第三分组',
                      fields: [
                        { field: 'c', props: {} },
                        { field: 'd', props: {} },
                      ],
                    },
                    { group: 'last', title: '末尾分组', fields: [] },
                  ]
                : []),
            ],
      },
    ],
  };
  const list = (records: unknown[]) => ({ records, pages: 1, totalKnown: true });
  return {
    request: async <T>(request: HttpRequestOptions) => {
      requests.push(request);
      const path = request.path;
      if (path.endsWith('/overview-mode'))
        return {
          overviewMode: mode,
          searchableFields: ['a', 'b', 'c'],
          compositionSkeletons: [
            {
              mode,
              title: '列表 + 卡片',
              navigationTitle: '记录列表',
              fieldGroupTitle: '列表展示字段',
              columns: mode === 'LIST_CARD',
              maxIdentityFields: 0,
            },
          ],
        } as T;
      if (path.endsWith('/context')) return { capabilities: [], actions: [] } as T;
      if (path.endsWith('/metadata-relations/query'))
        return list([
          { id: 'main', metadataId: 'main', relationAlias: '主实体', relationRole: 'main' },
          ...(withRelations
            ? ['child', 'other'].map((id, index) => ({
                id,
                metadataId: id,
                parentMetadataId: 'main',
                relationAlias: id,
                title: ['子表一', '子表二'][index],
                relationRole: 'child',
              }))
            : []),
        ]) as T;
      if (path.endsWith('/fields/query'))
        return list(/\/(child|other)\//.test(path) ? children : fields) as T;
      if (path.endsWith('/pages/query'))
        return list([{ id: 'page', alias: 'management', title: '拖拽验收', mainRelationId: 'main' }]) as T;
      if (path.endsWith('/presentation-variants/query'))
        return list([{ id: 'variant', pageId: 'page' }]) as T;
      if (path.endsWith('/revisions/query'))
        return list(
          JSON.stringify(request.body).includes('published')
            ? []
            : [{ id: 'draft', revisionNo: 1, version: 0, uiTreeJson: JSON.stringify(tree) }],
        ) as T;
      if (path.endsWith('/update/draft')) {
        tree = JSON.parse((request.body as { uiTreeJson: string }).uiTreeJson);
        return {
          id: 'draft',
          revisionNo: 1,
          version: 1,
          templateVersion: 2,
          uiTreeJson: JSON.stringify(tree),
        } as T;
      }
      if (path.endsWith('/preview')) {
        const draft = JSON.parse((request.body as { uiTreeJson: string }).uiTreeJson);
        const project = (entry: string | { field: string; props?: Record<string, unknown> }) => {
          const name = typeof entry === 'string' ? entry : entry.field;
          return {
            fieldRef: { fieldName: name },
            label:
              typeof entry === 'string'
                ? fields.find((field) => field.id === name)?.title
                : entry.props?.label,
            uiType: 'input',
            ...(typeof entry === 'string' ? {} : entry.props),
          };
        };
        const form = draft.nodes.find((node: { slot: string }) => node.slot === 'form');
        return {
          uiDescriptor: {
            schemaVersion: '1',
            detailRelations: (form.relations ?? []).map(
              (relation: { relation: string; title: string; fields: string[] }) => ({
                code: relation.relation,
                title: relation.title,
                listProjection: {
                  fields: relation.fields.map((fieldName) => ({ fieldName, title: `子字段${fieldName}` })),
                },
              }),
            ),
            moduleAlias: 'education.placement',
            page: {
              template:
                mode === 'TREE_CARD'
                  ? 'TREE_MANAGEMENT'
                  : mode === 'MICRO_LIST_CARD'
                    ? 'FLAT_MANAGEMENT'
                    : 'LIST_DETAIL_CARD',
              explorer:
                mode === 'LIST_CARD'
                  ? undefined
                  : {
                      ...draft.nodes.find((node: { slot: string }) => node.slot === 'explorer'),
                      searchPlaceholder: draft.props?.list?.searchPlaceholder,
                    },
              list: {
                fields: {
                  viewCode: 'list',
                  viewKind: 'LIST',
                  fields:
                    draft.nodes.find((node: { slot: string }) => node.slot === 'list')?.fields.map(project) ??
                    [],
                },
              },
              detail: {
                createTitle: '新建',
                emptyDescription: '无',
                editor: {
                  viewCode: 'editor',
                  viewKind: 'FORM',
                  fields: (form.order
                    ? form.order.flatMap((item: { field?: string; group?: string }) =>
                        item.field
                          ? form.fields.filter(
                              (field: string | { field: string }) =>
                                (typeof field === 'string' ? field : field.field) === item.field,
                            )
                          : form.groups.find((group: { group: string }) => group.group === item.group).fields,
                      )
                    : [
                        ...form.fields,
                        ...(form.groups ?? []).flatMap((group: { fields: unknown[] }) => group.fields),
                      ]
                  ).map(project),
                  formGroups: (form.groups ?? []).map(
                    (group: { group: string; title: string; fields: Array<string | { field: string }> }) => ({
                      groupCode: group.group,
                      title: group.title,
                      fields: group.fields.map((entry) => ({
                        fieldName: typeof entry === 'string' ? entry : entry.field,
                      })),
                    }),
                  ),
                },
              },
            },
          },
        } as T;
      }
      throw new Error(`Unexpected ${path}`);
    },
  };
}

it.each(['TREE_CARD', 'MICRO_LIST_CARD'])(
  'fills %s title, secondary and quick search using real tree gestures',
  async (mode) => {
    await page.viewport(1440, 950);
    const requests: HttpRequestOptions[] = [];
    configureModuleContext({ http: placementHttp(requests, false, mode) });
    const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 900 } });
    const tree = () => wrapper.findComponent(PageCompositionTree);
    try {
      await expect.poll(() => wrapper.find('[data-ui-tree-key="ui:explorer-title"]').exists()).toBe(true);
      await commands.treeGesture(
        '[data-ui-tree-key="metadata:field:a"]',
        '[data-ui-tree-key="ui:explorer-title"]',
      );
      await expect.poll(() => tree().props('explorerTitle')).toBe('字段甲');
      await commands.treeGesture(
        '[data-ui-tree-key="metadata:field:b"]',
        '[data-ui-tree-key="ui:explorer-secondary"]',
      );
      await expect.poll(() => tree().props('explorerSecondary')).toBe('字段乙');
      await commands.treeGesture(
        '[data-ui-tree-key="metadata:field:c"]',
        '[data-ui-tree-key="ui:template:list:quick-search"]',
      );
      await expect
        .poll(() =>
          tree()
            .props('quickSearchFields')
            ?.map((field: { fieldName: string }) => field.fieldName),
        )
        .toContain('c');
      const quick = wrapper.get('[data-ui-tree-key="ui:template:list:quick-search"]');
      await page.elementLocator(quick.element).dblClick();
      await page.getByRole('textbox', { name: '搜索占位提示', exact: true }).fill('按任务名称查找');
      await page.getByRole('button', { name: '关闭', exact: true }).click();
      await page.getByRole('button', { name: '保存草稿', exact: true }).click();
      await expect.poll(() => requests.some((request) => request.path.endsWith('/update/draft'))).toBe(true);
      const saved = requests.find((request) => request.path.endsWith('/update/draft'))!;
      const json = JSON.parse((saved.body as { uiTreeJson: string }).uiTreeJson);
      expect(json.mode).toBe(mode);
      expect(json.props.list.searchPlaceholder).toBe('按任务名称查找');
      expect(json.nodes.find((node: { slot: string }) => node.slot === 'explorer')).toMatchObject({
        titleField: 'a',
        secondaryField: 'b',
      });
      expect(json.quickSearchFields).toContain('c');
    } finally {
      wrapper.unmount();
    }
  },
);
