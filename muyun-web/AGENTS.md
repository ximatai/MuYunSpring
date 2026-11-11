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
npm run test:component -- tests/platform-admin-runtime/workflow/ModuleWorkflowDrawer.component.test.ts
```

日常逻辑或组件修改可用 `npm run test:local -- <路径>` 同时过滤 unit/component 项目；单一项目继续使用
`test:unit`、`test:component`。布局、鼠标命中或动效改动增加 `npm run test:browser -- <路径>`。
路径过滤没有匹配测试时应失败，不用 `--passWithNoTests` 掩盖遗漏。按风险补 `npm run typecheck` 和定向 lint/格式检查。

公共能力改动覆盖调用方；完整前端验收使用 `npm run check`，消费者公开面再加 `npm run verify:consumer`。
`prepare:consumer` 只准备 tarball 与独立消费者依赖，不代表契约已通过；`verify:consumer` 会校验并复用准备，
每次重新解包当前 tarball、构建示例及契约包并执行消费者测试。排查缓存或做冷准备验收可传 `-- --fresh`。
整轮输入基准在准备前固定，准备后和契约完成后复核；期间源码改变时本轮不能作为当前源码的验证结果。
同一工作区不要并发运行消费者准备/验证或 `pack:consumer`；中断留下 lock 时先确认所属进程已退出。

通过 npm 的测试入口运行时，Vitest 原生 JSON 报告写入独立的 `test-results/verification/run-*` 目录；
保留默认控制台报告和浏览器失败截图。结束后提示失败文件/用例的定向复验命令，不自动重试。
报告目录创建失败时退回默认控制台报告，仍执行测试并保留测试退出结果。
消费者契约必须经 `verify:consumer` 重建独立消费者，不能直接运行缺少产物的 packaged 测试。
慢文件摘要统计用例执行窗口，不包含 import/setup；分析加载瓶颈仍需结合 Vitest 原生耗时做同条件对照。
组件测试 mock 整个出口时，避免为已替换的协作者调用 importOriginal 加载无关组件；需要真实协作者行为时保留真实实现。

前端文本由根 `.gitattributes` 约定 LF。Prettier 的 `endOfLine: auto` 兼容已有 Windows CRLF 检出，
其余格式规则照常检查；不为换行差异批量格式化无关文件。

`npm run check` 已包含全量测试、静态检查和构建；无需在它之前重复运行全量 `test:unit`、`test:component`。前后端互不影响的改动，只验证涉及的技术栈；发现失败或影响扩大时再升级范围。

测试优先验证调用方可观察的行为和稳定平台契约；没有自然红路径或边界路径时，应在测试说明中说明原因，不制造无意义断言。
