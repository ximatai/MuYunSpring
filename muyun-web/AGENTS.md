# MuYun Web Guide

本文件补充仓库根 `AGENTS.md`，仅约束 `muyun-web` 的测试与前端工程边界。

## 测试架构

- Vitest 是唯一测试运行器；不得引入 `node:test`、`node:assert`、手工测试文件枚举或自定义测试 loader。
- 纯 TypeScript 测试命名为 `*.test.ts`，运行在 Vitest `unit` 项目的 Node 环境。
- Vue SFC、DOM 与交互测试命名为 `*.component.test.ts`，运行在 Vitest `component` 项目的 jsdom 环境，并使用 Vue Test Utils。
- 真实布局、鼠标命中与动效回归命名为 `*.browser.test.ts`，运行在 Vitest `browser` 项目的 Chromium 环境；Playwright 仅作为 Vitest provider 和浏览器操作适配器。
- 测试目录镜像 `src/` 的职责边界；跨层测试归入拥有入口编排职责的模块，不创建含义不清的公共测试目录。
- `tests/setup.ts` 只服务组件与浏览器测试；不要让纯逻辑测试隐式依赖浏览器全局对象。

## 验证

按根 `AGENTS.md` 的风险分级选择验证范围；修改测试文件本身不要求跑全套。

- **低风险**：纯文案、多语言、注释或文档调整，只检查相关内容；同步修改已有测试文案时，运行受影响的测试文件或用例即可。无需全量测试、类型检查、构建或消费者验证。
- **中风险**：局部逻辑或交互调整，运行受影响的 `unit`、`component` 测试；涉及真实布局、鼠标命中或动效时运行对应 `browser` 测试。按改动需要执行类型检查、定向 lint 或格式检查。
- **高风险**：平台公共组件或运行态契约、权限与跨模块行为、依赖、构建或测试基础设施调整，覆盖受影响的调用方；影响广泛或无法可靠界定时执行 `npm run check`。涉及消费者导出、声明、依赖或分发产物时，再执行 `npm run verify:consumer`。

定向验证使用 Vitest 自带的项目、路径和用例过滤，例如：

```bash
npx vitest run --project component tests/platform-admin-runtime/workflow/ModuleWorkflowDrawer.component.test.ts
```

`npm run check` 已包含全量测试、静态检查和构建；无需在它之前重复运行全量 `test:unit`、`test:component`。前后端互不影响的改动，只验证涉及的技术栈；发现失败或影响扩大时再升级范围。

测试优先验证调用方可观察的行为和稳定平台契约；没有自然红路径或边界路径时，应在测试说明中说明原因，不制造无意义断言。
