<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import {
  normalizeError,
  useLanguageContext,
  userFacingErrorMessage,
  type PersonalAccessTokenClient,
} from '@muyun/web-core';
import type { PersonalAccessTokenView } from '@muyun/web-contracts';
import {
  UiButton,
  UiDataTable,
  UiError,
  UiInput,
  UiSelect,
  UiTagList,
  UiTextArea,
  confirmAction,
  type UiDataTableColumn,
} from '@muyun/vue-ui-antdv';
import { DateTimeText } from '@muyun/platform-components';

defineOptions({ name: 'PersonalAccessTokens' });
const props = defineProps<{ client: PersonalAccessTokenClient; sessionToken: string }>();
const language = useLanguageContext();
const rows = ref<PersonalAccessTokenView[]>([]);
const canCreate = ref(false);
const name = ref('');
const validDays = ref(90);
const secret = ref<string>();
const issuedTokenId = ref<string>();
const busy = ref(false);
const error = ref<() => string>();
const loadFailed = ref(false);
const creating = ref(false);
const lifetimeOptions = computed(() =>
  [30, 90, 180, 365].map((days) => ({
    value: days,
    label: language.t('platform.access-tokens.days', '{days} 天', { days }),
  })),
);
const columns = computed<UiDataTableColumn[]>(() => [
  { key: 'name', title: language.t('platform.access-tokens.name', '名称') },
  { key: 'expiresAt', title: language.t('platform.access-tokens.expires-at', '到期时间'), width: 156 },
]);
const tableRows = computed(() => rows.value.map((row) => ({ ...row, status: status(row) })));
let disposed = false;
onBeforeUnmount(() => {
  disposed = true;
  secret.value = undefined;
});
onMounted(load);

async function load() {
  busy.value = true;
  error.value = undefined;
  loadFailed.value = false;
  try {
    const [capabilities, result] = await Promise.all([
      props.client.capabilities(props.sessionToken),
      props.client.list(props.sessionToken),
    ]);
    if (!disposed) {
      canCreate.value = capabilities.canCreate;
      rows.value = result;
    }
  } catch (cause) {
    if (!disposed) loadFailed.value = true;
    showError(cause);
  } finally {
    if (!disposed) busy.value = false;
  }
}
function showError(cause: unknown) {
  if (!disposed) error.value = () => userFacingErrorMessage(normalizeError(cause), language);
}
async function create() {
  if (!canCreate.value || busy.value || !name.value.trim() || secret.value) return;
  busy.value = true;
  creating.value = true;
  error.value = undefined;
  try {
    const result = await props.client.create(props.sessionToken, {
      name: name.value.trim(),
      validDays: validDays.value,
    });
    if (disposed) return;
    secret.value = result.token;
    issuedTokenId.value = result.accessToken.id;
    rows.value = [result.accessToken, ...rows.value];
    name.value = '';
  } catch (cause) {
    showError(cause);
  } finally {
    if (!disposed) {
      busy.value = false;
      creating.value = false;
    }
  }
}
async function revoke(id: string) {
  const row = rows.value.find((item) => item.id === id);
  if (!row || busy.value) return;
  const confirmed = await confirmAction(
    {
      title: language.t('platform.access-tokens.revoke-title', '撤销访问密钥'),
      content: language.t('platform.access-tokens.revoke-content', '使用“{name}”的工具将无法继续访问平台。', {
        name: row.name,
      }),
      danger: true,
    },
    language,
  );
  if (!confirmed || disposed || busy.value) return;
  busy.value = true;
  error.value = undefined;
  try {
    await props.client.revoke(props.sessionToken, row.id);
    if (!disposed) {
      if (row.id === issuedTokenId.value) secret.value = undefined;
      await load();
    }
  } catch (cause) {
    showError(cause);
  } finally {
    if (!disposed) busy.value = false;
  }
}
async function remove(id: string) {
  const row = rows.value.find((item) => item.id === id);
  if (!row?.revokedAt || busy.value) return;
  const confirmed = await confirmAction(
    {
      title: language.t('platform.access-tokens.delete-title', '删除访问密钥'),
      content: language.t('platform.access-tokens.delete-content', '从列表中删除“{name}”？审计记录将保留。', {
        name: row.name,
      }),
      danger: true,
    },
    language,
  );
  if (!confirmed || disposed || busy.value) return;
  busy.value = true;
  error.value = undefined;
  try {
    await props.client.remove(props.sessionToken, row.id);
    if (!disposed) {
      if (row.id === issuedTokenId.value) secret.value = undefined;
      await load();
    }
  } catch (cause) {
    showError(cause);
  } finally {
    if (!disposed) busy.value = false;
  }
}
function status(row: PersonalAccessTokenView) {
  if (row.revokedAt) return 'revoked';
  if (Date.parse(row.expiresAt) <= Date.now()) return 'expired';
  return 'active';
}
</script>

<template>
  <section v-if="canCreate || rows.length || error || secret" class="personal-access-tokens">
    <div v-if="secret" class="access-token-secret">
      <label>
        <span>{{ language.t('platform.access-tokens.copy-now', '密钥仅显示一次，请保存。') }}</span>
        <UiTextArea :value="secret" readonly :rows="2" />
      </label>
      <UiButton size="small" @click="secret = undefined">
        {{ language.t('platform.access-tokens.saved', '已保存') }}
      </UiButton>
    </div>
    <form v-else-if="canCreate" class="access-token-create" @submit.prevent="create">
      <label>
        <span>{{ language.t('platform.access-tokens.name', '名称') }}</span>
        <UiInput :value="name" :disabled="busy" autocomplete="off" @update:value="name = $event" />
      </label>
      <label>
        <span>{{ language.t('platform.access-tokens.lifetime', '有效期') }}</span>
        <UiSelect
          :value="validDays"
          :options="lifetimeOptions"
          :allow-clear="false"
          :disabled="busy"
          :aria-label="language.t('platform.access-tokens.lifetime', '有效期')"
          @update:value="validDays = Number($event)"
        />
      </label>
      <UiButton type="primary" html-type="submit" :disabled="busy || !name.trim()" :loading="creating">
        {{ language.t('platform.access-tokens.create', '创建密钥') }}
      </UiButton>
    </form>
    <UiError v-if="error" :title="error()" message="" />
    <UiButton v-if="loadFailed" type="text" size="small" :disabled="busy" @click="load">
      {{ language.t('platform.access-tokens.reload', '重新加载') }}
    </UiButton>
    <UiDataTable
      class="access-token-list"
      :columns="columns"
      :rows="tableRows"
      row-key="id"
      size="small"
      :loading="busy && !creating"
      :pagination="false"
      show-action-column
      action-column-title=""
      :action-column-fixed="false"
      :action-column-width="64"
      :empty-description="language.t('platform.access-tokens.empty', '暂无访问密钥')"
    >
      <template #cell="{ column, record }">
        <div v-if="column.key === 'name'" class="access-token-name" :title="String(record.id)">
          <span>{{ record.name }}</span
          ><code>{{ record.displayPrefix }}…</code>
        </div>
        <template v-else-if="column.key === 'expiresAt'">
          <UiTagList
            v-if="record.status !== 'active'"
            :items="[
              {
                key: String(record.status),
                label:
                  record.status === 'revoked'
                    ? language.t('platform.access-tokens.revoked', '已撤销')
                    : language.t('platform.access-tokens.expired', '已过期'),
                color: record.status === 'expired' ? 'red' : undefined,
              },
            ]"
          />
          <DateTimeText v-else :value="String(record.expiresAt)" precision="minute" />
        </template>
      </template>
      <template #rowActions="{ record }">
        <UiButton
          v-if="!record.revokedAt"
          type="text"
          size="small"
          danger
          :disabled="busy"
          @click="revoke(String(record.id))"
        >
          {{ language.t('platform.access-tokens.revoke', '撤销') }}
        </UiButton>
        <UiButton v-else type="text" size="small" danger :disabled="busy" @click="remove(String(record.id))">
          {{ language.t('platform.action.delete', '删除') }}
        </UiButton>
      </template>
    </UiDataTable>
  </section>
</template>

<style scoped>
.personal-access-tokens {
  display: grid;
  gap: 16px;
}
.access-token-create {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 112px auto;
  align-items: end;
  gap: 12px;
}
label {
  display: grid;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
}
.access-token-secret {
  display: grid;
  gap: 10px;
}
.access-token-secret > button {
  justify-self: end;
}
.access-token-secret :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.access-token-name {
  display: grid;
  gap: 2px;
  overflow-wrap: anywhere;
}
.access-token-name code {
  color: var(--muyun-support-text-secondary);
  font-size: 11px;
}
@media (max-width: 480px) {
  .access-token-create {
    grid-template-columns: minmax(0, 1fr) 104px;
  }
  .access-token-create > button {
    grid-column: 1 / -1;
    justify-self: end;
  }
}
</style>
