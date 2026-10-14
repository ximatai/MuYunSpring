<script setup lang="ts">
import { codeOperationErrorMessage } from '@muyun/web-core';
import { computed } from 'vue';
import { UiButton, UiInput, UiSelect, UiTreeSelect } from '@muyun/vue-ui-antdv';
import type { ModuleMenuSession } from './moduleMenuSession';
import { menuDirectoryOptions, menuSchemeScopeLabel } from './moduleMenuClient';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';

const props = defineProps<{ session: ModuleMenuSession; showSave?: boolean }>();
const emit = defineEmits<{ saved: []; opened: [] }>();
const form = props.session;
const language = form.language;
const navigation = useWorkbenchNavigation();
async function startAnother() {
  try {
    await form.startAnother();
  } catch (cause) {
    form.reportFailure(
      cause instanceof Error
        ? cause
        : codeOperationErrorMessage(
            language,
            'platform.screen.ModuleMenuEditor.cannot-start-a-new-entry-right-now',
            '暂时无法开始新入口',
          ),
    );
  }
}
const disabled = computed(
  () => form.loading.value || form.saving.value || form.resultUnknown.value || !form.authorized.value,
);
const candidateDisabled = computed(() => disabled.value || form.updating.value);
const options = computed(() =>
  form.options.value.map((item) => ({
    value: item.id!,
    label: `${item.title ?? item.alias ?? item.id} · ${menuSchemeScopeLabel(item, language)}`,
  })),
);
const directories = computed(() => [
  {
    value: 'root',
    title: language.t('platform.screen.moduleMenuWorkspace.top-level', '顶层'),
    children: menuDirectoryOptions(form.tree.value),
  },
]);
async function save() {
  try {
    const proposal = await form.prepare();
    await proposal.execute();
    emit('saved');
  } catch (cause) {
    if (!form.resultUnknown.value)
      form.reportFailure(
        cause instanceof Error
          ? cause
          : codeOperationErrorMessage(
              language,
              'platform.screen.ModuleMenuEditor.cannot-add-the-entry-right-now',
              '暂时无法添加入口',
            ),
      );
  }
}
async function check() {
  try {
    if (await form.lookupPending()) emit('saved');
  } catch {
    form.reportFailure(
      codeOperationErrorMessage(
        language,
        'platform.screen.ModuleMenuEditor.the-result-cannot-be-verified-right-now-retry-the-lookup',
        '结果暂时无法核实，请重试查询，勿重复添加。',
      ),
    );
  }
}
async function open() {
  await form.refreshVisibility();
  if (form.visibleMenu.value && navigation?.openMenu) {
    navigation.openMenu(form.visibleMenu.value);
    emit('opened');
  }
}
defineExpose({ save });
</script>

<template>
  <div class="module-menu-form">
    <template v-if="!form.saved.value">
      <label
        >{{ language.t('platform.screen.ModuleMenuEditor.menu-name', '菜单名称')
        }}<UiInput
          :value="form.title.value"
          :disabled="candidateDisabled"
          :aria-label="language.t('platform.screen.ModuleMenuEditor.menu-name', '菜单名称')"
          @update:value="form.update({ title: String($event ?? '') })"
      /></label>
      <label v-if="options.length > 1"
        >{{ language.t('platform.screen.ModuleMenuEditor.menu-scheme', '菜单方案')
        }}<UiSelect
          :value="form.schemeId.value"
          :options="options"
          :allow-clear="false"
          :disabled="disabled"
          @update:value="form.update({ schemeId: String($event ?? '') })"
      /></label>
      <label
        >{{ language.t('platform.screen.ModuleMenuEditor.position', '放置位置')
        }}<UiTreeSelect
          :value="form.parentId.value"
          :tree-data="directories"
          :allow-clear="false"
          :disabled="candidateDisabled"
          :loading="form.loading.value || form.updating.value"
          @update:value="form.update({ parentId: String($event ?? 'root') })"
      /></label>
      <details>
        <summary>{{ language.t('platform.screen.ModuleMenuEditor.more-settings', '更多设置') }}</summary>
        <label
          >{{ language.t('platform.screen.ModuleMenuEditor.open-mode', '打开方式')
          }}<UiSelect
            :value="form.openMode.value"
            :allow-clear="false"
            :disabled="candidateDisabled"
            :options="[
              {
                value: 'tab',
                label: language.t('platform.screen.ModuleMenuEditor.open-in-a-workbench-tab', '页签内打开'),
              },
              {
                value: 'window',
                label: language.t('platform.screen.ModuleMenuEditor.open-in-a-new-window', '新窗口打开'),
              },
            ]"
            @update:value="form.update({ openMode: $event === 'window' ? 'window' : 'tab' })"
        /></label>
      </details>
      <p v-if="form.duplicate.value" class="hint">
        {{
          language.t(
            'platform.screen.ModuleMenuEditor.duplicate-position',
            '此位置已有「{title}」，仍可另加入口。',
            { title: form.duplicate.value.menu.title },
          )
        }}
      </p>
      <p class="hint">
        {{
          language.t(
            'platform.screen.ModuleMenuEditor.adds-an-access-entry-only-it-does-not-activate-applications',
            '只添加访问入口，不开通应用或授予业务权限。',
          )
        }}
      </p>
      <p v-if="form.updating.value" role="status">
        {{
          language.t(
            'platform.screen.ModuleMenuEditor.loading-the-menu-scheme-wait-for-the-candidate-to-be',
            '正在读取菜单方案，请等待候选就绪。',
          )
        }}
      </p>
      <UiButton
        v-if="showSave && !form.resultUnknown.value"
        type="primary"
        :disabled="!form.canSave.value"
        :loading="form.saving.value"
        @click="save"
        >{{ language.t('platform.screen.ModuleMenuEditor.add-entry', '添加入口') }}</UiButton
      >
      <UiButton v-if="form.resultUnknown.value" @click="check">{{
        language.t('platform.screen.ModuleMenuEditor.check-save-result', '查询保存结果')
      }}</UiButton>
    </template>
    <template v-else>
      <strong>{{ language.t('platform.screen.ModuleMenuEditor.added-to-menu', '已添加到菜单') }}</strong>
      <p>{{ form.savedPath.value }}</p>
      <p class="hint">
        {{ language.t('platform.screen.ModuleMenuEditor.audience', '适用范围：')
        }}{{ form.savedAudience.value }}
      </p>
      <UiButton v-if="form.visibleMenu.value && navigation?.openMenu" type="primary" @click="open">{{
        language.t('platform.screen.ModuleMenuEntriesSection.open', '打开')
      }}</UiButton>
      <p v-else-if="form.visibilityChecked.value" class="hint">
        {{
          language.t(
            'platform.screen.ModuleMenuEditor.the-entry-was-saved-but-is-not-visible-in-navigation',
            '入口已保存，当前登录身份的导航暂不可见。请由适用范围内的使用者核实；若其也看不到，再检查上级菜单状态和业务访问权限。',
          )
        }}
      </p>
      <UiButton :disabled="form.saving.value" @click="startAnother">{{
        language.t('platform.screen.ModuleMenuEditor.add-another-entry', '另加一个入口')
      }}</UiButton>
      <UiButton v-if="form.error.value" :disabled="form.saving.value" @click="form.refreshVisibility">{{
        language.t('platform.screen.ModuleMenuEditor.refresh-navigation', '刷新导航')
      }}</UiButton>
    </template>
    <p v-if="form.entryIssue.value" role="status">{{ form.entryIssue.value }}</p>
    <p v-if="form.ready.value && !form.authorized.value" role="status">
      {{
        language.t(
          'platform.screen.ModuleMenuEditor.you-do-not-have-permission-to-add-menu-entries',
          '当前没有添加菜单入口权限。',
        )
      }}
    </p>
    <UiButton
      v-if="form.error.value && form.ready.value && !form.saved.value && !form.resultUnknown.value"
      :disabled="form.saving.value"
      @click="form.load"
      >{{ language.t('platform.screen.ModuleMenuEditor.reload-candidate', '重新读取候选') }}</UiButton
    >
    <p v-if="form.error.value" role="alert">{{ form.error.value }}</p>
    <UiButton
      v-if="form.error.value && !form.ready.value && !form.resultUnknown.value"
      :disabled="form.saving.value"
      @click="form.load"
      >{{ language.t('platform.screen.WorkflowWorkbenchView.retry', '重试') }}</UiButton
    >
  </div>
</template>

<style scoped>
.module-menu-form {
  display: grid;
  gap: 20px;
}
summary {
  cursor: pointer;
  color: var(--muyun-text-muted);
}
details[open] summary {
  margin-bottom: 12px;
}
label {
  display: grid;
  gap: 8px;
}
p {
  margin: 0;
  overflow-wrap: anywhere;
}
.hint {
  color: var(--muyun-text-muted);
  font-size: 13px;
}
[role='alert'] {
  color: var(--muyun-color-danger, #b42318);
}
</style>
