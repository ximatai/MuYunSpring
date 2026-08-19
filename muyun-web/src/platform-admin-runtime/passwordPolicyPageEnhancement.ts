import type { ModulePageEnhancement } from '@muyun/dynamic-page-runtime';
import PasswordPolicyPreview from '../views/PasswordPolicyPreview.vue';

/**
 * IAM-owned card assistance for the standard password-policy module page.
 * The component is selected by frontend source composition; no backend DSL can inject executable UI.
 */
export const passwordPolicyPageEnhancement: ModulePageEnhancement = {
  id: 'iam-password-policy-preview',
  target: { moduleAlias: 'iam.password_policy_rule' },
  card: {
    assistant: {
      component: PasswordPolicyPreview,
      placement: { boundary: 'outside', position: 'bottom' },
    },
  },
};
