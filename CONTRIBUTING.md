# 贡献说明

本文只记录当前仓库最低协作约束，避免流程重于项目本身。

## 开发前

- 先阅读 `README.md`、`docs/DEVELOPMENT_PRINCIPLES.md` 和当前任务涉及的专题文档。
- 默认在主工作区的 `main` 分支开发、验证和提交；新建分支、worktree 或独立检出目录须先取得用户明确同意，具体遵循 [Agent Guide](AGENTS.md#git-workspace)。
- 改动应沿用现有模块边界、命名和测试风格，不为单次需求新增不必要的框架或子项目。

## 提交前验证

按 [Agent Guide](AGENTS.md#tests-and-docs) 的风险分级选择验证范围；后端需要完整验证时使用：

```bash
./gradlew verifyAll
```

该任务覆盖所有子模块的单元测试和 `*IT` 集成测试，并与 CI 后端验证入口保持一致。仅文档改动做差异与一致性检查；局部逻辑改动运行受影响测试，影响广泛或范围无法可靠界定时扩大验证。前端遵循 `muyun-web/AGENTS.md`。

## Pull Request

- PR 说明应包含变更内容、验证结果和剩余风险。
- 使用 PR 时，合入前需要 GitHub Actions CI 通过；PR 不是默认新建分支或工作区的授权。
- 只有用户明确要求时才提交、推送或合并。

## 依赖升级

Dependabot 会每周检查 GitHub Actions 和 Gradle 依赖。依赖升级 PR 不自动合并，需要通过 CI 并按改动风险人工确认。
