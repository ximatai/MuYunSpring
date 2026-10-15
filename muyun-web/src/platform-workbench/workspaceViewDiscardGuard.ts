import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import { confirmAction, showErrorMessage } from '@muyun/vue-ui-antdv';
import { workspaceViewBusyStateSources, workspaceViewUnsavedStateSources } from './workspaceViewUnsavedState';

/** Clean pages need no asynchronous confirmation and retain immediate navigation semantics. */
export function hasWorkspaceViewDiscardProtection(keys: readonly string[]): boolean {
  return keys.some(
    (key) =>
      workspaceViewBusyStateSources(key).length > 0 || workspaceViewUnsavedStateSources(key).length > 0,
  );
}

/** Every host must consult the same draft facts before destroying a workspace page. */
export async function confirmDiscardWorkspaceViewState(
  keys: readonly string[],
  operation: 'close' | 'refresh' = 'close',
  language: LanguageContext = platformLanguageContext,
): Promise<boolean> {
  if (hasBusyState(keys, operation, language)) return false;
  const dirtySources = [...new Set(keys.flatMap(workspaceViewUnsavedStateSources))];
  if (dirtySources.length === 0) return true;
  const presentation = () => {
    const verb =
      operation === 'refresh'
        ? language.t('platform.screen.workspaceViewDiscardGuard.refresh', '刷新')
        : language.t('platform.screen.workspaceViewDiscardGuard.close', '关闭');
    return {
      title:
        operation === 'refresh'
          ? language.t('platform.screen.workspaceViewDiscardGuard.refresh-page', '刷新页面')
          : language.t('platform.screen.UiTabs.close-tab', '关闭标签'),
      content: language.t(
        'platform.screen.workspaceViewDiscardGuard.value-has-unsaved-changes-that-will-be-lost-when-you',
        '“{value1}”存在未保存的更改，{value2}后将丢失。是否继续？',
        {
          value1: [...new Set(keys.flatMap(workspaceViewUnsavedStateSources))].join(
            language.t('platform.list.separator', '、'),
          ),
          value2: verb,
        },
      ),
      okText: verb,
    };
  };
  const confirmed = await confirmAction({ ...presentation(), presentation }, language);
  // A mutation may begin while the user is considering the discard confirmation.
  return confirmed && !hasBusyState(keys, operation, language);
}

function hasBusyState(
  keys: readonly string[],
  operation: 'close' | 'refresh',
  language: LanguageContext,
): boolean {
  const verb =
    operation === 'refresh'
      ? language.t('platform.screen.workspaceViewDiscardGuard.refresh', '刷新')
      : language.t('platform.screen.workspaceViewDiscardGuard.close', '关闭');
  const sources = [...new Set(keys.flatMap(workspaceViewBusyStateSources))];
  if (sources.length === 0) return false;
  showErrorMessage(
    language.t(
      'platform.screen.workspaceViewDiscardGuard.value-has-an-operation-in-progress-wait-for-it-to',
      '“{value1}”正在处理操作，请完成后再{value2}。',
      { value1: sources.join(language.t('platform.list.separator', '、')), value2: verb },
    ),
  );
  return true;
}
