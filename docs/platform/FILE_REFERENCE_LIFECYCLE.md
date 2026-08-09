# MuYunFileServer 与文件引用生命周期

[MuYunFileServer](https://github.com/ximatai/MuYunFileserver) 是 MuYunSpring 唯一关联的文件服务，不按可替换存储 provider 或 SPI 建模。它维护文件二进制、哈希、类型、大小、上传者、租户、临时状态和物理清理；MuYunSpring 维护业务授权、文件引用、业务生命周期和审计。

MuYunSpring 的 Starter 以 `muyun.file-transfer.muyun-fileserver` 配置启用官方无状态适配器；`base-url`、`issuer` 和 `secret` 是必填项，系统态写入还必须显式配置 `system-scope-id`。适配器从可信当前用户上下文签发短时 token，并固定传递 `purpose`、`tenant_id` 和 `sub` 等协同声明，业务请求不能自行指定租户或主体。FileServer 的 public API、token 校验和存储部署细节以其仓库为准，本仓库不重复维护另一份传输接口文档。

## 两类业务绑定

- `RecordAttachment` 是已保存业务记录的多附件关系，保存 `moduleAlias + recordId + fileId` 及展示关系属性。它当前只维护关系与访问授权；删除关系不等于删除 FileServer 中的物理文件。
- 文件引用字段是业务模型上的单值或集合 `fileId`，例如知识文件或课件源文件。它不是附件关系的简写，必须由字段能力表达其可创建、可替换和展示规则。

二者可以复用同一套短时访问凭据，但不强行共用持久化模型或删除语义。本次标准保存生命周期只覆盖文件引用字段；附件关系是否需要物理删除，必须由其业务流程另行明确，不能借由关系移除推断。

## 标准生命周期

```text
业务动作授权
  -> 临时上传 access
  -> 浏览器 multipart 上传到 MuYunFileServer
  -> 标准保存动作确认 fileId 与 FileServer 元数据
  -> 文件转正
  -> 业务记录绑定
```

上传 access 必须使文件以临时状态创建。该约束由 MuYunFileServer 的 token 协议强制，浏览器不能用 multipart 字段改变存储状态。用户放弃保存的文件由 FileServer 临时清理策略回收。

保存确认至少校验文件存在、当前租户、临时/可绑定状态和业务允许的类型、大小等约束。业务字段的标题、后缀、大小、哈希等文件事实只能取自 FileServer，不能信任浏览器提交值。

MuYunSpring 与 FileServer 没有跨服务事务。文件引用保存优先保证已提交的业务记录只绑定 permanent 文件：先转正、后持久化。若后续业务持久化失败，会产生低概率的永久孤儿文件；当前仅记录结构化 JVM 日志，不建设平台级补偿表、重试状态机或通用任务中心。

## 当前接入边界

当前 `FileTransferAccessService` 和 `FileTransferClient` 是 MuYunFileServer 的平台门面，Starter 自动装配官方 transport adapter。标准 CRUD 的统一保存生命周期会在静态模型和动态记录的文件引用字段发生变化时，先确认并转正文件；若业务持久化随后失败，平台输出包含模块、记录和 fileId 的结构化 JVM 日志。

文件引用字段不配置 `uiType("fileTransfer")`。静态声明和动态元数据会编译为同一份 `fileReferences` descriptor，由标准表单运行器自动选择上传控件；控件以当前 `moduleAlias` 请求 `POST /{moduleAlias}/file-transfer/upload-ticket`，并携带字段、草稿、所选文件事实和明确意图：空引用为 `CREATE`，已绑定多文件字段新增一项为 `APPEND`，单文件字段覆盖旧值为 `REPLACE`。平台先确认 module、relation 和 field 已由当前静态或动态页面运行时声明为 `FileReference`，再只在匹配的 `FileReferenceUploadPolicy` 明确授权后签发 ticket；缺少声明、Policy 或文件传输配置都会返回可识别错误，不生成无条件上传授权。业务模块不能在浏览器上传完成时自行创建业务记录或调用转正。

业务应用迁移到该标准入口时，应为每个可上传字段注册 `FileReferenceUploadPolicy`：`supportsField` 是 descriptor 与 endpoint 共用的唯一字段覆盖声明，`authorize` 再基于草稿、文件事实和 `CREATE`/`APPEND`/`REPLACE` 意图执行目录、项目、状态及动作授权。一个字段只能匹配一个 Policy，避免依赖 Bean 顺序。标准保存不会再次强制调用该业务 Policy；它继续执行 FileServer 文件事实、租户和临时状态等平台不变量校验。应用原有同路径 `file-transfer/upload-ticket` Controller 在 Policy 已注册并通过契约测试后应删除，避免与平台标准映射产生冲突或使实际请求绕开 Policy。

文件引用字段 v1 的实体事实为：`maxFiles = 1` 时绑定物理 `STRING` 类型的 `fileId`；`maxFiles > 1` 时绑定物理 `JSON_SET` 的 `Collection<String>`。两种形态都可声明 MIME 类型、单文件大小和字段文件数量约束。静态模型通过 `@FileReference` 声明，动态运行态通过 `EntityDefinition.fileReferences` 表达；标准控件对选择和拖拽都执行 MIME、大小和数量的前端预检，并继续由后端保存生命周期作最终校验。`enabledWhen`、`disabledHint` 与普通字段一致地控制上传控件；禁用时不得打开文件选择器。

## 后续替换与明确删除

FileServer 中的文件不可编辑。业务替换文件时，应把新 `fileId` 写入标准 record payload；标准单文件控件只会在新文件成功上传且回填后，为旧 `fileId` 形成保存 metadata 中的逐条 `fileDeletions`。取消或上传失败不会形成删除意图；多文件 `APPEND` 不会生成删除意图。物理删除不从引用关系反推。

每一条删除意图都包含已有业务记录的 `recordPath`、文件字段名和旧 `fileId`。路径以根记录开始，后续节点以子表 relation code 加子记录 ID 定位；它使多个文件字段和同一聚合中的多个子记录有清楚、逐项的归属，而不重复 payload 中已明确的新文件值。

标准创建与更新请求的正式形态为 `{ "$save": { "record": ..., "metadata": { "fileDeletions": [...] } } }`。`$save` 是保留 envelope 字段，避免与静态业务模型的普通字段冲突。当前字段文件引用生命周期会处理根记录及其一层标准子表中的单文件字段：保存前确认删除意图中的旧 fileId 确为该字段的当前值，且 payload 已将字段清空或改为新值；业务持久化成功且事务提交后才调用 FileServer 删除。删除失败只记录日志，不回滚已保存的业务数据。

当前只支持根记录和一层标准子表：子记录必须仍在本次保存 payload 中，整行移除时不能借由文件删除意图推断删除语义；超过一层的嵌套路径，以及删除失败后的补偿/重试均不在此阶段范围。它们必须在有真实业务需求时各自定义明确的保存事实，不能退化成按 `fileId` 的隐式删除。
