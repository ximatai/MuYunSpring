export interface CodeRuleSaveReference {
  requestId: string;
  moduleAlias: string;
  entityAlias: string;
  fieldName: string;
  ruleId?: string;
}
export function codeRuleSaveRecovery(
  scope: string,
  storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>,
) {
  const key = `muyun.code-rule-save.${scope}`;
  const restore = (): CodeRuleSaveReference | undefined => {
    const raw = storage.getItem(key);
    if (!raw) return undefined;
    const value = JSON.parse(raw) as CodeRuleSaveReference;
    if (
      !value ||
      Object.keys(value).some(
        (name) => !['requestId', 'moduleAlias', 'entityAlias', 'fieldName', 'ruleId'].includes(name),
      ) ||
      typeof value.requestId !== 'string' ||
      !/^[a-zA-Z0-9-]{16,80}$/.test(value.requestId) ||
      [value.moduleAlias, value.entityAlias, value.fieldName].some(
        (item) => typeof item !== 'string' || !item || item.length > 128,
      ) ||
      (value.ruleId !== undefined && (typeof value.ruleId !== 'string' || value.ruleId.length > 64))
    )
      throw new Error('原编号保存查询引用无效');
    return value;
  };
  return {
    restore,
    save(value: CodeRuleSaveReference) {
      if (restore()) throw new Error('请先核实原编号保存结果');
      storage.setItem(key, JSON.stringify(value));
    },
    clear(requestId: string) {
      if (restore()?.requestId === requestId) storage.removeItem(key);
    },
  };
}
