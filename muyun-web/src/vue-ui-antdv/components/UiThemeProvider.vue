<script setup lang="ts">
import { ConfigProvider as AConfigProvider } from 'ant-design-vue';
import { computed } from 'vue';
import { antDesignThemeOf, cssVariablesOf, defaultUiTheme, type UiTheme } from '../theme';

defineOptions({ name: 'UiThemeProvider', inheritAttrs: false });

const props = withDefaults(defineProps<{ theme?: UiTheme }>(), { theme: () => defaultUiTheme });
const activeTheme = computed(() => props.theme);
const antTheme = computed(() => antDesignThemeOf(activeTheme.value));
const cssVariables = computed(() => cssVariablesOf(activeTheme.value));
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
