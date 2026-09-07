import { defineBrowserCommand } from '@vitest/browser-playwright';

export const treeGesture = defineBrowserCommand(
  async (
    { page, iframe },
    source: string,
    target: string,
    fraction: number = 0.5,
    finish: string = 'drop',
    horizontalFraction: number = 0.5,
  ) => {
    const from = await iframe.locator(source).boundingBox();
    const to = await iframe.locator(target).boundingBox();
    if (!from || !to) throw new Error('拖拽节点不可见');
    await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
    await page.mouse.down();
    await page.mouse.move(to.x + to.width * horizontalFraction, to.y + to.height * fraction, { steps: 8 });
    if (finish === 'escape') await page.keyboard.press('Escape');
    if (finish !== 'hold') await page.mouse.up();
  },
);

export const treeRelease = defineBrowserCommand(async ({ page }) => {
  await page.mouse.up();
});
export const treeMove = defineBrowserCommand(
  async ({ page, iframe }, target: string, fraction: number = 0.5, horizontalFraction: number = 0.5) => {
    const to = await iframe.locator(target).boundingBox();
    if (!to) throw new Error('拖拽目标不可见');
    await page.mouse.move(to.x + to.width * horizontalFraction, to.y + to.height * fraction, { steps: 8 });
  },
);
export const treeReducedMotion = defineBrowserCommand(async ({ page }, reduce: boolean) => {
  await page.emulateMedia({ reducedMotion: reduce ? 'reduce' : 'no-preference' });
});

export const treeWheel = defineBrowserCommand(async ({ page, iframe }, container: string, deltaY: number) => {
  const box = await iframe.locator(container).boundingBox();
  if (!box) throw new Error('滚动区域不可见');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.wheel(0, deltaY);
});

export const treeScrollGesture = defineBrowserCommand(
  async (
    { page, iframe },
    source: string,
    container: string,
    edge: 'bottom' | 'left' | 'right' = 'bottom',
  ) => {
    const from = await iframe.locator(source).boundingBox();
    const box = await iframe.locator(container).boundingBox();
    if (!from || !box) throw new Error('滚动目标不可见');
    await page.mouse.move(from.x + 20, from.y + from.height / 2);
    await page.mouse.down();
    await page.mouse.move(
      edge === 'bottom' ? box.x + 40 : edge === 'left' ? box.x + 2 : box.x + box.width - 2,
      edge === 'bottom' ? box.y + box.height - 2 : box.y + 20,
      { steps: 8 },
    );
  },
);

declare module 'vitest/browser' {
  interface BrowserCommands {
    treeWheel(container: string, deltaY: number): Promise<void>;
    treeScrollGesture(source: string, container: string, edge?: 'bottom' | 'left' | 'right'): Promise<void>;
    treeGesture(
      source: string,
      target: string,
      fraction?: number,
      finish?: 'drop' | 'escape' | 'hold',
      horizontalFraction?: number,
    ): Promise<void>;
    treeRelease(): Promise<void>;
    treeMove(target: string, fraction?: number, horizontalFraction?: number): Promise<void>;
    treeReducedMotion(reduce: boolean): Promise<void>;
  }
}
