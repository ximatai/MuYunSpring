import { useLanguageContext } from '@muyun/web-core';
import { computed, type MaybeRefOrGetter, toValue } from 'vue';
import type { DrawerPromotion } from '@muyun/platform-components';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';
import {
  createWorkspaceViewDescriptor,
  type WorkspaceViewDefinition,
  type WorkspaceViewInput,
} from './workspaceViews';

/** Facts required by the source host; dirty state belongs to the shared view session. */
export interface WorkspaceViewPromotionEligibility {
  hasStableIdentity: boolean;
  busy?: boolean;
  /** A legacy host may decline until its detail state has moved into a view session. */
  canChangeHost?: boolean;
}

export function canPromoteWorkspaceView(eligibility: WorkspaceViewPromotionEligibility) {
  return eligibility.hasStableIdentity && eligibility.busy !== true && eligibility.canChangeHost !== false;
}

export function useWorkspaceViewPromotion<TInput extends WorkspaceViewInput>(options: {
  view: WorkspaceViewDefinition<TInput>;
  input: MaybeRefOrGetter<TInput | undefined>;
  /** A source host can provide its resolved business identity for the new tab label. */
  title?: MaybeRefOrGetter<string | undefined>;
  eligibility: MaybeRefOrGetter<WorkspaceViewPromotionEligibility>;
  /** Transfers source state before the target opens; return false when an existing target rejects it. */
  beforePromote?: (input: TInput) => boolean | void | Promise<boolean | void>;
  onPromoted?: (result: { created: boolean }) => void;
  onPromotionRejected?: () => void;
}) {
  const navigation = useWorkbenchNavigation();
  const language = useLanguageContext();
  return computed<DrawerPromotion | undefined>(() => {
    const input = toValue(options.input);
    if (!input || !canPromoteWorkspaceView(toValue(options.eligibility)) || !navigation) return undefined;
    return {
      title: language.t('platform.workspace-view.pin-as-tab', '固定为页签'),
      promote: async () => {
        const accepted = await options.beforePromote?.(input);
        if (accepted === false) {
          options.onPromotionRejected?.();
          return;
        }
        const result = navigation.openPage(
          createWorkspaceViewDescriptor(options.view, input, 'tab', toValue(options.title), language),
        );
        options.onPromoted?.(result);
      },
    };
  });
}
