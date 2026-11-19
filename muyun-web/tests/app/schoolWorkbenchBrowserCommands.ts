import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { defineBrowserCommand } from '@vitest/browser-playwright';
import { createServer } from 'vite';
import { createViteConfig } from '../../vite.config.ts';

const subjectMenuTitle = '学科分类';

export const restoreSchoolSubjectMenu = defineBrowserCommand(async ({ context }) => {
  const target = process.env.MUYUN_PLATFORM_SERVICE_URL;
  const token = process.env.MUYUN_PLATFORM_BUSINESS_TOKEN;
  if (!target || !token) throw new Error('Set the school-demo service origin and tenant session token');
  const browser = context.browser();
  if (!browser) throw new Error('The Playwright browser is unavailable');

  const cacheDir = await mkdtemp(join(tmpdir(), 'muyun-school-workbench-'));
  try {
    // A normal Vite app document runs the standard application bootstrap without Vitest's module wrapper.
    const app = await createServer({
      ...createViteConfig('test'),
      configFile: false,
      cacheDir,
      define: {
        'import.meta.env.VITE_MUYUN_USE_MOCK': JSON.stringify('false'),
        'import.meta.env.VITE_MUYUN_API_BASE_URL': JSON.stringify('/api'),
      },
      server: {
        host: '127.0.0.1',
        port: 0,
        proxy: {
          '/api': {
            target,
            changeOrigin: true,
            configure: (proxy) => proxy.on('proxyReq', (request) => request.removeHeader('origin')),
          },
        },
      },
    });
    try {
      await app.listen();
      const appUrl = app.resolvedUrls!.local[0]!;
      const browserOptions = {
        viewport: { width: 1440, height: 814 },
        storageState: {
          cookies: [],
          origins: [
            {
              origin: new URL(appUrl).origin,
              localStorage: [{ name: 'muyun.auth.token', value: token }],
            },
          ],
        },
      };
      const first = await browser.newContext(browserOptions);
      let menuUrl: string;
      try {
        first.setDefaultTimeout(10000);
        const page = await first.newPage();
        await page.goto(appUrl);
        await page.getByRole('button', { name: '系统菜单', exact: true }).click();
        await page.getByRole('button', { name: '平台管理', exact: true }).hover();
        await page.getByRole('button', { name: subjectMenuTitle, exact: true }).click();
        await page.locator('.app-content .module-tree-workspace').waitFor();
        menuUrl = page.url();
      } finally {
        await first.close();
      }

      // Only the authenticated session is seeded. The source tab and its storage are gone.
      const fresh = await browser.newContext(browserOptions);
      try {
        fresh.setDefaultTimeout(10000);
        const page = await fresh.newPage();
        await page.goto(menuUrl);
        const workspace = page.locator('.app-content .module-tree-workspace');
        await workspace.getByText('TD-040 学科 45', { exact: true }).waitFor();
        await workspace.locator('.record-detail-panel-region').waitFor();
        const activeTab = page.getByRole('tab', { name: subjectMenuTitle, exact: true, selected: true });
        await activeTab.waitFor();
        const layout = await workspace.evaluate((surface) => {
          const tree = surface.querySelector<HTMLElement>('.tree-record-explorer .ui-tree')!;
          const host = surface.closest('.app-content')!;
          return {
            treeHeight: tree.clientHeight,
            treeScrollHeight: tree.scrollHeight,
            workspaceBottom: surface.getBoundingClientRect().bottom,
            hostBottom: host.getBoundingClientRect().bottom,
            workspaceHorizontalOverflow: surface.scrollWidth - surface.clientWidth,
          };
        });
        return { menuUrl, restoredUrl: page.url(), layout };
      } finally {
        await fresh.close();
      }
    } finally {
      await app.close();
    }
  } finally {
    await rm(cacheDir, { recursive: true, force: true });
  }
});

declare module 'vitest/browser' {
  interface BrowserCommands {
    restoreSchoolSubjectMenu(): Promise<{
      menuUrl: string;
      restoredUrl: string;
      layout: {
        treeHeight: number;
        treeScrollHeight: number;
        workspaceBottom: number;
        hostBottom: number;
        workspaceHorizontalOverflow: number;
      };
    }>;
  }
}
