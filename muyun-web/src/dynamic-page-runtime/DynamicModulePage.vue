<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed, ref } from 'vue';
import type { ActionContract, DynamicPageDescriptor, RecordData } from '@muyun/web-contracts';
import { resolveDynamicActionReactions } from './actionReactions';

const language = useLanguageContext();

defineOptions({ name: 'DynamicModulePage' });

const props = defineProps<{
  descriptor: DynamicPageDescriptor;
}>();

const record = ref<RecordData>({ ...props.descriptor.initialRecord });
const lastAction = ref<string>('none');

const saveEnvelope = computed(() => ({
  moduleAlias: props.descriptor.moduleAlias,
  values: record.value,
  children: {},
  originContext: {
    source: 'dynamic-runtime-skeleton',
  },
}));

function executeAction(action: ActionContract) {
  const reactions = resolveDynamicActionReactions(action, {
    moduleAlias: props.descriptor.moduleAlias,
    recordId: typeof record.value.id === 'string' ? record.value.id : undefined,
  });
  lastAction.value = `${action.actionCode}:${reactions.map((reaction) => reaction.type).join(',') || 'none'}`;
}
</script>

<template>
  <section class="runtime-page">
    <header class="section-header">
      <div>
        <h2>{{ descriptor.title }}</h2>
        <p>{{ descriptor.moduleAlias }}</p>
      </div>
      <div class="runtime-actions">
        <button
          v-for="action in descriptor.actions"
          :key="action.actionCode"
          type="button"
          @click="executeAction(action)"
        >
          {{ action.title }}
        </button>
      </div>
    </header>

    <div class="runtime-grid">
      <article class="panel">
        <h3>{{ language.t('platform.screen.DynamicModulePage.dynamic-form', '动态表单') }}</h3>
        <dl>
          <template v-for="field in descriptor.form.fields" :key="field.name">
            <dt>{{ field.label }}</dt>
            <dd>{{ record[field.name] ?? '—' }}</dd>
          </template>
        </dl>
      </article>
      <article class="panel">
        <h3>{{ language.t('platform.screen.DynamicModulePage.dynamic-list', '动态列表') }}</h3>
        <table>
          <thead>
            <tr>
              <th v-for="column in descriptor.list.columns" :key="column.key">{{ column.title }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in descriptor.records" :key="String(row.id)">
              <td v-for="column in descriptor.list.columns" :key="column.key">
                {{ row[column.key] ?? '—' }}
              </td>
            </tr>
          </tbody>
        </table>
      </article>
    </div>

    <article class="panel">
      <h3>{{ language.t('platform.screen.DynamicModulePage.runtime-output', '运行态输出') }}</h3>
      <p>
        last action: <code>{{ lastAction }}</code>
      </p>
      <pre>{{ JSON.stringify(saveEnvelope, null, 2) }}</pre>
    </article>
  </section>
</template>
