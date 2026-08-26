<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  RecordExplorerPanel,
  createScopedTreeModuleContext,
  presentPlatformError,
} from '@muyun/platform-components';
import { UiButton, UiSpin, UiTree, type UiTreeNode } from '@muyun/vue-ui-antdv';
import type { Organization, Tenant, WebTreeNode } from '@muyun/web-contracts';
import { useModuleContext } from '@muyun/web-core';
import type { ModulePageNavigatorExtensionContext } from '@muyun/dynamic-page-runtime';
import { useCurrentUserContext } from '../currentUserContext';

defineOptions({ name: 'RoleScopeTree' });

const props = defineProps<{ context: ModulePageNavigatorExtensionContext }>();

const tenantContext = useModuleContext<Tenant>({ moduleAlias: 'iam.tenant' });
const organizationContext = useModuleContext<Organization>({ moduleAlias: 'iam.organization' });
const currentUser = useCurrentUserContext();
const keyword = ref('');
const loading = ref(false);
const loadingMore = ref(false);
const hasMoreTenants = ref(false);
const tenantPage = ref(0);
const nodes = ref<UiTreeNode[]>([]);
const expandedKeys = ref<string[]>([]);
const treeReloadKey = ref(0);
const tenants = new Map<string, Tenant>();
const organizations = new Map<string, Organization>();
const organizationTenantIds = new Map<string, string>();
/** A collapse invalidates every in-flight response for that tenant. */
const organizationLoadGenerations = new Map<string, number>();
const TENANT_ROOT_PAGE_SIZE = 100;
let tenantLoadRevision = 0;
let tenantSearchTimer: ReturnType<typeof setTimeout> | undefined;

const canBrowseTenants = computed(() => currentUser?.value?.system === true);
const currentUserTenant = computed<Tenant | undefined>(() => {
  const tenantId = currentUser?.value?.tenantId;
  if (currentUser?.value?.system === true || !tenantId) return undefined;
  return { id: tenantId, title: tenantId, alias: tenantId, enabled: true } as Tenant;
});
const visibleNodes = computed(() => nodes.value);
const selectedTreeKey = computed(() => props.context.selectionKey);

watch(
  currentUserTenant,
  (tenant) => {
    if (tenant?.id && !canBrowseTenants.value && !props.context.selectionKey) {
      props.context.selectSelectionKey(`tenant:${tenant.id}`);
    }
  },
  { immediate: true },
);

onMounted(() => void loadTenants());
onBeforeUnmount(() => {
  if (tenantSearchTimer) clearTimeout(tenantSearchTimer);
});

watch([canBrowseTenants, currentUserTenant], () => void loadTenants());

watch(keyword, () => {
  if (!canBrowseTenants.value) return;
  if (tenantSearchTimer) clearTimeout(tenantSearchTimer);
  tenantSearchTimer = setTimeout(() => void loadTenants(), 240);
});

async function loadTenants(options: { append?: boolean } = {}) {
  const append = options.append === true;
  const revision = ++tenantLoadRevision;
  if (!append) {
    expandedKeys.value = [];
    treeReloadKey.value += 1;
    tenantPage.value = 0;
    hasMoreTenants.value = false;
  }
  if (!canBrowseTenants.value) {
    const tenant = currentUserTenant.value;
    if (!tenant?.id) {
      nodes.value = [];
      return;
    }
    tenants.clear();
    tenants.set(tenant.id, tenant);
    nodes.value = [tenantNode(tenant)];
    return;
  }
  loading.value = !append;
  loadingMore.value = append;
  try {
    await tenantContext.runtime.ready;
    const nextPage = append ? tenantPage.value + 1 : 1;
    const response = await tenantContext.abilities.crud().query({
      page: { pageNum: nextPage, pageSize: TENANT_ROOT_PAGE_SIZE },
      quickSearch: keyword.value.trim() || undefined,
    });
    if (revision !== tenantLoadRevision) return;
    if (!append) tenants.clear();
    const knownNodeKeys = new Set(append ? nodes.value.map((node) => node.key) : []);
    response.records.forEach((tenant) => {
      if (tenant.id) tenants.set(tenant.id, tenant);
    });
    const loadedNodes = response.records.map(tenantNode).filter((node) => !knownNodeKeys.has(node.key));
    nodes.value = append ? [...nodes.value, ...loadedNodes] : loadedNodes;
    tenantPage.value = nextPage;
    hasMoreTenants.value = response.totalKnown
      ? nextPage < response.pages
      : response.records.length === TENANT_ROOT_PAGE_SIZE;
  } catch (cause) {
    if (revision !== tenantLoadRevision) return;
    nodes.value = [];
    hasMoreTenants.value = false;
    presentPlatformError(cause, { source: 'role-scope-tree', phase: 'load' });
  } finally {
    if (revision === tenantLoadRevision) {
      loading.value = false;
      loadingMore.value = false;
    }
  }
}

function tenantNode(tenant: Tenant): UiTreeNode {
  return {
    key: `tenant:${tenant.id ?? ''}`,
    title: tenantTitle(tenant),
    secondary: tenant.alias ?? tenant.id,
    muted: tenant.enabled === false,
    isLeaf: false,
  };
}

async function loadChildren(node: UiTreeNode) {
  const tenantId = tenantIdFromKey(node.key);
  if (!tenantId) return;
  const tenant = tenants.get(tenantId);
  if (!tenant) return;
  const generation = nextOrganizationLoadGeneration(tenantId);
  try {
    const scopedContext = createScopedTreeModuleContext(organizationContext, {
      scopeFieldName: 'tenantId',
      scopeValue: tenantId,
      treePath: '/iam.organization/tree',
    });
    await scopedContext.runtime.ready;
    const response = await scopedContext.abilities.tree().tree();
    if (organizationLoadGenerations.get(tenantId) !== generation) return;
    const currentNode = findNode(nodes.value, node.key);
    if (!currentNode) return;
    replaceNode(node.key, {
      ...currentNode,
      children: uniqueOrganizationNodes(response.records, tenantId),
    });
  } catch (cause) {
    presentPlatformError(cause, { source: 'role-scope-tree', phase: 'load' });
    throw cause;
  }
}

function nextOrganizationLoadGeneration(tenantId: string) {
  const next = (organizationLoadGenerations.get(tenantId) ?? 0) + 1;
  organizationLoadGenerations.set(tenantId, next);
  return next;
}

function uniqueOrganizationNodes(records: WebTreeNode<Organization>[], tenantId: string): UiTreeNode[] {
  const keys = new Set<string>();
  return records.flatMap((record) => {
    const node = organizationNode(record, tenantId);
    if (keys.has(node.key)) return [];
    keys.add(node.key);
    return [node];
  });
}

function organizationNode(node: WebTreeNode<Organization>, tenantId: string): UiTreeNode {
  const organization = node.record;
  if (organization.id) {
    organizations.set(organization.id, organization);
    organizationTenantIds.set(organization.id, tenantId);
  }
  return {
    key: `organization:${organization.id ?? ''}`,
    title: organizationTitle(organization),
    secondary: organization.code ?? organization.id,
    muted: organization.enabled === false,
    isLeaf: node.children.length === 0,
    children: node.children.map((child) => organizationNode(child, tenantId)),
  };
}

function handleSelect(node: UiTreeNode) {
  const tenantId = tenantIdFromKey(node.key);
  if (tenantId) {
    const tenant = tenants.get(tenantId);
    props.context.selectSelectionKey(`tenant:${tenantId}`, {
      label: tenantTitle(tenant),
      secondaryLabel: tenant?.alias ?? tenantId,
    });
    return;
  }
  const organizationId = organizationIdFromKey(node.key);
  if (organizationId && organizations.has(organizationId) && organizationTenantIds.has(organizationId)) {
    const organization = organizations.get(organizationId);
    props.context.selectSelectionKey(`organization:${organizationId}`, {
      label: organizationTitle(organization),
      secondaryLabel: organization?.code ?? organizationId,
    });
  }
}

function clearSelection() {
  if (currentUser?.value?.system === true) {
    props.context.selectSelectionKey('platform', { label: '平台角色' });
    return;
  }
  const tenantId = currentUserTenant.value?.id;
  if (tenantId) {
    const tenant = tenants.get(tenantId);
    props.context.selectSelectionKey(`tenant:${tenantId}`, {
      label: tenantTitle(tenant),
      secondaryLabel: tenant?.alias ?? tenantId,
    });
  }
}

/** Collapsing a tenant deliberately releases its organization snapshot; its next expansion re-reads the range. */
function unloadChildren(node: UiTreeNode) {
  const tenantId = tenantIdFromKey(node.key);
  if (!tenantId) return;
  nextOrganizationLoadGeneration(tenantId);
  replaceNode(node.key, { ...node, children: undefined });
}

function replaceNode(key: string, replacement: UiTreeNode) {
  const replace = (items: UiTreeNode[]): UiTreeNode[] =>
    items.map((item) =>
      item.key === key ? replacement : item.children ? { ...item, children: replace(item.children) } : item,
    );
  nodes.value = replace(nodes.value);
}

function findNode(items: UiTreeNode[], key: string): UiTreeNode | undefined {
  for (const item of items) {
    if (item.key === key) return item;
    const child = item.children ? findNode(item.children, key) : undefined;
    if (child) return child;
  }
  return undefined;
}

function tenantIdFromKey(key: string) {
  return key.startsWith('tenant:') ? key.slice('tenant:'.length) : undefined;
}
function organizationIdFromKey(key: string) {
  return key.startsWith('organization:') ? key.slice('organization:'.length) : undefined;
}
function tenantTitle(tenant: Tenant | undefined) {
  return String(tenant?.title ?? tenant?.alias ?? tenant?.id ?? '未命名租户');
}
function organizationTitle(organization: Organization | undefined) {
  return String(organization?.title ?? organization?.code ?? organization?.id ?? '未命名机构');
}
</script>

<template>
  <RecordExplorerPanel
    class="role-scope-tree"
    title="租户"
    refresh-title="刷新租户列表"
    :search-keyword="keyword"
    search-placeholder="搜索租户名称、alias 或 ID"
    :searchable="canBrowseTenants"
    @refresh="loadTenants"
    @update:search-keyword="keyword = $event"
  >
    <UiSpin v-if="loading" tip="加载租户" />
    <UiTree
      v-else
      v-model:expanded-keys="expandedKeys"
      :nodes="visibleNodes"
      :selected-key="selectedTreeKey"
      :reload-key="treeReloadKey"
      :load-children="loadChildren"
      reload-on-reexpand
      @select="handleSelect"
      @deselect="clearSelection"
      @unload-children="unloadChildren"
    />
    <template v-if="canBrowseTenants && hasMoreTenants" #footer>
      <UiButton type="text" icon-name="reload" :loading="loadingMore" @click="loadTenants({ append: true })">
        加载更多租户
      </UiButton>
    </template>
  </RecordExplorerPanel>
</template>
