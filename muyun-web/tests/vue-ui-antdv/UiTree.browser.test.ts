import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { expect, it } from 'vitest';
import { commands } from 'vitest/browser';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';
import type { UiTreeDropEvent, UiTreeNode } from '@/vue-ui-antdv/types';
import 'ant-design-vue/dist/reset.css';

const node = (key: string): UiTreeNode => ({ key, title: key, isLeaf: true });
const selector = (id: string, key: string) => `#${id} [data-ui-tree-key="${key}"]`;
function tree(id: string, props: Partial<InstanceType<typeof UiTree>['$props']> = {}) {
  const wrapper = mount(UiTree, {
    attachTo: document.body,
    props: {
      nodes: [node('a'), node('b')],
      draggable: true,
      allowDrop: () => true,
      motionDurationMs: 100,
      ...props,
    },
  });
  wrapper.element.id = id;
  (wrapper.element as HTMLElement).style.width = '360px';
  return wrapper;
}
const frame = () =>
  new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));

it.each(['tree', 'flat'] as const)(
  'sorts %s with real mouse hit testing and suppresses post-drag selection',
  async (displayMode) => {
    const wrapper = tree('sort', { displayMode });
    await nextTick();
    await commands.treeGesture(selector('sort', 'b'), selector('sort', 'a'), 0.1, 'hold');
    const target = wrapper.get('[data-ui-tree-key="a"]');
    expect(target.classes()).toContain('ui-tree-node--drop-before');
    expect(target.get('.ui-tree-node__drop-indicator--before').isVisible()).toBe(true);
    await commands.treeRelease();
    expect(wrapper.emitted('drop')![0][0]).toMatchObject({ target: { position: 'before' } });
    expect(wrapper.emitted('select')).toBeUndefined();
    expect(wrapper.find('.ui-tree-node--dragging').exists()).toBe(false);
  },
);
it('keeps the tree inside-drop target visibly outlined', async () => {
  const wrapper = tree('inside-target', { selectedKey: 'b' });
  await nextTick();
  await commands.treeGesture(selector('inside-target', 'b'), selector('inside-target', 'a'), 0.5, 'hold');

  expect(wrapper.get('[data-ui-tree-key="b"] .ui-record-explorer-item').classes()).toContain(
    'ui-record-explorer-item-selected',
  );
  const target = wrapper.get('[data-ui-tree-key="a"]');
  expect(target.classes()).toContain('ui-tree-node--drop-inside');
  expect(getComputedStyle(target.get('.ui-record-explorer-item').element).boxShadow).not.toBe('none');

  await commands.treeRelease();
});
it.each(['copy', 'move'] as const)(
  'carries %s to another tree and cancels a second gesture',
  async (operation) => {
    const source = tree('source', { dragOperations: [operation], dragPayloadOf: () => ({ field: 'name' }) });
    const destination = tree('destination');
    await nextTick();
    await commands.treeGesture(selector('source', 'a'), selector('destination', 'b'));
    const event = destination.emitted('drop')![0][0] as UiTreeDropEvent;
    expect(event.operation).toBe(operation);
    expect(event.source.instanceId).not.toBe(event.target.instanceId);
    expect(event.source.payload).toEqual({ field: 'name' });
    expect(source.props('nodes')).toHaveLength(2);
    await commands.treeGesture(selector('source', 'a'), selector('destination', 'b'), 0.5, 'escape');
    expect(destination.emitted('drop')).toHaveLength(1);
    expect(destination.find('.ui-tree-node--drop-inside').exists()).toBe(false);
  },
);
it('shows root feedback for a cross-tree drop on a non-empty root', async () => {
  tree('root-source');
  const destination = tree('root-destination', { allowDrop: () => true });
  await nextTick();

  await commands.treeGesture(
    selector('root-source', 'a'),
    '#root-destination [data-ui-drop-root]',
    0.5,
    'hold',
  );

  const root = destination.get('[data-ui-drop-root]');
  expect(root.classes()).toContain('ui-tree__root-target--active');
  await expect.poll(() => getComputedStyle(root.element).opacity).toBe('1');
  await commands.treeRelease();
  expect(destination.emitted('drop')![0][0]).toMatchObject({ target: { kind: 'root' } });
});
it('moves nested nodes into leaves and empty roots using current permissions at release', async () => {
  const source = tree('nested', {
    nodes: [{ key: 'parent', title: 'Parent', children: [node('child')] }, node('leaf')],
    expandedKeys: ['parent'],
  });
  const destination = tree('empty', { nodes: [] });
  await nextTick();
  await commands.treeGesture(selector('nested', 'child'), selector('nested', 'leaf'));
  expect(source.emitted('drop')![0][0]).toMatchObject({
    target: { position: 'inside', node: { key: 'leaf' } },
  });
  await commands.treeGesture(selector('nested', 'child'), '#empty [data-ui-drop-root]');
  expect(destination.emitted('drop')![0][0]).toMatchObject({ target: { kind: 'root' } });
  await commands.treeGesture(selector('nested', 'child'), selector('nested', 'leaf'), 0.5, 'hold');
  await source.setProps({ allowDrop: () => false });
  await commands.treeRelease();
  expect(source.emitted('drop')).toHaveLength(1);
});
it('animates enter, move, update, reparent and leave, and removes ghosts', async () => {
  const wrapper = tree('motion', {
    nodes: [{ key: 'parent', title: 'Parent', children: [node('a')] }, node('b')],
    expandedKeys: ['parent'],
    motionDurationMs: 1000,
  });
  await frame();
  await wrapper.setProps({
    nodes: [node('b'), { key: 'parent', title: 'Parent', children: [node('a'), node('new')] }],
  });
  await frame();
  expect(wrapper.get('[data-ui-tree-key="b"]').element.getAnimations().length).toBeGreaterThan(0);
  expect(wrapper.get('[data-ui-tree-key="new"]').element.getAnimations().length).toBeGreaterThan(0);
  await wrapper.setProps({
    nodes: [node('b'), { key: 'parent', title: 'Changed', children: [node('new')] }, node('a')],
  });
  await frame();
  expect(wrapper.get('[data-ui-tree-key="parent"]').element.getAnimations().length).toBeGreaterThan(0);
  expect(wrapper.get('[data-ui-tree-key="a"]').element.getAnimations().length).toBeGreaterThan(0);
  expect(wrapper.findAll('[inert][aria-hidden="true"]')).toHaveLength(1);
  await wrapper.setProps({ nodes: [node('a')] });
  await frame();
  expect(wrapper.findAll('[inert][aria-hidden="true"]').length).toBeGreaterThan(0);
  await Promise.all(
    (wrapper.element as HTMLElement)
      .getAnimations({ subtree: true })
      .map((animation) => animation.finished.catch(() => {})),
  );
  expect(wrapper.find('[inert][aria-hidden="true"]').exists()).toBe(false);
});
it('suppresses structural animation on reset, filter and reduced motion', async () => {
  const wrapper = tree('quiet');
  await frame();
  for (const changeReason of ['reset', 'filter'] as const) {
    await wrapper.setProps({ changeReason, nodes: [node(changeReason)] });
    await frame();
    expect((wrapper.element as HTMLElement).getAnimations({ subtree: true })).toHaveLength(0);
  }
  await commands.treeReducedMotion(true);
  try {
    await wrapper.setProps({ changeReason: 'interaction', nodes: [node('reduced')] });
    await frame();
    expect((wrapper.element as HTMLElement).getAnimations({ subtree: true })).toHaveLength(0);
  } finally {
    await commands.treeReducedMotion(false);
  }
});
it('keeps keyboard focus after reparenting and supports cross-root keyboard movement', async () => {
  const source = tree('keyboard', {
    nodes: [{ key: 'parent', title: 'Parent', children: [node('child')] }, node('other')],
    expandedKeys: ['parent'],
  });
  const destination = tree('keyboard-target', { nodes: [] });
  await nextTick();
  const row = source.get('[data-ui-tree-key="child"]').element as HTMLElement;
  row.focus();
  await source.setProps({ nodes: [{ key: 'parent', title: 'Parent', children: [] }, node('child')] });
  expect(document.activeElement).toBe(source.get('[data-ui-tree-key="child"]').element);
  document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
  (destination.get('[data-ui-drop-root]').element as HTMLElement).focus();
  document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await nextTick();
  expect(destination.emitted('drop')![0][0]).toMatchObject({ target: { kind: 'root' } });
});
it('scrolls a long flat list during a held drag and commits once at the new hit position', async () => {
  const wrapper = tree('scroll', {
    displayMode: 'flat',
    nodes: Array.from({ length: 100 }, (_, i) => node(String(i))),
  });
  const container = wrapper.get('ul').element as HTMLElement;
  container.style.height = '140px';
  await nextTick();
  await commands.treeScrollGesture(selector('scroll', '0'), '#scroll ul');
  await expect.poll(() => container.scrollTop).toBeGreaterThan(0);
  await commands.treeRelease();
  expect(wrapper.emitted('drop')).toHaveLength(1);
  expect(wrapper.find('.ui-tree-node--dragging').exists()).toBe(false);
});
it('keeps queued structural motion through busy and hover state updates, and cancels on unmount', async () => {
  const wrapper = tree('busy-motion', { motionDurationMs: 1000 });
  await frame();
  await wrapper.setProps({ nodes: [node('b'), node('a')] });
  await wrapper.setProps({ draggable: false });
  await wrapper.setProps({ draggable: true });
  await frame();
  const animations = (wrapper.element as HTMLElement).getAnimations({ subtree: true });
  expect(animations.length).toBeGreaterThan(0);
  wrapper.unmount();
  expect(animations.every((animation) => animation.playState === 'idle')).toBe(true);
});
it('renders a moved identity once when its new parent opens in the same update', async () => {
  const wrapper = tree('expand-move', {
    nodes: [{ key: 'left', title: 'Left', children: [node('child')] }, node('right')],
    expandedKeys: ['left'],
  });
  await frame();
  await wrapper.setProps({
    nodes: [node('left'), { key: 'right', title: 'Right', children: [node('child')] }],
    expandedKeys: ['left', 'right'],
  });
  await frame();
  expect(wrapper.findAll('[data-ui-tree-key="child"]')).toHaveLength(1);
  expect(wrapper.get('[data-ui-tree-key="child"]').element.getAnimations().length).toBeGreaterThan(0);
});
