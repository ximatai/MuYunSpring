<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import {
  createAuthClient,
  createExternalLoginClient,
  createPersonalAccessTokenClient,
  normalizeError,
  userFacingErrorMessage,
  useLanguageContext,
  userPreferences,
  withHttpHeaders,
  type HttpClient,
} from '@muyun/web-core';
import { presentPlatformError, presentPlatformSuccess } from '@muyun/platform-components';
import { uiThemeSkinById, uiThemeSkins, defaultUiThemeSkinId, type UiThemeSkinId } from '@muyun/vue-ui-antdv';
import {
  createWorkbenchAccountMenu,
  type WorkbenchAccountCommand,
  type WorkbenchAccountContext,
  type WorkbenchAccountMenuOptions,
  type WorkbenchAccountCommandGuard,
} from '../platform-workbench/workbenchAccountMenu';
import WorkbenchLanguageSettings from '../platform-workbench/WorkbenchLanguageSettings.vue';
import ChangeOwnPasswordDialog from './ChangeOwnPasswordDialog.vue';
import CurrentUserProfileDialog from './CurrentUserProfileDialog.vue';
import PersonalAccessTokenDialog from './PersonalAccessTokenDialog.vue';
import ThemeSkinPreferencesDialog from './ThemeSkinPreferencesDialog.vue';
import { useCurrentUserProfile } from './useCurrentUserProfile';
import { usePersonalAccessTokenAccess } from './usePersonalAccessTokenAccess';
import {
  themeSkinPreferenceKey,
  restoreThemeSkinPreference,
  saveThemeSkinPreference,
  type ThemeSkinPreferenceFailure,
} from './themeSkinPreference';

defineOptions({ name: 'WorkbenchAccountCenter', inheritAttrs: false });
const props = defineProps<{
  context?: WorkbenchAccountContext;
  menu?: WorkbenchAccountMenuOptions;
  beforeCommand?: WorkbenchAccountCommandGuard;
  skinId?: UiThemeSkinId;
}>();
const emit = defineEmits<{
  userCommand: [key: string];
  logout: [reason: 'requested' | 'passwordChanged'];
  'update:skinId': [skinId: UiThemeSkinId];
}>();
const language = useLanguageContext();
// Transport is captured at invocation; the session token is supplied by the platform client.
const http: HttpClient = {
  request: (request) => {
    if (!props.context) return Promise.reject(new Error('Account context is unavailable'));
    return props.context.http.request(request);
  },
};
const auth = createAuthClient(http);
const external = createExternalLoginClient(http);
const tokens = createPersonalAccessTokenClient(http);
const profileHttp = computed(() => {
  const context = props.context;
  return context?.token
    ? withHttpHeaders(context.http, { Authorization: `Bearer ${context.token}` })
    : undefined;
});
const token = () => props.context?.token;
const identity = () => {
  const user = props.context?.currentUser;
  return JSON.stringify([token(), user?.userId, user?.tenantId, user?.organizationId, user?.system]);
};
const preferenceIdentity = computed(() =>
  props.context?.currentUser && token() && props.context.preferencePersistence !== 'local'
    ? identity()
    : undefined,
);
const profile = useCurrentUserProfile({
  currentUser: () => props.context?.currentUser,
  token,
  scopeKey: () => props.context?.http,
  client: auth,
});
const accountScope = computed<{ http?: HttpClient; identity: string }>((previous) => {
  const http = props.context?.http;
  const owner = identity();
  // Inline host props may be recreated on navigation without changing the account.
  return previous && previous.http === http && previous.identity === owner
    ? previous
    : { http, identity: owner };
});
const access = usePersonalAccessTokenAccess({
  scopeKey: () => accountScope.value,
  client: tokens,
  sessionToken: () =>
    props.context?.currentUser && !props.menu?.hiddenCommands?.includes('accessTokens') ? token() : undefined,
});
const items = computed(() =>
  props.context ? createWorkbenchAccountMenu(language, props.menu, access.available.value) : undefined,
);
const active = ref<WorkbenchAccountCommand>();
const mandatoryPasswordRecovery = ref(false);
const busy = ref(false);
const loading = ref(false);
const error = ref<() => string>();
const currentPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const selectedSkin = ref(
  uiThemeSkinById(props.skinId ?? userPreferences.get(themeSkinPreferenceKey, defaultUiThemeSkinId)).id,
);
const themeFailure = ref<ThemeSkinPreferenceFailure>();
let epoch = 0;
let commandRevision = 0;
let profileLoadRevision = 0;
let themeRevision = 0;
watch(
  () => props.skinId,
  (id) => {
    if (id) selectedSkin.value = uiThemeSkinById(id).id;
  },
);
function clearPassword() {
  currentPassword.value = '';
  newPassword.value = '';
  confirmPassword.value = '';
}
function reset() {
  ++epoch;
  ++commandRevision;
  ++themeRevision;
  active.value = undefined;
  mandatoryPasswordRecovery.value = false;
  busy.value = false;
  loading.value = false;
  error.value = undefined;
  themeFailure.value = undefined;
  clearPassword();
}
watch(
  [identity, () => props.context?.http, token],
  () => {
    reset();
    if (token() && props.context?.currentUser) void restoreSkin();
  },
  { immediate: true, flush: 'sync' },
);
watch(
  active,
  () => {
    ++profileLoadRevision;
    loading.value = false;
  },
  { flush: 'sync' },
);
watch(access.available, (available) => {
  if (!available && active.value === 'accessTokens') close();
});
watch(items, (value) => {
  if (
    active.value &&
    !mandatoryPasswordRecovery.value &&
    value &&
    !value.some((item) => item.key === active.value)
  )
    reset();
});
function close() {
  if (!busy.value) {
    ++commandRevision;
    active.value = undefined;
    mandatoryPasswordRecovery.value = false;
    error.value = undefined;
    clearPassword();
  }
}
function showError(cause: unknown) {
  const failure = normalizeError(cause);
  error.value = () => userFacingErrorMessage(failure, language);
}
function setSkin(id: UiThemeSkinId) {
  selectedSkin.value = id;
  emit('update:skinId', id);
}
async function restoreSkin() {
  if (props.context?.preferencePersistence === 'local') return;
  const run = ++themeRevision;
  try {
    const id = await restoreThemeSkinPreference(
      userPreferences,
      selectedSkin.value,
      () => run === themeRevision,
    );
    if (run === themeRevision) setSkin(id);
  } catch {
    /* A preference service failure does not block the workbench. */
  }
}
async function selectSkin(id: UiThemeSkinId) {
  if (busy.value || id === selectedSkin.value) return;
  const run = ++themeRevision;
  const previous = selectedSkin.value;
  setSkin(id);
  busy.value = true;
  themeFailure.value = undefined;
  try {
    if (props.context?.preferencePersistence === 'local') {
      await userPreferences.set(themeSkinPreferenceKey, id, { persistence: 'local' });
      return;
    }
    const result = await saveThemeSkinPreference(userPreferences, id, previous, () => run === themeRevision);
    if (run === themeRevision) {
      setSkin(result.skinId);
      themeFailure.value = result.failure;
    }
  } finally {
    if (run === themeRevision) busy.value = false;
  }
}
async function select(key: string) {
  if (!props.context) {
    emit('userCommand', key);
    return;
  }
  const item = items.value?.find((item) => item.key === key);
  if (!item || item.disabled || busy.value) return;
  const run = ++commandRevision;
  const owner = identity();
  try {
    if (props.beforeCommand && !(await props.beforeCommand({ key, currentUser: props.context.currentUser })))
      return;
    if (
      run !== commandRevision ||
      owner !== identity() ||
      !items.value?.some((item) => item.key === key && !item.disabled)
    )
      return;
    error.value = undefined;
    themeFailure.value = undefined;
    if (key.includes('.')) {
      emit('userCommand', key);
      return;
    }
    if (key === 'logout') {
      close();
      emit('logout', 'requested');
      return;
    }
    if (key === 'accessTokens') {
      await access.refresh();
      if (run !== commandRevision || owner !== identity() || !access.available.value) return;
    }
    clearPassword();
    mandatoryPasswordRecovery.value = false;
    active.value = key as WorkbenchAccountCommand;
    if (key === 'profile') await loadProfile();
  } catch (cause) {
    if (run === commandRevision && owner === identity()) {
      if (active.value) showError(cause);
      else presentPlatformError(cause, { source: 'workbench-account', phase: 'action' });
    }
  }
}
async function loadProfile() {
  const owner = epoch;
  const run = ++profileLoadRevision;
  const isCurrent = () => owner === epoch && run === profileLoadRevision && active.value === 'profile';
  loading.value = true;
  try {
    await profile.refresh();
  } catch (cause) {
    if (isCurrent()) showError(cause);
  } finally {
    if (isCurrent()) loading.value = false;
  }
}
async function saveProfile(value: { mobile: string; email: string; avatarAssetId?: string }) {
  if (busy.value) return;
  const run = epoch;
  busy.value = true;
  error.value = undefined;
  try {
    if ((await profile.save(value)) && run === epoch) {
      active.value = undefined;
      presentPlatformSuccess(language.t('platform.screen.App.your-profile-has-been-saved', '个人资料已保存'));
    }
  } catch (cause) {
    if (run === epoch) showError(cause);
  } finally {
    if (run === epoch) busy.value = false;
  }
}
function passwordError() {
  if (!currentPassword.value.trim())
    return language.t('platform.screen.App.enter-your-current-password', '请输入当前密码');
  if (!newPassword.value.trim())
    return language.t('platform.screen.App.enter-your-new-password', '请输入新密码');
  if (newPassword.value !== confirmPassword.value)
    return language.t('platform.screen.App.the-new-passwords-do-not-match', '两次输入的新密码不一致');
  if (currentPassword.value === newPassword.value)
    return language.t(
      'platform.screen.App.the-new-password-must-differ-from-the-current',
      '新密码不能与当前密码相同',
    );
}
async function savePassword() {
  if (busy.value) return;
  const validation = passwordError();
  if (validation) {
    error.value = () => validation;
    return;
  }
  const session = token();
  if (!session) {
    error.value = () =>
      language.t('platform.screen.App.your-session-is-no-longer-valid-please-sign', '登录已失效，请重新登录');
    return;
  }
  const run = epoch;
  busy.value = true;
  error.value = undefined;
  try {
    await auth.changeOwnPassword(
      { currentPassword: currentPassword.value, newPassword: newPassword.value },
      session,
    );
    if (run === epoch) {
      active.value = undefined;
      clearPassword();
      emit('logout', 'passwordChanged');
    }
  } catch (cause) {
    if (run === epoch) showError(cause);
  } finally {
    if (run === epoch) busy.value = false;
  }
}
/** Mandatory credential recovery is independent of optional menu visibility and business guards. */
async function requestPasswordChange() {
  await nextTick();
  reset();
  mandatoryPasswordRecovery.value = true;
  active.value = 'changePassword';
}
onBeforeUnmount(reset);
defineExpose({ requestPasswordChange });
</script>

<template>
  <slot
    :items="items"
    :select="select"
    :refresh="access.refresh"
    :profile="profile.profile.value"
    :skin-id="selectedSkin"
  />
  <WorkbenchLanguageSettings
    v-if="context"
    :open="active === 'languageSettings'"
    :identity-scope="preferenceIdentity"
    @update:open="!$event && close()"
  />
  <ChangeOwnPasswordDialog
    v-model:current-password="currentPassword"
    v-model:new-password="newPassword"
    v-model:confirm-password="confirmPassword"
    :open="active === 'changePassword'"
    :saving="busy"
    :error="error?.()"
    @close="close"
    @submit="savePassword"
  />
  <CurrentUserProfileDialog
    :open="active === 'profile'"
    :profile="profile.profile.value"
    :loading="loading"
    :saving="busy"
    :error="error?.()"
    :external-login-client="external"
    :http="profileHttp"
    :auth-token="active === 'profile' ? context?.token : undefined"
    @close="close"
    @submit="saveProfile"
  />
  <PersonalAccessTokenDialog
    :open="active === 'accessTokens'"
    :client="tokens"
    :session-token="context?.token"
    @close="close"
  />
  <ThemeSkinPreferencesDialog
    :open="active === 'themeSkin'"
    :skins="uiThemeSkins"
    :active-skin-id="selectedSkin"
    :saving="busy"
    :failure="themeFailure"
    @close="close"
    @select="selectSkin"
  />
</template>
