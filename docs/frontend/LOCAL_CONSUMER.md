# 前端本地消费者验证

MuYunSpring 前端提供两条本机接入路径：日常联调使用链接后的生成包，交付验证安装 tarball。两者都只消费公开包，不为业务 App 配置 `src/` 内部 alias；这样既保留快速反馈，也不让本机开发绕过 `exports`、声明文件、样式或 peer dependencies 契约。

当前首个应用交付包是 `@ximatai/muyun-web-app`。它交付 Workbench 壳、菜单/页签导航、标准模块运行器、平台 HTTP/session/menu client、平台业务组件，以及可直接打开的平台管理页；业务应用仍自行拥有业务页面和路由组合。

该包的 CSS 以显式子路径 `@ximatai/muyun-web-app/style.css` 交付。消费者必须在自己的入口导入它；包的 JavaScript 入口不隐式注入样式。这是未来 npm、SSR、按需构建和多应用共存时可审计的依赖契约。

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
