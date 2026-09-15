<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  ReferencePicker,
  UiButton,
  UiInput,
  type ReferencePickerCandidate,
  type ReferencePickerValidity,
} from '@ximatai/muyun-web-app';
import { createBusinessContactReferenceProvider } from '../fixtures/businessContactReference';

interface CustomerRecord {
  id?: string;
  customerName: string;
  industry: string;
  level: string;
  accountManagerId?: string;
}

const record = ref<CustomerRecord>({
  customerName: '业务项目客户',
  industry: 'software',
  level: 'key',
  accountManagerId: 'contact-024',
});

const rows = ref<CustomerRecord[]>([
  {
    id: 'biz-001',
    customerName: '业务项目客户',
    industry: 'software',
    level: 'key',
    accountManagerId: 'contact-024',
  },
  {
    id: 'biz-002',
    customerName: '启航采购项目',
    industry: 'retail',
    level: 'normal',
    accountManagerId: 'contact-003',
  },
  {
    id: 'biz-003',
    customerName: '澄明数据平台',
    industry: 'software',
    level: 'key',
    accountManagerId: 'contact-014',
  },
  {
    id: 'biz-004',
    customerName: '华北物流升级',
    industry: 'logistics',
    level: 'normal',
    accountManagerId: 'contact-022',
  },
]);

const formContactProvider = createBusinessContactReferenceProvider({
  kind: 'businessPurpose',
  id: 'business-customer-account-manager',
});
const queryContactProvider = createBusinessContactReferenceProvider({
  kind: 'businessPurpose',
  id: 'business-customer-list-filter',
});
const referenceColumns = [
  { key: 'title', title: '联系人' },
  { key: 'company', title: '所属企业' },
  { key: 'region', title: '区域' },
];

const accountManagerTitle = ref('苏南乔');
const queryDraftAccountManagerId = ref<string>();
const queryDraftAccountManagerTitle = ref('');
const appliedAccountManagerId = ref<string>();
const appliedAccountManagerTitle = ref('');
const queryDraftValidity = ref<ReferencePickerValidity>({ valid: true, status: 'ready' });

const visibleRows = computed(() =>
  appliedAccountManagerId.value
    ? rows.value.filter((row) => row.accountManagerId === appliedAccountManagerId.value)
    : rows.value,
);

const generatedAt = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'UTC',
}).format(new Date('2026-08-06T08:00:00Z'));

function titleOf(candidates: ReferencePickerCandidate[]) {
  return candidates[0]?.title ?? '';
}

function applyAccountManagerSelection(candidates: ReferencePickerCandidate[]) {
  accountManagerTitle.value = titleOf(candidates);
}

function resolveAccountManagerSelection(candidates: ReferencePickerCandidate[]) {
  if (candidates[0]) accountManagerTitle.value = titleOf(candidates);
}

function updateQueryDraft(value: string | string[] | undefined) {
  queryDraftAccountManagerId.value = Array.isArray(value) ? value[0] : value;
  if (!queryDraftAccountManagerId.value) queryDraftAccountManagerTitle.value = '';
}

function selectQueryDraft(candidates: ReferencePickerCandidate[]) {
  queryDraftAccountManagerTitle.value = titleOf(candidates);
}

function resolveQueryDraft(candidates: ReferencePickerCandidate[]) {
  if (candidates[0]) queryDraftAccountManagerTitle.value = titleOf(candidates);
}

function applyQuery() {
  if (!queryDraftValidity.value.valid) return;
  appliedAccountManagerId.value = queryDraftAccountManagerId.value;
  appliedAccountManagerTitle.value = queryDraftAccountManagerTitle.value;
}

function resetQuery() {
  queryDraftAccountManagerId.value = undefined;
  queryDraftAccountManagerTitle.value = '';
  appliedAccountManagerId.value = undefined;
  appliedAccountManagerTitle.value = '';
}
</script>

<template>
  <section class="business-grid">
    <article class="business-card">
      <h2>业务表单</h2>
      <dl class="customer-details">
        <dt>客户名称</dt>
        <dd><UiInput v-model:value="record.customerName" /></dd>
        <dt>行业</dt>
        <dd>{{ record.industry }}</dd>
        <dt>客户等级</dt>
        <dd>{{ record.level }}</dd>
        <dt>客户负责人</dt>
        <dd>
          <ReferencePicker
            :value="record.accountManagerId"
            :provider="formContactProvider"
            :columns="referenceColumns"
            title="选择客户负责人"
            placeholder="搜索并选择负责人"
            search-placeholder="姓名、企业或区域"
            @update:value="record.accountManagerId = Array.isArray($event) ? $event[0] : $event"
            @select="applyAccountManagerSelection"
            @selection-resolved="resolveAccountManagerSelection"
          />
          <small class="reference-state">
            保存字段：{{ record.accountManagerId || '未选择' }}；展示标题：{{
              accountManagerTitle || '待回显'
            }}
          </small>
        </dd>
      </dl>
    </article>

    <article class="business-card">
      <h2>业务列表</h2>
      <p class="generated-at">构建时间：{{ generatedAt }}</p>
      <div class="manual-query">
        <label for="customer-owner-query">按客户负责人筛选</label>
        <ReferencePicker
          id="customer-owner-query"
          :value="queryDraftAccountManagerId"
          :provider="queryContactProvider"
          :columns="referenceColumns"
          title="选择列表筛选负责人"
          placeholder="编辑筛选草稿"
          search-placeholder="姓名、企业或区域"
          @update:value="updateQueryDraft"
          @select="selectQueryDraft"
          @selection-resolved="resolveQueryDraft"
          @validity-change="queryDraftValidity = $event"
        />
        <div class="query-actions">
          <UiButton type="primary" :disabled="!queryDraftValidity.valid" @click="applyQuery">查询</UiButton>
          <UiButton @click="resetQuery">重置</UiButton>
        </div>
      </div>
      <p class="query-state">
        已应用条件：{{
          appliedAccountManagerId ? `${appliedAccountManagerTitle}（${appliedAccountManagerId}）` : '全部客户'
        }}
      </p>
      <table>
        <thead>
          <tr>
            <th>客户名称</th>
            <th>行业</th>
            <th>等级</th>
            <th>负责人 ID</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visibleRows" :key="String(row.id)">
            <td>{{ row.customerName }}</td>
            <td>{{ row.industry }}</td>
            <td>{{ row.level }}</td>
            <td>{{ row.accountManagerId }}</td>
          </tr>
          <tr v-if="!visibleRows.length">
            <td colspan="4" class="empty-row">没有符合当前负责人条件的客户</td>
          </tr>
        </tbody>
      </table>
    </article>
  </section>
</template>
