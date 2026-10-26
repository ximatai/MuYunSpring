import { computed, ref } from 'vue';
import type { Router } from 'vue-router';
import {
  normalizeError,
  useLanguageContext,
  userFacingErrorMessage,
  type ExternalLoginClient,
} from '@muyun/web-core';
import type { LoginResult } from '@muyun/web-contracts';
import { presentPlatformMessage, presentPlatformSuccess } from '@muyun/platform-components';
import { externalLoginFailureMessage, takeExternalLoginReturn } from './externalLoginReturn';

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
    error: computed(() => failure.value?.()),
    completing: computed(() => completing.value),
    /** True means login handoff or a signed-out failure already decided the screen. Concurrent calls coalesce. */
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
