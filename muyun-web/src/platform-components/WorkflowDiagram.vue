<script setup lang="ts">
import { computed, useId, ref, onMounted, onBeforeUnmount } from 'vue';
import type { WorkflowNode, WorkflowRoute } from '@muyun/web-contracts';
import { UiButton } from '@muyun/vue-ui-antdv';
import { workflowTitle } from './workflowPresentation';
defineOptions({ name: 'WorkflowDiagram' });
const markerId = `workflow-arrow-${useId()}`;
const props = defineProps<{
  nodes: WorkflowNode[];
  routes: WorkflowRoute[];
  selectedNodeKey?: string;
  interactive?: boolean;
}>();
const viewport = ref<HTMLElement>(),
  viewportWidth = ref(800),
  fit = ref(true);
let observer: ResizeObserver | undefined;
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined' && viewport.value) {
    observer = new ResizeObserver((entries) => {
      viewportWidth.value = entries[0]?.contentRect.width ?? 800;
    });
    observer.observe(viewport.value);
  }
});
onBeforeUnmount(() => observer?.disconnect());
const scale = computed(() => (fit.value ? Math.min(1, viewportWidth.value / graph.value.width) : 1));
const emit = defineEmits<{ select: [nodeKey: string] }>();
const graph = computed(() => {
  const ranks = new Map(props.nodes.map((node) => [node.nodeKey, 0]));
  const remaining = new Set(props.nodes.map((node) => node.nodeKey));
  const ordered: WorkflowNode[] = [];
  for (let step = 0; step < props.nodes.length; step++) {
    const ready = props.nodes.filter(
      (node) =>
        remaining.has(node.nodeKey) &&
        !props.routes.some(
          (route) => route.targetNodeKey === node.nodeKey && remaining.has(route.sourceNodeKey),
        ),
    );
    if (!ready.length) break;
    for (const node of ready) {
      remaining.delete(node.nodeKey);
      const rank = Math.max(
        0,
        ...props.routes
          .filter((route) => route.targetNodeKey === node.nodeKey)
          .map((route) => (ranks.get(route.sourceNodeKey) ?? 0) + 1),
      );
      ranks.set(node.nodeKey, rank);
      ordered.push(node);
    }
  }
  ordered.push(...props.nodes.filter((node) => remaining.has(node.nodeKey)));
  const counts = new Map<number, number>();
  const boxes = ordered.map((node) => {
    const rank = ranks.get(node.nodeKey) ?? 0;
    const row = counts.get(rank) ?? 0;
    counts.set(rank, row + 1);
    return { node, x: 24 + rank * 205, y: 24 + row * 115 };
  });
  const byKey = new Map(boxes.map((box) => [box.node.nodeKey, box]));
  return {
    boxes,
    width: Math.max(400, ...boxes.map((box) => box.x + 185)),
    height: Math.max(160, ...boxes.map((box) => box.y + 100)),
    lines: props.routes.flatMap((route) => {
      const source = byKey.get(route.sourceNodeKey),
        target = byKey.get(route.targetNodeKey);
      if (!source || !target) return [];
      const x1 = source.x + 170,
        y1 = source.y + 40,
        x2 = target.x,
        y2 = target.y + 40;
      return [
        {
          route,
          path: `M${x1},${y1} C${(x1 + x2) / 2},${y1} ${(x1 + x2) / 2},${y2} ${x2},${y2}`,
          x: (x1 + x2) / 2,
          y: (y1 + y2) / 2 - 8,
        },
      ];
    }),
  };
});
</script>
<template>
  <div class="workflow-diagram" aria-label="流程图">
    <div class="diagram-tools">
      <UiButton size="small" :disabled="fit" @click="fit = true">适应宽度</UiButton
      ><UiButton size="small" :disabled="!fit" @click="fit = false">实际大小</UiButton>
    </div>
    <div ref="viewport" class="diagram-viewport" :style="{ height: `${graph.height * scale}px` }">
      <div
        class="diagram-canvas"
        :style="{ width: `${graph.width}px`, height: `${graph.height}px`, transform: `scale(${scale})` }"
      >
        <svg
          :viewBox="`0 0 ${graph.width} ${graph.height}`"
          :width="graph.width"
          :height="graph.height"
          role="img"
          aria-label="流程连线"
        >
          <defs>
            <marker :id="markerId" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
              <path d="M0,0 L8,4 L0,8 Z" fill="currentColor" />
            </marker>
          </defs>
          <g
            v-for="line in graph.lines"
            :key="line.route.routeKey"
            :class="['workflow-route', line.route.routeStatus]"
          >
            <path :d="line.path" :marker-end="`url(#${markerId})`" />
            <text :x="line.x" :y="line.y" text-anchor="middle">
              {{ line.route.title ?? (line.route.defaultRoute ? '默认' : '') }}
            </text>
          </g>
        </svg>
        <component
          :is="interactive ? 'button' : 'div'"
          v-for="box in graph.boxes"
          :key="box.node.nodeKey"
          type="button"
          :class="['workflow-node', box.node.nodeStatus, { selected: selectedNodeKey === box.node.nodeKey }]"
          :style="{ left: `${box.x}px`, top: `${box.y}px` }"
          :tabindex="interactive ? 0 : undefined"
          @click="interactive && emit('select', box.node.nodeKey)"
        >
          <strong>{{ box.node.nodeTitle ?? box.node.title ?? box.node.nodeKey }}</strong>
          <span
            >{{ workflowTitle(box.node.nodeType) }} ·
            {{
              box.node.nodeStatus === 'waiting' && box.node.nodeType !== 'converge'
                ? '未到达'
                : workflowTitle(box.node.nodeStatus ?? box.node.approvalMode)
            }}</span
          >
        </component>
      </div>
    </div>
  </div>
</template>
<style scoped>
.workflow-diagram {
  position: relative;
  overflow: auto;
  background: var(--muyun-hover);
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
}
.diagram-tools {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 8px;
}
.diagram-viewport {
  position: relative;
  overflow: auto;
  min-height: 120px;
}
.diagram-canvas {
  position: relative;
  transform-origin: top left;
}
.workflow-node {
  position: absolute;
  width: 170px;
  min-height: 80px;
  padding: 12px;
  text-align: left;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-surface);
  color: inherit;
  cursor: default;
  display: grid;
  gap: 8px;
}
button.workflow-node {
  cursor: pointer;
}
.workflow-node span {
  font-size: 12px;
  color: var(--muyun-text-muted);
}
.workflow-node.active,
.workflow-node.selected {
  border: 2px solid var(--muyun-primary);
}
.workflow-node.completed {
  border-color: var(--muyun-success-text);
  background: var(--muyun-success-bg);
}
.workflow-node.skipped {
  opacity: 0.5;
}
.workflow-route {
  color: var(--muyun-text-muted);
}
.workflow-route path {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
}
.workflow-route text {
  fill: currentColor;
  font-size: 11px;
}
.workflow-route.effective,
.workflow-route.closed {
  color: var(--muyun-success-text);
}
.workflow-route.ineffective,
.workflow-route.dropped {
  opacity: 0.4;
}
</style>
