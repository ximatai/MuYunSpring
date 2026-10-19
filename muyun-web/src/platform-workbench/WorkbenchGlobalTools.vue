<script setup lang="ts">
import { computed, ref } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import type { CurrentUser } from '@muyun/web-contracts';
import { UiButton, UiDropdown, UiIcon, type UiDropdownItem } from '@muyun/vue-ui-antdv';
import WorkbenchLanguageSettings from './WorkbenchLanguageSettings.vue';

const props = defineProps<{
  currentUser?: CurrentUser;
  employeeName?: string;
  assistantAvailable: boolean;
  assistantOpen: boolean;
}>();
const emit = defineEmits<{
  toggleAssistant: [];
  userCommand: [key: string];
}>();
const language = useLanguageContext();
const languageSettingsOpen = ref(false);
const displayName = computed(
  () =>
    props.currentUser?.username ??
    props.currentUser?.userId ??
    language.t('platform.screen.Workbench.not-signed-in', '未登录'),
);
const initial = computed(() => displayName.value.trim().slice(0, 1).toUpperCase() || 'M');
const roleLabel = computed(() =>
  props.currentUser?.system
    ? language.t('platform.screen.Workbench.system-administrator', '系统管理员')
    : language.t('platform.screen.Workbench.business-user', '业务用户'),
);
const identityLabel = computed(() => props.employeeName?.trim() || roleLabel.value);
const workspaceLabel = computed(() =>
  props.currentUser?.tenantId
    ? language.t('platform.workbench.tenant-workspace', '租户：{tenant}', {
        tenant: props.currentUser.tenantId,
      })
    : undefined,
);
const userMenuItems = computed<UiDropdownItem[]>(() => [
  ...(props.currentUser
    ? [
        { key: 'identity', title: roleLabel.value, iconName: 'user' as const, disabled: true },
        ...(workspaceLabel.value
          ? [{ key: 'workspace', title: workspaceLabel.value, iconName: 'app' as const, disabled: true }]
          : []),
      ]
    : []),
  {
    key: 'languageSettings',
    iconName: 'language',
    title: language.t('platform.content.language-settings', '语言设置'),
  },
  {
    key: 'themeSkin',
    iconName: 'skin',
    title: language.t('platform.screen.Workbench.appearance', '皮肤切换'),
  },
  {
    key: 'changePassword',
    iconName: 'lock',
    title: language.t('platform.screen.Workbench.change-password', '修改密码'),
  },
  { key: 'profile', iconName: 'user', title: language.t('platform.screen.Workbench.my-profile', '个人信息') },
  {
    key: 'logout',
    iconName: 'power',
    title: language.t('platform.screen.Workbench.sign-out', '退出登录'),
    danger: true,
  },
]);
const userMenuLabel = computed(() =>
  language.t('platform.workbench.account-menu', '账号菜单：{name}', { name: displayName.value }),
);
function handleMenuSelect(key: string) {
  if (key === 'languageSettings') languageSettingsOpen.value = true;
  else if (key !== 'identity' && key !== 'workspace') emit('userCommand', key);
}
</script>
<template>
  <div
    class="topbar-actions"
    role="group"
    :aria-label="language.t('platform.screen.Workbench.global-tools', '全局工具')"
  >
    <UiButton
      v-if="assistantAvailable"
      class="workbench-tool assistant-toggle"
      type="text"
      icon-name="assistant"
      :aria-label="language.t('platform.screen.Workbench.ai-assistant', 'AI 助手')"
      :selected="assistantOpen"
      :aria-pressed="assistantOpen"
      :title="language.t('platform.screen.Workbench.ai-assistant', 'AI 助手')"
      @click="emit('toggleAssistant')"
      ><span class="assistant-label">{{
        language.t('platform.screen.Workbench.ai-assistant', 'AI 助手')
      }}</span></UiButton
    >
    <UiDropdown v-slot="{ toggle, open }" :items="userMenuItems" @select="handleMenuSelect">
      <button
        class="user-button"
        type="button"
        :aria-label="userMenuLabel"
        :title="currentUser ? `${displayName} · ${identityLabel}` : displayName"
        aria-haspopup="menu"
        :aria-expanded="open"
        @click.stop="toggle"
      >
        <span class="avatar" aria-hidden="true">{{ initial }}</span>
        <span class="user-meta">
          <strong>{{ displayName }}</strong>
          <small v-if="currentUser">{{ identityLabel }}</small>
        </span>
        <span class="user-caret" :class="{ 'user-caret--open': open }" aria-hidden="true">
          <UiIcon name="down" />
        </span>
      </button>
    </UiDropdown>
    <WorkbenchLanguageSettings
      v-model:open="languageSettingsOpen"
      :identity-scope="currentUser ? JSON.stringify([currentUser.userId, currentUser.tenantId]) : undefined"
    />
  </div>
</template>
<style scoped>
.topbar-actions {
  --workbench-tool-size: 36px;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  margin-inline-start: auto;
}
.topbar-actions :deep(.workbench-tool.ant-btn),
.user-button {
  box-sizing: border-box;
  flex-shrink: 0;
  height: var(--workbench-tool-size);
  border-radius: 6px;
  box-shadow: none;
}
.topbar-actions :deep(.workbench-tool.ant-btn) {
  min-width: var(--workbench-tool-size);
  padding-inline: 8px;
}
/* A quiet accent for the assistant; stronger feedback is reserved for deliberate interaction. */
.topbar-actions .assistant-toggle.ant-btn {
  --assistant-tint: color-mix(in srgb, var(--muyun-theme-base) 5%, var(--muyun-surface));
  --assistant-edge: color-mix(in srgb, var(--muyun-theme-base) 30%, var(--muyun-border));
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  position: relative;
  isolation: isolate;
  border: 1px solid transparent;
  background:
    linear-gradient(120deg, var(--muyun-surface), var(--assistant-tint)) padding-box,
    linear-gradient(125deg, var(--assistant-edge), var(--muyun-theme-border), var(--assistant-edge))
      border-box;
  color: var(--muyun-text-body);
  transition: box-shadow 160ms ease;
}
.assistant-toggle :deep(.ui-icon--lucide) {
  color: var(--muyun-theme-base);
}
.assistant-label {
  font-weight: 500;
}
.topbar-actions .assistant-toggle.ant-btn:not(:disabled):hover,
.topbar-actions .assistant-toggle.ant-btn:not(:disabled):focus-visible {
  --assistant-tint: color-mix(in srgb, var(--muyun-theme-base) 9%, var(--muyun-surface));
  --assistant-edge: color-mix(in srgb, var(--muyun-theme-base) 60%, var(--muyun-border));
  border-color: transparent;
  color: var(--muyun-text);
  box-shadow: 0 2px 10px color-mix(in srgb, var(--muyun-theme-base) 12%, transparent);
}
.topbar-actions .assistant-toggle.ant-btn[aria-pressed='true']:not(:disabled) {
  --assistant-tint: var(--muyun-selected);
  --assistant-edge: var(--muyun-theme-base);
  border-color: transparent;
  color: var(--muyun-text);
  background:
    linear-gradient(120deg, var(--assistant-tint), var(--muyun-surface)) padding-box,
    linear-gradient(125deg, var(--assistant-edge), var(--muyun-theme-border), var(--assistant-edge))
      border-box;
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--muyun-theme-base) 10%, transparent);
}
@media (prefers-reduced-motion: reduce) {
  .topbar-actions .assistant-toggle.ant-btn {
    transition: none;
  }
}
.user-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 3px 7px 3px 3px;
  min-width: 154px;
  max-width: 180px;
  border: 1px solid var(--muyun-border);
  background: var(--muyun-surface);
  color: var(--muyun-text);
  cursor: pointer;
}
.user-button:hover {
  background: var(--muyun-hover);
  border-color: var(--muyun-theme-border);
}
.topbar-actions :deep(.workbench-tool.ant-btn:focus-visible),
.user-button:focus-visible {
  outline: 2px solid var(--muyun-theme-border);
  outline-offset: 2px;
}
.user-button:active,
.user-button[aria-expanded='true'] {
  border-color: var(--muyun-theme-border);
  background: var(--muyun-selected);
}
.avatar {
  display: inline-grid;
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 6px;
  background: var(--muyun-theme-base);
  color: var(--muyun-theme-on-base);
  font-size: var(--muyun-font-size-helper, 12px);
  font-weight: 700;
}
.user-meta {
  display: grid;
  min-width: 0;
  text-align: start;
  line-height: 14px;
}
.user-meta strong,
.user-meta small {
  overflow: hidden;
  max-width: 104px;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--muyun-font-size-helper, 12px);
}
.user-meta small {
  color: var(--muyun-text-muted);
}
.user-caret {
  display: inline-flex;
  flex: 0 0 12px;
  width: 12px;
  height: 12px;
  align-items: center;
  justify-content: center;
  line-height: 1;
  margin-inline-start: auto;
  color: var(--muyun-text-muted);
  font-size: var(--muyun-font-size-helper, 12px);
}
.user-caret--open {
  transform: rotate(180deg);
}
@media (max-width: 480px) {
  .user-meta,
  .assistant-label {
    display: none;
  }
  .topbar-actions {
    gap: 2px;
  }
  .user-button {
    min-width: 0;
    padding-inline: 4px;
  }
}
</style>
