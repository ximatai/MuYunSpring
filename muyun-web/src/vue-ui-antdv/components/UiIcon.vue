<script setup lang="ts">
import { computed } from 'vue';
import {
  AppstoreOutlined,
  BellOutlined,
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  ExportOutlined,
  FilterOutlined,
  LockOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  QuestionCircleOutlined,
  PlusOutlined,
  PushpinOutlined,
  PoweroffOutlined,
  ReloadOutlined,
  SaveOutlined,
  SearchOutlined,
  SettingOutlined,
  SkinOutlined,
} from '@ant-design/icons-vue';

defineOptions({ name: 'UiIcon', inheritAttrs: false });

export type UiIconName =
  | 'app'
  | 'check'
  | 'close'
  | 'delete'
  | 'down'
  | 'edit'
  | 'export'
  | 'filter'
  | 'help'
  | 'lock'
  | 'menu-collapse'
  | 'menu-expand'
  | 'notification'
  | 'plus'
  | 'pin'
  | 'power'
  | 'reload'
  | 'save'
  | 'search'
  | 'skin'
  | 'settings';

const props = defineProps<{
  name: UiIconName;
}>();

const icons = {
  app: AppstoreOutlined,
  check: CheckOutlined,
  close: CloseOutlined,
  delete: DeleteOutlined,
  down: DownOutlined,
  edit: EditOutlined,
  export: ExportOutlined,
  filter: FilterOutlined,
  help: QuestionCircleOutlined,
  lock: LockOutlined,
  'menu-collapse': MenuFoldOutlined,
  'menu-expand': MenuUnfoldOutlined,
  notification: BellOutlined,
  plus: PlusOutlined,
  pin: PushpinOutlined,
  power: PoweroffOutlined,
  reload: ReloadOutlined,
  save: SaveOutlined,
  search: SearchOutlined,
  skin: SkinOutlined,
  settings: SettingOutlined,
} as const;

const icon = computed(() => icons[props.name]);
</script>

<template>
  <component :is="icon" :class="$attrs.class" :style="$attrs.style" />
</template>
