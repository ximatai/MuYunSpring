import { computed, ref, shallowRef } from 'vue';
import {
  createUuid,
  AppError,
  OperationRejectedError,
  OperationUsageError,
  type OperationProposal,
  type ModuleRuntimeContext,
  parseOperationReceiptReference,
  normalizeError,
  userFacingErrorMessage,
  resolveOperationErrorMessage,
  type OperationErrorMessage,
} from '@muyun/web-core';
import { platformLanguageContext, codeOperationErrorMessage, type LanguageContext } from '@muyun/web-core';
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
  language: LanguageContext = platformLanguageContext,
) {
  const revision = ref(0);
  const loading = ref(false);
  const updating = ref(false);
  const saving = ref(false);
  const failure = shallowRef<Error>();
  const error = computed(() =>
    failure.value
      ? failure.value instanceof OperationUsageError || failure.value instanceof OperationRejectedError
        ? resolveOperationErrorMessage(failure.value, language)
        : userFacingErrorMessage(normalizeError(failure.value), language)
      : '',
  );
  function reportFailure(cause: Error | OperationErrorMessage) {
    failure.value = cause instanceof Error ? cause : new OperationUsageError(cause);
  }
  function captureFailure(cause: unknown) {
    reportFailure(
      cause instanceof Error
        ? cause
        : codeOperationErrorMessage(
            language,
            'platform.screen.moduleMenuSession.the-operation-is-temporarily-unavailable-retry',
            '操作暂不可用，请重试。',
          ),
    );
  }
  const entrySnapshot = shallowRef<ModuleRuntimeContext>();
  const entryIssue = computed(() =>
    entrySnapshot.value ? moduleMenuEntryIssue(entrySnapshot.value, language) : '',
  );
  const title = ref('');
  const schemeId = ref('');
  const parentId = ref('root');
  const openMode = ref<MenuOpenMode>('tab');
  const schemes = shallowRef<MenuScheme[]>([]);
  const tree = shallowRef<MenuTreeNode[]>([]);
  const saved = shallowRef<MenuRecord>();
  const savedPath = ref('');
  const savedAudienceBasis = shallowRef<MenuScheme>();
  const savedAudienceLegacy = ref('');
  const savedAudience = computed(() =>
    savedAudienceBasis.value
      ? menuSchemeScopeLabel(savedAudienceBasis.value, language)
      : savedAudienceLegacy.value,
  );
  const visibleMenu = shallowRef<MenuRecord>();
  const visibilityChecked = ref(false);
  const authorized = ref(false);
  const ready = ref(false);
  const resultUnknown = ref(false);
  let disposed = false;
  let loadGeneration = 0;
  let updateGeneration = 0;
  let pending:
    | {
        requestId: string;
        schemeId: string;
        record?: MenuRecord;
        path?: string;
        audience?: string;
        audienceScheme?: MenuScheme;
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
      !updating.value &&
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
    if (!valid())
      throw new OperationUsageError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.the-menu-editing-scope-has-changed-reopen-the-editor',
          '菜单编辑范围已变化，请重新打开',
        ),
      );
  }
  function requireEditable(allowUpdating = false) {
    requireCurrent();
    if (
      !ready.value ||
      !authorized.value ||
      loading.value ||
      saving.value ||
      pending ||
      saved.value ||
      (!allowUpdating && updating.value)
    )
      throw new OperationUsageError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.wait-for-the-menu-candidate-to-be-ready-or-check',
          '请先等待菜单候选就绪，或查询未确定的保存结果',
        ),
      );
  }
  async function load() {
    requireCurrent();
    if (saved.value || (pending && ready.value)) return;
    updateGeneration++;
    updating.value = false;
    const generation = ++loadGeneration;
    loading.value = true;
    ready.value = false;
    failure.value = undefined;
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
            throw new OperationUsageError(
              codeOperationErrorMessage(
                language,
                'platform.screen.moduleMenuSession.the-original-entry-lookup-scope-is-invalid-verify-the-save',
                '原入口查询范围无效，请核实保存结果后继续',
              ),
            );
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
      entrySnapshot.value = entry;
      schemes.value = catalog;
      title.value ||= entry.title ?? moduleAlias;
      openMode.value = entry.entryType === 'link' ? 'window' : 'tab';
      const preferred = mine[0]?.record.schemeId;
      const selected =
        options.value.find((item) => item.id === (pending?.schemeId ?? schemeId.value)) ??
        options.value.find((item) => item.id === preferred) ??
        options.value[0];
      if (!selected?.id)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.moduleMenuSession.no-menu-schemes-are-available-configure-an-applicable-scheme-first',
            '暂无可用菜单方案，请先配置适用的菜单方案',
          ),
        );
      const nodes = await client.tree(selected.id);
      if (!valid() || generation !== loadGeneration) return;
      schemeId.value = selected.id;
      tree.value = nodes;
      parentId.value = 'root';
      ready.value = true;
      revision.value++;
    } catch (cause) {
      if (valid() && generation === loadGeneration) captureFailure(cause);
    } finally {
      if (valid() && generation === loadGeneration) loading.value = false;
    }
  }
  function resolveRevision(input: ModuleMenuRevision) {
    const targetScheme = input.schemeId ?? schemeId.value;
    if (!options.value.some((item) => item.id === targetScheme))
      throw new OperationUsageError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.select-an-available-menu-scheme',
          '请选择当前可用的菜单方案',
        ),
      );
    const targetTitle = input.title ?? title.value;
    if ([...targetTitle].length > 120)
      throw new OperationUsageError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.the-menu-name-must-not-exceed-120-characters',
          '菜单名称不能超过120个字符',
        ),
      );
    const targetParent = input.parentId ?? (targetScheme === schemeId.value ? parentId.value : 'root');
    const targetOpenMode = input.openMode ?? openMode.value;
    const resolveNodes = (nodes: MenuTreeNode[]) => {
      if (
        targetParent !== 'root' &&
        !availableDirectories(nodes).some((item) => item.menu.id === targetParent)
      )
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.moduleMenuSession.select-an-enabled-directory-in-the-current-scheme',
            '请选择当前方案中的启用目录',
          ),
        );
      return {
        schemeId: targetScheme,
        nodes,
        parentId: targetParent,
        title: targetTitle,
        openMode: targetOpenMode,
      };
    };
    return targetScheme === schemeId.value
      ? resolveNodes(tree.value)
      : client.tree(targetScheme).then(resolveNodes);
  }
  function applyRevision(next: Awaited<ReturnType<typeof resolveRevision>>) {
    schemeId.value = next.schemeId;
    tree.value = next.nodes;
    parentId.value = next.parentId;
    title.value = next.title;
    openMode.value = next.openMode;
    revision.value++;
  }
  async function planRevision(input: ModuleMenuRevision) {
    requireEditable();
    const before = revision.value;
    const next = await resolveRevision(input);
    return () => {
      requireEditable();
      if (before !== revision.value)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.moduleMenuSession.the-menu-candidate-has-changed-reload-it',
            '菜单候选已变化，请重新读取',
          ),
        );
      applyRevision(next);
    };
  }
  async function update(input: ModuleMenuRevision) {
    let generation: number | undefined;
    try {
      requireEditable(true);
      generation = ++updateGeneration;
      updating.value = true;
      const before = ++revision.value;
      failure.value = undefined;
      const prepared = resolveRevision(input);
      // Local edits finish before Vue flushes; only a remote tree read keeps the editor waiting.
      const next = 'then' in prepared ? await prepared : prepared;
      if (!valid() || generation !== updateGeneration) return;
      requireEditable(true);
      if (before !== revision.value)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.moduleMenuSession.the-menu-candidate-has-changed-reload-it',
            '菜单候选已变化，请重新读取',
          ),
        );
      applyRevision(next);
      failure.value = undefined;
    } catch (cause) {
      if (valid() && (generation === undefined || generation === updateGeneration)) captureFailure(cause);
    } finally {
      if (generation === updateGeneration) updating.value = false;
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
      failure.value = undefined;
    } catch {
      if (valid())
        reportFailure(
          codeOperationErrorMessage(
            language,
            'platform.screen.moduleMenuSession.the-menu-was-added-but-navigation-could-not-be-refreshed',
            '菜单已添加，导航刷新失败。请查询或刷新导航，无需重复添加。',
          ),
        );
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
      const audienceBasis =
        original.audienceScheme ?? options.value.find((item) => item.id === resolved.schemeId);
      savedAudienceBasis.value = audienceBasis ? { ...audienceBasis } : undefined;
      savedAudienceLegacy.value = original.audience ?? '';
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
      title: language.t('platform.screen.moduleMenuSession.menu-entry-saved', '菜单入口已保存'),
      lines: valid()
        ? [
            savedPath.value,
            visibilityChecked.value
              ? visibleMenu.value
                ? language.t(
                    'platform.screen.moduleMenuSession.visible-to-the-current-user-the-business-page-can-be',
                    '当前用户可见，可打开业务页面。',
                  )
                : language.t(
                    'platform.screen.moduleMenuSession.the-entry-was-saved-but-is-not-visible-to-the',
                    '入口已保存；当前登录身份暂不可见，请由适用范围内的使用者核实导航。',
                  )
              : language.t(
                  'platform.screen.moduleMenuSession.navigation-visibility-has-not-been-verified-do-not-add-the',
                  '导航可见性尚未核实；不要重复添加。',
                ),
          ]
        : [
            language.t(
              'platform.screen.moduleMenuSession.the-save-completed-verify-the-entry-in-its-original-scope',
              '保存已完成，请在原范围核实入口。',
            ),
          ],
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
      throw new OperationRejectedError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.you-do-not-have-permission-to-add-menu-entries',
          '当前没有添加菜单入口权限',
        ),
      );
    const entryProblem = moduleMenuEntryIssueMessage(entry, language);
    if (entryProblem) throw new OperationRejectedError(entryProblem);
    const scheme = catalog.find((item) => item.id === record.schemeId && item.enabled !== false);
    if (!scheme)
      throw new OperationRejectedError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.the-menu-scheme-is-no-longer-available',
          '菜单方案已不可用',
        ),
      );
    if (
      record.parentId !== 'root' &&
      !availableDirectories(nodes).some((item) => item.menu.id === record.parentId)
    )
      throw new OperationRejectedError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.the-target-directory-is-no-longer-available-review-the-entry',
          '放置目录已不可用，请重新审阅',
        ),
      );
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
      throw new OperationRejectedError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.the-menu-scheme-audience-or-directory-has-changed-reload-and',
          '菜单方案适用范围或目录已变化，请重新读取候选并审阅',
        ),
      );
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
    if (!canSave.value)
      throw new OperationUsageError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.provide-a-menu-name-and-an-available-business-page',
          '请补齐菜单名称及可用业务页面',
        ),
      );
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
    if (revision.value !== before)
      throw new OperationUsageError(
        codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.the-menu-candidate-has-changed-review-it-again',
          '菜单候选已变化，请重新审阅',
        ),
      );
    const path = basis.path;
    const requestId = createUuid();
    const isCurrent = () =>
      valid() &&
      scope.active() &&
      ready.value &&
      authorized.value &&
      !updating.value &&
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
        title: language.t('platform.screen.moduleMenuSession.add-a-business-entry', '添加业务入口'),
        lines: [
          path,
          language.t('platform.screen.moduleMenuSession.audience-value', '适用范围：{value1}', {
            value1: menuSchemeScopeLabel(basis.scheme, language),
          }),
          language.t('platform.screen.moduleMenuSession.open-mode-value', '打开方式：{value1}', {
            value1:
              record.openMode === 'window'
                ? language.t('platform.screen.moduleMenuSession.new-window', '新窗口')
                : language.t('platform.screen.moduleMenuSession.workbench-tab', '页签内'),
          }),
          language.t(
            'platform.screen.moduleMenuSession.adds-an-entry-only-it-does-not-activate-applications-or',
            '只添加入口，不开通应用或授予业务权限。',
          ),
          ...(duplicate.value
            ? [
                language.t(
                  'platform.screen.moduleMenuSession.this-position-already-contains-value-another-entry-will-be-added',
                  '此位置已有「{value1}」，本次将另加一个入口。',
                  { value1: duplicate.value.menu.title },
                ),
              ]
            : []),
        ],
        details: {
          title: language.t('platform.screen.moduleMenuSession.view-target-module', '查看目标模块'),
          lines: [moduleAlias],
        },
      },
      confirmLabel: language.t('platform.screen.moduleMenuSession.confirm-entry-creation', '确认添加入口'),
      expiresAt: Date.now() + 5 * 60_000,
      isCurrent,
      async execute() {
        if (!isCurrent() || saving.value)
          throw new OperationRejectedError(
            codeOperationErrorMessage(
              language,
              'platform.screen.moduleMenuSession.the-menu-candidate-or-editing-scope-has-changed-review-it',
              '菜单候选或编辑范围已变化，请重新审阅',
            ),
          );
        saving.value = true;
        failure.value = undefined;
        let posted = false;
        try {
          await recheck(record, basis.signature);
          if (!isCurrent())
            throw new OperationRejectedError(
              codeOperationErrorMessage(
                language,
                'platform.screen.moduleMenuSession.the-menu-candidate-or-editing-scope-has-changed-review-it',
                '菜单候选或编辑范围已变化，请重新审阅',
              ),
            );
          pending = {
            requestId,
            schemeId: record.schemeId,
            record,
            path,
            audience: menuSchemeScopeLabel(basis.scheme, language),
            audienceScheme: { ...basis.scheme },
          };
          await receipts.save(receiptReference);
          if (!valid() || !scope.active() || revision.value !== before)
            throw new OperationRejectedError(
              codeOperationErrorMessage(
                language,
                'platform.screen.moduleMenuSession.the-menu-candidate-or-editing-scope-has-changed-review-it',
                '菜单候选或编辑范围已变化，请重新审阅',
              ),
            );
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
                reportFailure(
                  codeOperationErrorMessage(
                    language,
                    'platform.screen.moduleMenuSession.the-operation-was-not-submitted-but-the-original-request-reference',
                    '操作未提交，但原请求标识尚未清除，请先查询结果。',
                  ),
                );
              }
              throw new OperationRejectedError(messageOf(cause, language));
            }
            if (pending) {
              resultUnknown.value = true;
              revision.value++;
            }
            if (pending) {
              reportFailure(
                codeOperationErrorMessage(
                  language,
                  'platform.screen.moduleMenuSession.the-save-result-is-unresolved-check-the-original-request-result',
                  '保存结果尚未确定，请查询原请求结果，勿重复添加。',
                ),
              );
            } else {
              captureFailure(cause);
            }
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
      failure.value = undefined;
      revision.value++;
      return {
        title: language.t('platform.operation.not-submitted', '操作未提交'),
        lines: [
          language.t(
            'platform.screen.moduleMenuSession.the-recovery-reference-was-cleared-you-can-review-and-add',
            '恢复标识已清除，可以重新审阅并添加入口。',
          ),
        ],
      };
    }
    const receipt = await client.receipt(original.schemeId, original.requestId);
    requireCurrent();
    if (pending !== original || !receipt.committed || !receipt.recordId) return;
    return accepted(receipt.recordId);
  }
  return {
    moduleAlias,
    language,
    revision,
    loading,
    updating,
    saving,
    error,
    reportFailure,
    failureCause: () => failure.value,
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
      if (!saved.value || saving.value || pending)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.screen.moduleMenuSession.verify-the-current-entry-save-result-first',
            '请先核实当前入口保存结果',
          ),
        );
      await receipts.clear();
      requireCurrent();
      saved.value = undefined;
      savedPath.value = '';
      savedAudienceBasis.value = undefined;
      savedAudienceLegacy.value = '';
      visibleMenu.value = undefined;
      visibilityChecked.value = false;
      ready.value = false;
      revision.value++;
      await load();
    },
    dispose() {
      disposed = true;
      loadGeneration++;
      updateGeneration++;
      updating.value = false;
      revision.value++;
    },
    invalidate() {
      updateGeneration++;
      updating.value = false;
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
export function moduleMenuEntryIssue(
  entry: ModuleRuntimeContext,
  language: LanguageContext = platformLanguageContext,
): string {
  return moduleMenuEntryIssueMessage(entry, language)?.message ?? '';
}
function moduleMenuEntryIssueMessage(
  entry: ModuleRuntimeContext,
  language: LanguageContext,
): OperationErrorMessage | undefined {
  if (entry.entryType === 'route')
    return entry.entryRoute
      ? undefined
      : codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.this-module-has-no-page-address-return-to-its-configuration',
          '该模块尚未配置页面地址，请返回模块补充。',
        );
  if (entry.entryType === 'link')
    return entry.entryExternalUrl
      ? undefined
      : codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.this-module-has-no-link-address-return-to-its-configuration',
          '该模块尚未配置链接地址，请返回模块补充。',
        );
  return entry.uiDescriptor?.page
    ? undefined
    : codeOperationErrorMessage(
        language,
        'platform.screen.moduleMenuSession.this-module-has-no-available-business-page-publish-its-page',
        '该模块尚无可用业务页面，请先发布页面配置。',
      );
}
function messageOf(
  cause: unknown,
  language: LanguageContext = platformLanguageContext,
): string | OperationErrorMessage {
  return cause instanceof OperationUsageError || cause instanceof OperationRejectedError
    ? (cause.textMessage ?? cause.message)
    : cause instanceof Error
      ? cause.message
      : codeOperationErrorMessage(
          language,
          'platform.screen.moduleMenuSession.the-operation-is-temporarily-unavailable-retry',
          '操作暂不可用，请重试。',
        );
}
