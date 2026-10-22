<script setup lang="ts">
import { computed, ref } from 'vue';
import { ReferencePicker, UiButton, type ReferencePickerCandidate } from '@ximatai/muyun-web-app';
import {
  createBusinessDirectoryReferenceProvider,
  type DirectoryAuthorizationScope,
} from '../fixtures/businessDirectoryReference';

const authorizationScope = ref<DirectoryAuthorizationScope>('all');
const provider = computed(() => createBusinessDirectoryReferenceProvider(authorizationScope.value));
const selectedIds = ref<string[]>(['west-24', 'retired-service', 'hidden-service', 'deleted-service']);
const summaries = ref<ReferencePickerCandidate[]>([]);
const confirmedIds = ref<string[]>([]);

function select(candidates: ReferencePickerCandidate[]) {
  summaries.value = candidates;
  confirmedIds.value = candidates.map(({ id }) => id);
}
</script>

<template>
  <section class="directory-reference-example">
    <h2>跨目录服务引用</h2>
    <p>目录与分类只收窄候选；跨目录、跨页的选择在确认后一次交付 ID。</p>
    <UiButton @click="authorizationScope = authorizationScope === 'all' ? 'east' : 'all'">
      当前授权样板：{{ authorizationScope === 'all' ? '全部目录' : '仅华东目录' }}（切换）
    </UiButton>
    <ReferencePicker
      :value="selectedIds"
      :provider="provider"
      multiple
      :page-size="10"
      title="选择合同服务"
      :columns="[
        { key: 'title', title: '服务' },
        { key: 'directory', title: '目录' },
        { key: 'category', title: '分类' },
      ]"
      @update:value="selectedIds = Array.isArray($event) ? $event : $event ? [$event] : []"
      @selection-resolved="summaries = $event"
      @select="select"
    />
    <p>
      历史回显：{{
        summaries.map((item) => `${item.title}${item.unavailable ? '（不可用）' : ''}`).join('、')
      }}
    </p>
    <p>持久化 ID：{{ selectedIds.join('、') || '空' }}</p>
    <p>最近确认：{{ confirmedIds.join('、') || '尚未确认' }}</p>
    <small
      >本地数据样板包含未搜索的第 24 条、仅可保留的停用历史项、不可见及失效
      ID；正式接入由业务服务校验授权。</small
    >
  </section>
</template>

<style scoped>
.directory-reference-example {
  display: grid;
  gap: 12px;
}
</style>
