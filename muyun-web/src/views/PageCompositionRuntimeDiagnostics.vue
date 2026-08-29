<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { RecordDetailPanel, presentPlatformError } from '@muyun/platform-components';
import { useModuleContext } from '@muyun/web-core';
import { UiEmpty, UiSpin } from '@muyun/vue-ui-antdv';
import type { ResolvedModuleUiDescriptor, ResolvedViewFieldDescriptor } from '@muyun/web-contracts';

defineOptions({ name: 'PageCompositionRuntimeDiagnostics' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const loading = ref(false);
const runtime = ref<ModuleRuntimeContext>();

const page = computed(() => runtime.value?.uiDescriptor?.page);
const listFields = computed(() => page.value?.list?.fields?.fields ?? []);
const formFields = computed(() => page.value?.detail?.editor?.fields ?? []);
const hasCompiledPage = computed(() =>
  Boolean(page.value && (listFields.value.length || formFields.value.length)),
);

watch(
  () => props.moduleAlias,
  () => void loadRuntime(),
  { immediate: true },
);

type ModuleRuntimeContext = {
  moduleAlias: string;
  title?: string;
  moduleKind?: 'STATIC' | 'DYNAMIC';
  mainEntityAlias?: string;
  uiDescriptor?: ResolvedModuleUiDescriptor;
};

async function loadRuntime() {
  loading.value = true;
  runtime.value = undefined;
  try {
    runtime.value = await moduleContext.http.request<ModuleRuntimeContext>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(props.moduleAlias)}/context`,
    });
  } catch (cause) {
    presentPlatformError(cause, { source: 'page-composition-diagnostics', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

function fieldTitle(field: ResolvedViewFieldDescriptor) {
  return field.label ?? field.fieldRef.fieldName;
}

function fieldSummary(field: ResolvedViewFieldDescriptor) {
  const properties = [
    field.width,
    field.align,
    field.columnSpan === 2 ? '整行' : undefined,
    field.readOnly?.constant ? '只读' : undefined,
  ].filter(Boolean);
  return properties.length ? properties.join(' · ') : '平台默认呈现';
}
</script>

<template>
  <RecordDetailPanel title="运行与诊断" :subtitle="`${moduleTitle ?? moduleAlias} · 当前运行态`">
    <UiSpin v-if="loading" tip="解析页面运行态" />
    <UiEmpty
      v-else-if="!hasCompiledPage"
      description="当前模块尚未解析出可运行的管理页；请先发布 Web 全局页面修订。"
    />
    <div v-else class="page-runtime-diagnostics">
      <p class="page-runtime-diagnostics__hint">
        此处展示服务端已解析的页面 descriptor；字段、属性和读写约束均以该运行态为准。
      </p>
      <section class="page-runtime-diagnostics__summary">
        <div>
          <span>运行模型</span>
          <strong>{{ runtime?.uiDescriptor?.schemaVersion }}</strong>
        </div>
        <div>
          <span>页面模板</span>
          <strong>{{ page?.template }}</strong>
        </div>
        <div>
          <span>主实体</span>
          <strong>{{ runtime?.mainEntityAlias }}</strong>
        </div>
      </section>
      <section class="page-runtime-diagnostics__slots">
        <article>
          <header>
            <strong>列表字段</strong><span>{{ listFields.length }} 个</span>
          </header>
          <UiEmpty v-if="!listFields.length" description="当前列表未配置字段" />
          <ul v-else>
            <li v-for="field in listFields" :key="field.fieldRef.fieldName">
              <div>
                <strong>{{ fieldTitle(field) }}</strong
                ><span>{{ field.fieldRef.fieldName }}</span>
              </div>
              <small>{{ fieldSummary(field) }}</small>
            </li>
          </ul>
        </article>
        <article>
          <header>
            <strong>详情 / 表单字段</strong><span>{{ formFields.length }} 个</span>
          </header>
          <UiEmpty v-if="!formFields.length" description="当前表单未配置字段" />
          <ul v-else>
            <li v-for="field in formFields" :key="field.fieldRef.fieldName">
              <div>
                <strong>{{ fieldTitle(field) }}</strong
                ><span>{{ field.fieldRef.fieldName }}</span>
              </div>
              <small>{{ fieldSummary(field) }}</small>
            </li>
          </ul>
        </article>
      </section>
    </div>
  </RecordDetailPanel>
</template>

<style scoped>
.page-runtime-diagnostics {
  display: grid;
  gap: 16px;
}
.page-runtime-diagnostics__hint {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
  line-height: 1.55;
}
.page-runtime-diagnostics__summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.page-runtime-diagnostics__summary div {
  display: grid;
  gap: 4px;
  padding: 12px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
}
.page-runtime-diagnostics__summary span,
.page-runtime-diagnostics__slots header span,
.page-runtime-diagnostics__slots li span,
.page-runtime-diagnostics__slots small {
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.page-runtime-diagnostics__slots {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.page-runtime-diagnostics__slots article {
  min-width: 0;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  overflow: hidden;
}
.page-runtime-diagnostics__slots header {
  display: flex;
  justify-content: space-between;
  padding: 10px 12px;
  background: var(--muyun-surface-muted);
}
.page-runtime-diagnostics__slots ul {
  display: grid;
  margin: 0;
  padding: 0;
  list-style: none;
}
.page-runtime-diagnostics__slots li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-top: 1px solid var(--muyun-border-subtle);
}
.page-runtime-diagnostics__slots li div {
  display: grid;
  gap: 2px;
  min-width: 0;
}
.page-runtime-diagnostics__slots li small {
  align-self: center;
  text-align: right;
}
@media (max-width: 760px) {
  .page-runtime-diagnostics__summary,
  .page-runtime-diagnostics__slots {
    grid-template-columns: 1fr;
  }
}
</style>
