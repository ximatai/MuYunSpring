# 身份与权限错误码目录

本文只登记 IAM 对外业务错误的稳定机器语义，供 Web 特殊交互、契约测试、日志检索和未来国际化使用。它不替代 API 文档，也不收录参数解析、树结构损坏、数据库故障等技术异常。

## 使用边界

IAM 业务失败统一通过 `BusinessExceptions.warning(...)` 返回：

```json
{
  "code": "iam.employee-position.already-exists",
  "message": "该职员已存在相同任职",
  "messageArgs": {}
}
```

`code` 是稳定键；`message` 是当前中文默认回退文案；可翻译的安全参数放入 `messageArgs`。调用方不得按文案分支，也不得将记录 ID、数据库状态或异常栈拼进默认文案。完整 envelope 和前端兜底责任见[错误处理治理](../../../frontend/governance/ERROR_HANDLING_GOVERNANCE.md)。

命名统一为 `iam.<聚合>.<业务语义>`：聚合使用稳定的模块或子资源名，业务语义使用小写 kebab-case。新增错误码只有在业务规则、测试、审计或特殊 UI 需要稳定区分时才登记。

## 已收口目录

| 聚合       | 规则类别                                                       | 当前稳定错误码                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ---------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 部门       | 所属机构不可用                                                 | `iam.department.organization-not-active`                                                                                                                                                                                                                                                                                                                                                                                                   |
| 职员       | 部门与机构归属不一致                                           | `iam.employee.department-organization-mismatch`                                                                                                                                                                                                                                                                                                                                                                                            |
| 职员账号   | 职员已绑定账号                                                 | `iam.employee-account.already-bound`                                                                                                                                                                                                                                                                                                                                                                                                       |
| 任职       | 归属、引用有效性、主岗位与重复约束                             | `iam.employee-position.not-belong-to-employee`、`iam.employee-position.employee-not-active`、`iam.employee-position.organization-not-active`、`iam.employee-position.department-not-active`、`iam.employee-position.position-not-active`、`iam.employee-position.department-organization-mismatch`、`iam.employee-position.primary-owner-mismatch`、`iam.employee-position.primary-already-exists`、`iam.employee-position.already-exists` |
| 岗位分类   | 删除存在子项或岗位引用的分类                                   | `iam.position-category.delete-has-children`、`iam.position-category.delete-referenced`                                                                                                                                                                                                                                                                                                                                                     |
| 岗位       | 删除仍被任职引用的岗位                                         | `iam.position.delete-referenced`                                                                                                                                                                                                                                                                                                                                                                                                           |
| 用户与会话 | 管理当前登录账号密码、强制当前用户下线、临时密码、撤销当前会话 | `iam.user.password-admin-current-user`、`iam.user.force-logout-current-user`、`iam.user.temporary-password-unavailable`、`iam.user-session.revoke-current-denied`                                                                                                                                                                                                                                                                          |
| 角色       | 授权主体、角色形态、归属与共享、角色组成员、动作与数据范围     | `iam.role.*`（见下表）                                                                                                                                                                                                                                                                                                                                                                                                                     |

### 角色授权

角色是 IAM 中规则密度最高的聚合，保留完整目录，避免前端把多种授权失败折叠成一条笼统提示。

| 类别         | 错误码                                                                                                                                                                                                                                                                  |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 可绑定性     | `iam.role.inactive`、`platform-private-not-bindable`、`not-account-role`、`not-employment-role`、`account-grant-kind-denied`、`employment-grant-role-mismatch`、`account-grant-role-mismatch`                                                                           |
| 角色维护     | `iam.role.system-managed-mutation-denied`、`assignment-type-immutable`、`kind-immutable`、`owner-scope-type-immutable`、`owner-scope-id-immutable`                                                                                                                      |
| 归属与共享   | `iam.role.platform-management-system-context-required`、`tenant-context-required`、`owner-tenant-mismatch`、`owner-organization-tenant-mismatch`、`platform-share-policy-invalid`、`tenant-share-policy-invalid`、`organization-share-policy-invalid`                   |
| 角色组       | `iam.role.group-action-grant-denied`、`group-member-not-found`、`group-member-assignment-type-invalid`、`group-member-kind-invalid`、`group-member-inactive`、`group-data-grant-member-duplicate`                                                                       |
| 模块动作授权 | `iam.role.data-grant-duplicate`、`data-grant-required`、`employment-data-scope-required`、`account-role-data-scope-denied`、`data-grant-scope-required`、`custom-data-scope-unsupported`、`reference-dependency-unavailable`、`reference-dependency-action-unsupported` |
| 数据授权角色 | `iam.role.not-data-grant-role`、`data-grant-action-unsupported`、`data-grant-scope-required`                                                                                                                                                                            |

表格中省略 `iam.role.` 前缀的条目均以上述前缀组合为完整错误码。

## 不纳入本目录的错误

以下错误仍按平台基础契约处理，不能为个别 IAM 页面私自复制一组局部错误码：

1. 请求字段缺失、类型不符等基础参数错误。
2. 通用树的环、跨父节点排序或数据损坏等结构性错误。
3. 乐观锁冲突，统一使用平台 `CONFLICT_VERSION`。
4. 租户未开通模块所属应用，统一使用平台 `APPLICATION_NOT_OPENED`；它是模块执行前置门禁，不复制为 IAM 页面私有错误码。
5. 数据库、网络、密码哈希等技术故障。

后续新增真实 IAM 页面时，若出现需要字段定位或专属展示槽位的错误，再在对应业务契约与本目录中同步登记；不预建前端错误码映射表。
