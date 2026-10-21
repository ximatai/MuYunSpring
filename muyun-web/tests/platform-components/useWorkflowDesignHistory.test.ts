import { effectScope, ref } from 'vue';
import { expect, it } from 'vitest';
import { useWorkflowDesignHistory } from '@/platform-components/useWorkflowDesignHistory';
import type { WorkflowDesign } from '@muyun/web-contracts';
it('restores semantic settings and layout together and derives dirty state from the last successful save', () => {
  const scope = effectScope();
  scope.run(() => {
    const design = ref<WorkflowDesign>({
      nodes: [{ nodeKey: 'a', nodeType: 'approval', title: '原名' }],
      links: [],
    });
    const history = useWorkflowDesignHistory(design, 3);
    design.value = { ...design.value, nodes: [{ ...design.value.nodes[0], title: '新名' }] };
    design.value = { ...design.value, layoutJson: '位置' };
    history.markSaved();
    expect(history.dirty.value).toBe(false);
    history.undo();
    expect(design.value.nodes[0].title).toBe('新名');
    expect(design.value.layoutJson).toBeUndefined();
    expect(history.dirty.value).toBe(true);
    history.redo();
    expect(history.dirty.value).toBe(false);
    history.undo();
    design.value = { ...design.value, layoutJson: '另一位置' };
    expect(history.canRedo.value).toBe(false);
    history.undo();
    history.undo();
    expect(design.value.nodes[0].title).toBe('原名');
    history.reset({ nodes: [], links: [] });
    expect(history.canUndo.value).toBe(false);
    expect(history.dirty.value).toBe(false);
  });
  scope.stop();
});
it('bounds history, ignores no-op updates and resets version-local undo without changing an unsaved initial template', () => {
  const scope = effectScope();
  scope.run(() => {
    const empty: WorkflowDesign = { nodes: [], links: [] };
    const design = ref(empty);
    const history = useWorkflowDesignHistory(design, 2);
    history.reset({ nodes: [{ nodeKey: 'start', nodeType: 'start' }], links: [] }, empty);
    expect(history.dirty.value).toBe(true);
    design.value = JSON.parse(JSON.stringify(design.value));
    expect(history.canUndo.value).toBe(false);
    for (const layoutJson of ['one', 'two', 'three']) design.value = { ...design.value, layoutJson };
    history.undo();
    history.undo();
    expect(design.value.layoutJson).toBe('two');
    expect(history.canUndo.value).toBe(false);
    history.reset(empty);
    expect(history.canRedo.value).toBe(false);
  });
  scope.stop();
});

it('invalidates async checkpoints on edits, undo/redo, session reset and scope disposal, but not saves or no-ops', () => {
  const scope = effectScope();
  let isCurrent!: () => boolean;
  scope.run(() => {
    const original: WorkflowDesign = { nodes: [{ nodeKey: 'a', nodeType: 'approval' }], links: [] };
    const design = ref(original);
    const history = useWorkflowDesignHistory(design);
    isCurrent = history.checkpoint();
    history.markSaved();
    design.value = JSON.parse(JSON.stringify(design.value));
    expect(isCurrent()).toBe(true);
    design.value = { ...design.value, layoutJson: 'moved' };
    expect(isCurrent()).toBe(false);
    const beforeUndo = history.checkpoint();
    history.undo();
    history.redo();
    expect(beforeUndo()).toBe(false);
    const beforeReset = history.checkpoint();
    history.reset(design.value);
    expect(beforeReset()).toBe(false);
    isCurrent = history.checkpoint();
    expect(isCurrent()).toBe(true);
  });
  scope.stop();
  expect(isCurrent()).toBe(false);
});
