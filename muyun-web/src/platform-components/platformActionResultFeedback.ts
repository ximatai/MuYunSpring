import { presentPlatformSuccess, type PlatformErrorFeedbackContext } from './platformErrorFeedback';
import { showErrorMessage, showInfoMessage, showWarningMessage } from '@muyun/vue-ui-antdv';
import { platformLanguageContext } from '@muyun/web-core';
import {
  resolvePlatformActionResult,
  type PlatformActionResultReactionHandler,
} from './platformActionResultReactions';

export {
  createPlatformActionResultReactionHandlers,
  mergePlatformActionResultReactionHandlers,
  platformActionResultReactions,
  platformActionResultReactionTypes,
  resolvePlatformActionResult,
  resolvePlatformActionResultMessage,
  withPlatformActionResultReactions,
} from './platformActionResultReactions';
export type {
  PlatformActionResult,
  PlatformActionResultReaction,
  PlatformActionResultReactionHandler,
  PlatformActionResultReactionPayload,
  PlatformActionResultReactionType,
  PlatformActionResultStandardReactionHandlers,
} from './platformActionResultReactions';

export interface PlatformActionResultFeedbackContext extends PlatformErrorFeedbackContext {
  fallbackMessage?: string;
}

export interface PlatformActionResultHandlingContext extends PlatformActionResultFeedbackContext {
  reactionHandlers?: Record<string, PlatformActionResultReactionHandler | undefined>;
}

export async function handlePlatformActionSuccess(
  result: unknown,
  context: PlatformActionResultHandlingContext = {},
) {
  const actionResult = resolvePlatformActionResult(result, {
    fallbackMessage:
      context.fallbackMessage ??
      (context.language ?? platformLanguageContext).t('platform.action.success', '操作成功'),
  });
  presentActionMessage(actionResult.message, actionResult.messageType, context);
  for (const reaction of actionResult.reactions) {
    await context.reactionHandlers?.[reaction.type]?.(reaction, actionResult);
  }
  return actionResult;
}

export function presentPlatformActionSuccess(
  result: unknown,
  context: PlatformActionResultFeedbackContext = {},
) {
  const actionResult = resolvePlatformActionResult(result, {
    fallbackMessage:
      context.fallbackMessage ??
      (context.language ?? platformLanguageContext).t('platform.action.success', '操作成功'),
  });
  presentActionMessage(actionResult.message, actionResult.messageType, context);
}

function presentActionMessage(
  message: string,
  messageType: string | undefined,
  context: PlatformErrorFeedbackContext,
) {
  if (messageType === 'WARNING') {
    showWarningMessage(message);
    return;
  }
  if (messageType === 'INFO') {
    showInfoMessage(message);
    return;
  }
  if (messageType === 'ERROR') {
    showErrorMessage(message);
    return;
  }
  presentPlatformSuccess(message, context);
}
