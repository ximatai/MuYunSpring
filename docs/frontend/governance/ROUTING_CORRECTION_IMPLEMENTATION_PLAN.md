# MuYun 前端路由、菜单与页签开发规范

## 目标

前端页面、浏览器地址和工作台页签使用同一份事实：当前 Vue Router 地址。页签只保存可恢复的完整地址和展示信息；页面只由 `RouterView` 渲染；同一路由的多个独立页面由 URL 中的 `InstanceKey` 区分。

本规范只约束前端路由、菜单入口和页签协作，不改变后端菜单、模块、保存或权限接口。

## 为什么要改造

旧工作台将浏览器地址、当前渲染组件和页签激活状态分别维护：页面由容器和动态组件选择，页签再维护另一份目标地址。三份状态很容易在菜单切换、刷新、复制地址以及浏览器前进后退时失去一致。

旧模式还存在以下直接问题：

- 列表与表单若共用同一路径，例如把新建用户表示为 `/iam/users?userAction=add`，工作台会把它识别为用户列表菜单页，导致新建覆盖列表页签，而不是新开表单页签。
- 通过模块别名猜测 Vue 页面，或在静态页面查找失败后进入动态模块运行器，会隐藏菜单配置错误；使用者看见的是错误页面，不是可修正的配置原因。
- 页签实例没有统一地址标识时，同一路由重复打开、KeepAlive 缓存、刷新和复制 URL 无法稳定区分，页面状态可能互相覆盖。
- 用 `_muyunTitle` 写入标题或由页面异步改名，会把展示信息混入业务 URL；保存后替换地址时，页签标题又可能退化为路由文本。
- 页面注册表同步加载业务组件时，未访问页面也会进入首屏加载，启动成本随页面数量增长。

## 新模式带来的收益

- Vue Router 地址成为页面和页签的唯一事实；工作台只保存可恢复的完整地址和展示信息。
- 列表、表单和详情使用不同路径；资源编号、页面动作和页签实例分别由路径参数、`action` 和 `InstanceKey` 表达。
- 菜单按显式入口类型和精确路径编译；错误菜单能显示原因和修正建议，不会误落入其他页面。
- `InstanceKey` 隔离同一路由的多个页签和缓存；`replaceRoute` 保留当前实例，保存不会新增页签或覆盖列表页签。
- 已知标题通过 `tabTitle` 随导航传递，地址保持业务语义，保存后的标题与打开查看页保持一致。
- 静态页面使用懒加载 loader，只有真正访问时才下载对应页面代码。

## 路由模型

### 静态页面注册

前端自带页面必须在 `src/app/staticRouteDefinitions.ts` 以 `StaticRouteDefinition` 声明：

```ts
{
  route: '/iam/users',
  moduleAlias: 'iam.user',
  componentPath: '/src/views/UserManagementView.vue',
  layout: 'workspace',
}
```

- `route` 是稳定的内部绝对地址，必须全局唯一。
- `moduleAlias` 只用于模块上下文、权限和菜单一致性校验，不用于推断页面组件。
- `componentPath` 必须精确匹配 `import.meta.glob('/src/views/**/*View.vue')` 的懒加载结果；禁止顶层同步导入页面或使用 eager glob。
- `menuEntry: false` 表示该地址只能由业务流程直接打开，不能配置为菜单入口。详情、表单、授权等辅助页面必须使用该标记。

### 路由分类

| 类型           | 来源与规则                                                                            |
| -------------- | ------------------------------------------------------------------------------------- |
| 基础路由       | 登录、首页和诊断页在 Router 创建时直接登记。                                          |
| 静态菜单路由   | 菜单 `ROUTE` 的 `route` 与静态定义精确匹配后动态登记。                                |
| 非菜单业务分支 | 在静态定义中以 `menuEntry: false` 声明，并随所属模块菜单一并登记。                    |
| 动态模块路由   | 菜单 `MODULE` 编译为标准模块运行器地址，不映射到业务 Vue 页面。                       |
| 外链           | `LINK + TAB` 进入工作台外链承载页；`LINK + WINDOW` 只打开浏览器窗口，不登记内部页面。 |
| 诊断路由       | 通配地址保留原 URL；有菜单配置问题时显示配置诊断，否则显示 404。                      |

## 菜单到路由的编译规则

- `entryType` 是菜单入口的唯一分流依据；禁止根据 `route`、`externalUrl` 是否为空猜测入口类型。
- `ROUTE` 菜单只可按 `route` 精确查找 `StaticRouteDefinition`，并校验菜单与定义的 `moduleAlias` 相同。
- `MODULE`、`ROUTE`、`LINK` 的字段组合不完整或互相冲突时，生成结构化诊断，不注册该菜单路由；其他正确菜单继续可用。
- 同一路径只能被完全相同的菜单定义复用。入口类型、模块或页面文件不同即为冲突，整体拒绝该路径。
- 菜单首次加载后，路由校验和动态登记对当前身份只执行一次。退出、切换身份、租户、菜单方案或刷新菜单时必须调用 `resetMenuRoutes()`，移除旧 RouteRecord 并清空诊断状态。

## URL 与页签实例

### 地址职责

- 路径参数只表达已存在资源的身份，例如 `:userId`。
- query 参数表达当前页面确实需要恢复的状态，例如 `action=view`。
- `InstanceKey` 只表达工作台页签实例，不承载资源编号、操作类型、租户或任何业务数据。
- 业务页面必须验证自己声明的路径参数和 query；旧参数或不支持的动作应给出明确错误，不能静默按默认页面继续执行。

### InstanceKey 规则

- 所有工作台内部导航统一通过 `openRoute`、`replaceRoute` 或 `openPage` 进入；业务页面不得自行生成 UUID。
- `openRoute` 的目标地址没有 `InstanceKey` 时，工作台生成 UUID；`newInstance: true` 即使已有实例键也强制生成新的 UUID。
- 刷新、复制地址、浏览器前进后退直接使用 URL 原有的 `InstanceKey`，不生成新值。
- `replaceRoute` 变更当前页签的业务地址时保留当前 `InstanceKey` 和页签 key，不产生新页签，也不增加浏览器历史记录。
- KeepAlive 的页面缓存键使用路由名称和 `InstanceKey`；不能用业务 ID、菜单 ID 或组件对象代替实例键。

### 页签标题

- 打开或替换时已知标题，调用方必须传递 `tabTitle`；标题不写入 URL。
- `replaceRoute` 必须保留调用方标题，不能让新路由的默认地址覆盖当前页签名称。
- `setTabName(instanceKey, name)` 只用于必须在异步加载后才能得到的标题，且只能更新指定实例的显示文字，不能改写 URL 或其他页签。
- `_muyunTitle` 不作为新的业务路由协议使用。

## 新页面接入步骤

1. 在静态注册表声明菜单地址；如有表单、详情或辅助分支，同时声明 `menuEntry: false` 地址。
2. 使用路径参数表达资源身份，用显式 query 表达动作或可恢复页面状态；为每种组合写独立解析和校验规则。
3. 从列表或动作区使用 `openRoute` 打开独立页面，并传入业务可确定的 `tabTitle`。
4. 保存、取消或状态切换需要复用当前页签时使用 `replaceRoute`，传入目标地址、必要 query 和新的 `tabTitle`。
5. 关闭业务页签时使用 `closeCurrentTab`，回退到稳定的列表地址；删除等不需要恢复的动作不写入 URL。
6. 补充路由状态、菜单编译器或工作台页签测试，覆盖实际新增的路径和实例行为。

禁止按 `moduleAlias` 猜测 Vue 页面、在业务代码拼接 `InstanceKey`、让列表地址同时承担表单状态、长期保留新旧两套地址协议，或把无效静态路由兜底进动态模块运行器。

## 验收与验证

至少覆盖以下场景：

- 菜单 `ROUTE` 精确匹配静态定义，非菜单分支不会成为菜单入口。
- 无效菜单显示配置问题，未知地址显示 404，浏览器地址保持不变。
- 同一路由新开两次得到不同 `InstanceKey`；刷新和复制地址保持同一实例。
- 打开非菜单表单页不会替换所属列表菜单页签。
- 保存后使用 `replaceRoute` 保留当前实例键和页签 key，并更新为业务标题。
- 退出或切换菜单上下文后，旧动态路由和诊断结果不再生效。

前端变更默认运行：

```bash
npm run typecheck --prefix muyun-web
npm run test --prefix muyun-web -- tests/app/menuRouteCompiler.test.ts tests/app/workbenchStartup.test.ts tests/consumer/AppWorkbenchShell.component.test.ts
npm run build --prefix muyun-web
```

涉及菜单初始化、诊断或路由运行时边界时，再运行 `npm run check --prefix muyun-web`。
