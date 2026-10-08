import { defineBrowserCommand } from '@vitest/browser-playwright';

export const workflowCanvasDrag = defineBrowserCommand(
  async ({ page, iframe }, selector: string, dx: number, dy: number, start?: { x: number; y: number }) => {
    const bounds = await iframe.locator(selector).boundingBox();
    if (!bounds) throw new Error('流程画布拖动目标不可见');
    const canvasBounds = await iframe.locator('.diagram-viewport').boundingBox();
    const canvasWidth = await iframe.locator('.diagram-viewport').evaluate((element) => element.clientWidth);
    const scale = canvasBounds!.width / canvasWidth;
    const x = bounds.x + (start ? start.x * scale : bounds.width / 2),
      y = bounds.y + (start ? start.y * scale : bounds.height / 2);
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + dx * scale, y + dy * scale, { steps: 8 });
    await page.mouse.up();
  },
);
declare module 'vitest/browser' {
  interface BrowserCommands {
    workflowCanvasDrag(
      selector: string,
      dx: number,
      dy: number,
      start?: { x: number; y: number },
    ): Promise<void>;
  }
}
