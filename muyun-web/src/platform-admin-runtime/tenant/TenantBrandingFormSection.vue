<script setup lang="ts">
import { computed, onMounted } from 'vue';
import type { ModulePageFormContributionContext } from '@muyun/dynamic-page-runtime';

defineOptions({ name: 'TenantBrandingFormSection' });

const props = defineProps<{ context: ModulePageFormContributionContext }>();
const logoWithTitle = computed(() => props.context.draft.workbenchBrandMode !== 'logoOnly');

onMounted(() => {
  if (props.context.mode === 'create' && props.context.draft.workbenchBrandMode == null) {
    props.context.setField('workbenchBrandMode', 'logoWithTitle');
  }
});
</script>

<template>
  <section class="tenant-branding-form-section">
    <div>
      <h3>主标题 UI 个性化配置</h3>
      <p>选择一种明确的品牌组合，避免横向 Logo 与标题文字在紧凑侧栏中相互挤压。</p>
    </div>
    <p class="tenant-branding-form-section__mode-hint">
      {{ logoWithTitle ? '使用正方形图标搭配主标题。' : '仅展示 Logo，不显示主标题和副标题。' }}
    </p>
  </section>
</template>

<style scoped>
.tenant-branding-form-section {
  display: flex;
  align-items: end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--muyun-border);
}
.tenant-branding-form-section h3,
.tenant-branding-form-section p {
  margin: 0;
}
.tenant-branding-form-section h3 {
  font-size: 15px;
}
.tenant-branding-form-section p {
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.tenant-branding-form-section__mode-hint {
  padding-bottom: 1px;
}
</style>
