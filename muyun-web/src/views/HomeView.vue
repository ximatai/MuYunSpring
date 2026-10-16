<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';

const language = useLanguageContext();
</script>
<template>
  <section class="home-view">
    <p class="home-eyebrow">Workspace</p>
    <h2>{{ language.t('platform.screen.HomeView.select-a-menu-on-the-left', '请选择左侧菜单') }}</h2>
    <p>
      {{
        language.t(
          'platform.screen.HomeView.choose-a-feature-from-the-left-menu-to-get-started',
          '从左侧菜单选择功能，即可开始工作。',
        )
      }}
    </p>
  </section>
</template>

<style scoped>
.home-view {
  display: grid;
  gap: 8px;
  min-height: 240px;
  align-content: center;
  justify-items: center;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
  color: var(--muyun-text-body);
  text-align: center;
}

.home-eyebrow,
h2,
p {
  margin: 0;
}

.home-eyebrow {
  color: var(--muyun-text-muted);
  font-size: 12px;
  font-weight: 700;
}

h2 {
  color: var(--muyun-text);
  font-size: 18px;
}
</style>
