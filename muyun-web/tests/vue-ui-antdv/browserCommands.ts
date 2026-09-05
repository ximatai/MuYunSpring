import { defineBrowserCommand } from '@vitest/browser-playwright';

export const treeGesture = defineBrowserCommand(
  async (
    { page, iframe },
    source: string,
    target: string,
    fraction: number = 0.5,
    finish: string = 'drop',
  ) => {
    const from = await iframe.locator(source).boundingBox();
    const to = await iframe.locator(target).boundingBox();
    if (!from || !to) throw new Error('拖拽节点不可见');
    await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
    await page.mouse.down();
    await page.mouse.move(to.x + to.width / 2, to.y + to.height * fraction, { steps: 8 });
    if (finish === 'escape') await page.keyboard.press('Escape');
    if (finish !== 'hold') await page.mouse.up();
  },
);

export const treeRelease = defineBrowserCommand(async ({ page }) => {
  await page.mouse.up();
});
export const treeReducedMotion = defineBrowserCommand(async ({ page }, reduce: boolean) => {
  await page.emulateMedia({ reducedMotion: reduce ? 'reduce' : 'no-preference' });
});

export const treeScrollGesture = defineBrowserCommand(
  async ({ page, iframe }, source: string, container: string) => {
    const from = await iframe.locator(source).boundingBox();
    const box = await iframe.locator(container).boundingBox();
    if (!from || !box) throw new Error('滚动目标不可见');
    await page.mouse.move(from.x + 20, from.y + from.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + 40, box.y + box.height - 2, { steps: 8 });
  },
);

declare module 'vitest/browser' {
  interface BrowserCommands {
    treeScrollGesture(source: string, container: string): Promise<void>;
    treeGesture(
      source: string,
      target: string,
      fraction?: number,
      finish?: 'drop' | 'escape' | 'hold',
    ): Promise<void>;
    treeRelease(): Promise<void>;
    treeReducedMotion(reduce: boolean): Promise<void>;
  }
}
