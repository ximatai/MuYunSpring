import { onScopeDispose, ref, watch } from 'vue';
import type { CurrentUser, CurrentUserProfile, UpdateCurrentUserProfileRequest } from '@muyun/web-contracts';
import type { AuthClient } from '@muyun/web-core';

/** Profile presentation stays outside the authenticated principal and is cleared on identity changes. */
export function useCurrentUserProfile(options: {
  currentUser: () => CurrentUser | undefined;
  token: () => string | undefined;
  client: Pick<AuthClient, 'currentProfile' | 'updateCurrentProfile'>;
}) {
  const profile = ref<CurrentUserProfile>();
  let revision = 0;
  let disposed = false;
  const identity = () => {
    const user = options.currentUser();
    return user
      ? JSON.stringify([options.token(), user.userId, user.tenantId, user.organizationId, user.system])
      : undefined;
  };
  async function request(load: (token: string) => Promise<CurrentUserProfile>) {
    const token = options.token();
    const owner = identity();
    if (!token || !owner || disposed) return false;
    const run = ++revision;
    const active = () => !disposed && run === revision && owner === identity();
    try {
      const result = await load(token);
      if (!active()) return false;
      profile.value = result;
      return true;
    } catch (cause) {
      if (active()) throw cause;
      return false;
    }
  }
  const refresh = () => request((token) => options.client.currentProfile(token));
  const save = (value: UpdateCurrentUserProfileRequest) =>
    request((token) => options.client.updateCurrentProfile(value, token));
  watch(
    identity,
    () => {
      revision++;
      profile.value = undefined;
      // A decorative name must not block startup or surface a second login error.
      void refresh().catch(() => undefined);
    },
    { immediate: true, flush: 'sync' },
  );
  onScopeDispose(() => {
    disposed = true;
    revision++;
  });
  return { profile, refresh, save };
}
