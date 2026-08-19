<script setup lang="ts">
import { computed } from 'vue';
import { UiError } from '@muyun/vue-ui-antdv';
import { usePageRoute } from '../app/pageRouteContext';
import UserDetailRouteView from './UserDetailRouteView.vue';
import UserManagementListView from './UserManagementListView.vue';
import { userManagementRouteStateOf } from './userManagementRouteState';

defineOptions({ name: 'UserManagementView' });

const route = usePageRoute();
const pageState = computed(() =>
  userManagementRouteStateOf(
    route.value.matched.at(-1)?.path,
    stringRouteParam(route.value.params.userId),
    route.value.query,
  ),
);

function stringRouteParam(value: unknown): string | undefined {
  return typeof value === 'string' && value ? value : undefined;
}
</script>

<template>
  <section class="user-management-page">
    <UiError v-if="pageState.error" title="用户页面地址错误" :message="pageState.error" />
    <UserManagementListView v-else-if="!pageState.action" />
    <UserDetailRouteView v-else :action="pageState.action" :user-id="pageState.userId" />
  </section>
</template>

<style scoped>
.user-management-page {
  height: 100%;
  min-height: 0;
}
</style>
