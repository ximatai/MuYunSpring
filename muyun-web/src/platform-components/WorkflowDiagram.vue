<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import type { Graph, Node } from '@antv/x6';
import type { WorkflowNode, WorkflowRoute } from '@muyun/web-contracts';
import { UiButton, UiSelect, UiTooltip } from '@muyun/vue-ui-antdv';
import {
  createWorkflowDiagramModel,
  serializeWorkflowDiagramLayout,
  type WorkflowDiagramNode,
} from './workflowDiagramModel';
import { presentPlatformError } from './platformErrorFeedback';

defineOptions({ name: 'WorkflowDiagram' });
const workflowLanguage = useLanguageContext();
const props = defineProps<{
  nodes: WorkflowNode[];
  routes: WorkflowRoute[];
  selectedNodeKey?: string;
  selectedRouteKey?: string;
  interactive?: boolean;
  editable?: boolean;
  layoutJson?: string;
  semanticJson?: string;
  /** Detail views keep business facts visible; the designer retains a larger canvas. */
  presentation?: 'detail' | 'designer';
}>();
const emit = defineEmits<{
  select: [nodeKey: string];
  selectRoute: [routeKey: string];
  layoutChange: [layoutJson: string];
}>();
const shell = ref<HTMLElement>(),
  viewport = ref<HTMLElement>(),
  canvas = ref<HTMLElement>();
const ready = ref(false),
  failed = ref(false),
  zoom = ref(100),
  fullscreen = ref(false);
const model = computed(() =>
  createWorkflowDiagramModel(
    props.nodes,
    props.routes,
    props.layoutJson,
    props.semanticJson,
    workflowLanguage,
  ),
);
const startNode = computed(() => model.value.nodes.find((node) => node.type === 'start'));
const endNode = computed(() => model.value.nodes.find((node) => node.type === 'end'));
const focusNode = computed(
  () =>
    model.value.nodes.find((node) => node.status === 'active') ??
    model.value.nodes.find((node) => node.type === 'end' && node.status === 'completed') ??
    model.value.nodes.find((node) => node.type === 'start') ??
    model.value.nodes[0],
);
const focusTitle = computed(() =>
  focusNode.value?.status === 'active'
    ? workflowLanguage.t('platform.screen.WorkflowDiagram.locate-current', '定位当前节点')
    : focusNode.value?.type === 'end'
      ? workflowLanguage.t('platform.screen.WorkflowDiagram.locate-end', '定位结束节点')
      : workflowLanguage.t('platform.screen.WorkflowDiagram.locate-start', '定位开始节点'),
);
let graph: Graph | undefined,
  observer: ResizeObserver | undefined,
  disposed = false,
  initialized = false;

function nodeAttributes(node: WorkflowDiagramNode) {
  const selected = props.selectedNodeKey === node.key;
  const color =
    selected || node.status === 'active'
      ? 'var(--muyun-primary)'
      : node.status === 'completed'
        ? 'var(--muyun-success-text)'
        : ['branch', 'converge'].includes(node.type)
          ? 'var(--muyun-primary)'
          : 'var(--muyun-border)';
  return {
    root: {
      role: props.interactive ? 'button' : 'img',
      tabindex: props.interactive ? 0 : -1,
      'aria-label': `${node.title} ${node.subtitle}`,
      'aria-pressed': props.interactive ? String(selected) : undefined,
      'data-workflow-node': node.key,
      style: { cursor: props.editable ? 'move' : props.interactive ? 'pointer' : 'default' },
      opacity: node.status === 'skipped' ? 0.5 : 1,
    },
    body: {
      width: node.width,
      height: node.height,
      rx: ['start', 'end'].includes(node.type) ? 30 : 10,
      fill: node.status === 'completed' ? 'var(--muyun-success-bg)' : 'var(--muyun-surface)',
      stroke: color,
      strokeWidth: selected || node.status === 'active' ? 2 : 1.3,
    },
    accent: { x: 16, y: 20, width: 4, height: node.height - 40, rx: 2, fill: color, stroke: 'none' },
    title: {
      refX: 0,
      refY: 0,
      x: 32,
      y: node.height === 64 ? 23 : 30,
      text: node.title,
      textWrap: { width: node.width - 48, height: node.height === 64 ? 22 : 40, ellipsis: true },
      textAnchor: 'start',
      textVerticalAnchor: 'middle',
      fontSize: 14,
      fontWeight: 600,
      fontFamily: 'inherit',
      fill: 'var(--muyun-text)',
    },
    subtitle: {
      refX: 0,
      refY: 0,
      x: 32,
      y: node.height - 20,
      text: node.subtitle,
      fontSize: 11,
      textAnchor: 'start',
      fontFamily: 'inherit',
      fill: 'var(--muyun-text-muted)',
    },
  };
}
function routeColor(status?: string, selected?: boolean) {
  return selected
    ? 'var(--muyun-primary)'
    : ['effective', 'closed'].includes(status ?? '')
      ? 'var(--muyun-success-text)'
      : 'var(--muyun-text-muted)';
}
function render() {
  if (!graph) return;
  const cards = model.value.nodes.map((node) =>
    graph!.createNode({
      id: `node:${node.key}`,
      shape: 'rect',
      x: node.x,
      y: node.y,
      width: node.width,
      height: node.height,
      data: { key: node.key },
      markup: [
        { tagName: 'rect', selector: 'body' },
        { tagName: 'rect', selector: 'accent' },
        { tagName: 'text', selector: 'title' },
        { tagName: 'text', selector: 'subtitle' },
      ],
      attrs: nodeAttributes(node),
      zIndex: 2,
    }),
  );
  const edges = model.value.routes.map((route) => {
    const selected = props.selectedRouteKey === route.key;
    const inactive = ['ineffective', 'dropped'].includes(route.status ?? '');
    const color = routeColor(route.status, selected);
    const parallel = model.value.routes.filter(
      (item) => item.source === route.source && item.target === route.target,
    );
    const lane = parallel.findIndex((item) => item.key === route.key) - (parallel.length - 1) / 2;
    const offset = lane * Math.min(32, 160 / parallel.length);
    const source = model.value.nodes.find((node) => node.key === route.source)!;
    const target = model.value.nodes.find((node) => node.key === route.target)!;
    return graph!.createEdge({
      id: `route:${route.key}`,
      source: { cell: `node:${route.source}`, anchor: { name: 'bottom', args: { dx: offset } } },
      target: { cell: `node:${route.target}`, anchor: { name: 'top', args: { dx: offset } } },
      vertices:
        parallel.length > 1
          ? [
              {
                x: (source.x + source.width / 2 + target.x + target.width / 2) / 2 + offset,
                y: (source.y + source.height + target.y) / 2 + offset,
              },
            ]
          : route.vertices,
      data: { key: route.key },
      zIndex: 1,
      router: { name: 'orth', args: { padding: 12 } },
      connector: { name: 'rounded', args: { radius: 8 } },
      attrs: {
        root: {
          role: props.interactive ? 'button' : 'img',
          tabindex: props.interactive ? 0 : -1,
          'aria-label': workflowLanguage.t('platform.screen.WorkflowDiagram.route-label', '路径：{route}', {
            route: route.title || route.key,
          }),
          'data-workflow-route': route.key,
          style: { cursor: props.interactive ? 'pointer' : 'default' },
        },
        line: {
          stroke: color,
          strokeWidth: selected ? 2.5 : 1.5,
          strokeDasharray: inactive ? '5 4' : '',
          opacity: inactive ? 0.4 : 1,
          targetMarker: { name: 'block', width: 8, height: 6 },
        },
        wrap: { strokeWidth: 16 },
      },
      labels: route.title
        ? [
            {
              position: 0.4,
              attrs: {
                label: { text: route.title, fontSize: 11, fill: color, fontFamily: 'inherit' },
                body: { fill: 'var(--muyun-surface)', stroke: 'var(--muyun-border-subtle)', rx: 4, ry: 4 },
              },
            },
          ]
        : [],
    });
  });
  graph.resetCells([...cards, ...edges]);
  if (!initialized && cards.length && (viewport.value?.clientWidth ?? 0) > 0) {
    initialized = true;
    locate(focusNode.value?.key);
  }
}
function syncSelection() {
  if (!graph) return;
  model.value.nodes.forEach((node) =>
    (graph!.getCellById(`node:${node.key}`) as Node | null)?.setAttrs(nodeAttributes(node)),
  );
  model.value.routes.forEach((route) => {
    const selected = props.selectedRouteKey === route.key;
    graph!.getCellById(`route:${route.key}`)?.attr('line/stroke', routeColor(route.status, selected));
    graph!.getCellById(`route:${route.key}`)?.attr('line/strokeWidth', selected ? 2.5 : 1.5);
  });
}
function keyboard(event: KeyboardEvent) {
  if (!props.interactive || !['Enter', ' '].includes(event.key) || !(event.target instanceof Element)) return;
  const node = event.target.closest('[data-workflow-node]')?.getAttribute('data-workflow-node');
  const route = event.target.closest('[data-workflow-route]')?.getAttribute('data-workflow-route');
  if (node || route) {
    event.preventDefault();
    if (node) void selectObject('node', node);
    else if (route) void selectObject('route', route);
  }
}
function locate(key?: string, preserveZoom = false) {
  const cell = key && graph?.getCellById(`node:${key}`);
  if (!cell || !graph) return;
  if (!preserveZoom) graph.zoomTo(1);
  const bounds = cell.getBBox();
  graph.positionPoint({ x: bounds.center.x, y: bounds.center.y }, '50%', '50%');
}
function fit() {
  graph?.zoomToFit({ padding: 36, maxScale: 1, minScale: 0.15 });
}
function changeZoom(delta: number) {
  graph?.zoom(delta);
}
function actualSize() {
  graph?.zoomTo(1);
}
function savePositions() {
  if (!graph || !props.editable) return;
  emit(
    'layoutChange',
    serializeWorkflowDiagramLayout(
      graph.getNodes().map((node) => ({ key: node.getData<{ key: string }>().key, ...node.position() })),
      props.routes,
    ),
  );
}
function autoLayout() {
  if (!graph || !props.editable) return;
  emit(
    'layoutChange',
    serializeWorkflowDiagramLayout(
      createWorkflowDiagramModel(props.nodes, props.routes, undefined, props.semanticJson).nodes,
      props.routes,
    ),
  );
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement === shell.value) await document.exitFullscreen();
    else await shell.value?.requestFullscreen();
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-diagram', phase: 'action' });
  }
}
function fullscreenChanged() {
  fullscreen.value = document.fullscreenElement === shell.value;
}
watch(model, render);
watch(
  () => props.nodes.find((node) => node.nodeStatus === 'active')?.nodeKey,
  (key, previous) => {
    if (initialized && key && key !== previous) locate(key);
  },
  { flush: 'post' },
);
watch(
  () => [props.selectedNodeKey, props.selectedRouteKey, props.interactive, props.editable],
  syncSelection,
);
async function selectObject(kind: 'node' | 'route', key: string) {
  if (document.fullscreenElement === shell.value) await document.exitFullscreen();
  if (kind === 'node') emit('select', key);
  else emit('selectRoute', key);
}
onMounted(async () => {
  document.addEventListener('fullscreenchange', fullscreenChanged);
  try {
    const { Graph } = await import('@antv/x6');
    if (disposed || !viewport.value || !canvas.value) return;
    graph = new Graph({
      container: canvas.value,
      width: viewport.value.clientWidth,
      height: viewport.value.clientHeight,
      async: false,
      background: false,
      grid: { size: 1, visible: false },
      scaling: { min: 0.15, max: 2 },
      panning: { enabled: true, eventTypes: ['leftMouseDown', 'mouseWheel'] },
      mousewheel: { enabled: true, modifiers: ['ctrl', 'meta'], factor: 1.1 },
      interacting: () => ({
        nodeMovable: Boolean(props.editable),
        edgeMovable: false,
        edgeLabelMovable: false,
        arrowheadMovable: false,
        vertexMovable: false,
        vertexAddable: false,
        vertexDeletable: false,
      }),
      connecting: { allowBlank: false, allowLoop: false },
    });
    graph.on('node:click', ({ node }) => {
      if (props.interactive) void selectObject('node', node.getData<{ key: string }>().key);
    });
    graph.on('edge:click', ({ edge }) => {
      if (props.interactive) void selectObject('route', edge.getData<{ key: string }>().key);
    });
    graph.on('node:moved', savePositions);
    graph.on('scale', ({ sx }) => {
      zoom.value = Math.round(sx * 100);
    });
    observer = new ResizeObserver(() => {
      if (graph && viewport.value) {
        graph.resize(viewport.value.clientWidth, viewport.value.clientHeight);
        if (viewport.value.clientWidth === 0 || viewport.value.clientHeight === 0) return;
        if (!initialized) render();
      }
    });
    observer.observe(viewport.value);
    render();
    ready.value = true;
  } catch (cause) {
    failed.value = true;
    presentPlatformError(cause, { source: 'workflow-diagram', phase: 'load' });
  }
});
onBeforeUnmount(() => {
  disposed = true;
  observer?.disconnect();
  graph?.dispose();
  document.removeEventListener('fullscreenchange', fullscreenChanged);
});
</script>
<template>
  <section
    ref="shell"
    class="workflow-diagram"
    :class="{
      'workflow-diagram--detail': presentation === 'detail',
      'workflow-diagram--designer': presentation === 'designer',
    }"
    :aria-label="workflowLanguage.t('platform.screen.WorkflowDiagram.diagram', '流程图')"
  >
    <div v-if="$slots.actions" class="diagram-edit-tools"><slot name="actions" /></div>
    <div class="diagram-header">
      <div
        class="diagram-tools"
        role="group"
        :aria-label="workflowLanguage.t('platform.screen.WorkflowDiagram.tools', '流程画布工具')"
      >
        <UiButton
          size="small"
          icon-only
          icon-name="fit-screen"
          :aria-label="workflowLanguage.t('platform.screen.WorkflowDiagram.fit', '全图')"
          :title="workflowLanguage.t('platform.screen.WorkflowDiagram.fit', '全图')"
          :disabled="!ready"
          @click="fit"
        />
        <UiButton
          size="small"
          icon-only
          icon-name="minus"
          :aria-label="workflowLanguage.t('platform.screen.WorkflowDiagram.zoom-out', '缩小')"
          :title="workflowLanguage.t('platform.screen.WorkflowDiagram.zoom-out', '缩小')"
          :disabled="!ready || zoom <= 15"
          @click="changeZoom(-0.15)"
        />
        <UiButton class="diagram-zoom-value" size="small" :disabled="!ready" @click="actualSize"
          >{{ zoom }}%</UiButton
        >
        <UiButton
          size="small"
          icon-only
          icon-name="plus"
          :aria-label="workflowLanguage.t('platform.screen.WorkflowDiagram.zoom-in', '放大')"
          :title="workflowLanguage.t('platform.screen.WorkflowDiagram.zoom-in', '放大')"
          :disabled="!ready || zoom >= 200"
          @click="changeZoom(0.15)"
        />
        <template v-if="presentation === 'designer'">
          <UiButton
            size="small"
            icon-only
            icon-name="locate-start"
            :aria-label="workflowLanguage.t('platform.screen.WorkflowDiagram.locate-start', '定位开始节点')"
            :title="workflowLanguage.t('platform.screen.WorkflowDiagram.locate-start', '定位开始节点')"
            :disabled="!ready || !startNode"
            @click="locate(startNode?.key)"
          />
          <UiButton
            size="small"
            icon-only
            icon-name="locate-end"
            :aria-label="workflowLanguage.t('platform.screen.WorkflowDiagram.locate-end', '定位结束节点')"
            :title="workflowLanguage.t('platform.screen.WorkflowDiagram.locate-end', '定位结束节点')"
            :disabled="!ready || !endNode"
            @click="locate(endNode?.key)"
          />
        </template>
        <UiButton
          size="small"
          icon-only
          v-if="presentation !== 'designer'"
          icon-name="locate"
          :aria-label="focusTitle"
          :title="focusTitle"
          :disabled="!ready || !focusNode"
          @click="locate(focusNode?.key)"
        />
        <UiButton
          v-if="editable"
          size="small"
          :aria-label="workflowLanguage.t('platform.screen.WorkflowDiagram.auto-layout', '自动整理')"
          :title="workflowLanguage.t('platform.screen.WorkflowDiagram.auto-layout', '自动整理')"
          :disabled="!ready"
          @click="autoLayout"
          >{{ workflowLanguage.t('platform.screen.WorkflowDiagram.auto-layout', '自动整理') }}</UiButton
        >
        <UiButton
          size="small"
          icon-only
          :icon-name="fullscreen ? 'fullscreen-exit' : 'fullscreen'"
          :aria-label="
            fullscreen
              ? workflowLanguage.t('platform.screen.WorkflowDiagram.exit-fullscreen', '退出全屏')
              : workflowLanguage.t('platform.screen.WorkflowDiagram.fullscreen', '全屏')
          "
          :title="
            fullscreen
              ? workflowLanguage.t('platform.screen.WorkflowDiagram.exit-fullscreen', '退出全屏')
              : workflowLanguage.t('platform.screen.WorkflowDiagram.fullscreen', '全屏')
          "
          :disabled="!ready"
          @click="toggleFullscreen"
        />
      </div>
      <div class="diagram-navigation">
        <UiSelect
          class="diagram-locator"
          :value="undefined"
          :placeholder="workflowLanguage.t('platform.screen.WorkflowDiagram.locate-node', '定位节点')"
          :options="model.nodes.map((node) => ({ value: node.key, label: node.title }))"
          @update:value="locate(String($event))"
        />
        <slot name="navigation" />
        <span v-if="presentation !== 'designer'"
          >{{
            workflowLanguage.t(
              'platform.screen.WorkflowDiagram.pan-help',
              '拖动空白平移 · Ctrl / ⌘ + 滚轮缩放',
            )
          }}<span v-if="editable">{{
            workflowLanguage.t('platform.screen.WorkflowDiagram.drag-help', ' · 拖动节点调整布局')
          }}</span></span
        >
        <UiTooltip
          v-if="presentation === 'designer'"
          :title="
            workflowLanguage.t(
              'platform.screen.WorkflowDiagram.pan-help',
              '拖动空白平移 · Ctrl / ⌘ + 滚轮缩放',
            ) +
            (editable
              ? workflowLanguage.t('platform.screen.WorkflowDiagram.drag-help', ' · 拖动节点调整布局')
              : '')
          "
        >
          <UiButton
            size="small"
            :aria-label="
              workflowLanguage.t(
                'platform.screen.WorkflowDiagram.pan-help',
                '拖动空白平移 · Ctrl / ⌘ + 滚轮缩放',
              )
            "
            >?</UiButton
          >
        </UiTooltip>
      </div>
    </div>
    <p v-if="failed" role="alert">
      {{
        workflowLanguage.t('platform.screen.WorkflowDiagram.load-failed', '流程图加载失败，请刷新页面重试。')
      }}
    </p>
    <p v-else-if="!nodes.length" class="diagram-empty">
      {{ workflowLanguage.t('platform.screen.WorkflowDiagram.empty', '暂无流程节点') }}
    </p>
    <div ref="viewport" class="diagram-viewport" @keydown="keyboard">
      <div ref="canvas" class="diagram-canvas" />
    </div>
  </section>
</template>
<style scoped>
.workflow-diagram {
  overflow: hidden;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 10px;
  background: var(--muyun-surface);
}
.diagram-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  border-bottom: 1px solid var(--muyun-border-subtle);
}
.diagram-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
}
.diagram-zoom-value {
  width: 56px;
  min-width: 56px;
  flex: 0 0 56px;
  font-variant-numeric: tabular-nums;
}
.diagram-navigation {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--muyun-text-muted);
}
.diagram-locator {
  width: 160px;
}
.diagram-viewport {
  height: 480px;
  min-width: 0;
  background: var(--muyun-hover);
}
.workflow-diagram--designer {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  container-type: inline-size;
}
.workflow-diagram--designer .diagram-viewport {
  flex: 1;
  height: auto;
  min-height: 0;
}
.workflow-diagram--designer .diagram-header {
  border: 0;
}
.workflow-diagram--designer .diagram-tools,
.workflow-diagram--designer .diagram-navigation,
.diagram-edit-tools {
  position: absolute;
  z-index: 2;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  max-width: calc(100% - 24px);
  padding: 6px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-surface);
  box-shadow: 0 2px 8px rgb(0 0 0 / 6%);
}
.diagram-edit-tools {
  top: 12px;
  left: 12px;
}
.workflow-diagram--designer .diagram-navigation {
  top: 12px;
  right: 12px;
}
.workflow-diagram--designer .diagram-tools {
  left: 12px;
  bottom: 12px;
}
@container (max-width: 640px) {
  .workflow-diagram--designer .diagram-navigation {
    top: 66px;
    left: 12px;
    right: auto;
  }
  .workflow-diagram--designer .diagram-locator {
    width: 130px;
  }
}
.workflow-diagram--detail .diagram-viewport {
  height: 320px;
}
.diagram-empty {
  padding: 12px;
  color: var(--muyun-text-muted);
}
.workflow-diagram:fullscreen {
  display: flex;
  flex-direction: column;
  border: 0;
  border-radius: 0;
}
.workflow-diagram:fullscreen .diagram-viewport {
  flex: 1;
  height: auto;
}
.diagram-viewport :deep([role='button']:focus-visible) {
  outline: none;
}
.diagram-viewport :deep([role='button']:focus-visible > rect:first-child) {
  stroke: var(--muyun-primary);
  stroke-width: 3px;
}
</style>
