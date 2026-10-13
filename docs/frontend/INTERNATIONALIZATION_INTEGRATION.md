# 语言基础接入

当前提供统一语言目录、请求与消费者语言上下文、账户偏好、代码资源以及已生效配置文案的独立翻译发布。平台与填报界面范围、业务记录内容的后续边界见[国际化专项治理](../platform/INTERNATIONALIZATION_GOVERNANCE.md)。

## 后端

Starter 自动装配语言目录、代码资源和请求过滤器。公共接口 `GET /platform.localization/catalog` 返回 `defaultTag` 与 `languages`；每个语言包含 `tag`、`nativeName`、`fallbackTag` 、`direction` 和 `enabled`。HTTP 通过 `Accept-Language` 选择已登记语言，响应通过 `Content-Language` 标明有效语言，缓存以 `Vary: Accept-Language` 区分请求。

应用在 classpath 的 `META-INF/muyun/i18n/languages.properties` 增加语言登记，在 `META-INF/muyun/i18n/messages_<目录中的规范语言标签>.properties` 增加 UTF-8 文案。平台和业务应用的同名资源文件共同加载；业务键使用所属应用命名空间，冲突译文拒绝装配。语言可以预先登记并配置 `enabled=false`，停用语言不进入用户选择或请求协商，但仍可作为已登记资源的回退来源。部署可用 `muyun.i18n.enabled-languages=zh-CN,en` 指定选择列表，覆盖登记的启停默认值；列表必须全部已登记，未选择语言仍保留资源回退能力。同名登记的元信息与译文仍不允许覆盖。默认语言由 `muyun.i18n.default-language` 设置，必须已登记并启用；不读取 JVM 默认语言作为隐式回退。

语言登记示例：

```properties
de.name=Deutsch
de.direction=ltr
de.enabled=false
de.fallback=en
```

平台已经登记 `zh-CN`、`en` 与默认停用的 `fr`。法语是部分资源的扩展验收包，使用 `muyun.i18n.enabled-languages=zh-CN,en,fr` 启用，缺失资源沿 `fr → en → zh-CN` 回退；应用不要用同名登记覆盖启停或回退关系。正式推广应补全、审校所属应用与在线配置译文。

`LanguageContext` 只表达展示语言，与租户、身份、时区和币种独立。请求结束后恢复原作用域；异步 Servlet 再派发保持同一请求语言，Spring MVC 的默认 LocaleResolver 使用同一有效语言。后台执行需要显式创建语言作用域，不依赖线程池继承请求状态。

`TextResources.resolve(key, defaultText, arguments)` 使用当前语言、登记的回退链及原文依次解析。参数使用 `{name}`，保留业务值；返回的 `Resolution` 同时表达有效资源语言、回退及缺失事实。平台 `TextResolver` 同时解析代码资源与已发布配置译文；`prepare(sources)` 对一个展示批次读取配置译文，避免逐字段查询。

## 前端与公共消费者

公共 npm 包提供 `createLanguageContext`、`provideLanguageContext`、`loadLanguageCatalog`、`withLanguageContext`、`UiLanguageProvider`、`LanguageSelector` 及 `createLanguagePreferenceController`。一个应用保持稳定语言上下文，切换语言调用 `setLanguage(tag)`，调用 `setLanguage()` 恢复目录默认值；未明确选择时采用部署默认语言，目录加载前的原始偏好保留并在目录到达后重新匹配。`requestedLanguageTag` 表达原始选择（缺省表示跟随目录默认值），`languageTag` 表达有效语言；恢复或保存失败不会覆盖后续手动选择。切换语言不重新创建页面或表单。

```ts
const language = createLanguageContext();
const http = withLanguageContext(
  createHttpClient({ baseUrl, token }),
  language,
);
await loadLanguageCatalog(http, language);
language.registerResources("en", { "sales.order.title": "Order" });
```

根组件使用 `<UiLanguageProvider :context="language">`，内部通过 `useLanguageContext()` 获取上下文，或用 `language.t(key, defaultText, args)` 显示文字。消费者仍通过 `configureUserPreferencePersistence` 连接自己的认证 HTTP 工厂；`LanguageSelector` 的 `identityScope` 使用当前账户身份，未登录时保留设备偏好。账户切换和用户选择会使过期恢复结果失效；账户持久化失败不会转写设备存储。

框架前端语言资源在 `src/web-core/locales/languagePacks.json` 登记和交付。新增语言增加资源与登记；第三方控件资源通过 `registerUiLanguageLocales` 在应用装配时登记。平台公共键不提供租户覆盖，业务应用以自身命名空间贡献文案。该语言基础只调整展示文本和请求上下文，不修改业务数据、草稿及保存协议。

框架内置中英法控件资源，停用状态仍由语言目录治理。`examples/business-web/src/locales/deployment.json` 演示消费端以资源与部署登记增加语言；`samples/published-consumer` 验证独立 Maven 应用的目录、所属应用语言资源、插值原值及请求响应语言。仓库全量验证覆盖三语作用域、主子表草稿与发布刷新，真实浏览器检查长字段标签的列边界与可点击控件。

## 声明文案与展示快照

`TextReference` 保留 `sourceKind`（`CODE` 或 `CONFIG`）、稳定 `sourceId`、原文版本 `sourceVersion` 与 `originalText`；`TextResolver` 是两种来源的统一解析入口。代码声明的译文仍由所属应用部署，在线配置译文按原文版本独立发布。原文内容版本使用 UTF-8 内容摘要，翻译修改不改变原文版本。`CODE` 解析部署资源，`CONFIG` 只读取身份、原文版本及原文内容匹配的已发布译文；未发布草稿不参与展示。

静态模块编译时为明确的展示槽位保留 `sourceTexts`。文案键按模块、槽位及稳定字段或分组身份生成，例如 `ui/education.teacher/page/detail/editor/fields/main/title/label`；调整字段顺序不改变文案身份。字段名、动作码、公式、权限和业务记录值不进入这个目录。页面自己的标签与默认编辑器的标签属于不同槽位，可以分别翻译。

模块运行上下文的 HTTP 响应生成展示投影，同时提供 `textBindings`，其中包含对应原文引用及语言资源。输出地址按本次展示结构生成，保留编译原文与共享执行计划；在线配置覆盖保留自己的原文引用，不借用底层声明的翻译。表单和列表展示消费这一资源，不把译文写入普通保存请求。

前端 `runtime.snapshot()` 保留原文执行事实，`runtime.presentationSnapshot()` 提供随语言更新的展示。声明式表单复用原有字段身份和草稿，语言切换不重新创建业务会话。公共消费者在 `UiLanguageProvider` 内使用模块上下文；在 Vue 之外创建上下文时，通过 `createModuleContext({ moduleAlias, http, language })` 显式传递所属应用语言上下文。

公共动作消息可以携带 `textSource` 和具名参数，HTTP 交付只解析展示文字，保留动作码、记录身份、数据变更和原始参数。业务记录名称作为参数原值交付，不能把它当作配置标签翻译。错误日志保留原文事实，返回提示按请求语言解析。

## 在线配置译文维护与发布

工作台 `/platform/translations` 维护配置生产者提供的原文。目录覆盖全局 Web 页面展示槽位、菜单名称、字典项标题、动态元数据字段名称，以及已发布或退役流程版本的冻结节点名称、办理指引标题和说明。页面自定义标签与元数据原生名称分别归属自己的来源；静态页面的在线视图覆盖保留配置来源。目录读取与维护沿用系统配置作用域和动作授权，普通配置保存继续编辑原文，不通过译文维护修改配置结构或业务值。

维护接口归属 `platform.localization`，沿用平台动作授权和系统配置作用域：

- `GET /platform.localization/modules/{moduleAlias}/sources`：读取由生产者提供的配置原文引用；代码文案不进入在线维护目录。
- `GET /platform.localization/modules/{moduleAlias}/translations`：读取维护草稿、发布译文和原文版本。
- `POST /platform.localization/drafts`：提交 `moduleAlias/sourceId/sourceVersion/languageTag/text/version`；新增时不提交草稿版本，更新必须提交已读取的版本。
- `POST /platform.localization/translations/{id}/publish`：提交 `sourceVersion/version`，核验有效原文与乐观锁版本后独立发布。

每个原文版本保留独立译文记录。原文变化后旧译文仍可按其冻结引用读取，但不能通过当前原文维护命令误发布为新原文的翻译；维护界面将旧译文作为复核参考。译文发布产生 `configuration-translations-changed` 事实，不重编译页面执行计划，不发布页面结构，不执行 schema 或业务规则刷新。

模块会话可调用 `runtime.refreshTexts()`；工作台收到发布通知时自动刷新相关上下文。刷新只按稳定来源身份、版本和原文匹配替换语言绑定，不更改执行描述符、记录、权限或草稿；加载顺序变化不改变身份，过期响应不覆盖较新的资源。公开消费者可以接入同一通知并显式调用此方法。

## 专题展示与冻结历史

菜单导航、字段选项、流程节点和工作台卡片提供明确的 `titleText` 或 `nodeTitleText` 资源。通用列表与筛选展示使用有效页面字段标签，记录标题、意见和业务原值保持原有契约。枚举选项使用 `enum/{声明类名}/{code}/title` 代码资源，字典选项绑定实际字典项配置身份；选项解析与数据交换仍读取原始标题及编码。使用字典的已安装模块会收到经过模块读取权限过滤的展示失效通知，选项缓存更新不替换表单草稿或业务记录。

流程节点翻译归属实际发布版本及节点键。维护目录保留已发布版本原文，因此可以修正旧版本译文，而不发布新流程或改写实例。运行和历史展示按实例冻结的版本、节点原文读取最新匹配译文；临时加签的用户节点名称和审批意见保留原文。

声明明确的系统历史消息在事件产生时保留文本引用。`TextReference.frozenCode(key, original)` 使用 `key@{原文内容版本}` 部署资源，只解析该版本，不借用当前普通文案键的新模板；缺失或无法核实的来源回退到冻结原文。流程归档携带来源，办理事实和用户输入不被展示更正。

业务提醒通过 `textResources` 携带所有启用语言的资源，浏览器按接收侧语言展示；原始动作、参数与接收者授权规则保持独立。异步请求显式携带语言并恢复工作线程上下文，助手使用正式界面语言与页面事实，不翻译业务值或改变候选、确认和执行权限。

Excel 模板及导出仅投影明确声明的列标签。工作表名称、实体身份、技术表头、下拉原值、引用标题反推、歧义拒绝及记录值保持原有数据交换契约。
