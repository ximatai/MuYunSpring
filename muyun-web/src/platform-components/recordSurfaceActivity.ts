import { computed, inject, onActivated, onDeactivated, provide, ref, type InjectionKey, type Ref } from 'vue';

const recordSurfaceActivity: InjectionKey<Readonly<Ref<boolean>>> = Symbol('record-surface-activity');

/** Retained drawer content must not keep performing reading work after its surface closes. */
export function provideRecordSurfaceActivity(active: () => boolean) {
  const parent = useRecordSurfaceActivity();
  provide(
    recordSurfaceActivity,
    computed(() => parent.value && active()),
  );
}

export function useRecordSurfaceActivity() {
  const parent = inject(
    recordSurfaceActivity,
    computed(() => true),
  );
  const activated = ref(true);
  onActivated(() => {
    activated.value = true;
  });
  onDeactivated(() => {
    activated.value = false;
  });
  return computed(() => parent.value && activated.value);
}
