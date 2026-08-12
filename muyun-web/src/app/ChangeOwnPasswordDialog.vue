<script setup lang="ts">
import { UiInput, UiModal } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'ChangeOwnPasswordDialog' });

withDefaults(
  defineProps<{
    open?: boolean;
    currentPassword: string;
    newPassword: string;
    confirmPassword: string;
    saving?: boolean;
    error?: string;
  }>(),
  {
    open: false,
    saving: false,
    error: undefined,
  },
);

const emit = defineEmits<{
  close: [];
  submit: [];
  'update:currentPassword': [value: string];
  'update:newPassword': [value: string];
  'update:confirmPassword': [value: string];
}>();
</script>

<template>
  <UiModal
    :open="open"
    title="修改密码"
    confirm-text="保存"
    :confirm-loading="saving"
    :confirm-disabled="!currentPassword || !newPassword || !confirmPassword"
    @confirm="emit('submit')"
    @cancel="emit('close')"
  >
    <div class="change-own-password-fields">
      <label>
        <span>当前密码</span>
        <UiInput
          :value="currentPassword"
          type="password"
          autocomplete="current-password"
          :disabled="saving"
          placeholder="请输入当前密码"
          @update:value="emit('update:currentPassword', $event)"
        />
      </label>
      <label>
        <span>新密码</span>
        <UiInput
          :value="newPassword"
          type="password"
          autocomplete="new-password"
          :disabled="saving"
          placeholder="请输入新密码"
          @update:value="emit('update:newPassword', $event)"
        />
      </label>
      <label>
        <span>确认新密码</span>
        <UiInput
          :value="confirmPassword"
          type="password"
          autocomplete="new-password"
          :disabled="saving"
          placeholder="请再次输入新密码"
          @update:value="emit('update:confirmPassword', $event)"
        />
      </label>
    </div>

    <p v-if="error" class="change-own-password-error">{{ error }}</p>
  </UiModal>
</template>

<style scoped>
.change-own-password-fields {
  display: grid;
  gap: 12px;
}

.change-own-password-fields label {
  display: grid;
  gap: 6px;
  color: var(--muyun-support-text-body);
  font-size: 13px;
}

.change-own-password-error {
  margin: 0;
  color: var(--muyun-danger-soft-text);
  font-size: 13px;
}
</style>
