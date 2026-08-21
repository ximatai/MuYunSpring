<script setup lang="ts">
import { computed } from 'vue';
import { ExternalLink, Pin, PinOff } from 'lucide-vue-next';
import {
  AppstoreOutlined,
  BellOutlined,
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  DownloadOutlined,
  DownOutlined,
  EditOutlined,
  ExportOutlined,
  FilterOutlined,
  LeftOutlined,
  LockOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MinusOutlined,
  QuestionCircleOutlined,
  PlusOutlined,
  PoweroffOutlined,
  ReloadOutlined,
  RightOutlined,
  SaveOutlined,
  SearchOutlined,
  SettingOutlined,
  SkinOutlined,
  SwapOutlined,
} from '@ant-design/icons-vue';

defineOptions({ name: 'UiIcon', inheritAttrs: false });

export type UiIconName =
  | 'app'
  | 'check'
  | 'close'
  | 'delete'
  | 'download'
  | 'down'
  | 'edit'
  | 'export'
  | 'filter'
  | 'help'
  | 'left'
  | 'lock'
  | 'menu-collapse'
  | 'menu-expand'
  | 'minus'
  | 'notification'
  | 'open-in-new'
  | 'plus'
  | 'pin-off'
  | 'pin'
  | 'power'
  | 'reload'
  | 'right'
  | 'save'
  | 'search'
  | 'skin'
  | 'settings'
  | 'swap';

const props = defineProps<{
  name: UiIconName;
}>();

const icons = {
  app: AppstoreOutlined,
  check: CheckOutlined,
  close: CloseOutlined,
  delete: DeleteOutlined,
  download: DownloadOutlined,
  down: DownOutlined,
  edit: EditOutlined,
  export: ExportOutlined,
  filter: FilterOutlined,
  help: QuestionCircleOutlined,
  left: LeftOutlined,
  lock: LockOutlined,
  'menu-collapse': MenuFoldOutlined,
  'menu-expand': MenuUnfoldOutlined,
  minus: MinusOutlined,
  notification: BellOutlined,
  'open-in-new': ExternalLink,
  plus: PlusOutlined,
  pin: Pin,
  'pin-off': PinOff,
  power: PoweroffOutlined,
  reload: ReloadOutlined,
  right: RightOutlined,
  save: SaveOutlined,
  search: SearchOutlined,
  skin: SkinOutlined,
  settings: SettingOutlined,
  swap: SwapOutlined,
} as const;

const lucideIconNames = new Set<UiIconName>(['open-in-new', 'pin', 'pin-off']);

const icon = computed(() => icons[props.name]);
const lucideProps = computed(() =>
  lucideIconNames.has(props.name) ? { size: 16, strokeWidth: 1.8 } : undefined,
);
</script>

<template>
  <component
    :is="icon"
    :class="[$attrs.class, { 'ui-icon--lucide': lucideIconNames.has(name) }]"
    :style="$attrs.style"
    v-bind="lucideProps"
  />
</template>

<style scoped>
.ui-icon--lucide {
  width: 1em;
  height: 1em;
  vertical-align: -0.125em;
}
</style>
