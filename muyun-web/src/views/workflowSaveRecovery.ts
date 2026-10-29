export interface WorkflowSaveReference {
  requestId: string;
  versionId: string;
  expectedVersion: number;
}

/** Tab-scoped query evidence only: no draft, executable payload, or authorization. */
export function workflowSaveRecovery(
  scope: string,
  storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>,
) {
  const key = `muyun.workflow-design-save.${scope}`;
  function restore(): WorkflowSaveReference | undefined {
    const raw = storage.getItem(key);
    if (!raw) return undefined;
    const value = JSON.parse(raw) as WorkflowSaveReference;
    if (
      !value ||
      Object.keys(value).sort().join(',') !== 'expectedVersion,requestId,versionId' ||
      typeof value.requestId !== 'string' ||
      !/^[a-zA-Z0-9-]{16,80}$/.test(value.requestId) ||
      typeof value.versionId !== 'string' ||
      !value.versionId ||
      value.versionId.length > 128 ||
      !Number.isSafeInteger(value.expectedVersion) ||
      value.expectedVersion < 0
    )
      throw new Error('原流程保存查询引用无效，请核实后恢复');
    return value;
  }
  return {
    restore,
    save(reference: WorkflowSaveReference) {
      if (restore()) throw new Error('原流程保存结果尚未核实');
      storage.setItem(key, JSON.stringify(reference));
    },
    clear(requestId: string) {
      if (restore()?.requestId === requestId) storage.removeItem(key);
    },
  };
}
