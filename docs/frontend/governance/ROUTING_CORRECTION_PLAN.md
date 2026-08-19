> 本文是 MuYun 前端路由体系的架构纠偏基准，只说明目标架构、公共契约和实施边界，不展开各业务页面的逐页迁移。与现有前端路由治理文档冲突时，以本文为准。

# 1、现在错误设计造成的问题

## 1.1 业务页面没有进入真正的 Vue Router

当前 Vue Router 只注册了 `/` 和 `/:pathMatch(.*)*`，两条路由都进入 `HomeView`。应用、模块、菜单、IAM 等业务页面虽然拥有看似完整的 `route`，但这些 path 没有被注册为独立的 `RouteRecord`。

当前页面打开链路实际是：

```text
浏览器 URL
  -> HomeView 通配路由
  -> Workbench 自行解析 PageDescriptor
  -> 自行查找页面注册表
  -> WorkbenchOutlet / PlatformAdminOutlet
  -> <component :is="...">
```

因此，`route` 当前只是 Workbench 内部的页面查询键，并没有成为 Vue Router 直接管理的页面地址。路由、页签和组件实例形成了多套互相同步的状态，当前 URL 也不再是页面状态的唯一事实来源。

这会直接造成以下问题：

- 浏览器前进、后退不能天然切换真实业务页面，只能依赖额外代码模拟同步。
- 刷新、复制 URL、从收藏夹打开时，需要从 URL 反向重建 Workbench 内部状态，容易产生偏差。
- Vue Router 的导航守卫、路由元信息、嵌套路由和异常路由无法在正确层工作。
- 页签状态、当前页面状态和浏览器历史可能互相不一致。

## 1.2 页面注册表将所有 Vue 页面同步导入首屏

现有静态页面注册表直接执行：

```ts
import OrganizationManagementView from "../views/OrganizationManagementView.vue";
import RoleManagementView from "../views/RoleManagementView.vue";
```

然后把已经加载的组件对象放入注册项：

```ts
{
  route: '/iam/organizations',
  moduleAlias: 'iam.organization',
  component: OrganizationManagementView,
  layout: 'workspace',
}
```

这些同步 import 会把所有页面及其依赖纳入初始模块图。页面数量越多，初始 JavaScript 的下载、解析、执行和依赖初始化成本越高；即使用户从未进入某个页面，也会承担对应的启动成本。这不是 Vue Router 推荐的路由级异步加载方式。

## 1.3 Workbench 用组件显隐模拟页面和缓存

当前 Workbench 遍历已经打开的全部页签，通过 `v-if` 决定是否挂载，通过 `v-show` 切换可见页面。页面再由 `<component :is="...">` 动态渲染。

这种方式把“页面路由”和“保留 DOM 实例”混成了一件事：

- 页签越多，同时常驻的组件实例越多，内存、监听器和后台请求难以治理。
- 页面激活、停用不遵循 Vue Router 与 KeepAlive 的标准生命周期。
- 无法通过 `RouterView + KeepAlive` 统一声明哪些路由需要缓存。
- 同一路由打开多个页面实例时，没有稳定的路由实例 key，查询条件、分页、表单草稿等状态可能互相覆盖。
- 关闭页签、浏览器回退和缓存淘汰之间缺少统一语义。

## 1.4 菜单入口类型依靠空字段推断

平台模块已经拥有 `entryType = MODULE | ROUTE | LINK`，但当前用户菜单接口没有把它作为明确的入口契约返回。前端根据字段是否存在反向猜测：

```ts
if (menu.externalUrl) {
  // LINK
} else if (menu.route) {
  // ROUTE
} else {
  // MODULE
}
```

这会把“入口类型”退化为多个可空字段的偶然组合，无法准确区分正确配置和残缺配置。例如，`ROUTE` 忘记配置 route 后会被当作 `MODULE`，而不是得到“内部路由不能为空”的明确错误。

`moduleKind` 也不能代替 `entryType`：`moduleKind = STATIC | DYNAMIC` 表达后端模块由静态 Java 声明还是动态元数据定义；`entryType` 才表达页面由标准模块运行器、Vue 页面还是外部链接承载。

## 1.5 前端存在多种页面查找与静默兜底

当前页面解析同时存在以下路径：

1. 使用 `moduleAlias` 查找本地业务 route。
2. 使用 `route + moduleAlias` 查找静态页面组件。
3. 没有 route 时，仅使用 `moduleAlias` 继续匹配。
4. 静态页面未匹配时，进入 `DynamicModuleHost`。

这使后台配置、前端 route、模块上下文和组件映射之间没有唯一匹配规则。用户把 route 或 moduleAlias 配错时，系统可能打开错误页面、展示空页面，或者静默进入 `DynamicModuleHost`，无法告诉用户究竟是哪一项配置错误。

`DynamicModuleHost` 本质上是标准模块页面运行器：它根据 `moduleAlias`、`pageMode` 和 UI 描述渲染通用列表、表单或详情。它可以承载符合标准页面描述协议的静态模块或动态模块，但不能作为 Vue 页面查找失败后的兜底。

# 2、我们要修复的目的

## 2.1 建立单一页面导航事实

所有平台内部页面都必须成为真实的 Vue Router RouteRecord。浏览器当前 URL 是当前页面和当前实例的唯一事实来源，页签只负责展示和触发路由导航，不再自行决定渲染哪个页面。

目标导航链路统一为：

```text
首次受保护路由导航
  -> 获取当前用户菜单
  -> 校验菜单入口
  -> 动态注册 RouteRecord
  -> Vue Router 匹配目标 URL
  -> RouterView 渲染页面
  -> KeepAlive 按路由实例 key 缓存
```

浏览器前进、后退、刷新、复制地址和从收藏夹打开都沿用这条链路，不再从另一套 Workbench 页面状态反向模拟路由。

## 2.2 保留三种入口能力并明确入口类型

模块的 `entryType` 是入口类型的唯一事实来源，菜单只引用模块入口，不允许维护一份能够与模块冲突的独立类型。菜单接口必须返回从模块入口解析得到的显式 `entryType`。

| `entryType`       | 页面能力                 | 必要配置                                 | Vue Router 承载方式                                          |
| ----------------- | ------------------------ | ---------------------------------------- | ------------------------------------------------------------ |
| `MODULE`          | 标准模块列表、表单、详情 | `moduleAlias`、`pageMode` 及可选 UI 配置 | `/platform/dynamic/:moduleAlias/:pageMode`，加载标准模块页面 |
| `ROUTE`           | 前端专用 Vue 页面        | `moduleAlias`、内部 `route`              | `route` 精确匹配前端静态 RouteDefinition                     |
| `LINK` + `TAB`    | 第三方页面嵌入           | `externalUrl`                            | `/platform/external`，由 iframe 页面承载                     |
| `LINK` + `WINDOW` | 第三方页面新窗口         | `externalUrl`                            | 点击时打开新窗口，不创建内部页面实例                         |

其中：

- `route` 是前后端共同约定的浏览器 URL，也是 Vue 页面唯一匹配键。
- `moduleAlias` 只负责模块身份、权限、数据、动作和上下文一致性校验，不参与查找 `.vue`。
- `componentPath` 是前端源码内部信息，只存在于前端静态注册表，后台不得配置 `.vue` 文件路径。
- 同一个模块可以拥有多个 Vue 页面；不同页面通过不同 route 区分，并共享相同的模块上下文。

## 2.3 页面按需加载并可验证真实组件路径

前端继续保留静态页面注册表，以显式维护 route、模块上下文和布局契约；注册表不再同步 import 组件，而是声明真实 `componentPath`。

Vite 使用限定目录的 `import.meta.glob` 在构建期收集页面文件。默认 glob 结果是 `路径 -> () => import(...)` 的懒加载映射，不使用 `{ eager: true }`。这样既能在注册阶段验证 `.vue` 是否真实存在，又不会在启动时下载、解析和执行全部页面组件。

## 2.4 恢复标准路由缓存和多实例隔离

主内容区使用 `RouterView` 插槽渲染当前 RouteRecord，可缓存页面由 `KeepAlive` 管理。页面实例标识统一命名为 `InstanceKey`，URL query 参数也固定使用完全相同的名称 `InstanceKey`。缓存 key 由 `route.name + InstanceKey` 组成：

```text
cacheKey = route.name + ':' + (route.query.InstanceKey ?? 'default')
```

同一路由需要打开多个独立页面时，由工作台提供的公共导航方法（例如 `workbenchNavigation.openRoute()`）生成新的 `InstanceKey` 并写入 URL query。每个实例拥有独立的浏览器地址、浏览器历史记录和 KeepAlive 缓存；回到同一个完整 URL 时恢复对应实例，而不是复用另一个页面的状态。

## 2.5 配置错误必须可定位、可修正

菜单初始化必须在注册路由前完成结构化校验。任何错误都要明确指出菜单、入口类型、模块、route、组件路径、失败原因和修改建议。

校验只在首次获取菜单或菜单版本发生变化时执行一次。后续打开页面不重新请求菜单，也不重新扫描全部配置。校验结果保存在独立的 Pinia Store 中，供菜单和通配路由诊断页面共同读取。

无效菜单不注册业务 RouteRecord，也不能回退到标准模块页面；其他有效路由继续工作。用户点击错误菜单或直接访问未注册地址时，由 Vue Router 最后的通配路由在原始 URL 上展示诊断结果：能够找到对应配置问题时显示“菜单配置错误”，找不到任何菜单和前端路由记录时才显示“404 页面不存在”。

# 3、修正方案

## 3.1 统一入口契约

`PlatformModule.entryType` 保持 `MODULE | ROUTE | LINK` 三种类型，并作为入口类型的唯一持久化事实。菜单管理界面在选择模块后展示该入口类型，但不允许菜单另行修改。

当前用户菜单返回模型增加显式的解析字段：

```ts
type MenuEntryType = "module" | "route" | "link";

interface MenuRecord {
  id: string;
  title: string;
  moduleAlias?: string;
  entryType?: MenuEntryType;
  openMode?: "tab" | "window";
  route?: string;
  externalUrl?: string;
  pageMode?: "LIST" | "FORM" | "DETAIL";
  defaultUiConfigId?: string;
  defaultQueryTemplateId?: string;
  entryParamsJson?: string;
}
```

`entryType` 是模块入口在菜单响应中的只读投影，不在菜单表中形成第二份可独立编辑的数据。前端必须使用 `switch (entryType)` 编译导航目标，禁止通过 `route/externalUrl` 是否为空推断入口类型。

入口字段组合严格遵循以下规则：

| 入口类型 | 必须存在                                 | 必须为空                                                                       |
| -------- | ---------------------------------------- | ------------------------------------------------------------------------------ |
| 容器菜单 | `title`                                  | `entryType`、`moduleAlias`、`route`、`externalUrl`、`openMode`、低代码入口配置 |
| `MODULE` | `moduleAlias`、`openMode`                | `route`、`externalUrl`                                                         |
| `ROUTE`  | `moduleAlias`、`openMode`、`route`       | `externalUrl`、`pageMode`、默认 UI/查询配置                                    |
| `LINK`   | `moduleAlias`、`openMode`、`externalUrl` | `route`、`pageMode`、默认 UI/查询配置                                          |

后端保存模块和菜单时先执行同样的组合校验；前端加载菜单时再次验证交付结果，防止历史数据、版本不一致或异常接口响应进入路由表。

## 3.2 建立静态 Vue 页面注册表

前端专用 Vue 页面使用静态 RouteDefinition 声明：

```ts
interface StaticRouteDefinition {
  route: `/${string}`;
  moduleAlias: string;
  componentPath: `/src/views/${string}.vue`;
  layout: "flow" | "workspace";
  menuEntry?: boolean;
}
```

现有注册项调整为纯声明：

```ts
const staticRouteDefinitions: StaticRouteDefinition[] = [
  {
    route: "/iam/organizations",
    moduleAlias: "iam.organization",
    componentPath: "/src/views/OrganizationManagementView.vue",
    layout: "workspace",
  },
];
```

禁止在注册表顶部同步 import 业务页面。所有允许成为 route 页面的 Vue 文件从约定目录收集：

```ts
const routePageLoaders = import.meta.glob("/src/views/**/*View.vue", {
  import: "default",
});
```

glob pattern 必须是源码中的字面量并限制在路由页面目录，不使用 `/src/**/*.vue` 扫描普通组件、弹窗和布局组件，也不使用 `{ eager: true }`。

注册表编译时按 `componentPath` 从 `routePageLoaders` 取得 loader：

```ts
function compileStaticRoute(definition: StaticRouteDefinition) {
  const component = routePageLoaders[definition.componentPath];
  if (!component) {
    throw routeConfigurationError(definition, "声明的 Vue 页面文件不存在");
  }

  return {
    path: definition.route,
    name: createStaticRouteName(definition.route),
    component,
    meta: {
      entryType: "route",
      moduleAlias: definition.moduleAlias,
      layout: definition.layout,
      cacheable: true,
    },
  };
}
```

这里的菜单匹配仍然只有一种：菜单 `route` 精确匹配 `StaticRouteDefinition.route`。`componentPath` 只是已经匹配成功的注册项内部用于验证和取得 loader 的字段，不构成第二种菜单匹配模式。

## 3.3 全局前置路由守卫动态注册用户路由

使用 Vue Router 的全局前置守卫 `router.beforeEach` 统一处理用户路由初始化。全局前置守卫在每次导航开始前执行，但菜单请求、配置校验和动态路由注册只在当前身份首次进入受保护页面且尚未完成初始化时执行。

路由初始化只预置登录、Workbench 布局、路由诊断通配页和其他不依赖用户菜单的基础 RouteRecord。业务 RouteRecord 由全局前置守卫按当前身份动态注册。

```ts
let menuRouteInitialization: Promise<MenuRouteInitializationResult> | undefined;
let menuRoutesReady = false;

function ensureMenuRoutes() {
  menuRouteInitialization ??= initializeMenuRoutes().catch((error) => {
    menuRouteInitialization = undefined;
    throw error;
  });
  return menuRouteInitialization;
}

router.beforeEach(async (to) => {
  if (isPublicRoute(to)) return true;
  if (menuRoutesReady) return true;

  const result = await ensureMenuRoutes();
  menuRoutesReady = true;

  routeDiagnosticsStore.replaceIssues(result.issues);
  return to.fullPath;
});
```

全局前置守卫每次导航时先判断目标是否为公开页面，再判断当前身份的菜单路由是否已经初始化。公开页面直接放行；已经初始化的受保护页面直接放行；只有尚未初始化的受保护页面才执行以下步骤：

1. 获取当前用户可见菜单。
2. 把菜单树展开为入口列表。
3. 根据显式 `entryType` 校验字段组合。
4. 校验所有静态 RouteDefinition 和 glob 组件路径。
5. 将全部错误一次写入路由诊断 Store。
6. 将没有错误的菜单编译为 RouteRecord。
7. 使用 `router.addRoute(workbenchRouteName, routeRecord)` 注册到 Workbench 布局下。
8. 标记初始化完成，再返回 `to.fullPath` 重新匹配首次目标，防止守卫无限循环。

并发发生多个尚未初始化的受保护导航时必须复用同一个初始化 Promise，禁止重复请求菜单或重复注册 RouteRecord。初始化完成后的正常导航虽然仍会经过全局前置守卫，但会被 `menuRoutesReady` 直接放行，不重复请求菜单、不重复扫描注册表，也不重新执行完整校验。

菜单请求失败和菜单配置错误是两类错误：请求失败进入可重试的加载错误状态；配置错误进入路由诊断 Store，但不影响其他有效路由注册。

用户退出登录、切换身份、租户或菜单方案时，调用 `router.addRoute()` 返回的移除函数清理上一个身份的动态路由，同时清理路由诊断 Store、对应页签、KeepAlive 实例键和初始化状态，下一次受保护导航重新获取菜单。

## 3.4 三种入口的 RouteRecord 编译规则

### 标准模块页面

`MODULE` 入口根据 `moduleAlias` 和 `pageMode` 生成具体 URL：

```text
/platform/dynamic/{encodeURIComponent(moduleAlias)}/{pageMode.toLowerCase()}
```

每个有效菜单注册一个指向同一标准模块页面 loader 的 RouteRecord，路由参数和 `meta` 携带 `menuId`、`moduleAlias`、`pageMode`、默认 UI 配置及查询模板。标准模块页面负责消费描述并渲染列表、表单或详情，不参与静态 Vue 页面匹配。

### 前端 Vue 页面

`ROUTE` 入口使用菜单 route 精确查找唯一 StaticRouteDefinition：

```text
menu.route
  -> staticRouteDefinitionsByRoute.get(menu.route)
  -> 校验 menu.moduleAlias === definition.moduleAlias
  -> 取得 glob loader
  -> router.addRoute(...)
```

route 查找失败、命中多个注册项或 moduleAlias 不一致时，该菜单无效。禁止再使用 moduleAlias 查找另一个页面，也禁止转入标准模块页面。

### 第三方页面

`LINK + TAB` 导航到 `/platform/external`，由一个真实 RouteRecord 渲染 iframe 承载页；目标 URL 来自已经验证的菜单入口。`LINK + WINDOW` 在点击时打开新窗口，不进入 Workbench 的 RouterView 和 KeepAlive。

`LINK` 的 URL 安全策略、允许域和 iframe 安全属性沿用平台外部页面治理边界；本次路由纠偏不扩展第三方接入能力。

## 3.5 RouterView、页签和 KeepAlive

Workbench 主内容区只渲染当前路由：

```vue
<RouterView v-slot="{ Component, route }">
  <KeepAlive>
    <component
      :is="Component"
      v-if="route.meta.cacheable !== false"
      :key="pageCacheKey(route)"
    />
  </KeepAlive>

  <component
    :is="Component"
    v-if="route.meta.cacheable === false"
    :key="pageCacheKey(route)"
  />
</RouterView>
```

不再遍历所有页签并同时挂载页面，也不再使用 `v-show` 控制哪个页面可见。页签模型只保留标题、关闭策略、锁定状态及目标 URL；点击页签执行 `router.push(tab.fullPath)`，激活页签从当前 route 反向确定。

缓存 key 规则固定为：

```ts
function pageCacheKey(route: RouteLocationNormalizedLoaded) {
  const pageInstanceKey = String(route.query.InstanceKey ?? "default");
  return `${String(route.name)}:${pageInstanceKey}`;
}
```

`InstanceKey` 的生成和使用规则固定如下：

1. “工作台统一导航入口”指工作台提供的公共导航方法，例如 `workbenchNavigation.openRoute()`。它不是某个页面，也不是路由守卫。
2. 菜单组件和业务页面需要打开路由时，只能调用这个公共方法；它们不能直接调用 `crypto.randomUUID()`，也不能自行向 query 写入新的 `InstanceKey`。
3. 全局前置路由守卫只负责菜单加载、配置校验和动态路由注册，禁止生成或修改 `InstanceKey`。否则刷新、前进和后退经过守卫时会改变页面实例身份。
4. 公共导航方法接收“打开默认实例”或“新开独立实例”的明确参数。只有执行“新开独立实例”时，才使用 `crypto.randomUUID()` 生成新的 `InstanceKey`。
5. 普通菜单点击复用默认实例，不在 URL 中写入 `InstanceKey`；读取时把缺省值解释为 `default`。
6. 新生成的值必须写入 URL query，参数名严格为 `InstanceKey`，不得使用 `instanceKey`、`_muyunInstanceKey` 等其他名称。
7. 点击已有页签不调用新开逻辑，而是直接导航到页签保存的 `fullPath`，因此不得重新生成 `InstanceKey`。
8. 浏览器前进、后退、刷新、复制 URL 和工作台恢复都读取 URL 中已有的 `InstanceKey`，不得替换它。
9. 页面实例关闭后再次执行“新开独立实例”，必须生成新值，不复用已关闭实例的值。
10. `InstanceKey` 只标识页面实例，不保存查询条件、分页、表单草稿等页面数据；刷新后需要恢复这些数据时，必须另行使用 URL、持久化 Store 或后端草稿保存。

公共导航方法的职责只有四项：判断打开默认实例还是新实例、合并业务 query、新开时生成 `InstanceKey`、调用 `router.push()`。菜单组件和业务页面只传递导航意图：

```ts
interface OpenRouteOptions {
  newInstance?: boolean;
  query?: Record<string, RouteQueryValue>;
}

function createInstanceKey() {
  return crypto.randomUUID();
}

function openRoute(path: string, options: OpenRouteOptions = {}) {
  const query = { ...options.query };

  if (options.newInstance) {
    // 只有公共导航方法可以生成新的页面实例标识。
    query.InstanceKey = createInstanceKey();
  } else {
    // 默认实例不在 URL 中显示 InstanceKey。
    delete query.InstanceKey;
  }

  return router.push({ path, query });
}
```

调用方只能这样使用：

```ts
// 菜单点击：打开或激活默认实例。
workbenchNavigation.openRoute("/iam/organizations");

// 业务动作明确要求多开：创建一个新的独立实例。
workbenchNavigation.openRoute("/iam/organizations", {
  newInstance: true,
});

// 点击已有页签：复用页签中已经保存的完整 URL。
router.push(tab.fullPath);
```

禁止在其他位置重复实现生成逻辑：

```ts
// 禁止：菜单组件或业务页面自己生成。
router.push({
  path: "/iam/organizations",
  query: { InstanceKey: crypto.randomUUID() },
});

// 禁止：路由守卫在导航过程中补写或替换。
to.query.InstanceKey = crypto.randomUUID();
```

普通菜单打开：

```text
/iam/organizations
```

同一路由新开两个独立实例：

```text
/iam/organizations?InstanceKey=3f90db94-47b8-4ce8-94e8-f40dfde5703d
/iam/organizations?InstanceKey=84fb449e-ae13-408c-919c-2fbb03771c9a
```

两个 URL 使用同一个 RouteRecord 和同一个 Vue 页面组件，但 `pageCacheKey` 不同，因此 KeepAlive 保存两个互不冲突的组件实例。

路由组件需要刷新数据或暂停资源时，使用 `onActivated`、`onDeactivated` 和 `onBeforeUnmount` 区分重新激活、进入缓存和最终销毁，禁止通过父级 `v-show` 猜测页面是否处于活动状态。

## 3.6 首次校验与诊断结果存储

路由配置校验器写成普通 TypeScript 函数。它只接收菜单、静态注册表和 glob loader 表，返回有效路由和配置问题；不调用 Vue Router、不操作 Pinia，也不显示界面消息。

```ts
interface MenuRouteValidationResult {
  validRoutes: RouteRecordRaw[];
  issues: RouteConfigurationIssue[];
}

function validateAndCompileMenuRoutes(
  menus: MenuRecord[],
  definitions: StaticRouteDefinition[],
  componentLoaders: Record<string, () => Promise<unknown>>,
): MenuRouteValidationResult;
```

首次注册阶段至少执行以下校验：

- 静态注册表 route 必须是合法内部绝对路径，且全局唯一。
- 静态注册表路由名称必须唯一。
- `componentPath` 必须精确存在于 glob loader 表，大小写必须一致。
- `ROUTE` 菜单必须提供 route，且只能精确命中一个静态注册项。
- 菜单 moduleAlias 必须与静态注册项 moduleAlias 一致。
- `MODULE` 不得携带 route 或 externalUrl。
- `LINK` 不得携带内部 route 或标准模块页面配置。
- 同一路径被多个菜单使用时，所有菜单必须指向完全相同的 RouteDefinition；只要其中一个菜单存在类型、moduleAlias 或页面定义冲突，该路径整体不注册，避免错误菜单命中另一个菜单注册的页面。

配置问题使用统一结构记录。`actual` 保存后台菜单的实际配置，`expected` 保存前端注册表要求的值，诊断页面据此直接显示“哪里和哪里对不上”。

```ts
type RouteConfigurationIssueCode =
  | "MENU_ROUTE_MISSING"
  | "ROUTE_NOT_REGISTERED"
  | "MODULE_ALIAS_MISMATCH"
  | "COMPONENT_NOT_FOUND"
  | "ENTRY_FIELDS_CONFLICT"
  | "ROUTE_CONFLICT";

interface RouteConfigurationIssue {
  code: RouteConfigurationIssueCode;
  menuId?: string;
  menuTitle?: string;
  entryType?: MenuEntryType;
  route?: string;
  componentPath?: string;
  actual?: {
    entryType?: MenuEntryType;
    moduleAlias?: string;
    route?: string;
    externalUrl?: string;
  };
  expected?: {
    entryType?: MenuEntryType;
    moduleAlias?: string;
    route?: string;
    componentPath?: string;
  };
  reason: string;
  suggestion?: string;
}
```

诊断结果放在独立的 `routeDiagnosticsStore` 中。这个 Pinia Store 只保存问题和查询索引，使菜单组件与通配路由页面读取同一份结果。

```ts
interface RouteDiagnosticsState {
  issues: RouteConfigurationIssue[];
  issuesByMenuId: Record<string, RouteConfigurationIssue[]>;
  issuesByRoute: Record<string, RouteConfigurationIssue[]>;
}

interface RouteDiagnosticsStore {
  replaceIssues(issues: RouteConfigurationIssue[]): void;
  findIssues(menuId?: string, route?: string): RouteConfigurationIssue[];
  clear(): void;
}
```

`findIssues` 有 menuId 时优先查询 `issuesByMenuId`，没有命中时再查询 `issuesByRoute`。索引在 `replaceIssues` 时一次建立，页面打开时只是按 key 查找，不重新执行校验。

Pinia 不保存以下运行对象：

- Vue 页面组件和 KeepAlive 组件实例。
- `import.meta.glob` 生成的 loader。
- RouteRecord 和 `router.addRoute()` 返回的路由移除函数。
- Router、请求客户端等外部运行实例。

这些对象由路由初始化模块管理。诊断 Store 也不写入 localStorage，因为结果同时依赖当前用户菜单和当前前端构建版本，刷新后必须重新获取菜单并校验。

退出登录、切换用户、租户或菜单方案时必须清空 Store。菜单或模块入口保存成功后，也必须移除旧动态路由、清空诊断结果并重新获取菜单，不能继续使用修改前的判断。

## 3.7 通配路由统一展示配置错误和404

基础路由的最后一条必须是通配路由：

```ts
{
  path: "/:pathMatch(.*)*",
  name: "route-diagnostics",
  component: () => import("/src/views/RouteDiagnosticsView.vue"),
}
```

通配路由直接在用户输入的原始 URL 上渲染诊断页面，不重定向到 `/404`。因此浏览器前进、后退、刷新和复制地址都保留真正失败的 path，诊断页通过 `route.path` 和 `route.fullPath` 直接取得原始地址。

错误菜单仍然展示在菜单中，并明确标记“配置错误”。点击错误菜单时导航到它配置的原始 route，同时携带菜单 ID：

```ts
router.push({
  path: menu.route,
  query: {
    _muyunMenuId: menu.id,
  },
});
```

错误菜单对应的 path 不注册业务 RouteRecord，因此会进入通配诊断页。诊断页优先用 `_muyunMenuId` 查找当前菜单的问题；URL 没有 menuId 时，再用原始 path 查找该路径的全部问题。

```ts
const menuId = stringQueryValue(route.query._muyunMenuId);
const issues = routeDiagnosticsStore.findIssues(menuId, route.path);
const pageState = issues.length > 0 ? "configuration-error" : "not-found";
```

同一路径被多个菜单使用时，`_muyunMenuId` 用于精确指出用户点击的菜单；直接输入 URL 没有 menuId 时，页面展示这个 path 下的全部冲突。只要同一路径存在配置冲突，该路径就整体不注册，保证它不会意外打开另一个菜单的页面。

诊断页面按结果显示两种明确内容：

```text
页面入口配置错误

菜单：角色管理
menuId：platform.menu.iam.role
请求地址：/iam/roles

后台实际配置：
entryType = ROUTE
moduleAlias = iam.user
route = /iam/roles

前端路由要求：
moduleAlias = iam.role
route = /iam/roles
componentPath = /src/views/RoleManagementView.vue

不一致字段：moduleAlias
修改建议：将菜单绑定模块修改为 iam.role
```

如果 Store 中没有对应菜单或 path 的问题，则显示真正的404：

```text
404 页面不存在

请求地址：/unknown/page
当前用户菜单没有配置这个地址，前端也没有注册这个路由。
```

403无权限、菜单接口请求失败、异步组件下载失败和页面代码运行异常不进入这套404判断：403显示权限错误；菜单请求失败显示可重试的菜单加载错误；组件下载失败和页面运行异常进入页面加载错误处理。

配置错误不允许转入 `DynamicModuleHost`，也不允许自动改写菜单。错误报告可以给出相近 route 等修改建议，但最终必须由用户修正后台菜单或前端注册表。

## 3.8 改造边界与完成标准

本方案实施时移除页面级同步 import、Workbench 页面显隐循环、`<component>` 页面主分流、基于字段空值的入口类型推断、moduleAlias 页面查找以及 `DynamicModuleHost` 静默兜底。保留菜单、页签、标准模块运行器、第三方嵌入和模块上下文能力，但全部通过真实 RouteRecord 协作。

本文件不规定各业务页面内部组件如何拆分，不列出逐页面迁移顺序，也不扩展微前端、远程组件、第三方域名治理或新的页面设计器能力。

路由纠偏完成必须满足：

1. 所有内部页面都能在 Vue Router 中找到对应 RouteRecord。
2. 首屏不再同步加载未访问的业务页面组件。
3. 浏览器前进、后退、刷新和复制 URL 能恢复同一页面实例。
4. 同一路由的不同 `InstanceKey` 拥有互不冲突的 KeepAlive 状态，URL 参数名统一为 `InstanceKey`。
5. MODULE、ROUTE、LINK 三种入口均由显式 entryType 分流。
6. route 是 Vue 页面唯一匹配键，moduleAlias 只做上下文和一致性校验。
7. 首次菜单加载完成全量校验，后续页面导航不重复请求菜单或扫描全部配置。
8. 所有无效菜单均产生可定位、可修正的结构化错误，且不存在静默兜底。
9. 通配路由保留原始 URL，能够明确区分菜单配置错误与真正的404。
