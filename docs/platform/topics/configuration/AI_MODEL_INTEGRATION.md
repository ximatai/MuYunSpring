# 模型连接配置

## 能力定位

首期模型能力只解决受治理的模型连接与文本调用：平台或租户可配置一个 OpenAI-compatible 模型连接，业务代码通过 `AiModelGateway` 使用当前有效连接。它不包含智能体身份、工具调用、菜单代办、单据修改、元数据编排、会话记忆或模型路由。

静态与动态业务未来都只能通过来源无关的 `AiModelGateway` 访问模型；不得在 Controller、动态执行器或业务 Service 中直接持有供应商 SDK、API Key 或供应商 URL。

## 配置与作用域

`AiModelPlatformSetting` 是平台级单例，`tenantRegistrationEnabled` 默认关闭。开启后，具备既有模块动作权限的租户管理员可在当前租户维护一条模型连接；平台管理员在系统态维护平台连接。

`AiModelConfiguration` 的 `tenantId` 为空表示平台连接，当前租户值表示租户连接。每个作用域最多一条未删除配置。有效配置按以下规则解析：

1. 当前租户存在配置时，必须使用该配置；它停用、凭据缺失或调用失败时明确报错。
2. 当前租户没有配置时，使用平台配置。
3. 不存在可用的最终配置时返回 `CONFIG_MISSING`；不会静默改用其他作用域或其他供应商。

首期只支持 `OPENAI_COMPATIBLE` 协议，并固定接入 DeepSeek、阿里云百炼和本地 LM Studio。租户不能填写任意 `baseUrl`，避免把配置维护面变成内网请求入口。供应商特有参数、模型自动发现、多模型路由和降级不属于当前契约。

## 凭据保护

API Key 是写入专用字段：读取 API、模块描述符、OpenAPI 和运行审计都不返回其值，只可得知 `apiKeyConfigured`。更新时不传 Key 保留原值，传入非空值替换原值。

凭据持久化复用平台字段保护能力：写入时以 AES-GCM 加密并以 HMAC-SHA-256 生成完整性签名，读取时先解密再校验签名。生产环境必须通过部署密钥提供 Base64 编码的 16、24 或 32 字节主密钥：

```yaml
muyun:
  security:
    field-protection:
      key-base64: ${MUYUN_SECURITY_FIELD_PROTECTION_KEY_BASE64}
```

缺少该配置时，平台不允许持久化受保护的模型凭据。密钥本身不进入数据库、日志、审计或配置导出。后续如迁移到 Vault/KMS，应替换密钥提供器，不改变模型配置或调用门面。

## 调用与诊断

`AiModelGateway` 当前提供普通文本生成和 SSE 流式文本输出。适配器使用 OpenAI chat-completions 协议，统一映射消息、温度和最大输出 token；服务端不保存对话内容。

`POST /platform.ai_model_configuration/{id}/test` 是持有配置更新权限的连通性诊断。它发送固定的最小探测请求，只返回连通状态与耗时，不返回模型文本、完整供应商响应或凭据。

本地 LM Studio 可用于集成验证，使用 `LM_STUDIO_KEY` 作为请求凭据；测试或运行日志不得输出该环境变量。
