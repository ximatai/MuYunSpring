<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiInput, UiModal } from '@muyun/vue-ui-antdv';
import { SingleImageFileReferenceField } from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';
import type { CurrentUserProfile, ResolvedFileReferenceFieldDescriptor } from '@muyun/web-contracts';

defineOptions({ name: 'CurrentUserProfileDialog' });

const props = withDefaults(
  defineProps<{
    open?: boolean;
    profile?: CurrentUserProfile;
    loading?: boolean;
    saving?: boolean;
    error?: string;
    avatarContext: ModuleContext<unknown>;
  }>(),
  { open: false, loading: false, saving: false, error: undefined, profile: undefined },
);

const emit = defineEmits<{
  close: [];
  submit: [value: { mobile: string; email: string; avatarAssetId?: string }];
}>();
const mobile = ref('');
const email = ref('');
const avatarAssetId = ref<string>();

watch(
  () => props.profile,
  (profile) => {
    mobile.value = profile?.employee?.mobile ?? '';
    email.value = profile?.employee?.email ?? '';
    avatarAssetId.value = profile?.employee?.avatarAssetId;
  },
  { immediate: true },
);

const employee = computed(() => props.profile?.employee);
const positions = computed(() => employee.value?.positions ?? []);
const avatarDefinition: ResolvedFileReferenceFieldDescriptor = {
  fieldRef: { fieldName: 'avatarAssetId' },
  allowedMediaTypes: ['image/png', 'image/jpeg', 'image/gif', 'image/webp'],
  maxFileSizeBytes: 1048576,
  maxFiles: 1,
  storagePolicy: 'DATABASE_INLINE',
  uploadAvailable: true,
  readAvailable: true,
};
const avatarDraft = computed(() => ({ id: employee.value?.id, avatarAssetId: avatarAssetId.value }));
</script>

<template>
  <UiModal
    :open="open"
    title="个人信息"
    :width="640"
    class="current-user-profile-dialog"
    :confirm-text="employee?.contactEditable ? '保存联系方式' : '关闭'"
    :confirm-loading="saving"
    :confirm-disabled="loading"
    @confirm="employee?.contactEditable ? emit('submit', { mobile, email, avatarAssetId }) : emit('close')"
    @cancel="emit('close')"
  >
    <p v-if="loading" class="profile-loading">正在整理你的资料…</p>
    <template v-else>
      <section v-if="employee" class="profile-hero">
        <div class="profile-avatar">
          <SingleImageFileReferenceField
            label="头像"
            :value="avatarAssetId"
            :record="avatarDraft"
            :context="avatarContext"
            :definition="avatarDefinition"
            :disabled="!employee.contactEditable || saving"
            :form-session-key="employee.id"
            upload-hint="支持 PNG、JPG、GIF、WebP，文件不超过 1024 KB。"
            @update:value="avatarAssetId = $event"
          />
        </div>
        <div class="profile-identity">
          <p class="profile-kicker">职员档案</p>
          <h2>{{ employee.title || '未命名职员' }}</h2>
          <p class="profile-number">{{ employee.employeeNo || '尚未分配员工号' }}</p>
          <div class="profile-tags">
            <span>{{ employee.organizationTitle || employee.organizationId || '未分配机构' }}</span>
            <span>{{ employee.departmentTitle || employee.departmentId || '未分配部门' }}</span>
          </div>
        </div>
        <aside class="profile-account">
          <span>登录账号</span>
          <strong>{{ profile?.username || '—' }}</strong>
          <small>{{ profile?.timeZone || '系统默认时区' }}</small>
        </aside>
      </section>

      <section v-if="employee" class="profile-body">
        <div class="profile-card profile-card--readonly">
          <div class="profile-card-heading"><span>任职信息</span><small>由组织管理员维护</small></div>
          <dl class="profile-details">
            <dt>机构 / 部门</dt>
            <dd>
              {{ employee.organizationTitle || employee.organizationId || '—' }} /
              {{ employee.departmentTitle || employee.departmentId || '—' }}
            </dd>
            <dt>岗位</dt>
            <dd>
              {{
                positions
                  .map((item) => `${item.title || item.id || '—'}${item.primary ? '（主岗）' : ''}`)
                  .join('、') || '—'
              }}
            </dd>
          </dl>
        </div>
        <div class="profile-card profile-card--editable">
          <div class="profile-card-heading"><span>联系方式</span><small>仅你本人可修改</small></div>
          <div class="profile-contact-fields">
            <label
              ><span>手机号</span
              ><UiInput
                :value="mobile"
                :disabled="!employee.contactEditable || saving"
                @update:value="mobile = $event"
            /></label>
            <label
              ><span>邮箱</span
              ><UiInput
                :value="email"
                :disabled="!employee.contactEditable || saving"
                @update:value="email = $event"
            /></label>
          </div>
          <p v-if="!employee.contactEditable" class="profile-hint">当前职员已停用，联系方式不能自助修改。</p>
        </div>
      </section>
      <section v-else class="profile-empty-state">
        <span class="profile-empty-monogram">{{ (profile?.username || '?').slice(0, 1).toUpperCase() }}</span>
        <h2>{{ profile?.username || '当前账号' }}</h2>
        <p>此账号尚未绑定职员档案。请在职员管理中完成绑定后维护个人资料。</p>
      </section>
    </template>
    <p v-if="error" class="profile-error">{{ error }}</p>
  </UiModal>
</template>

<style scoped>
.profile-hero {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr) auto;
  gap: 18px;
  align-items: stretch;
  padding: 20px;
  color: var(--muyun-support-text-body);
  background: linear-gradient(
    120deg,
    color-mix(in srgb, var(--muyun-theme-soft) 76%, var(--muyun-support-surface)),
    var(--muyun-support-surface)
  );
  border: 1px solid var(--muyun-theme-border);
  border-radius: 12px;
}
.profile-avatar {
  align-self: center;
  position: relative;
  width: 92px;
}
.profile-identity {
  display: grid;
  align-content: center;
  min-width: 0;
}
.profile-kicker {
  margin: 0 0 4px;
  color: var(--muyun-theme-base);
  font-size: 11px;
  font-weight: 700;
  line-height: 16px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.profile-identity h2 {
  margin: 0;
  color: var(--muyun-support-text-heading);
  font-size: 22px;
  line-height: 28px;
  letter-spacing: -0.02em;
}
.profile-number {
  margin: 5px 0 6px;
  color: var(--muyun-support-text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 16px;
}
.profile-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.profile-tags span {
  max-width: 160px;
  overflow: hidden;
  color: var(--muyun-support-text-body);
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.profile-tags span + span::before {
  margin-right: 6px;
  color: var(--muyun-theme-base);
  content: '/';
}
.profile-account {
  display: grid;
  align-self: stretch;
  align-content: center;
  min-width: 118px;
  gap: 3px;
  padding-left: 18px;
  border-left: 1px solid var(--muyun-theme-border);
}
.profile-account span,
.profile-account small {
  color: var(--muyun-support-text-secondary);
  font-size: 11px;
  line-height: 16px;
}
.profile-account strong {
  color: var(--muyun-support-text-heading);
  font-size: 13px;
  line-height: 18px;
}
.profile-body {
  display: grid;
  grid-template-columns: 1fr 1.1fr;
  gap: 12px;
  margin-top: 14px;
}
.profile-card {
  display: grid;
  align-content: start;
  gap: 14px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 10px;
  background: var(--muyun-support-surface);
}
.profile-card--editable {
  border-top: 3px solid var(--muyun-theme-base);
  padding-top: 14px;
}
.profile-card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 20px;
  gap: 12px;
  color: var(--muyun-support-text-heading);
  font-size: 14px;
  font-weight: 650;
}
.profile-card-heading small {
  color: var(--muyun-support-text-muted);
  font-size: 11px;
  font-weight: 400;
  line-height: 16px;
}
.profile-details {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  margin: 0;
  font-size: 13px;
  line-height: 20px;
}
.profile-details dt {
  color: var(--muyun-support-text-secondary);
}
.profile-details dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--muyun-support-text-body);
}
.profile-contact-fields {
  display: grid;
  gap: 11px;
}
.profile-contact-fields label {
  display: grid;
  gap: 6px;
  color: var(--muyun-support-text-secondary);
  font-size: 12px;
  line-height: 16px;
}
.profile-hint,
.profile-error,
.profile-loading {
  margin: 12px 0 0;
  font-size: 12px;
}
.profile-hint,
.profile-loading {
  color: var(--muyun-support-text-secondary);
}
.profile-error {
  color: var(--muyun-danger-soft-text);
}
.profile-empty-state {
  display: grid;
  place-items: center;
  gap: 9px;
  padding: 36px 24px;
  text-align: center;
  border: 1px dashed var(--muyun-support-border);
  border-radius: 12px;
}
.profile-empty-state h2,
.profile-empty-state p {
  margin: 0;
}
.profile-empty-state h2 {
  color: var(--muyun-support-text-heading);
  font-size: 17px;
}
.profile-empty-state p {
  max-width: 300px;
  color: var(--muyun-support-text-secondary);
  font-size: 13px;
  line-height: 1.65;
}
.profile-empty-monogram {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  color: white;
  font-weight: 700;
  background: var(--muyun-theme-base);
  border-radius: 14px;
}
.profile-avatar :deep(.single-image-file-reference-field) {
  gap: 0;
}
.profile-avatar :deep(.single-image-file-reference-field__header) {
  display: none;
}
.profile-avatar :deep(.file-transfer-uploader__drop-zone) {
  min-height: 92px;
  padding: 8px;
  border-radius: 12px;
}
.profile-avatar :deep(.file-transfer-uploader__drop-zone-title) {
  font-size: 11px;
}
.profile-avatar :deep(.file-transfer-uploader__drop-zone-hint) {
  display: none;
}
.profile-avatar :deep(.file-transfer-uploader__list) {
  position: absolute;
  z-index: 2;
  top: calc(100% + 10px);
  left: 0;
  width: 260px;
  padding: 8px;
  background: var(--muyun-support-surface);
  border: 1px solid var(--muyun-danger-soft-border, var(--muyun-support-border));
  border-radius: 8px;
  box-shadow: 0 10px 24px rgb(18 37 63 / 0.16);
}
.profile-avatar :deep(.file-transfer-uploader__item) {
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 8px;
  padding: 0;
  border: 0;
}
.profile-avatar :deep(.file-transfer-uploader__name) {
  font-size: 12px;
}
.profile-avatar :deep(.file-transfer-uploader__state) {
  grid-column: 1 / -1;
  line-height: 1.35;
}
.profile-avatar :deep(.file-transfer-uploader__actions) {
  grid-column: 2;
  grid-row: 1;
}
.profile-hero:has(.file-transfer-uploader__list) {
  margin-bottom: 70px;
}
.profile-avatar :deep(.single-image-file-reference-field__preview) {
  min-height: 92px;
  border-radius: 12px;
}
.profile-avatar :deep(.single-image-file-reference-field__preview img) {
  height: 92px;
  padding: 4px;
}
.profile-avatar :deep(.single-image-file-reference-field__preview strong),
.profile-avatar :deep(.single-image-file-reference-field__preview > span:last-child) {
  display: none;
}
.profile-avatar :deep(.single-image-file-reference-field__state-icon) {
  font-size: 20px;
}
@media (max-width: 620px) {
  .profile-hero {
    grid-template-columns: 76px 1fr;
    gap: 14px;
    padding: 16px;
  }
  .profile-avatar {
    width: 76px;
  }
  .profile-account {
    grid-column: 1 / -1;
    grid-template-columns: auto 1fr;
    padding: 10px 0 0;
    border-top: 1px solid var(--muyun-theme-border);
    border-left: 0;
  }
  .profile-account small {
    grid-column: 2;
  }
  .profile-body {
    grid-template-columns: 1fr;
  }
  .profile-avatar :deep(.file-transfer-uploader__drop-zone),
  .profile-avatar :deep(.single-image-file-reference-field__preview) {
    min-height: 76px;
  }
  .profile-avatar :deep(.single-image-file-reference-field__preview img) {
    height: 76px;
  }
}
</style>
