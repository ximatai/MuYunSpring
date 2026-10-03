import { computed, ref, shallowRef } from 'vue';
import {
  AppError,
  OperationRejectedError,
  OperationUsageError,
  type OperationProposal,
  type ModuleRuntimeContext,
  parseOperationReceiptReference,
} from '@muyun/web-core';
import type {
  MenuOpenMode,
  MenuRecord,
  MenuScheme,
  MenuTreeNode,
  OperationReceiptReference,
} from '@muyun/web-contracts';
import {
  menuPlacements,
  menuSchemeScopeLabel,
  type MenuPlacement,
  type createModuleMenuClient,
} from './moduleMenuClient';

export type ModuleMenuClient = ReturnType<typeof createModuleMenuClient>;
export interface ModuleMenuRevision {
  title?: string;
  openMode?: MenuOpenMode;
  schemeId?: string;
  parentId?: string;
}

export interface ModuleMenuReceiptStore {
  restore(): Promise<OperationReceiptReference | undefined>;
  save(reference: OperationReceiptReference): Promise<void>;
  clear(): Promise<void>;
}

/** One standard menu candidate and durable save identity, shared by all UI entry points. */
export function createModuleMenuSession(
  client: ModuleMenuClient,
  moduleAlias: string,
  scope: { valid(): boolean; active(): boolean },
  receipts: ModuleMenuReceiptStore,
  refreshMenus: () => Promise<MenuTreeNode[]> = client.visible,
) {
  const revision = ref(0);
  const loading = ref(false);
  const saving = ref(false);
  const error = ref('');
  const entryIssue = ref('');
  const title = ref('');
  const schemeId = ref('');
  const parentId = ref('root');
  const openMode = ref<MenuOpenMode>('tab');
  const schemes = shallowRef<MenuScheme[]>([]);
  const tree = shallowRef<MenuTreeNode[]>([]);
  const saved = shallowRef<MenuRecord>();
  const savedPath = ref('');
  const savedAudience = ref('');
  const visibleMenu = shallowRef<MenuRecord>();
  const visibilityChecked = ref(false);
  const authorized = ref(false);
  const ready = ref(false);
  const resultUnknown = ref(false);
  let disposed = false;
  let loadGeneration = 0;
  let pending:
    | {
        requestId: string;
        schemeId: string;
        record?: MenuRecord;
        path?: string;
        audience?: string;
        rejected?: boolean;
      }
    | undefined;
  let restored = false;
  const valid = () => !disposed && scope.valid();
  const options = computed(() => schemes.value.filter((item) => item.enabled !== false && item.id));
  const directories = computed(() => availableDirectories(tree.value));
  const canSave = computed(
    () =>
      valid() &&
      ready.value &&
      authorized.value &&
      !loading.value &&
      !saving.value &&
      !pending &&
      !saved.value &&
      !entryIssue.value &&
      Boolean(title.value.trim()),
  );
  const duplicate = computed(() =>
    menuPlacements(tree.value).find(
      ({ menu }) => menu.moduleAlias === moduleAlias && menu.parentId === parentId.value,
    ),
  );
  function requireCurrent() {
    if (!valid()) throw new OperationUsageError('菜单编辑范围已变化，请重新打开');
  }
  function requireEditable() {
    requireCurrent();
    if (!ready.value || !authorized.value || loading.value || saving.value || pending || saved.value)
      throw new OperationUsageError('请先等待菜单候选就绪，或查询未确定的保存结果');
  }
  async function load() {
    requireCurrent();
    if (saved.value || (pending && ready.value)) return;
    const generation = ++loadGeneration;
    loading.value = true;
    ready.value = false;
    error.value = '';
    try {
      if (!restored) {
        const stored = await receipts.restore();
        if (!valid() || generation !== loadGeneration) return;
        if (stored) {
          const reference = parseOperationReceiptReference(stored);
          if (
            reference.kind !== 'record-save' ||
            reference.moduleAlias !== 'platform.menu' ||
            !reference.pageContext?.scheme
          )
            throw new OperationUsageError('原入口查询范围无效，请核实保存结果后继续');
          pending = { requestId: reference.requestId, schemeId: reference.pageContext.scheme };
          resultUnknown.value = true;
        }
        restored = true;
      }
      const [catalog, entry, management, mine] = await Promise.all([
        client.schemes(),
        client.context(moduleAlias),
        client.context('platform.menu'),
        client.visible(),
      ]);
      if (!valid() || generation !== loadGeneration) return;
      authorized.value = management.actions.some(
        (action) => action.actionCode === 'create' && action.authorized,
      );
      entryIssue.value = moduleMenuEntryIssue(entry);
      schemes.value = catalog;
      title.value ||= entry.title ?? moduleAlias;
      openMode.value = entry.entryType === 'link' ? 'window' : 'tab';
      const preferred = mine[0]?.record.schemeId;
      const selected =
        options.value.find((item) => item.id === (pending?.schemeId ?? schemeId.value)) ??
        options.value.find((item) => item.id === preferred) ??
        options.value[0];
      if (!selected?.id) throw new OperationUsageError('暂无可用菜单方案，请先配置适用的菜单方案');
      const nodes = await client.tree(selected.id);
      if (!valid() || generation !== loadGeneration) return;
      schemeId.value = selected.id;
      tree.value = nodes;
      parentId.value = 'root';
      ready.value = true;
      revision.value++;
    } catch (cause) {
      if (valid() && generation === loadGeneration) error.value = messageOf(cause);
    } finally {
      if (valid() && generation === loadGeneration) loading.value = false;
    }
  }
  async function planRevision(input: ModuleMenuRevision) {
    requireEditable();
    const before = revision.value;
    const targetScheme = input.schemeId ?? schemeId.value;
    if (!options.value.some((item) => item.id === targetScheme))
      throw new OperationUsageError('请选择当前可用的菜单方案');
    const targetTitle = input.title ?? title.value;
    if ([...targetTitle].length > 120) throw new OperationUsageError('菜单名称不能超过120个字符');
    const nodes = targetScheme === schemeId.value ? tree.value : await client.tree(targetScheme);
    const targetParent = input.parentId ?? (targetScheme === schemeId.value ? parentId.value : 'root');
    if (targetParent !== 'root' && !availableDirectories(nodes).some((item) => item.menu.id === targetParent))
      throw new OperationUsageError('请选择当前方案中的启用目录');
    return () => {
      requireEditable();
      if (before !== revision.value) throw new OperationUsageError('菜单候选已变化，请重新读取');
      schemeId.value = targetScheme;
      tree.value = nodes;
      parentId.value = targetParent;
      title.value = targetTitle;
      openMode.value = input.openMode ?? openMode.value;
      revision.value++;
    };
  }
  async function update(input: ModuleMenuRevision) {
    try {
      (await planRevision(input))();
      error.value = '';
    } catch (cause) {
      error.value = messageOf(cause);
    }
  }
  async function refreshVisibility() {
    requireCurrent();
    const id = saved.value?.id;
    if (!id) return;
    visibilityChecked.value = false;
    visibleMenu.value = undefined;
    try {
      const nodes = await refreshMenus();
      if (!valid() || saved.value?.id !== id) return;
      visibleMenu.value = menuPlacements(nodes).find(({ menu }) => menu.id === id)?.menu;
      visibilityChecked.value = true;
      error.value = '';
    } catch {
      if (valid()) error.value = '菜单已添加，导航刷新失败。请查询或刷新导航，无需重复添加。';
    }
  }
  async function accepted(recordId: string, record?: MenuRecord) {
    if (valid()) {
      const original = pending!;
      const resolved =
        record ??
        (original.record
          ? { ...original.record, id: recordId }
          : await client.view(original.schemeId, recordId));
      requireCurrent();
      saved.value = resolved;
      savedAudience.value =
        original.audience ??
        menuSchemeScopeLabel(options.value.find((item) => item.id === resolved.schemeId));
      savedPath.value =
        original.path ??
        [
          options.value.find((item) => item.id === resolved.schemeId)?.title,
          menuPlacements(tree.value).find(({ menu }) => menu.id === resolved.parentId)?.path,
          resolved.title,
        ]
          .filter(Boolean)
          .join(' / ');
      pending = undefined;
      resultUnknown.value = false;
      revision.value++;
      await receipts.clear().catch(() => {});
      if (valid()) await refreshVisibility();
    }
    return {
      title: '菜单入口已保存',
      lines: valid()
        ? [
            savedPath.value,
            visibilityChecked.value
              ? visibleMenu.value
                ? '当前用户可见，可打开业务页面。'
                : '入口已保存；当前登录身份暂不可见，请由适用范围内的使用者核实导航。'
              : '导航可见性尚未核实；不要重复添加。',
          ]
        : ['保存已完成，请在原范围核实入口。'],
    };
  }
  async function recheck(record: MenuRecord, expectedSignature?: string) {
    const [entry, management, catalog, nodes] = await Promise.all([
      client.context(moduleAlias),
      client.context('platform.menu'),
      client.schemes(),
      client.tree(record.schemeId),
    ]);
    requireCurrent();
    if (!management.actions.some((action) => action.actionCode === 'create' && action.authorized))
      throw new OperationRejectedError('当前没有添加菜单入口权限');
    if (moduleMenuEntryIssue(entry)) throw new OperationRejectedError(moduleMenuEntryIssue(entry));
    const scheme = catalog.find((item) => item.id === record.schemeId && item.enabled !== false);
    if (!scheme) throw new OperationRejectedError('菜单方案已不可用');
    if (
      record.parentId !== 'root' &&
      !availableDirectories(nodes).some((item) => item.menu.id === record.parentId)
    )
      throw new OperationRejectedError('放置目录已不可用，请重新审阅');
    const ancestry = record.parentId === 'root' ? [] : directoryAncestry(nodes, record.parentId!);
    const signature = JSON.stringify([
      scheme.id,
      scheme.alias ?? null,
      scheme.title ?? null,
      scheme.scopeType ?? null,
      scheme.tenantId ?? null,
      scheme.organizationId ?? null,
      ancestry.map((item) => [item.id, item.title]),
    ]);
    if (expectedSignature && signature !== expectedSignature)
      throw new OperationRejectedError('菜单方案适用范围或目录已变化，请重新读取候选并审阅');
    return {
      scheme,
      signature,
      path: [scheme.title ?? scheme.alias, ...ancestry.map((item) => item.title), record.title]
        .filter(Boolean)
        .join(' / '),
    };
  }
  async function prepare(): Promise<OperationProposal> {
    requireEditable();
    if (!canSave.value) throw new OperationUsageError('请补齐菜单名称及可用业务页面');
    const before = revision.value;
    const record = {
      moduleAlias,
      schemeId: schemeId.value,
      parentId: parentId.value,
      title: title.value.trim(),
      openMode: openMode.value,
      enabled: true,
    } as MenuRecord;
    const basis = await recheck(record);
    requireEditable();
    if (revision.value !== before) throw new OperationUsageError('菜单候选已变化，请重新审阅');
    const path = basis.path;
    const requestId = crypto.randomUUID();
    const isCurrent = () =>
      valid() &&
      scope.active() &&
      ready.value &&
      authorized.value &&
      !pending &&
      !saved.value &&
      revision.value === before;
    const receiptReference: OperationReceiptReference = {
      kind: 'record-save',
      moduleAlias: 'platform.menu',
      requestId,
      pageContext: { scheme: record.schemeId },
    };
    return {
      receiptReference,
      presentation: {
        title: '添加业务入口',
        lines: [
          path,
          `适用范围：${menuSchemeScopeLabel(basis.scheme)}`,
          `打开方式：${record.openMode === 'window' ? '新窗口' : '页签内'}`,
          '只添加入口，不开通应用或授予业务权限。',
          ...(duplicate.value ? [`此位置已有「${duplicate.value.menu.title}」，本次将另加一个入口。`] : []),
        ],
        details: { title: '查看目标模块', lines: [moduleAlias] },
      },
      confirmLabel: '确认添加入口',
      expiresAt: Date.now() + 5 * 60_000,
      isCurrent,
      async execute() {
        if (!isCurrent() || saving.value)
          throw new OperationRejectedError('菜单候选或编辑范围已变化，请重新审阅');
        saving.value = true;
        error.value = '';
        let posted = false;
        try {
          await recheck(record, basis.signature);
          if (!isCurrent()) throw new OperationRejectedError('菜单候选或编辑范围已变化，请重新审阅');
          pending = {
            requestId,
            schemeId: record.schemeId,
            record,
            path,
            audience: menuSchemeScopeLabel(basis.scheme),
          };
          await receipts.save(receiptReference);
          if (!valid() || !scope.active() || revision.value !== before)
            throw new OperationRejectedError('菜单候选或编辑范围已变化，请重新审阅');
          posted = true;
          const result = await client.insert(record.schemeId, record, requestId);
          return await accepted(result.record.id, result.record);
        } catch (cause) {
          if (valid()) {
            if (
              !posted ||
              cause instanceof OperationRejectedError ||
              (cause instanceof AppError && [400, 401, 403, 404, 409, 422].includes(cause.status ?? 0))
            ) {
              try {
                if (pending) await receipts.clear();
                pending = undefined;
              } catch {
                if (pending) pending.rejected = true;
                resultUnknown.value = true;
                error.value = '操作未提交，但原请求标识尚未清除，请先查询结果。';
              }
              throw new OperationRejectedError(messageOf(cause));
            }
            if (pending) {
              resultUnknown.value = true;
              revision.value++;
            }
            error.value = pending ? '保存结果尚未确定，请查询原请求结果，勿重复添加。' : messageOf(cause);
          }
          throw cause;
        } finally {
          if (valid()) saving.value = false;
        }
      },
      lookup: lookupPending,
    };
  }
  async function lookupPending() {
    requireCurrent();
    if (!pending || saving.value) return;
    const original = pending;
    if (original.rejected) {
      await receipts.clear();
      requireCurrent();
      if (pending !== original) return;
      pending = undefined;
      resultUnknown.value = false;
      error.value = '';
      revision.value++;
      return { title: '操作未提交', lines: ['恢复标识已清除，可以重新审阅并添加入口。'] };
    }
    const receipt = await client.receipt(original.schemeId, original.requestId);
    requireCurrent();
    if (pending !== original || !receipt.committed || !receipt.recordId) return;
    return accepted(receipt.recordId);
  }
  return {
    moduleAlias,
    revision,
    loading,
    saving,
    error,
    entryIssue,
    title,
    schemeId,
    parentId,
    openMode,
    schemes,
    tree,
    options,
    directories,
    saved,
    savedPath,
    savedAudience,
    visibleMenu,
    visibilityChecked,
    authorized,
    ready,
    resultUnknown,
    canSave,
    duplicate,
    load,
    update,
    planRevision,
    prepare,
    lookupPending,
    refreshVisibility,
    async startAnother() {
      requireCurrent();
      if (!saved.value || saving.value || pending) throw new OperationUsageError('请先核实当前入口保存结果');
      await receipts.clear();
      requireCurrent();
      saved.value = undefined;
      savedPath.value = '';
      savedAudience.value = '';
      visibleMenu.value = undefined;
      visibilityChecked.value = false;
      ready.value = false;
      revision.value++;
      await load();
    },
    dispose() {
      disposed = true;
      loadGeneration++;
      revision.value++;
    },
    invalidate() {
      revision.value++;
    },
  };
}
export type ModuleMenuSession = ReturnType<typeof createModuleMenuSession>;
function directoryAncestry(nodes: MenuTreeNode[], id: string, parents: MenuRecord[] = []): MenuRecord[] {
  for (const node of nodes) {
    const path = [...parents, node.record];
    if (node.record.id === id) return path;
    const found = directoryAncestry(node.children, id, path);
    if (found.length) return found;
  }
  return [];
}
export function availableDirectories(nodes: MenuTreeNode[]): MenuPlacement[] {
  return nodes.flatMap(({ record, children }) =>
    record.enabled === false
      ? []
      : [
          ...(!record.moduleAlias ? [{ menu: record, path: record.title }] : []),
          ...availableDirectories(children).map((item) => ({
            ...item,
            path: `${record.title} / ${item.path}`,
          })),
        ],
  );
}
export function moduleMenuEntryIssue(entry: ModuleRuntimeContext) {
  if (entry.entryType === 'route') return entry.entryRoute ? '' : '该模块尚未配置页面地址，请返回模块补充。';
  if (entry.entryType === 'link')
    return entry.entryExternalUrl ? '' : '该模块尚未配置链接地址，请返回模块补充。';
  return entry.uiDescriptor?.page ? '' : '该模块尚无可用业务页面，请先发布页面配置。';
}
function messageOf(cause: unknown) {
  return cause instanceof Error ? cause.message : '操作暂不可用，请重试。';
}
