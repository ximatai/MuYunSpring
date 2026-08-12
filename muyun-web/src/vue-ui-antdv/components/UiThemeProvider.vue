<script setup lang="ts">
import { ConfigProvider as AConfigProvider } from 'ant-design-vue';
import { computed, watchEffect } from 'vue';
import { antDesignThemeOf, cssVariablesOf, defaultUiTheme, type UiTheme } from '../theme';

defineOptions({ name: 'UiThemeProvider', inheritAttrs: false });

const props = withDefaults(defineProps<{ theme?: UiTheme; scope?: 'local' | 'global' }>(), {
  theme: () => defaultUiTheme,
  scope: 'local',
});
const activeTheme = computed(() => props.theme);
const antTheme = computed(() => antDesignThemeOf(activeTheme.value));
const cssVariables = computed(() => cssVariablesOf(activeTheme.value));
const originalDocumentVariables = new Map<string, string | undefined>();

watchEffect((onCleanup) => {
  if (props.scope !== 'global' || typeof document === 'undefined') {
    return;
  }
  Object.entries(cssVariables.value).forEach(([name, value]) => {
    if (!originalDocumentVariables.has(name)) {
      originalDocumentVariables.set(name, document.documentElement.style.getPropertyValue(name) || undefined);
    }
    document.documentElement.style.setProperty(name, value);
  });
  onCleanup(() => {
    originalDocumentVariables.forEach((value, name) => {
      if (value === undefined) {
        document.documentElement.style.removeProperty(name);
        return;
      }
      document.documentElement.style.setProperty(name, value);
    });
  });
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
