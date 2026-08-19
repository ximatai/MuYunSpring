import { handlePlatformActionSuccess, presentPlatformError } from '@muyun/platform-components';

/**
 * Common action boundary for a descriptor-driven page session.
 *
 * Enhancements are extension code. They may open their own UI or issue a
 * request, but an exception must never escape into the host render session.
 */
export function useModulePageActions() {
  function presentActionSuccess(result: unknown, fallbackMessage: string, source = 'dynamic-module-action') {
    return handlePlatformActionSuccess(result, {
      source,
      phase: 'action',
      fallbackMessage,
    });
  }

  async function runEnhancementAction<TContext>(
    contribution: { key: string; run(context: TContext): void | Promise<void> },
    actionContext: TContext,
  ) {
    try {
      await contribution.run(actionContext);
    } catch (cause) {
      presentPlatformError(cause, {
        source: `module-page-enhancement:${contribution.key}`,
        phase: 'action',
      });
    }
  }

  return { presentActionSuccess, runEnhancementAction };
}
