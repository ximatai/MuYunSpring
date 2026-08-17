# 静态 UI 样板接入收口记录

本文记录静态业务前后端打通样板的阶段性收口结果。稳定路线已回收到 [UI 声明与读投影设计](../../../architecture/UI_DECLARATION_AND_READ_PROJECTION.md)，本文只保留样板现状、仍需专项治理的边界和删除条件。

## 当前阶段状态

子资源表单契约阶段已完成阶段性收口。已完成非字典子资源样板、子资源默认表单 view code 命名门面、relation 字段编译失败契约、`/form/schema` 兼容出口来源收口，以及动态发布快照到源无关 UI 定义的最小身份边界。动态真实 Web 链路已完成第一步：动态模块 runtime context 返回共用 `ResolvedModuleUiDescriptor`，动态 host 复用列表和表单字段运行器展示列表与只读表单。剩余工作不再继续横向铺静态页面，后续进入动态保存 envelope、SQL 列投影和字段级授权等专项。

## 已稳定契约

1. 静态 service 不承载 UI schema，`FormAbility` 不再作为职员表单 schema 来源。
2. 前端正式运行协议为 `ResolvedModuleUiDescriptor`，runtime context 不再暴露或消费来源态 `uiDefinition`。
3. `iam.employee` 和 `iam.department` 静态 UI 声明已覆盖默认列表视图和默认表单视图。
4. descriptor 不暴露物理列、表名、schema 名或 SQL 片段。
5. `/iam.employee/query` 和 `/iam.department/query` 响应按 resolved list view 和 `RecordReadProjection` 输出裁剪；SQL 仍暂时读取完整实体。
6. `StaticRecordReadProjectionService` 已作为静态模块读投影门面，负责投影编译和 Web 响应重建。
7. `recordFormFieldModel` 已作为前端表单字段解析门面，负责 descriptor 字段顺序、fallback 顺序、字段状态和 fallback 控件类型解析。
8. `resolveRecordFormFields` 已作为前端 form view 字段 Map 门面，负责从 resolved descriptor 按 view code 取表单字段，业务页不再手写 view 查找。
9. `createScopedTreeModuleContext` 已作为前端 scope/tree 组合门面，负责作用域 query、树加载、平铺树候选和排序端点组合。
10. `createStaticTreeResourceModuleContext` 已作为前端静态树资源上下文门面，负责把动态资源路径下的 tree client 接回 `ModuleContext` 并提供空 scope 默认态。
11. 静态子资源动作贡献可同步合并子资源实体事实和 UI view，用于把父模块下的子资源表单纳入同一套 descriptor。
12. 子资源默认表单 view code 使用稳定命名门面生成，约定为 `<resource>_default_form`，后端声明和前端读取不各自拼接字符串。
13. 动态发布快照已有最小归一证据：可转换为静态声明共用的 `ModuleUiDefinition` 主线；动态 viewCode 使用 UI set alias，`uiConfigId` 只作为发布配置、请求校验和页面执行上下文。
14. `/form/schema` 只消费当前模块的静态 UI 定义；子资源 controller 贡献给父模块的 UI view 不作为当前 controller 自身表单 schema 来源。
15. 动态模块 runtime context 已按发布快照生成 `ResolvedModuleUiDescriptor`；动态 host 通过 `RecordQueryListPanel` 和 `RecordFormFields` 消费同一套 descriptor，列表查询会把 `uiConfigId` 和 `queryTemplateId` 放入标准查询请求顶层。

## 前端样板现状

1. `RecordQueryListPanel` 可从 resolved list view 推导列表列，并提供标准 CRUD 顶部动作和行级动作。
2. `RecordFormFields` 已承接普通输入字段、`enabledStatus` 和 `recordPicker` 控件；未显式传入字段顺序时，可按 descriptor 字段顺序和 fallback 顺序稳定推导字段。
3. `executeStaticFormSave` 已承接保存动作的重复提交保护、权限提示、校验提示、loading、成功反馈和异常反馈。
4. `executeStaticRecordAction` 已承接启停和删除动作的重复提交保护、权限提示、确认后的 loading、成功反馈和异常反馈。
5. `EmployeeManagementView` 不再硬编码职员列表列；普通表单字段顺序来自 resolved form view，页面仅保留机构 scope 展示、部门选择器上下文、保存载荷归一和启停/删除业务分支。
6. 部门管理已迁入标准模块运行器：租户、机构树、部门树和父部门选择器 scope 均由页面 descriptor 的导航与 `PICKER_QUERY` 上下文绑定声明，不再保留专用 Vue 页面。
7. `DictionaryManagementView` 已复用静态树资源上下文门面承接应用 scope 下的字典类目树和类目 scope 下的字典项树，并复用 `RecordFormFields` 承接类目表单和后端 `item_default_form` 条目表单；页面仍保留应用选择、类目/条目状态编排和保存 payload 归一。
8. `PositionManagementView` 已复用父模块 descriptor 下的 `position_default_form` 子资源表单和 `RecordFormFields` 承接岗位表单；页面仍保留岗位分类树、岗位列表、所属分类候选和岗位状态编排。
9. `RecordQueryListPanel` 在 descriptor 加载失败时进入明确失败态，不把声明加载失败伪装成空列表。

## 仍属业务编排

以下内容继续留在具体业务页，不应为了“完全配置化”提前下沉：

1. 机构树选择、组织作用域和外部查询值。
2. `recordPicker` 的候选来源、作用域、标题函数和刷新 key。
3. 保存 payload 归一化，例如组织 ID 注入、字符串 trim、空字符串归一为 `undefined`。
4. 启停动作的 enable / disable 分支选择。
5. 删除确认文案和删除后的页面状态同步。
6. 职员账号、职员任岗、业务代办和受托代办子面板。
7. 具体业务页选择哪个 scope、scope 值来自哪里、父子选择约束、树节点行内动作和排序交互。

## 后续专项

1. 字段级授权配置和角色授权存储模型。
2. `RecordReadProjection` 的 SQL 列投影阶段，包含后端白名单解析后的 `selectColumns`。
3. 动态保存 envelope 与动作态表单执行器；当前动态 host 只承诺列表与只读表单展示，不承诺动态表单保存。
4. 动态记录读投影与 SQL 列投影阶段，包含后端白名单解析后的 `selectColumns`。
5. 动作后的页面状态同步如果在多个模块重复出现，再抽为更高层页面状态运行器；不要只为单个样板提前抽象。

## 验收证据

1. 后端测试覆盖静态 UI 声明扫描、descriptor 编译、runtime context 协议、读投影计划和 `/iam.employee/query`、`/iam.department/query` 输出裁剪。
2. 后端测试覆盖 `StaticRecordReadProjectionService`、字段保护输出策略、action 权限上下文和字段级可读策略。
3. 后端测试覆盖动态配置最小样例可归一到同一套 `ModuleUiDefinition`，并锁定动态 viewCode 与 `uiConfigId` 的身份边界。
4. 后端测试覆盖 `/form/schema` 兼容出口只读取当前模块静态 UI 定义，子资源 UI 贡献不会污染当前 controller 表单 schema。
5. 后端测试覆盖动态模块 runtime context 可从发布快照输出 `ResolvedModuleUiDescriptor`。
6. 前端测试覆盖 `RecordQueryListPanel`、`RecordFormFields`、表单字段解析模型、scope/tree 组合门面、静态树资源上下文门面、标准 CRUD 动作、保存动作执行器、记录动作执行器、动态 host、`DictionaryManagementView` 和 `PositionManagementView` 接入契约。
7. 阶段验证命令：`npm test --prefix muyun-web`、`npm run build --prefix muyun-web`、`./gradlew verifyAll`、`git diff --check`；后端统一入口已包含所有子模块的单元测试和 `*IT` 集成测试。

## 删除条件

当后续专项治理入口全部迁移到长期架构文档、页面交付专题或独立 issue 后，本文可以删除。删除前确认：

1. [UI 声明与读投影设计](../../../architecture/UI_DECLARATION_AND_READ_PROJECTION.md) 已覆盖目标协议、分层边界、读投影和运行器路线。
2. 页面交付专题已覆盖动态发布快照接入共用 descriptor 和运行器的真实链路。
3. 静态样板相关契约均有测试覆盖，不依赖本文解释才能维护。
