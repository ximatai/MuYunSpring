import { createTenantScopeNavigator, type ModulePageEnhancement } from '@muyun/dynamic-page-runtime';
import PasswordSecurityCard from '../views/PasswordSecurityCard.vue';

/**
 * Unified "Settings -> Password security" surface for the platform/tenant password policy page.
 * The card is a frontend-owned enhancement of the standard module page: the descriptor still owns
 * the record, save boundary and tenant scope, while this card adds effective-policy, rule and trial
 * assistance against the same target scope. No backend DSL selects or injects executable UI.
 */
export const passwordSecurityPageEnhancement: ModulePageEnhancement = {
  id: 'iam-password-security',
  target: { moduleAlias: 'iam.password_security_policy' },
  navigator: createTenantScopeNavigator(),
  list: { singleRecord: true },
  card: {
    assistant: {
      component: PasswordSecurityCard,
      handlesEmptyDetail: true,
      placement: { boundary: 'inside', position: 'bottom' },
    },
  },
};
