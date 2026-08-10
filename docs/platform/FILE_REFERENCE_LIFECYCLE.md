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

业务应用迁移到该标准入口时，应为每个可上传字段注册 `FileReferenceUploadPolicy`：`supportsField` 是 descriptor 与 endpoint 共用的唯一字段覆盖声明，`authorize` 再基于草稿、文件事实和 `CREATE`/`APPEND`/`REPLACE` 意图执行目录、项目、状态及动作授权。一个字段只能匹配一个 Policy，避免依赖 Bean 顺序。Policy 只承担上传 admission，不替代应用 CRUD service 对目录、项目、状态和其他业务不变量的正常校验；标准保存不会再次强制调用该业务 Policy，只执行 FileServer 文件事实、租户、临时状态、类型和大小等存储不变量校验及既有生命周期。应用原有同路径 `file-transfer/upload-ticket` Controller 在 Policy 已注册并通过契约测试后应删除，避免与平台标准映射产生冲突或使实际请求绕开 Policy。

文件引用字段 v1 的实体事实为：`maxFiles = 1` 时绑定物理 `STRING` 类型的 `fileId`；`maxFiles > 1` 时绑定物理 `JSON_SET` 的 `Collection<String>`。两种形态都可声明 MIME 类型、单文件大小和字段文件数量约束。静态模型通过 `@FileReference` 声明，动态运行态通过 `EntityDefinition.fileReferences` 表达；标准控件对选择和拖拽都执行 MIME、大小和数量的前端预检，并继续由后端保存生命周期作最终校验。`enabledWhen`、`disabledHint` 与普通字段一致地控制上传控件；禁用时不得打开文件选择器。

## 文件元数据快照

业务确实需要查询、导出或审计文件名、大小、后缀、MIME 或 SHA-256 时，可在独立末梢字段上以 `@FileReferenceMetadataField` 显式声明其单文件 `@FileReference` 来源；动态模型在同一 `FileReferenceDefinition.metadataFields` 中声明相同映射。例如：

```java
private String sourceFileId;

@FileReferenceMetadataField(source = "sourceFileId", value = ORIGINAL_FILENAME)
private String sourceFilename;

@FileReferenceMetadataField(source = "sourceFileId", value = SIZE_BYTES)
private Long sourceFileSize;
```

绑定字段是平台托管快照，不是客户端输入：新文件转正后由 FileServer 权威值写入；保留同一 `fileId` 时从既有记录保留；清空引用时同步清空。字符串事实必须绑定物理 `STRING` 字段，大小必须绑定物理 `LONG` 字段；同一目标字段不得被两个文件引用复用。平台不会猜测或写入 `title`、`name`、`remark` 等业务语义字段。

多文件引用暂不允许元数据绑定。平台不隐式选择 JSON、拼接文本或子表作为快照形态；当真实业务需要集合文件事实时，应以独立、显式的集合快照契约进入平台，而不是改变当前单文件绑定的含义。

## 替换与自动删除

标准创建和更新始终直接提交业务实体，不使用 `$save`、保存 metadata 或客户端文件删除意图。保存生命周期比较数据库中的 `existingRecord` 与本次 `incomingRecord`：新增文件必须是临时文件并在落库前转正；保留文件不访问 FileServer；被替换或移除的旧文件在业务事务提交后删除。删除失败只记录日志，不回滚已保存的业务数据。

单文件按 0/1 值比较，多文件按集合差集比较。父子聚合中仍在本次 payload 的子记录由子实体自身的 CRUD 生命周期比较文件字段，因此不需要浏览器提交 relation path；整行删除属于子记录删除治理，不由字段差集推断物理删除。

取消编辑、上传失败和保存前校验失败不会删除任何旧正式文件；未被保存引用的临时文件继续由 FileServer TTL 清理。平台不允许把一个新绑定位置指向已正式的 fileId，从而在不建设文件资产表或全局引用计数的当前阶段维持文件引用的独占绑定语义。
