# 语言基础接入

当前提供语言目录、语言上下文、代码资源解析、账户语言偏好及控件语言。页面配置译文、业务规则提示和平台各专题的完整覆盖继续按[国际化专项治理](../platform/INTERNATIONALIZATION_GOVERNANCE.md)推进。

## 后端

Starter 自动装配语言目录、代码资源和请求过滤器。公共接口 `GET /platform.localization/catalog` 返回 `defaultTag` 与 `languages`；每个语言包含 `tag`、`nativeName`、`fallbackTag` 、`direction` 和 `enabled`。HTTP 通过 `Accept-Language` 选择已登记语言，响应通过 `Content-Language` 标明有效语言，缓存以 `Vary: Accept-Language` 区分请求。

应用在 classpath 的 `META-INF/muyun/i18n/languages.properties` 增加语言登记，在 `META-INF/muyun/i18n/messages_<目录中的规范语言标签>.properties` 增加 UTF-8 文案。平台和业务应用的同名资源文件共同加载；业务键使用所属应用命名空间，冲突译文拒绝装配。语言可以预先登记并配置 `enabled=false`，停用语言不进入用户选择或请求协商，但仍可作为已登记资源的回退来源。部署可用 `muyun.i18n.enabled-languages=zh-CN,en` 指定选择列表，覆盖登记的启停默认值；列表必须全部已登记，未选择语言仍保留资源回退能力。同名登记的元信息与译文仍不允许覆盖。默认语言由 `muyun.i18n.default-language` 设置，必须已登记并启用；不读取 JVM 默认语言作为隐式回退。

语言登记示例：

```properties
fr.name=Français
fr.direction=ltr
fr.enabled=true
fr.fallback=en
```

`LanguageContext` 只表达展示语言，与租户、身份、时区和币种独立。请求结束后恢复原作用域；异步 Servlet 再派发保持同一请求语言，Spring MVC 的默认 LocaleResolver 使用同一有效语言。后台执行需要显式创建语言作用域，不依赖线程池继承请求状态。

`TextResources.resolve(key, defaultText, arguments)` 使用当前语言、登记的回退链及原文依次解析。参数使用 `{name}`，保留业务值；返回的 `Resolution` 同时表达有效资源语言、回退及缺失事实。当前解析面面向代码资源，在线配置的来源和版本契约另按专项阶段接入。

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

## 声明文案与展示快照

`TextReference` 保留 `sourceKind`（`CODE` 或 `CONFIG`）、稳定 `sourceId`、原文版本 `sourceVersion` 与 `originalText`；`TextResolver` 是两种来源的统一解析入口。代码声明的译文仍由所属应用部署，在线配置译文按原文版本独立发布。原文内容版本使用 UTF-8 内容摘要，翻译修改不改变原文版本。目前 `CODE` 解析代码资源，`CONFIG` 保留原文回退；在线维护与独立发布按专项下一阶段接入。

静态模块编译时为明确的展示槽位保留 `sourceTexts`。文案键按模块、槽位及稳定字段或分组身份生成，例如 `ui/education.teacher/page/detail/editor/fields/main/title/label`；调整字段顺序不改变文案身份。字段名、动作码、公式、权限和业务记录值不进入这个目录。页面自己的标签与默认编辑器的标签属于不同槽位，可以分别翻译。

模块运行上下文的 HTTP 响应生成展示投影，同时提供 `textBindings`，其中包含对应原文引用及语言资源。输出地址按本次展示结构生成，保留编译原文与共享执行计划；在线配置覆盖接入时须取得自己的原文引用，不借用底层声明的翻译。表单和列表展示消费这一资源，不把译文写入普通保存请求。

前端 `runtime.snapshot()` 保留原文执行事实，`runtime.presentationSnapshot()` 提供随语言更新的展示。声明式表单复用原有字段身份和草稿，语言切换不重新创建业务会话。公共消费者在 `UiLanguageProvider` 内使用模块上下文；在 Vue 之外创建上下文时，通过 `createModuleContext({ moduleAlias, http, language })` 显式传递所属应用语言上下文。

公共动作消息可以携带 `textSource` 和具名参数，HTTP 交付只解析展示文字，保留动作码、记录身份、数据变更和原始参数。业务记录名称作为参数原值交付，不能把它当作配置标签翻译。错误日志保留原文事实，返回提示按请求语言解析。
