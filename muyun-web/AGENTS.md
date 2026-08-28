# MuYun Web Guide

本文件补充仓库根 `AGENTS.md`，仅约束 `muyun-web` 的测试与前端工程边界。

## 测试架构

- Vitest 是唯一测试运行器；不得引入 `node:test`、`node:assert`、手工测试文件枚举或自定义测试 loader。
- 纯 TypeScript 测试命名为 `*.test.ts`，运行在 Vitest `unit` 项目的 Node 环境。
- Vue SFC、DOM 与交互测试命名为 `*.component.test.ts`，运行在 Vitest `component` 项目的 jsdom 环境，并使用 Vue Test Utils。
- 真实浏览器布局与滚动测试命名为 `tests/browser/*.browser.test.ts`，运行在 Vitest `browser` 项目；只验证 jsdom 无法证明的 DOM 几何、滚动与响应式契约。
- 测试目录镜像 `src/` 的职责边界；跨层测试归入拥有入口编排职责的模块，不创建含义不清的公共测试目录。
- `tests/setup.ts` 只服务组件测试；不要让纯逻辑测试隐式依赖浏览器全局对象。

## 验证

修改前端测试或测试基础设施后，依次运行：

```bash
npm run test:unit
npm run test:component
npm run test:browser
npm run check
npm run verify:consumer
```

测试优先验证调用方可观察的行为和稳定平台契约；没有自然红路径或边界路径时，应在测试说明中说明原因，不制造无意义断言。
