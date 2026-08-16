<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type {
  MenuNavigationTarget,
  MenuRecord,
  MenuTab,
  PageDescriptor,
  WorkbenchStartupState,
} from '../web-contracts';
import { userPreferences } from '../web-core';
import Workbench from '../platform-workbench/Workbench.vue';
import { provideWorkbenchNavigation } from '../platform-workbench/workbenchNavigation';
import { pageDescriptorToUrl, type PageDescriptorResolveOptions } from '../platform-workbench/menuNavigation';
import type { WorkbenchRealtimeStatus } from '../platform-workbench/realtimeStatus';
import {
  arrangeLockedMenuTabs,
  closeMenuTab,
  closeMenuTabs,
  menuTargetUrl,
  openDirectTab,
  openMenuTab,
  reorderMenuTabs,
  removeLockedMenuTabs,
  restoreLockedWorkbenchTabs,
  restoreWorkbenchStartupStateFromUrl,
  updateLockedMenuTabs,
} from '../app/workbenchStartup';
import { restoreLockedTabPreference, saveLockedTabPreference } from '../app/lockedTabPreference';
import { workbenchRouteWriteFor } from '../app/workbenchRouteSync';
import { syncModulePageWorkspaceViewContributions } from '../platform-workbench/modulePageWorkspaceViews';
import type { AppWorkbenchNavigation } from './workbenchNavigation';

defineOptions({ name: 'AppWorkbenchShell' });

const props = withDefaults(
  defineProps<{
    startup: WorkbenchStartupState;
    /** The consumer router's current full path, including search and hash. */
    location: string;
    resolveOptions?: PageDescriptorResolveOptions;
    loading?: boolean;
    error?: string;
    realtimeStatus?: WorkbenchRealtimeStatus;
    themeAppearance?: 'light' | 'dark';
  }>(),
  {
    resolveOptions: () => ({}),
    loading: false,
    error: undefined,
    realtimeStatus: 'unavailable',
    themeAppearance: 'light',
  },
);

const emit = defineEmits<{
  'update:startup': [value: WorkbenchStartupState];
  navigate: [navigation: AppWorkbenchNavigation];
  userCommand: [key: string];
}>();

const activeTabKey = computed(() => props.startup.activeTabKey);
const lockedTabs = ref<MenuTab[]>([]);
let lockedTabPreferenceRevision = 0;
let lockedTabPreferenceWrite = Promise.resolve();
let isMounted = false;

provideWorkbenchNavigation({ openPage, replacePage, closePage: closeTab });

onMounted(() => {
  isMounted = true;
  syncModulePageWorkspaceViewContributions();
  void restoreLockedTabs();
  restoreFromLocation(props.location);
});

onBeforeUnmount(() => {
  isMounted = false;
});

watch(
  () => props.location,
  (location) => restoreFromLocation(location),
);

function update(startup: WorkbenchStartupState, mode: 'push' | 'replace' | undefined = undefined) {
  emit('update:startup', startup);
  if (mode) syncBrowserUrl(startup, mode);
}

function selectMenu(menu: MenuRecord, target: MenuNavigationTarget) {
  if (target.openMode === 'window') {
    window.open(menuTargetUrl(menu, target), '_blank', 'noopener,noreferrer');
    return;
  }
  const result = openMenuTab(props.startup.tabs ?? [], menu, target, props.resolveOptions);
  update({ ...props.startup, tabs: result.tabs, activeTabKey: result.activeTabKey }, 'push');
}

function changeTab(key: string) {
  if (key === props.startup.activeTabKey) return;
  update({ ...props.startup, activeTabKey: key }, 'push');
}

function closeTab(key: string) {
  const result = closeMenuTab(props.startup.tabs ?? [], props.startup.activeTabKey, key);
  if (lockedTabs.value.some((tab) => tab.key === key)) {
    persistLockedTabs(removeLockedMenuTabs(lockedTabs.value, [key]));
  }
  update({ ...props.startup, tabs: result.tabs, activeTabKey: result.activeTabKey }, 'replace');
}

function closeTabs(keys: string[]) {
  const result = closeMenuTabs(props.startup.tabs ?? [], props.startup.activeTabKey, keys);
  const lockedKeys = keys.filter((key) => lockedTabs.value.some((tab) => tab.key === key));
  if (lockedKeys.length > 0) persistLockedTabs(removeLockedMenuTabs(lockedTabs.value, lockedKeys));
  update({ ...props.startup, tabs: result.tabs, activeTabKey: result.activeTabKey }, 'replace');
}

function reorderTabs(keys: string[]) {
  const tabs = reorderMenuTabs(
    props.startup.tabs ?? [],
    keys,
    lockedTabs.value.map((tab) => tab.key),
  );
  const nextLockedTabs = tabs.filter((tab) =>
    lockedTabs.value.some((lockedTab) => lockedTab.key === tab.key),
  );
  if (nextLockedTabs.map((tab) => tab.key).join('|') !== lockedTabs.value.map((tab) => tab.key).join('|')) {
    persistLockedTabs(nextLockedTabs);
  }
  update({ ...props.startup, tabs });
}

function toggleTabLock(key: string) {
  const tab = props.startup.tabs?.find((item) => item.key === key);
  if (!tab) return;
  const isLocked = lockedTabs.value.some((item) => item.key === key);
  const currentLockedTabs = lockedTabs.value;
  const nextLockedTabs = isLocked
    ? removeLockedMenuTabs(currentLockedTabs, [key])
    : updateLockedMenuTabs(currentLockedTabs, tab);
  persistLockedTabs(nextLockedTabs);
  update({ ...props.startup, tabs: arrangeLockedMenuTabs(props.startup.tabs ?? [], nextLockedTabs) });
}

function persistLockedTabs(nextLockedTabs: MenuTab[]) {
  const previousLockedTabs = lockedTabs.value;
  const revision = ++lockedTabPreferenceRevision;
  lockedTabs.value = nextLockedTabs;
  void persistLockedTabPreference(nextLockedTabs, previousLockedTabs, revision);
}

async function persistLockedTabPreference(
  nextLockedTabs: MenuTab[],
  previousLockedTabs: MenuTab[],
  revision: number,
) {
  try {
    lockedTabPreferenceWrite = lockedTabPreferenceWrite
      .catch(() => undefined)
      .then(() => saveLockedTabPreference(userPreferences, nextLockedTabs));
    await lockedTabPreferenceWrite;
  } catch {
    if (revision !== lockedTabPreferenceRevision) return;
    lockedTabs.value = previousLockedTabs;
    update({
      ...props.startup,
      tabs: arrangeLockedMenuTabs(props.startup.tabs ?? [], previousLockedTabs, false),
    });
  }
}

function openPage(descriptor: PageDescriptor) {
  const result = openDirectTab(props.startup.tabs ?? [], descriptor);
  update({ ...props.startup, tabs: result.tabs, activeTabKey: result.activeTabKey }, 'push');
  return { created: result.created };
}

function replacePage(pageKey: string, descriptor: PageDescriptor) {
  const tabs = (props.startup.tabs ?? []).map((tab) =>
    tab.key === pageKey
      ? {
          ...tab,
          title: descriptor.title ?? tab.title,
          pageDescriptor: descriptor,
          restoreState: { url: pageDescriptorToUrl(descriptor) },
        }
      : tab,
  );
  const replacement = tabs.find((tab) => tab.key === pageKey);
  if (replacement && lockedTabs.value.some((tab) => tab.key === pageKey)) {
    persistLockedTabs(updateLockedMenuTabs(lockedTabs.value, replacement));
  }
  update(
    {
      ...props.startup,
      tabs,
    },
    'replace',
  );
}

function syncBrowserUrl(state: WorkbenchStartupState, mode: 'push' | 'replace') {
  const navigation = workbenchRouteWriteFor(state, props.location, mode);
  if (navigation) emit('navigate', navigation);
}

function restoreFromLocation(location: string) {
  update(restoreWorkbenchStartupStateFromUrl(props.startup, location, props.resolveOptions));
}

async function restoreLockedTabs() {
  const restoreRevision = lockedTabPreferenceRevision;
  try {
    const restoredLockedTabs = restoreLockedWorkbenchTabs(
      await restoreLockedTabPreference(userPreferences),
      props.startup.menus,
      props.resolveOptions,
    );
    if (!isMounted || restoreRevision !== lockedTabPreferenceRevision) return;
    lockedTabs.value = restoredLockedTabs;
    const lockedKeys = new Set(restoredLockedTabs.map((tab) => tab.key));
    const tabs = [
      ...restoredLockedTabs,
      ...(props.startup.tabs ?? []).filter((tab) => !lockedKeys.has(tab.key)),
    ];
    update({ ...props.startup, tabs: arrangeLockedMenuTabs(tabs, restoredLockedTabs) });
  } catch {
    // The workbench remains usable when preference storage is temporarily unavailable.
  }
}
</script>

<template>
  <Workbench
    :startup="startup"
    :loading="loading"
    :error="error"
    :active-tab-key="activeTabKey"
    :locked-tab-keys="lockedTabs.map((tab) => tab.key)"
    :realtime-status="realtimeStatus"
    :theme-appearance="themeAppearance"
    @select-menu="selectMenu"
    @change-tab="changeTab"
    @close-tab="closeTab"
    @close-tabs="closeTabs"
    @reorder-tabs="reorderTabs"
    @toggle-tab-lock="toggleTabLock"
    @user-command="emit('userCommand', $event)"
  >
    <template #default="{ pageDescriptor }">
      <slot :page-descriptor="pageDescriptor" />
    </template>
  </Workbench>
</template>
