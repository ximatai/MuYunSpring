import { OperationUsageError, parseOperationReceiptReference } from '@muyun/web-core';
import type { OperationReceiptReference } from '@muyun/web-contracts';

type RecordSaveReference = Extract<OperationReceiptReference, { kind: 'record-save' }>;
type ReceiptStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/** One browser tab retains only a query reference, never a draft or authorization. */
export function createRecordSaveRecovery(scope: string, storage: ReceiptStorage) {
  const key = `muyun.record-save.${scope}`;
  return {
    restore(): RecordSaveReference | undefined {
      const stored = storage.getItem(key);
      if (!stored) return undefined;
      const reference = parseOperationReceiptReference(JSON.parse(stored));
      if (reference.kind !== 'record-save') throw new OperationUsageError('原保存查询引用无效');
      return reference;
    },
    save(reference: RecordSaveReference) {
      const previous = this.restore();
      if (previous && previous.requestId !== reference.requestId)
        throw new OperationUsageError('原保存结果尚未确定，请先核实，不要重复提交');
      storage.setItem(key, JSON.stringify(parseOperationReceiptReference(reference)));
    },
    clear(requestId: string) {
      if (this.restore()?.requestId === requestId) storage.removeItem(key);
    },
  };
}
