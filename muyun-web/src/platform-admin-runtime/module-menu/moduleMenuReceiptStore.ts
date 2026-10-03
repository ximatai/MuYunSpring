import { OperationUsageError, userPreferences, type UserPreferenceStore } from '@muyun/web-core';
import type { OperationReceiptReference } from '@muyun/web-contracts';
import type { ModuleMenuReceiptStore } from './moduleMenuSession';

export type MenuReceiptPointerStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/** Each request has its own account-owned reference; the original browser tab retains only its lookup pointer. */
export function createModuleMenuReceiptStore(
  identity: string,
  moduleAlias: string,
  valid: () => boolean,
  preferences: UserPreferenceStore = userPreferences,
  pointerStorage?: MenuReceiptPointerStorage,
): ModuleMenuReceiptStore {
  const pointerKey = `muyun.menu-pending.${JSON.stringify([identity, moduleAlias])}`;
  const options = { persistence: 'backend' as const };
  let requestId: string | undefined;
  function storage() {
    if (!valid()) throw new OperationUsageError('菜单编辑身份已变化');
    return pointerStorage ?? window.sessionStorage;
  }
  function referenceKey(id: string) {
    if (!/^[a-zA-Z0-9-]{16,80}$/.test(id)) throw new OperationUsageError('原入口查询标识无效');
    // Preference segments must begin with a letter; request identities never share a mutable key.
    return `menu.pending.r${id.toLowerCase().replaceAll('-', '')}`;
  }
  return {
    async restore() {
      requestId = storage().getItem(pointerKey) ?? undefined;
      if (!requestId) return undefined;
      const reference = await preferences.restore<OperationReceiptReference | undefined>(
        referenceKey(requestId),
        undefined,
        options,
      );
      if (!reference) throw new OperationUsageError('原入口查询引用暂不可读取，请核实后重试');
      return reference;
    },
    async save(reference) {
      if (reference.kind !== 'record-save') throw new OperationUsageError('原入口查询标识无效');
      const pointer = storage();
      requestId = reference.requestId;
      await preferences.set(referenceKey(requestId), reference, options);
      storage(); // Identity may change while the account write is pending.
      pointer.setItem(pointerKey, requestId);
    },
    async clear() {
      const pointer = storage();
      const original = requestId;
      if (!original) return;
      await preferences.remove(referenceKey(original), options);
      storage();
      if (pointer.getItem(pointerKey) === original) pointer.removeItem(pointerKey);
      if (requestId === original) requestId = undefined;
    },
  };
}
