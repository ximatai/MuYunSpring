<script setup lang="ts">
import { computed } from 'vue';
import type { RecordFormFieldState } from '@muyun/platform-components';
import type {
  ModulePageFormContribution,
  ModulePageFormContributionContext,
  ModulePageFormSurface,
} from './modulePageEnhancements';

defineOptions({ name: 'ModulePageFormContributionRenderer' });

const props = defineProps<{
  contributions: readonly ModulePageFormContribution[];
  surface: ModulePageFormSurface;
  position: 'before-fields' | 'after-fields' | 'before' | 'after';
  field?: Readonly<RecordFormFieldState>;
  contextFor(
    contribution: ModulePageFormContribution,
    field?: Readonly<RecordFormFieldState>,
  ): ModulePageFormContributionContext;
}>();

const matchedContributions = computed(() =>
  props.contributions.filter((contribution) => {
    const location = contribution.location;
    if (location.surface !== props.surface) return false;
    if ('section' in location) return location.section === props.position;
    if (!props.field || location.fieldName !== props.field.fieldName) return false;
    return location.placement === props.position;
  }),
);
</script>

<template>
  <template v-for="contribution in matchedContributions" :key="contribution.key">
    <component :is="contribution.component" :context="contextFor(contribution, field)" />
  </template>
</template>
