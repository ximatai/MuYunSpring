<script setup lang="ts">
import { workflowNodeAccent } from './workflowPresentation';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
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
  selectedNodeKeys?: string[];
  canUndo?: boolean;
  canRedo?: boolean;
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
  selectionChange: [keys: string[]];
  undo: [];
  redo: [];
  copy: [event: ClipboardEvent];
  paste: [event: ClipboardEvent];
  copyShortcut: [];
  pasteShortcut: [];
  deleteSelection: [];
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
  const gateway = ['branch', 'converge'].includes(node.type);
  const center = node.width / 2;
  const typeAccent = workflowNodeAccent(node.type);
  const selected = props.selectedNodeKey === node.key || props.selectedNodeKeys?.includes(node.key);
  const color =
    selected || node.status === 'active'
      ? 'var(--muyun-primary)'
      : node.status === 'completed'
        ? 'var(--muyun-success-text)'
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
      ...(gateway
        ? {
            d: `M ${center} 0 L ${node.width} ${node.height / 2} L ${center} ${node.height} L 0 ${node.height / 2} Z`,
          }
        : {}),
      width: node.width,
      height: node.height,
      rx: ['start', 'end'].includes(node.type) ? 30 : 10,
      fill:
        node.status === 'completed'
          ? 'var(--muyun-success-bg)'
          : gateway && selected
            ? 'var(--muyun-selected)'
            : 'var(--muyun-surface)',
      stroke: color,
      strokeWidth: selected || node.status === 'active' ? 2 : 1.3,
    },
    accent: {
      x: 16,
      y: 20,
      width: 4,
      height: node.height - 40,
      rx: 2,
      fill: typeAccent ?? color,
      stroke: 'none',
      display: gateway ? 'none' : 'block',
    },
    description: { text: `${node.title} ${node.subtitle}` },
    title: {
      refX: 0,
      refY: 0,
      x: gateway ? center : 32,
      y: gateway ? node.height / 2 : node.height === 64 ? 23 : 30,
      text: node.title,
      textWrap: {
        width: gateway ? node.width / 2 + 12 : node.width - 48,
        height: gateway ? 22 : node.height === 64 ? 22 : 40,
        ellipsis: true,
      },
      textAnchor: gateway ? 'middle' : 'start',
      textVerticalAnchor: 'middle',
      fontSize: gateway ? 13 : 14,
      fontWeight: 600,
      fontFamily: 'inherit',
      fill: 'var(--muyun-text)',
    },
    subtitle: {
      refX: 0,
      refY: 0,
      x: gateway ? center : 32,
      y: gateway ? 73 : node.height - 20,
      text: node.subtitle,
      display: gateway ? 'none' : 'block',
      ...(gateway ? { textWrap: { width: 104, height: 16, ellipsis: true } } : {}),
      fontSize: 11,
      textAnchor: gateway ? 'middle' : 'start',
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
        { tagName: ['branch', 'converge'].includes(node.type) ? 'path' : 'rect', selector: 'body' },
        { tagName: 'rect', selector: 'accent' },
        { tagName: 'text', selector: 'title' },
        { tagName: 'text', selector: 'subtitle' },
        { tagName: 'title', selector: 'description' },
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
  if (formTarget(event)) return;
  if (
    props.interactive &&
    props.presentation === 'designer' &&
    props.editable &&
    !event.isComposing &&
    !event.ctrlKey &&
    !event.metaKey &&
    !event.altKey &&
    ['Delete', 'Backspace'].includes(event.key)
  ) {
    event.preventDefault();
    if (!event.repeat) emit('deleteSelection');
    return;
  }
  if (
    props.interactive &&
    props.presentation === 'designer' &&
    (event.ctrlKey || event.metaKey) &&
    !event.altKey &&
    !event.isComposing
  ) {
    const key = event.key.toLowerCase();
    if (key === 'c' || (key === 'v' && props.editable)) {
      event.preventDefault();
      if (key === 'c') emit('copyShortcut');
      else emit('pasteShortcut');
      return;
    }
    if (key === 'a') {
      event.preventDefault();
      emit(
        'selectionChange',
        props.nodes.map((node) => node.nodeKey),
      );
      return;
    }
    if (props.editable && (key === 'z' || key === 'y')) {
      event.preventDefault();
      if (key === 'y' || event.shiftKey) emit('redo');
      else emit('undo');
      return;
    }
  }
  if (!props.interactive || !['Enter', ' '].includes(event.key) || !(event.target instanceof Element)) return;
  const node = event.target.closest('[data-workflow-node]')?.getAttribute('data-workflow-node');
  const route = event.target.closest('[data-workflow-route]')?.getAttribute('data-workflow-route');
  if (node || route) {
    event.preventDefault();
    if (node) void selectObject('node', node);
    else if (route) void selectObject('route', route);
  }
}
function formTarget(event: Event) {
  return (
    event.target instanceof Element &&
    Boolean(
      event.target.closest(
        'input, textarea, select, [contenteditable]:not([contenteditable="false"]), [role="textbox"], [role="combobox"]',
      ),
    )
  );
}
function clipboard(event: ClipboardEvent, action: 'copy' | 'paste') {
  if (!props.interactive || props.presentation !== 'designer' || formTarget(event)) return;
  if (action === 'copy') emit('copy', event);
  else if (props.editable) emit('paste', event);
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
  () => [
    props.selectedNodeKey,
    props.selectedNodeKeys,
    props.selectedRouteKey,
    props.interactive,
    props.editable,
  ],
  syncSelection,
);
async function selectObject(kind: 'node' | 'route', key: string) {
  if (document.fullscreenElement === shell.value) await document.exitFullscreen();
  if (kind === 'node') emit('select', key);
  else emit('selectRoute', key);
  if (props.presentation === 'designer') {
    await nextTick();
    viewport.value?.focus({ preventScroll: true });
  }
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
    graph.on('node:click', ({ node, e }) => {
      if (!props.interactive) return;
      const key = node.getData<{ key: string }>().key;
      if (props.presentation === 'designer' && (e.ctrlKey || e.metaKey || e.shiftKey)) {
        const selected = new Set(
          props.selectedNodeKeys ?? (props.selectedNodeKey ? [props.selectedNodeKey] : []),
        );
        if (selected.has(key)) selected.delete(key);
        else selected.add(key);
        emit('selectionChange', [...selected]);
        viewport.value?.focus({ preventScroll: true });
      } else void selectObject('node', key);
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
    @keydown="keyboard"
    @copy="clipboard($event, 'copy')"
    @paste="clipboard($event, 'paste')"
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
        <template v-if="presentation === 'designer'">
          <UiButton
            size="small"
            :disabled="!editable || !canUndo"
            :title="workflowLanguage.t('platform.workflow-config.undo-help', '撤销（Ctrl / ⌘ + Z）')"
            @click="emit('undo')"
            >{{ workflowLanguage.t('platform.workflow-config.undo', '撤销') }}</UiButton
          >
          <UiButton
            size="small"
            :disabled="!editable || !canRedo"
            :title="
              workflowLanguage.t(
                'platform.workflow-config.redo-help',
                '重做（Ctrl / ⌘ + Shift + Z / Ctrl + Y）',
              )
            "
            @click="emit('redo')"
            >{{ workflowLanguage.t('platform.workflow-config.redo', '重做') }}</UiButton
          >
        </template>
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
              : '') +
            '\n' +
            workflowLanguage.t(
              'platform.workflow-config.keyboard-help',
              '画布快捷键：Ctrl / ⌘ + Z 撤销，Shift + Z 重做，A 全选，C 复制，V 粘贴，Delete / Backspace 删除选中节点；按住 Shift 点击可多选。选择节点或路径作为粘贴位置，分支会整体复制。',
            )
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
    <div
      ref="viewport"
      class="diagram-viewport"
      :tabindex="presentation === 'designer' ? 0 : undefined"
      :aria-label="workflowLanguage.t('platform.workflow-config.canvas-editor', '流程画布编辑区')"
    >
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
.diagram-viewport :deep([role='button']:focus) {
  outline: none;
}
.diagram-viewport :deep([role='button']:focus-visible > :first-child) {
  stroke: var(--muyun-primary);
  stroke-width: 3px;
}
</style>
