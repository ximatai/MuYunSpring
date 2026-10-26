import { computed, ref } from 'vue';
import type { Router } from 'vue-router';
import {
  normalizeError,
  useLanguageContext,
  userFacingErrorMessage,
  type ExternalLoginClient,
} from '@muyun/web-core';
import type { ExternalProvisioningConfirmation, LoginResult } from '@muyun/web-contracts';
import { presentPlatformMessage, presentPlatformSuccess } from '@muyun/platform-components';
import {
  externalLoginFailureMessage,
  takeExternalLoginReturn,
  type PendingExternalLogin,
} from './externalLoginReturn';

/** App-level composition; protocol clients and browser transaction storage remain separate concerns. */
export interface ExternalLoginHostOptions {
  client: ExternalLoginClient;
  router: Router;
  /** Receives the session exactly as password login does; the host owns persistence and startup. */
  onAuthenticated: (login: LoginResult) => void | Promise<void>;
  signedIn: () => boolean;
  /** Workbench route registered as the provider redirect URI, relative to the router base. */
  callbackPath?: string;
}

/** Public host state. Call completeReturn before restoring the ordinary workbench session. */
export function useExternalLoginHost(options: ExternalLoginHostOptions) {
  const language = useLanguageContext();
  const failure = ref<() => string>();
  const completing = ref(false);
  let inFlight: Promise<boolean> | undefined;
  const provisioning = ref<{
    pending: PendingExternalLogin;
    confirmation: ExternalProvisioningConfirmation;
  }>();
  const confirming = ref(false);

  async function confirmProvisioning(): Promise<void> {
    const selected = provisioning.value;
    if (!selected || confirming.value) return;
    confirming.value = true;
    failure.value = undefined;
    try {
      const result = await options.client.confirm(selected.pending.providerId, {
        state: selected.pending.state,
        browserProof: selected.pending.browserProof,
      });
      if (result.status === 'succeeded') {
        provisioning.value = undefined;
        if (result.login) await options.onAuthenticated(result.login);
        else
          failure.value = () =>
            language.t('platform.external-login.created-sign-in-again', '账号已创建，请重新扫码登录');
      } else failure.value = () => externalLoginFailureMessage(result, language);
    } catch (cause) {
      failure.value = () => userFacingErrorMessage(normalizeError(cause), language);
    } finally {
      confirming.value = false;
    }
  }

  async function complete(): Promise<boolean> {
    completing.value = true;
    failure.value = undefined;
    try {
      await options.router.isReady();
      const route = options.router.currentRoute.value;
      const path = options.callbackPath ?? '/';
      const returned = route.path === path ? takeExternalLoginReturn(route.query) : undefined;
      if (!returned) return false;
      const pending = returned.pending;
      // Replace before network I/O: refresh/back must never replay provider parameters.
      await options.router.replace({
        path,
        query: pending?.purpose === 'login' && pending.tenantId ? { _muyunTenantId: pending.tenantId } : {},
        hash: '',
      });
      if (!pending) return false;
      const completion = await options.client.complete(pending.providerId, {
        state: pending.state,
        browserProof: pending.browserProof,
        parameters: returned.parameters,
      });
      if (
        completion.status === 'confirmationRequired' &&
        completion.confirmation &&
        pending.purpose === 'login'
      ) {
        provisioning.value = { pending, confirmation: completion.confirmation };
        return true;
      }
      if (completion.status === 'succeeded' && completion.login) {
        await options.onAuthenticated(completion.login);
        return true;
      }
      if (completion.status === 'succeeded') {
        presentPlatformSuccess(
          language.t('platform.external-login.bound', '已绑定 {provider}', {
            provider: completion.binding?.providerTitle ?? '',
          }),
        );
        return false;
      }
      failure.value = () => externalLoginFailureMessage(completion, language);
      if (pending.purpose === 'login' && !options.signedIn()) return true;
      presentPlatformMessage(failure.value());
      return false;
    } catch (cause) {
      failure.value = () => userFacingErrorMessage(normalizeError(cause), language);
      if (!options.signedIn()) return true;
      presentPlatformMessage(failure.value());
      return false;
    } finally {
      completing.value = false;
    }
  }

  return {
    client: options.client,
    /** Read-only server profile. Browser credentials remain private to this host instance. */
    confirmation: computed(() => provisioning.value?.confirmation),
    confirming: computed(() => confirming.value),
    confirmProvisioning,
    cancelProvisioning() {
      if (confirming.value) return;
      provisioning.value = undefined;
      failure.value = undefined;
    },
    error: computed(() => failure.value?.()),
    completing: computed(() => completing.value),
    /** True means session handoff, pending confirmation, or a signed-out failure decided the screen. Concurrent calls coalesce. */
    completeReturn(): Promise<boolean> {
      if (!inFlight)
        inFlight = complete().finally(() => {
          inFlight = undefined;
        });
      return inFlight;
    },
  };
}

export type ExternalLoginHost = ReturnType<typeof useExternalLoginHost>;
