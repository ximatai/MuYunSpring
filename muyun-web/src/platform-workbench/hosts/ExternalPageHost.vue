<script setup lang="ts">
import type { ExternalLinkPageDescriptor, RemoteUrlPageDescriptor } from '@muyun/web-contracts';
import { computed } from 'vue';

defineOptions({ name: 'ExternalPageHost' });

const props = defineProps<{
  descriptor: ExternalLinkPageDescriptor | RemoteUrlPageDescriptor;
}>();

const title = computed(() => props.descriptor.title ?? props.descriptor.target.url);
</script>

<template>
  <iframe
    v-if="descriptor.openMode === 'iframe'"
    class="external-frame"
    data-testid="external-page-frame"
    :src="descriptor.target.url"
    :title="title"
  />
  <section v-else class="page-host">
    <span class="host-badge">{{ descriptor.openMode }}</span>
    <h2>{{ title }}</h2>
    <a
      class="external-link"
      data-testid="external-page-window-link"
      :href="descriptor.target.url"
      target="_blank"
      rel="noopener noreferrer"
    >
      打开页面
    </a>
  </section>
</template>

<style scoped>
.external-frame {
  display: block;
  width: 100%;
  min-height: 520px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
  box-shadow: 0 16px 34px rgb(15 23 42 / 7%);
}

.page-host {
  display: grid;
  align-content: start;
  gap: 10px;
  min-height: 280px;
  padding: 28px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
  box-shadow: 0 16px 34px rgb(15 23 42 / 7%);
}

.host-badge {
  width: fit-content;
  padding: 4px 8px;
  border-radius: 999px;
  background: var(--muyun-support-hover);
  color: var(--muyun-support-text-muted);
  font-size: 12px;
  font-weight: 700;
}

h2 {
  margin: 0;
  color: var(--muyun-support-text);
  font-size: 22px;
}

.external-link {
  display: inline-flex;
  width: fit-content;
  margin-top: 8px;
  color: var(--muyun-theme-base);
  text-decoration: none;
}
</style>
