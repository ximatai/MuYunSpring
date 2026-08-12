<script setup lang="ts">
import { ConfigProvider as AConfigProvider } from 'ant-design-vue';
import { computed, watchEffect } from 'vue';
import { antDesignThemeOf, cssVariablesOf, defaultUiTheme, type UiTheme } from '../theme';

defineOptions({ name: 'UiThemeProvider', inheritAttrs: false });

const props = withDefaults(defineProps<{ theme?: UiTheme }>(), { theme: () => defaultUiTheme });
const activeTheme = computed(() => props.theme);
const antTheme = computed(() => antDesignThemeOf(activeTheme.value));

watchEffect(() => {
  if (typeof document !== 'undefined') {
    Object.entries(cssVariablesOf(activeTheme.value)).forEach(([name, value]) =>
      document.documentElement.style.setProperty(name, value),
    );
  }
});
</script>

<template>
  <AConfigProvider :theme="antTheme"><slot /></AConfigProvider>
</template>
