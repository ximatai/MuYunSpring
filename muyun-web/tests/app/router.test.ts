// @vitest-environment jsdom

import { assert, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { router, workbenchRouteName } from '@/app/router.ts';

setActivePinia(createPinia());

it('registers menu routes beneath the workbench route while retaining their absolute browser path', async () => {
  const name = 'test:workbench-child-route';
  const remove = router.addRoute(workbenchRouteName, {
    path: '/__test/workbench-child',
    name,
    component: { template: '<div>child</div>' },
  });

  try {
    await router.push('/__test/workbench-child');
    assert.deepEqual(
      router.currentRoute.value.matched.map((record) => record.name),
      [workbenchRouteName, name],
    );
  } finally {
    remove();
    await router.replace('/');
  }
});
