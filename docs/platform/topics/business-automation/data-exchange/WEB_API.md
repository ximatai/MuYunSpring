# 导入导出 Web API

静态与动态标准模块继承 `RecordExchangeWeb`，须明确开启 `EXCHANGE`，所有入口保留正常租户、动作与业务范围校验。

## 公共入口

基础路径 `/{moduleAlias}/exchange/files`：

| POST 路径 | 输入 | 结果 |
| --- | --- | --- |
| `/template` | JSON `purpose: DATA`、可选 `language`、IANA `timeZone` | 带可验证文件身份的填写模板 |
| `/preview` | multipart `file`，可选 JSON `command` | 文档身份、核对摘要、各表匹配字段、当前值、拟修改值、错误、待确认对象 |
| `/import` | multipart `file` 与已核对 JSON `command` | 新增、更新、跳过、错误数量，逐行回执，失败文件 base64 |
| `/export` | JSON `ids`、可选正常 `query`、`options` | xlsx 业务填写、译文维护或阅读文件 |

`options.purpose` 为 `DATA`、`TRANSLATIONS` 或 `READING`，后两者必须指定目标 `language`。`timeZone` 默认 `UTC`。查询导出不把当前页当作全量结果；单次最大 1000 行，超限须缩小范围。`ids` 与 `query` 同时提供时取交集。

查询导出的 `X-MuYun-Content-Language` 保留成功列表查询的业务阅读语言，用于译文筛选与排序；它与 `options.language` 指定的文件输出语言分别表达。例如可以按英文译文查询，再导出德文阅读文件。身份交集在查询计数与分页前应用，行数限制作用于最终输出范围。

`command` 包含 `documentId`、`previewDigest`、各实体 `sheets`（`entityAlias`、`matchField`、`duplicateStrategy`），可选 `confirmations`、`sourceLanguage` 与 `resolutionLanguage`。确认项绑定文件行、引用字段、输入文本及实际候选对象 ID。核对摘要绑定文件与选项；修改任何一项后须重新预览。`resolutionLanguage` 只影响本次业务名称解析，不能改动译文维护文件的目标语言。

模板及数据文件采用两行表头。更新空白或缺列不修改，`__clearFields` 显式清空。`__exchangeRow` 为服务器验证的行身份，`__inputIdentity` 保持失败新行身份，`__contextOnly` 表示只读关联上下文，`__error` 仅为说明。人工译文文件使用独立原文和译文版本列；阅读文件的技术列与持久化用途阻止误回导。

独立业务单元分别提交；主子表整单失败回滚。错误文件只含待修正单元及必要的只读关联上下文，可直接修正重传，不要求通过更换重复策略规避重复写入。已成功行继续修改须重新导出。

## 旧动态入口

兼容保留 `/{moduleAlias}/exchange/template`、`/import/parse`、`/import/execute`、`/import/error-file/{token}`、`/export/data` 与 `/export/selected`。解析、执行和导出适配公共引擎，旧导出保留其分页与已编译查询语义。旧模板参数与协议 1 仍可读取。

旧执行响应仍提供消息、文件名、token 与按实体汇总；token 下载只允许原操作人，且重新校验当前业务读取权限。文件身份不会授予任何记录访问权。新客户端优先使用公共入口，以获得明确核对与引用对象确认流程。
