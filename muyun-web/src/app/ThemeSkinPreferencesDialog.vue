<script setup lang="ts">
import { UiModal, type UiThemeSkin, type UiThemeSkinId } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'ThemeSkinPreferencesDialog' });

withDefaults(
  defineProps<{
    open: boolean;
    skins: readonly UiThemeSkin[];
    activeSkinId: UiThemeSkinId;
    saving?: boolean;
    error?: string;
  }>(),
  { saving: false, error: undefined },
);

const emit = defineEmits<{
  close: [];
  select: [skinId: UiThemeSkinId];
}>();

function moveSkinFocus(event: KeyboardEvent, currentIndex: number) {
  const keys = ['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp', 'Home', 'End'];
  if (!keys.includes(event.key)) return;
  event.preventDefault();
  const group = event.currentTarget instanceof HTMLElement ? event.currentTarget.parentElement : undefined;
  const count = group?.children.length ?? 0;
  if (!count) return;
  const nextIndex =
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? count - 1
        : (currentIndex + (event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : -1) + count) % count;
  const nextCard = group?.children.item(nextIndex) as HTMLElement | null;
  const nextSkin = nextCard?.dataset.skinId;
  if (!nextSkin) return;
  emit('select', nextSkin as UiThemeSkinId);
  nextCard?.focus();
}
</script>

<template>
  <UiModal
    :open="open"
    title="偏好设置"
    :width="460"
    :confirm-text="saving ? '保存中…' : '完成'"
    :confirm-loading="saving"
    @confirm="emit('close')"
    @cancel="emit('close')"
  >
    <section class="theme-skin-preferences">
      <header>
        <h2>工作台皮肤</h2>
        <p>皮肤会同步保存到当前账号，并在下次启动时优先恢复。</p>
      </header>
      <p v-if="error" class="theme-skin-error" role="alert">{{ error }}</p>
      <div class="theme-skin-grid" role="radiogroup" aria-label="工作台皮肤">
        <button
          v-for="(skin, index) in skins"
          :key="skin.id"
          class="theme-skin-card"
          :class="{ selected: skin.id === activeSkinId }"
          type="button"
          role="radio"
          :aria-checked="skin.id === activeSkinId"
          :tabindex="skin.id === activeSkinId ? 0 : -1"
          :data-skin-id="skin.id"
          :disabled="saving"
          @click="emit('select', skin.id)"
          @keydown="moveSkinFocus($event, index)"
        >
          <span
            class="theme-skin-preview"
            :style="{
              '--skin-canvas': skin.theme.support.canvas,
              '--skin-surface': skin.theme.support.surface,
              '--skin-text': skin.theme.support.text,
              '--skin-primary': skin.theme.theme.base,
              '--skin-accent': skin.theme.brandAccent.base,
            }"
          >
            <i class="theme-skin-preview-sidebar" />
            <i class="theme-skin-preview-toolbar" />
            <i class="theme-skin-preview-card" />
          </span>
          <strong>{{ skin.title }}</strong>
          <small>{{ skin.description }}</small>
        </button>
      </div>
    </section>
  </UiModal>
</template>

<style scoped>
.theme-skin-preferences,
.theme-skin-preferences header {
  display: grid;
  gap: 8px;
}

.theme-skin-preferences h2,
.theme-skin-preferences p {
  margin: 0;
}

.theme-skin-preferences h2 {
  color: var(--muyun-support-text);
  font-size: 15px;
}

.theme-skin-preferences header p {
  color: var(--muyun-support-text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.theme-skin-error {
  padding: 8px 10px;
  border: 1px solid var(--muyun-danger-border);
  border-radius: 6px;
  background: var(--muyun-danger-soft);
  color: var(--muyun-danger-soft-text);
  font-size: 12px;
}

.theme-skin-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.theme-skin-card {
  display: grid;
  gap: 7px;
  min-width: 0;
  padding: 8px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
  color: var(--muyun-support-text);
  text-align: left;
  cursor: pointer;
}

.theme-skin-card:hover,
.theme-skin-card:focus-visible {
  border-color: var(--muyun-theme-border);
  background: var(--muyun-theme-soft);
  outline: 0;
}

.theme-skin-card.selected {
  border-color: var(--muyun-theme-base);
  box-shadow: inset 0 0 0 1px var(--muyun-theme-base);
}

.theme-skin-card:disabled {
  cursor: wait;
  opacity: 0.72;
}

.theme-skin-card strong {
  font-size: 13px;
}

.theme-skin-card small {
  min-height: 30px;
  color: var(--muyun-support-text-muted);
  font-size: 11px;
  line-height: 1.4;
}

.theme-skin-preview {
  position: relative;
  display: block;
  height: 56px;
  overflow: hidden;
  border-radius: 5px;
  background: var(--skin-canvas);
}

.theme-skin-preview-sidebar,
.theme-skin-preview-toolbar,
.theme-skin-preview-card {
  position: absolute;
  display: block;
}

.theme-skin-preview-sidebar {
  inset: 0 auto 0 0;
  width: 25%;
  background: var(--skin-surface);
  border-right: 3px solid var(--skin-primary);
}

.theme-skin-preview-toolbar {
  inset: 7px 7px auto 33%;
  height: 7px;
  border-radius: 999px;
  background: var(--skin-text);
  opacity: 0.76;
}

.theme-skin-preview-card {
  right: 7px;
  bottom: 7px;
  left: 33%;
  height: 25px;
  border-radius: 4px;
  background: var(--skin-surface);
  box-shadow: inset 4px 0 0 var(--skin-accent);
}
</style>
