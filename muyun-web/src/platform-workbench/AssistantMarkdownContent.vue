<script setup lang="ts">
import { computed } from 'vue';
import { renderAssistantMarkdown } from './assistantMarkdown';

defineOptions({ name: 'AssistantMarkdownContent' });

const props = defineProps<{ content: string }>();
const renderedContent = computed(() => renderAssistantMarkdown(props.content));
</script>

<template>
  <!-- The renderer disables raw HTML and restricts links before this boundary. -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="assistant-markdown" v-html="renderedContent" />
</template>

<style scoped>
.assistant-markdown {
  min-width: 0;
  overflow-wrap: anywhere;
}

.assistant-markdown :deep(> :first-child) {
  margin-top: 0;
}

.assistant-markdown :deep(> :last-child) {
  margin-bottom: 0;
}

.assistant-markdown :deep(p),
.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol),
.assistant-markdown :deep(blockquote),
.assistant-markdown :deep(pre),
.assistant-markdown :deep(table) {
  margin: 0 0 8px;
}

.assistant-markdown :deep(h1),
.assistant-markdown :deep(h2),
.assistant-markdown :deep(h3),
.assistant-markdown :deep(h4),
.assistant-markdown :deep(h5),
.assistant-markdown :deep(h6) {
  margin: 12px 0 6px;
  color: inherit;
  font-size: 1em;
  font-weight: 600;
  line-height: 1.45;
}

.assistant-markdown :deep(h1),
.assistant-markdown :deep(h2) {
  font-size: 1.08em;
}

.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol) {
  padding-left: 20px;
}

.assistant-markdown :deep(li + li) {
  margin-top: 3px;
}

.assistant-markdown :deep(blockquote) {
  padding-left: 10px;
  border-left: 3px solid var(--muyun-support-border);
  color: var(--muyun-support-text-muted);
}

.assistant-markdown :deep(code) {
  padding: 1px 4px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--muyun-support-text) 8%, transparent);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.9em;
}

.assistant-markdown :deep(pre) {
  overflow-x: auto;
  padding: 10px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 6px;
  background: var(--muyun-support-surface);
  white-space: pre;
}

.assistant-markdown :deep(pre code) {
  padding: 0;
  background: transparent;
}

.assistant-markdown :deep(a) {
  color: var(--muyun-brand-accent-base);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.assistant-markdown :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
}

.assistant-markdown :deep(th),
.assistant-markdown :deep(td) {
  padding: 5px 8px;
  border: 1px solid var(--muyun-support-border);
  text-align: left;
  white-space: nowrap;
}

.assistant-markdown :deep(hr) {
  margin: 10px 0;
  border: 0;
  border-top: 1px solid var(--muyun-support-border);
}
</style>
