# 菜单、路由与页签契约

本文记录当前菜单入口、页面宿主、地址恢复和工作台页签的稳定边界。平台分层见[前端技术架构](../TECHNICAL_ARCHITECTURE.md)，业务 App 接入见[消费者接入](../LOCAL_CONSUMER.md)。

## 菜单与页面来源

菜单表达用户可打开的业务入口，页面宿主负责实际呈现；二者通过 `MenuNavigationTarget` 和 `PageDescriptor` 衔接。

| 菜单入口 | 页面来源 | 承载规则 |
| --- | --- | --- |
| `module` | 静态或动态模块的标准交付 | 默认进入统一 `ModulePageHost`，复用模块 descriptor、权限及记录会话；消费 App 显式提供 `businessModuleRoutes` 时进入映射的业务路由宿主。 |
| `route` | 明确登记的静态页面或业务路由 | 按精确地址与模块身份匹配，不按模块别名猜测 Vue 组件。 |
| `link` | 显式配置的外部 URL | `tab` 在 iframe 中承载，`window` 打开新窗口，不按 URL 形态推断。 |

- `entryType` 决定入口类别，`openMode` 表达 `tab` 或 `window`；缺少必要字段或字段冲突时给出配置诊断。
- 一个错误菜单不阻断其他有效入口；失效的静态页面不能兜底到动态模块运行器。
- 平台静态页面清单由 `platformAdminRoutes` 与仓库宿主的 `staticRouteDefinitions` 对齐，页面实现按需加载。
- 非菜单辅助路由通过 `menuEntry: false` 显式声明；消费 App 拥有自己的 Router 和业务页面注册。
- 静态、动态来源共用标准模块宿主；兼容的 `dynamic-module-host` 名称不代表另一套运行器。

实现入口：[菜单编译器](../../../muyun-web/src/app/menuRouteCompiler.ts)、[菜单与描述符转换](../../../muyun-web/src/platform-workbench/menuNavigation.ts)、[宿主分发](../../../muyun-web/src/platform-workbench/pageHostRegistry.ts)。

## 地址与页签身份

公开地址只表达可恢复的业务入口和必要参数。资源 ID、页面模式和业务查询属于地址语义；物理页签实例、标题和缓存身份属于工作台内部状态。

- `InstanceKey` 是内部页面实例标识，不写入公开 URL。导航与描述符序列化会剔除该标记，不能通过拼接 query 创建页签实例。
- 菜单身份与页面实例身份分别维护；同一业务地址可以在工作台内拥有独立实例，`newInstance: true` 通过导航 API 表达。
- 标准模块地址由统一地址目录生成，默认按应用和模块别名构成可读路径；语义覆盖由注册表显式提供，不使用旧的 `/platform/dynamic` 技术地址。
- 同模块多入口必须保留入口级配置和恢复语义，不能仅按模块别名合并；只读业务入口见[只读业务投影与同源多入口](../READONLY_BUSINESS_VIEWS.md)。
- 标题通过 `tabTitle` 或受控的 `setTabName` 更新，不写入 URL，也不成为页签身份。
- 地址恢复仍须核对当前菜单、身份和模块上下文，不从历史页签推定当前访问权限。

实现与契约：[导航接口](../../../muyun-web/src/platform-workbench/workbenchNavigation.ts)、[公开地址回归](../../../muyun-web/tests/platform-workbench/workbenchNavigation.test.ts)、[启动与恢复](../../../muyun-web/tests/app/workbenchStartup.test.ts)。

## Router 与工作台协作

`AppWorkbenchShell` 管理菜单、页签和内部页面导航，消费 App 的 Router 管理实际地址及业务路由。

1. App 将 `currentRoute.fullPath` 作为 Shell 的 `location`。
2. Shell 发出 `navigate` 请求，App 用自己的 `router.push` 或 `router.replace` 执行。
3. 页签激活、地址变更和前进后退通过同一条同步链路恢复页面，不分别维护互不相干的激活状态。
4. 平台仓库宿主在进入受保护路由前准备菜单和动态路由；身份、租户及菜单上下文变化时回收对应旧路由与诊断。
5. 仅刷新菜单时保留已打开页面与编辑现场；不得把导航树重载等同于重建所有页签。

契约入口：[工作台壳层](../../../muyun-web/src/consumer/AppWorkbenchShell.vue)、[壳层测试](../../../muyun-web/tests/consumer/AppWorkbenchShell.component.test.ts)、[路由运行时测试](../../../muyun-web/tests/app/menuRouteRuntime.test.ts)。

## 页面导航与未保存状态

业务页面通过 `useWorkbenchNavigation` 进入所属工作台的导航策略，不直接改写页签数组或生成内部实例键。

- `openMenu` 复用侧栏菜单的打开方式；`openRoute` 和 `openPage` 打开页面；`replaceRoute`、`replacePage` 按现有页签身份替换内容。
- 标题在调用时已知就随导航交付；异步标题只更新指定页面，不改变地址和其他页签。
- 关闭、退出和切换上下文遵循所属会话的未保存保护；取消确认保留草稿和页面现场。
- 页面实例键、缓存键与内部刷新键由宿主维护。刷新当前页面不能清除同级页签；切换页签不能静默丢失编辑内容。
- 仓库宿主的普通页签通过浏览器 `sessionStorage` 恢复，并核对当前用户；锁定页签通过账号后端偏好恢复。这些持久化只保留页签入口与描述符，不保存页面草稿或组件实例，也不代替当前身份与菜单校验。
- 只把入口恢复信息放入公开地址；完整表单草稿、滚动和组件状态不写进 query，也不把跨浏览器草稿恢复视为已支持。
- 标准管理入口优先使用模块列表详情和抽屉。用户、系统用户、角色等当前页面边界见[静态管理页组件边界](../STATIC_MANAGEMENT_PAGE_COMPONENTS.md#当前页面口径)。

未保存保护见[工作区关闭回归](../../../muyun-web/tests/platform-workbench/workspaceViewDiscardGuard.component.test.ts)，静态路由布局和缓存见[宿主组件测试](../../../muyun-web/tests/app/StaticRoutePageHost.component.test.ts)及[浏览器测试](../../../muyun-web/tests/app/StaticRoutePageHost.browser.test.ts)。

## 外部页面与后续边界

外部页面当前由 `ExternalPageHost` 提供 iframe 或新窗口入口。独立部署页面仍受自身身份、安全和浏览器嵌入策略约束；不能从“能打开 URL”推导出共享表单会话、标题同步或跨应用未保存保护。

完整 iframe 消息协议、远程 manifest 动态发现、微前端运行时及跨应用状态恢复均须由真实业务需求触发后再设计，不作为普通页面接入前提。内部宿主已有能力不等于公开消费者门面已提供同等支持。

## 验证

按根 `AGENTS.md` 与 `muyun-web/AGENTS.md` 的风险分级选择验证，不因菜单或文档变更机械执行全量构建。

- 菜单和静态路由调整覆盖 `menuRouteCompiler`、`menuRouteRuntime` 的精确匹配及失败诊断。
- 导航与地址调整覆盖公开 URL、独立实例、替换、恢复及标题；消费面改动增加真实安装包契约验证。
- 草稿、缓存和布局调整覆盖关闭取消、身份切换、页签保留、当前页刷新及真实浏览器尺寸。
