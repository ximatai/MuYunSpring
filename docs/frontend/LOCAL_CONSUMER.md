# 前端本地消费者验证

只读聚合、具名业务视图与展示组件支持清单见[只读业务投影与同源多入口](READONLY_BUSINESS_VIEWS.md)。普通投影优先使用公开读取工作区，定制展示再组合标准组件，不依赖内部模块运行器。

MuYunSpring 前端提供两条本机接入路径：日常联调使用链接后的生成包，交付验证安装 tarball。两者都只消费公开包，不为业务 App 配置 `src/` 内部 alias；这样既保留快速反馈，也不让本机开发绕过 `exports`、声明文件、样式或 peer dependencies 契约。

当前首个应用交付包是 `@ximatai/muyun-web-app`。它交付 Workbench 壳、菜单/页签导航、标准模块运行器、平台 HTTP/session/menu client、平台业务组件，以及可直接打开的平台管理页；业务应用仍自行拥有业务页面和路由组合。

该包的 CSS 以显式子路径 `@ximatai/muyun-web-app/style.css` 交付。消费者必须在自己的入口导入它；包的 JavaScript 入口不隐式注入样式。这是未来 npm、SSR、按需构建和多应用共存时可审计的依赖契约。

## 非 UI 记录读取与测试

记录读取会话可通过 `@ximatai/muyun-web-app/record-read` 独立消费。该子路径只提供
`useRecordReadSession`、`resolveRecordReadEntry` 及其类型，不加载 UI、样式或工作台注册；
仍需 Vue peer dependency，不承诺整个 SDK 根入口可在 Node 或 SSR 中加载。

```ts
import { effectScope } from 'vue';
import { useRecordReadSession, type RecordReadView } from '@ximatai/muyun-web-app/record-read';

declare const view: RecordReadView;
const scope = effectScope();
const session = scope.run(() => useRecordReadSession({ view }));
// view 是应用提供的 RecordReadView；测试结束后释放请求和 watcher。
scope.stop();
```

纯逻辑测试使用 Vitest 的 Node 环境和 Vue `effectScope`，组件交互测试使用 jsdom；
身份、租户或授权范围变化时更新 `scopeKey`，后端仍独立鉴权。不得通过截取发布包片段、
执行内部打包代码或导入未公开的 `dist` 文件复用实现。消费者验证会在隔离安装的 tarball 上
以原生 Node 导入该子路径并验证基本读取和作用域释放；身份切换及迟到响应由源码会话测试覆盖。

## 交付验证：tarball

框架仓库内的 `examples/business-web` 是真实消费者：它只依赖 npm 上的公开包与 peer dependencies。执行以下命令会先构建当前源码的 tarball，再临时安装这个 tarball 并构建示例；不会修改示例的 `package.json` 或 lockfile。

```bash
npm ci --prefix muyun-web
npm run verify:consumer --prefix muyun-web
```

消费包包含页面增强与工作区贡献的导入期注册；打包清单须保留这些 JavaScript 副作用，不能仅将 CSS 标记为有副作用。消费者契约会实际挂载角色范围导航和用户在线状态单元格，校验这些默认能力在宿主二次构建后仍可用。

tarball 会生成在仓库的 `build/consumer-npm/`。消费者仓库不得提交本机路径或 tarball 本身。

## 日常联调：链接生成包

应用与框架在本机同时开发时，先在框架仓库生成一次包，再到业务 App 执行链接：

```bash
npm run pack:consumer --prefix muyun-web
cd <business-app>
npm link <muyun-spring-repository>/build/consumer-npm/staging/web-app
```

之后每次修改平台前端源码，只需再次运行 `npm run pack:consumer --prefix muyun-web`；链接保持不变。生成包目录会被整体替换，因此业务 App 必须用 `vite --force` 重启或重新优化依赖，避免继续使用旧的预构建缓存。联调结束后，在业务 App 执行 `npm unlink @ximatai/muyun-web-app`，再执行 `npm install` 回到 npm 中声明的正式版本。

消费者最小接入顺序：

1. 安装公开包与 `vue`；npm 会按公开包声明解析运行所需的 peer dependencies。业务代码不直接导入 `ant-design-vue` 或 `@ant-design/icons-vue`。
2. 在应用入口导入 `@ximatai/muyun-web-app/style.css`。
3. 配置平台 HTTP/session/menu client、当前用户上下文和 Workbench navigation；平台管理页通过 `PlatformAdminOutlet` 承载，业务 route 由 App 显式处理。
4. 构建 App；发布前以 tarball 重复该步骤，不能只用符号链接验证。

管理型 App 的根组件应在同一处完成上下文装配：`configureModuleContext` /
`provideModuleContextConfig` 提供 HTTP factory，`provideCurrentUserContext` 提供当前用户，
`provideWorkbenchNavigation` 连接 App 自己的页签状态。`PlatformAdminOutlet` 只消费这些
公开契约，不依赖框架仓库的 `src/app` 宿主实现。

正式 npm 发布后，本地 tarball 消费者验证仍保留，并作为发布前检查。

## 自有壳的外部登录与个人绑定

根包公开 `useExternalLoginHost`、`ExternalLoginHostOptions`、`ExternalLoginHost`、
`CurrentUserProfileDialog`、`ExternalIdentityBindings` 和 `ExternalProvisioningConfirmation`。宿主继续使用现有的
`LoginView`、`createExternalLoginClient`、`createLoginContextClient`；无需读取私有路径、
复制 state/browserProof 或操作事务 sessionStorage。standalone App 也使用同一宿主入口。

下面片段假定宿主已有 `router`、匿名 HTTP client `http`、`session`、`authClient`、
`loginContextClient` 与 `acceptLogin`。`acceptLogin` 与密码登录共用，负责保存平台会话并加载工作台。

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useExternalLoginHost, createExternalLoginClient, LoginView,
  ExternalIdentityBindings, ExternalProvisioningConfirmation } from '@ximatai/muyun-web-app';
const ready = ref(false), client = createExternalLoginClient(http);
const { completeReturn, error, completing, confirmation, confirming, confirmProvisioning, cancelProvisioning } = useExternalLoginHost({
  client, router, signedIn: () => Boolean(session.value),
  onAuthenticated: acceptLogin,
});
onMounted(async () => {
  if (!(await completeReturn())) await restoreOrdinarySession();
  ready.value = true;
});
</script>
<template>
  <ExternalProvisioningConfirmation v-if="ready && confirmation" :confirmation="confirmation"
    :busy="confirming" :error="error" @confirm="confirmProvisioning" @cancel="cancelProvisioning" />
  <LoginView v-else-if="ready && !session" :auth-client="authClient"
    :login-context-client="loginContextClient" :external-login-client="client"
    :loading="completing" :error="error" @authenticated="acceptLogin" />
  <ExternalIdentityBindings v-if="ready && session" :client="client" :token="session.token" />
</template>
```

应在恢复普通会话前调用 `completeReturn()`，并在完成前暂缓渲染登录表单，让清理后的租户地址
决定表单的租户锁定。返回 `true` 表示已交接登录结果、等待开户确认或已有待展示的未登录失败；返回 `false`
表示继续宿主原有会话恢复流程（包括绑定成功）。`error`、`completing` 是只读响应式状态；
未登录失败交给 `LoginView.error` 展示，已登录失败和绑定成功使用平台反馈。
AUTO 返回的 `confirmation` 是服务端只读资料，宿主使用公开确认组件或自有视图展示，调用
`confirmProvisioning()` 后仍通过 `onAuthenticated` 交接会话；`confirming` 防止重复提交和请求期间取消。
`cancelProvisioning()` 丢弃当前确认凭证，返回登录页；刷新后须重新扫码。REVIEW 待审或拒绝通过
`error` 提示，不交接业务会话。
`callbackPath` 默认 `/`，可指定已注册的宿主回调路由（相对于 Router base），并与后端 redirect URI 对齐。
重复并发调用会合并；事务在请求前消费，地址以 Router replace 清除厂商参数和 hash，保留发起登录
的锁定租户。刷新或旧回调重放不会重试已消费事务；网络失败后需重新发起授权。
凭证只在请求体中提交，不进入 URL，宿主自行负责会话持久化，不应把 token 写入导航参数。

`LoginView` 在锁定租户且登录上下文提供外部方式时显示按钮，传入同一个 client 后自动保存事务、
跳转或展示 WeLink 二维码。个人信息可直接使用 `CurrentUserProfileDialog` 的
`externalLoginClient` / `authToken` 属性；自有个人中心也可单独使用 `ExternalIdentityBindings`
的 `client` / `token` 属性，复用列表、绑定、解绑确认与二维码。二维码展示由这些组件内部管理，
无需额外公开厂商 SDK 协作者。绑定组件应在会话交接后挂载，或在 token 改变时重建。

## 顶栏业务扩展

业务 App 可在 `AppWorkbenchShell` 的 `#topbar-actions="{ currentUser }"` 插槽中放入自己的 Vue 组件，入口位于账号菜单左侧；同一插槽支持多个组件，未接入时不占位。图标、样式、下拉菜单、显隐与业务请求由 App 控制，可复用公开的 `UiButton`、`UiDropdown` 和 `UiIcon`；打开业务页使用 `useWorkbenchNavigation()`，沿用工作台页签导航。

完整接入片段与组件见[顶栏业务入口示例](../../muyun-web/examples/business-web/README.md#工作台顶栏业务入口)。框架宿主中的演示图标仅在开发环境显示，业务 App 通过插槽自行装配；界面显隐不替代服务端授权。

## 自定义业务页的执行租户选择

公开入口提供 `TenantScopeExplorer` 与 `ManagementExplorerColumn`，复用内部标准页面的租户列表、
远程搜索、刷新和折叠交互。所需 `ModuleContext`、`QueryListRecord`、`CurrentUser`、`HttpClient`
同样从 `@ximatai/muyun-web-app` 导入，无需内部文件路径或源码 alias。

完整最小页面见 [TenantScopedOverviewExample.vue](../../muyun-web/examples/business-web/src/views/TenantScopedOverviewExample.vue)。
业务应用拥有该页面，传入当前身份、宿主 HTTP 客户端和自己的概览接口：

```vue
<TenantScopedOverviewExample
  :current-user="currentUser"
  :http="http"
  overview-path="/business.overview"
/>
```

接入约定：

- 系统身份已加载且 `system === true` 时显示选择器。候选通过
  `createModuleContext<QueryListRecord>({ moduleAlias: 'iam.tenant', runtimeAccess: 'REFERENCE', http })`
  读取，继续接受 `iam.tenant` 的授权引用校验；不使用管理查询或客户端自造的租户目录。
- 折叠列必须放在 `ManagementWorkspace` 内，显式设置 `collapsible`；`explorerCount` 与实际显示列数一致。
  普通租户用户隐藏选择列，固定使用身份中的 `tenantId`；身份或执行租户缺失时暂停业务读取。
- 租户目录使用未绑定执行租户的宿主客户端。业务请求为当前范围创建
  `withHttpHeaders(http, { 'X-MuYun-Tenant-Id': tenantId })`，不改写全局客户端、登录身份或其他页签。
- 身份、租户或接口改变时同步清空旧内容，取消旧请求，并拒绝已失效会话的迟到成功或错误。
  同一轮宿主更新完成后才发送请求，避免组合中间状态。即使传输层忽略取消，旧响应也不能回填；
  页面卸载使用同一释放边界。

此选择只确定请求执行范围，不授予跨租户权限。业务接口仍须执行动作授权、有效租户校验和领域范围约束；
候选读取成功不保证某个业务动作可执行。业务自有的详情请求、角色内容差异与编辑门禁由业务处理。

安装包消费者验证会检查示例的 Vue 类型、独立构建与组件交互，不替代业务后端授权验收。

## 自定义业务页的搜索与导航面板

根包公开 `UiSearchInput` 和 `RecordExplorerPanel`，复用标准页面内部组件。
`UiSearchInput` 使用 `v-model:value`；按钮或 Enter 触发 `search(value, 'input')`，
清空按钮或 Escape 清空值并触发 `search('', 'clear')`。`searchIconOnly` 只改变搜索按钮呈现。
`RecordExplorerPanel` 接收 `title`、`v-model:searchKeyword`、`refreshDisabled`，通过 `refresh`
交接刷新；搜索默认折叠，有关键字时自动展开，收起搜索会清空关键字。正文与底部分别使用
默认和 `footer` 插槽。业务仍负责筛选与数据请求；无需直接使用 Ant Design Vue API。
两组件样式均通过上述 `style.css` 显式导入。

## 临时业务提醒

公开 `BusinessNotificationPanel` 继续接收 `notifications`、`executeAction` 和可选附件槽，不增加消息中心。
单条提醒只展示卡片；多条时铃铛位于右下角，切换本次提醒卡片的展开与收起（无面板外框），减少为单条时自动恢复单卡片状态；叉号只移除本次页面展示，不代表业务完成。
宿主用 `@dismiss` 同步删除内存集合对应项；组件也会本地排除，避免依赖宿主回传才更新数量。
动作成功后是否移除由 `dismissOnSuccess` 声明；取消或失败返回 `false`。业务撤回通过更新输入集合表达。
动作需要确认时使用公开 `confirmAction({ title: '确认操作', layer: 'notification' })`，使确认入口位于提醒之上。
账号切换时以身份为 `key` 重建组件。刷新后集合清空，业务恢复仍由领域服务负责。
边界和完整生命周期见[实时业务提醒接入](../platform/BUSINESS_NOTIFICATION_BACKEND_INTEGRATION.md#本次页面内的临时提醒)。

## 宿主请求与认证恢复

标准模块、角色范围导航和用户在线状态共用宿主注入的 HTTP factory。辅助模块 API 通过
`useModuleHttpClient()` 取得所属页面的客户端，不自行创建第二套连接配置；页面会话提供的
`ModuleHttpProvider` 优先于应用默认配置。用户在线状态按该客户端隔离批次与缓存，实时事件仅刷新
该 store 已观察的用户；观察集合在首次请求前建立，随 store 一起释放。页面最后一个观察者释放后
取消订阅，已释放页面的晚返回不进入新页面缓存。

默认 factory 可直接使用公开的 `createBackendHttpClient`，保留当前登录令牌、语言、流式请求和
已提交数据变化分发。其 API 地址、备用开发令牌及 credentials 由宿主构建配置。宿主需要运行时
地址、自定义认证头或传输策略时，可注入自己的 factory，内置辅助能力沿用同一客户端。

消费应用使用默认客户端时，应通过公开的 `configureAuthenticationRecovery(handler)` 配置认证失效
处理；回调收到错误和该请求实际使用的令牌，宿主应据此识别旧会话的晚返回，并决定是否恢复登录。
返回 `true` 表示已全局处理；传入 `undefined` 解除处理器。匿名客户端不触发认证恢复。自定义
`createHttpClient` 的宿主可直接使用其 `onAuthenticationRequired`，模块与辅助 API 共用该策略。
实时连接仍由宿主通过 `connectAppRealtime` 显式传入连接参数并管理生命周期。
