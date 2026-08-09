# MuYunFileServer 与文件引用生命周期

MuYunFileServer 是 MuYunSpring 的关联文件服务，不按可替换存储 provider 建模。它维护文件二进制、哈希、类型、大小、上传者、租户、临时状态和物理清理；MuYunSpring 维护业务授权、文件引用、业务生命周期和审计。

## 两类业务绑定

- `RecordAttachment` 是已保存业务记录的多附件关系，保存 `moduleAlias + recordId + fileId` 及展示关系属性。
- 文件引用字段是业务模型上的单值或集合 `fileId`，例如知识文件或课件源文件。它不是附件关系的简写，必须由字段能力表达其可创建、可替换和展示规则。

二者复用同一文件生命周期，但不强行共用持久化模型。

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

当前 `FileTransferAccessService`、`FileTransferClient` 和可由业务显式注入回调的上传组件提供 MuYunFileServer 传输底座。标准 CRUD 的统一保存生命周期会在静态模型和动态记录的文件引用字段发生变化时，先确认并转正文件；若业务持久化随后失败，平台输出包含模块、记录和 fileId 的结构化 JVM 日志。

这不是静态或动态文件字段已经完成的声明。在具备同一套保存确认、替换治理和标准动作前，任何标准模块表单都不应配置 `fileTransfer` 控件。后续正式能力应将静态声明和动态元数据编译为同一份文件引用字段 descriptor，由页面运行器消费该 descriptor，而不是让控件推断业务 URL。

文件引用字段 v1 已先收敛其实体事实：它只允许绑定一个物理 `STRING` 类型的 `fileId`，可声明 MIME 类型与单文件大小约束。静态模型通过 `@FileReference` 声明，动态运行态通过 `EntityDefinition.fileReferences` 表达；两种来源已经接入统一保存生命周期，但尚未接入标准页面动作，因此不对 UI 暴露上传控件。
