<script setup lang="ts">
import { ConfigProvider as AConfigProvider } from 'ant-design-vue';
import { computed, onUnmounted, watch } from 'vue';
import { antDesignThemeOf, cssVariablesOf, defaultUiTheme, type UiTheme } from '../theme';
import { installGlobalThemeVariables, removeGlobalThemeVariables } from '../themeGlobalScope';

defineOptions({ name: 'UiThemeProvider', inheritAttrs: false });

const props = withDefaults(defineProps<{ theme?: UiTheme; scope?: 'local' | 'global' }>(), {
  theme: () => defaultUiTheme,
  scope: 'local',
});
const activeTheme = computed(() => props.theme);
const antTheme = computed(() => antDesignThemeOf(activeTheme.value));
const cssVariables = computed(() => cssVariablesOf(activeTheme.value));
const providerId = Symbol('UiThemeProvider');

watch(
  () => [props.scope, cssVariables.value] as const,
  ([scope, variables]) => {
    if (typeof document === 'undefined') return;
    if (scope !== 'global') {
      removeGlobalThemeVariables(providerId);
      return;
    }
    installGlobalThemeVariables(providerId, variables);
  },
  { immediate: true },
);

onUnmounted(() => {
  removeGlobalThemeVariables(providerId);
});
</script>

<template>
  <div class="ui-theme-provider" :style="cssVariables">
    <AConfigProvider :theme="antTheme"><slot /></AConfigProvider>
  </div>
</template>

<style scoped>
.ui-theme-provider {
  display: contents;
}
</style>
