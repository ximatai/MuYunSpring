import type { ModulePageEnhancement } from '@ximatai/muyun-web-app';
import HoldActionControlExample from '../components/HoldActionControlExample.vue';

/** Register in the application's enhancement contributions after declaring the server action. */
export const holdActionEnhancement: ModulePageEnhancement = {
  id: 'business-device-hold',
  target: { moduleAlias: 'business.device' },
  detail: {
    controls: [
      {
        key: 'hold',
        actionCode: 'hold',
        title: '按住操作',
        placement: 'after-primary',
        component: HoldActionControlExample,
      },
    ],
  },
};
