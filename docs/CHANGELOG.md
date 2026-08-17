# 变更记录

本文件记录面向 MuYunSpring 使用者的正式发布内容：新增能力、行为变化、兼容性影响和迁移要求。

## Unreleased

## 0.26.8 - 2026-08-17

### Added

- 平台新增在线实时业务提醒：业务服务可按全局、租户、机构、部门、岗位或人员范围发布通知，前端工作台统一展示并支持导航和业务命令动作。

### Changed

- 业务提醒的领域契约收敛到平台层，IAM 负责收件人身份范围解析，Web 层只承担在线投递与安全投影；提醒动作继续通过业务服务重新校验权限、数据范围、状态和幂等性。

## 0.26.6 - 2026-08-11

### Added

- 动态模块页面运行器支持范围微列表工作区、树形范围选择和受控模块页面增强。
- 标准模块页面新增轻量列表刷新门面，HTTP 层支持 SSE 流式响应与显式异步请求上下文。
- 完善文件引用表单运行时：支持上传、移除、附件字段与记录附件的统一保存生命周期，以及文件大小展示。
- IAM 租户管理员授权接入平台授权动作，并统一其权限语义。

### Changed

- 收敛模块页面展示、菜单入口 bootstrap、应用壳层和品牌菜单控制等前端运行时边界。
- 静态与动态页面复用统一的字段展示、选项回填和受控交互语义。

### Fixed

- 修复禁用模块动作、树模块列表刷新、工作台路由与页签同步、文件保存失败及失效历史引用更新等边界行为。
- 修正菜单切换悬停抑制和附件移除交互，避免多余保存提示。

## 0.26.5 - 2026-08-07

### Fixed

- 标准模块详情抽屉在完整详情读取成功前保持加载态，失败时可重试，避免以列表投影直接编辑或保存。
- npm 消费者验证改为隔离安装刚生成的 tarball，避免复用 registry 中同版本包造成假阳性。
- `RecordModeDrawer` 承担标准编辑与保存动作，保持模块运行器不直接依赖 UI adapter。

### Changed

- 面向业务 App 的示例只使用 `@ximatai/muyun-web-app` 公开组合包；内部 `@muyun/*` 目录不再被表述为当前消费者依赖。

## 0.26.4 - 2026-08-06

### Fixed

- 修复 `@ximatai/muyun-web-app` 的发布声明引用内部 alias、管理型 App 路由误捕获与动态运行器 UI adapter 边界问题。

### Changed

- `gradle.properties` 的 `muyunVersion` 成为前端 npm 包版本的一致性来源；打包时自动校验两者对齐。

## 0.26.3 - 2026-08-06

### Changed

- 首发 `@ximatai/muyun-web-app@0.26.3`：面向管理型 App 交付工作台、标准模块运行器、平台管理页与完整 TypeScript 类型声明。
- 发布前以本地 Maven 坐标启动独立消费者；Maven Central 真实坐标验证保留为首发或发布链路调整后的人工检查。
- Release workflow 将消费者预检、发布工作区清理和 Maven Central 上传分阶段执行，保证上传只使用本次构建的构件。

### Removed

- 不再在 `local` profile 启动时自动升级 pre-FieldSpec 字段目录 schema；升级前须在应用停机后执行
  `scripts/migrations/field-catalog-pre-fieldspec-postgresql.sql`。

## 0.26.1 - 2026-08-05

### Added

- `muyun-spring-bom`：统一公共平台 artifact 的依赖版本。
- `muyun-spring-boot-starter`：标准 Spring Boot 自动装配入口，业务应用无需依赖 `muyun-boot`。
- Maven Central 发布任务、签名校验、tag gate 和本地消费者仓库验证。

### Changed

- `muyun-boot` 收敛为框架自身的本地运行宿主；平台装配迁移至公共 Starter。
