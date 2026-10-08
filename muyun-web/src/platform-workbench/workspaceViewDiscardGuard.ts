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
): Promise<boolean> {
  const verb = operation === 'refresh' ? '刷新' : '关闭';
  if (hasBusyState(keys, verb)) return false;
  const dirtySources = [...new Set(keys.flatMap(workspaceViewUnsavedStateSources))];
  if (dirtySources.length === 0) return true;
  const confirmed = await confirmAction({
    title: operation === 'refresh' ? '刷新页面' : '关闭标签',
    content: `“${dirtySources.join('、')}”存在未保存的更改，${verb}后将丢失。是否继续？`,
    okText: verb,
  });
  // A mutation may begin while the user is considering the discard confirmation.
  return confirmed && !hasBusyState(keys, verb);
}

function hasBusyState(keys: readonly string[], verb: string): boolean {
  const sources = [...new Set(keys.flatMap(workspaceViewBusyStateSources))];
  if (sources.length === 0) return false;
  showErrorMessage(`“${sources.join('、')}”正在处理操作，请完成后再${verb}。`);
  return true;
}
