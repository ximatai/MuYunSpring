# 身份与权限 Web API

本文按当前 Controller 中已存在的 URL 梳理身份与权限相关 Web 入口，只列功能点，不写完整 OpenAPI。

受保护的静态管理入口通过 Web mixin 或动作端点进入当前用户、租户、动作权限和数据权限上下文。登录、登出和当前用户菜单入口按各自 Controller 语义处理。

## 登录与当前身份

| 方法   | URL                 | 功能                                                                                               |
| ------ | ------------------- | -------------------------------------------------------------------------------------------------- |
| `POST` | `/iam.auth/login`   | 用户登录。请求包含 `tenantId`、`username`、`password`；返回 Bearer token、当前登录 `sessionId`、签发时间和当前用户信息。 |
| `GET`  | `/iam.auth/login-context?tenantId={tenantId}` | 匿名读取 URL 锁定租户的公开登录上下文。仅返回锁定租户标识及工作台品牌投影，供标准登录页展示 Logo、主标题和副标题；目标租户必须处于启用状态。 |
| `POST` | `/iam.auth/logout`  | 当前 Bearer token 登出。token 从 `Authorization: Bearer ...` 读取。                                |
| `GET`  | `/iam.auth/context` | 返回当前请求解析出的用户上下文，用于前端会话恢复和启动态确认。                                     |
| `GET`  | `/iam.auth/tenant-branding` | 返回当前租户的工作台品牌投影（`lightLogo`、可选的 `darkLogo`）；Logo 以受限 Base64 图片 data URL 保存。 |

后续请求通过 `Authorization: Bearer <token>` 解析当前用户。服务端在 `iam_user_session` 中保存 token hash，不保存明文 token；session 使用滑动过期并受绝对过期时间约束；同一用户允许多端登录。解析成功后，Web Filter 会写入 `CurrentUserContext`；租户用户同步写入 `TenantContext`，系统用户进入系统态。登出和修改密码会撤销对应 session。

## 通用管理接口

下列模块复用通用 CRUD、启停、排序或树接口：

| 模块     | 根路径                   | 能力                           |
| -------- | ------------------------ | ------------------------------ |
| 租户     | `/iam.tenant`            | CRUD、启停、排序、系统态访问   |
| 组织机构 | `/iam.organization`      | CRUD、启停、树、树内排序       |
| 部门     | `/iam.department`        | CRUD、启停、树、树内排序       |
| 职员     | `/iam.employee`          | CRUD、启停、排序               |
| 岗位分类 | `/iam.position_category` | CRUD、启停、树、树内排序       |
| 岗位     | `/iam.position`          | CRUD、启停、排序，可挂岗位分类 |
| 用户     | `/iam.user`              | CRUD、启停、密码管理           |
| 角色     | `/iam.role`              | CRUD、启停、排序               |

当前身份权限专题没有 Controller 继承 `ReadOnlyWeb`。`ReadOnlyWeb` 的 `query`、`view` 映射用于其他专题的只读对象，不在本专题 URL 清单内。

通用接口：

| 方法   | URL                           | 功能                                              |
| ------ | ----------------------------- | ------------------------------------------------- |
| `POST` | `/{moduleAlias}/query`        | 分页查询。支持的条件以对应 Web 层和服务能力为准。 |
| `GET`  | `/{moduleAlias}/view/{id}`    | 查看单条记录。                                    |
| `POST` | `/{moduleAlias}/insert`       | 新增记录。                                        |
| `POST` | `/{moduleAlias}/update/{id}`  | 更新记录。                                        |
| `POST` | `/{moduleAlias}/delete/{id}`  | 删除记录。                                        |
| `POST` | `/{moduleAlias}/enable/{id}`  | 启用记录。                                        |
| `POST` | `/{moduleAlias}/disable/{id}` | 停用记录。                                        |
| `POST` | `/{moduleAlias}/sort/{id}`    | 同级排序；树模块也使用该路径做树内移动。          |

通用记录动作 `delete`、`enable`、`disable` 统一提交 `{"version": n}`。版本不匹配时返回 `409 CONFLICT_VERSION`，调用方不得以旧快照重试。

树模块额外接口：

| 方法  | URL                        | 功能                                                  |
| ----- | -------------------------- | ----------------------------------------------------- |
| `GET` | `/{moduleAlias}/tree`      | 读取根节点树；`flat=true` 时返回扁平列表。            |
| `GET` | `/{moduleAlias}/tree/{id}` | 读取指定节点子树；支持 `flat` 和 `includeSelf` 参数。 |

## 租户

根路径：`/iam.tenant`

| 方法   | URL                        | 功能                                     |
| ------ | -------------------------- | ---------------------------------------- |
| `POST` | `/iam.tenant/query`        | 查询租户。                               |
| `GET`  | `/iam.tenant/view/{id}`    | 查看租户。                               |
| `POST` | `/iam.tenant/insert`       | 新增租户；租户 alias 当前等同于记录 ID。 |
| `POST` | `/iam.tenant/update/{id}`  | 更新租户。                               |
| `POST` | `/iam.tenant/delete/{id}`  | 删除租户。                               |
| `POST` | `/iam.tenant/enable/{id}`  | 启用租户。                               |
| `POST` | `/iam.tenant/disable/{id}` | 停用租户。                               |
| `POST` | `/iam.tenant/sort/{id}`    | 调整租户排序。                           |

### 租户已开通应用

根路径：`/iam.tenant/{tenantId}/applications`

| 方法 | URL | 功能 |
| --- | --- | --- |
| `POST` | `/iam.tenant/{tenantId}/applications/query` | 查询租户已开通应用。 |
| `POST` | `/iam.tenant/{tenantId}/applications/configure` | 以 `applicationAliases` 全量配置租户可用应用。应用必须全局启用且允许分配给租户；`iam` 为不可移除基线应用。 |

应用开通是模块动作的前置门禁。移除应用后，普通租户用户访问该应用模块会返回 `APPLICATION_NOT_OPENED`；角色授权、菜单和配置事实保留。

## 组织机构

根路径：`/iam.organization`

| 方法   | URL                              | 功能                       |
| ------ | -------------------------------- | -------------------------- |
| `POST` | `/iam.organization/query`        | 查询机构。                 |
| `GET`  | `/iam.organization/view/{id}`    | 查看机构。                 |
| `POST` | `/iam.organization/insert`       | 新增机构。                 |
| `POST` | `/iam.organization/update/{id}`  | 更新机构。                 |
| `POST` | `/iam.organization/delete/{id}`  | 删除机构。                 |
| `POST` | `/iam.organization/enable/{id}`  | 启用机构。                 |
| `POST` | `/iam.organization/disable/{id}` | 停用机构。                 |
| `GET`  | `/iam.organization/tree`         | 读取机构树。               |
| `GET`  | `/iam.organization/tree/{id}`    | 读取指定机构子树。         |
| `POST` | `/iam.organization/sort/{id}`    | 调整机构树位置或同级顺序。 |

## 部门

根路径：`/iam.department`

| 方法   | URL                            | 功能                               |
| ------ | ------------------------------ | ---------------------------------- |
| `POST` | `/iam.department/query`        | 查询部门。                         |
| `GET`  | `/iam.department/view/{id}`    | 查看部门。                         |
| `POST` | `/iam.department/insert`       | 新增部门；必须指定所属机构。       |
| `POST` | `/iam.department/update/{id}`  | 更新部门。                         |
| `POST` | `/iam.department/delete/{id}`  | 删除部门。                         |
| `POST` | `/iam.department/enable/{id}`  | 启用部门。                         |
| `POST` | `/iam.department/disable/{id}` | 停用部门。                         |
| `GET`  | `/iam.department/tree`         | 读取部门树；查询条件可按机构过滤。 |
| `GET`  | `/iam.department/tree/{id}`    | 读取指定部门子树。                 |
| `POST` | `/iam.department/sort/{id}`    | 调整部门树位置或同级顺序。         |

## 职员

根路径：`/iam.employee`

| 方法   | URL                                                             | 功能                                                                                        |
| ------ | --------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `POST` | `/iam.employee/query`                                           | 查询职员。                                                                                  |
| `GET`  | `/iam.employee/view/{id}`                                       | 查看职员。                                                                                  |
| `POST` | `/iam.employee/insert`                                          | 新增职员；必须指定所属机构和部门。                                                          |
| `POST` | `/iam.employee/update/{id}`                                     | 更新职员基础信息。                                                                          |
| `POST` | `/iam.employee/delete/{id}`                                     | 删除职员。                                                                                  |
| `POST` | `/iam.employee/enable/{id}`                                     | 启用职员。                                                                                  |
| `POST` | `/iam.employee/disable/{id}`                                    | 停用职员。                                                                                  |
| `POST` | `/iam.employee/sort/{id}`                                       | 调整职员在部门内的排序。                                                                    |
| `GET`  | `/iam.employee/{employeeId}/account`                            | 查询职员绑定的登录账号；职员与账号当前为可选一对一关系。                                    |
| `POST` | `/iam.employee/{employeeId}/account`                            | 为职员绑定登录账号；一个职员最多绑定一个账号，一个账号最多绑定一个职员。                    |
| `POST` | `/iam.employee/{employeeId}/account/provision`                  | 基于职员档案创建登录账号并自动绑定，适合职员侧“设置账号”流程。                              |
| `POST` | `/iam.employee/{employeeId}/account/delete`                     | 删除职员账号绑定。                                                                          |
| `GET`  | `/iam.employee/{employeeId}/positions`                          | 查询职员任岗关系。                                                                          |
| `GET`  | `/iam.employee/{employeeId}/employment-view`                    | 查询包含职员、机构、部门、岗位展示信息的任职视图，供职员任职管理与列表子表使用。            |
| `POST` | `/iam.employee/{employeeId}/positions`                          | 为职员新增任岗关系。                                                                        |
| `POST` | `/iam.employee/{employeeId}/positions/{relationId}/update`      | 更新职员任岗关系。                                                                          |
| `POST` | `/iam.employee/{employeeId}/positions/{relationId}/delete`      | 删除职员任岗关系。                                                                          |
| `POST` | `/iam.employee/{employeeId}/positions/{relationId}/enable`      | 启用职员任岗关系。                                                                          |
| `POST` | `/iam.employee/{employeeId}/positions/{relationId}/disable`     | 停用职员任岗关系。                                                                          |
| `POST` | `/iam.employee/{employeeId}/positions/{relationId}/primary`     | 将指定任岗设为职员主岗，并降级同职员其他启用主岗。                                          |
| `POST` | `/iam.employee/{employeeId}/positions/{relationId}/sort`        | 调整职员任岗关系排序。                                                                      |
| `GET`  | `/iam.employee/{employeeId}/delegations`                        | 查询职员作为委托人的业务代办关系。                                                          |
| `GET`  | `/iam.employee/{employeeId}/delegated-to-me`                    | 查询职员作为受托人的业务代办关系。                                                          |
| `POST` | `/iam.employee/{employeeId}/delegations`                        | 为职员新增业务代办关系；委托人由路径职员确定，可配置有效期、任岗、模块 scope 和动作 scope。 |
| `POST` | `/iam.employee/{employeeId}/delegations/{delegationId}/update`  | 更新职员业务代办关系；普通更新不承担启停语义。                                              |
| `POST` | `/iam.employee/{employeeId}/delegations/{delegationId}/delete`  | 删除职员业务代办关系。                                                                      |
| `POST` | `/iam.employee/{employeeId}/delegations/{delegationId}/enable`  | 启用职员业务代办关系。                                                                      |
| `POST` | `/iam.employee/{employeeId}/delegations/{delegationId}/disable` | 停用职员业务代办关系。                                                                      |

静态 Web 动作入口可通过请求头声明业务代办：

| 请求头                                 | 说明                                                                                                  |
| -------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `X-MuYun-Acting-Principal-Employee-Id` | 被代办职员 ID。出现该头时，入口会校验当前登录账号是否为有效受托职员，并校验代办关系、有效期和 scope。 |
| `X-MuYun-Acting-Principal-Position-Id` | 可选，被代办任岗关系 ID；用于让被代办业务主体落到具体任岗上下文。                                     |

声明代办并命中当前模块动作后，动作权限和数据权限按被代办业务主体解释；当前登录账号仍作为实际操作者用于租户上下文和审计。

## 岗位分类

根路径：`/iam.position_category`

| 方法   | URL                                   | 功能                           |
| ------ | ------------------------------------- | ------------------------------ |
| `POST` | `/iam.position_category/query`        | 查询岗位分类。                 |
| `GET`  | `/iam.position_category/view/{id}`    | 查看岗位分类。                 |
| `POST` | `/iam.position_category/insert`       | 新增岗位分类。                 |
| `POST` | `/iam.position_category/update/{id}`  | 更新岗位分类。                 |
| `POST` | `/iam.position_category/delete/{id}`  | 删除岗位分类。                 |
| `POST` | `/iam.position_category/enable/{id}`  | 启用岗位分类。                 |
| `POST` | `/iam.position_category/disable/{id}` | 停用岗位分类。                 |
| `GET`  | `/iam.position_category/tree`         | 读取岗位分类树。               |
| `GET`  | `/iam.position_category/tree/{id}`    | 读取指定岗位分类子树。         |
| `POST` | `/iam.position_category/sort/{id}`    | 调整岗位分类树位置或同级顺序。 |

## 岗位

根路径：`/iam.position`

| 方法   | URL                          | 功能                 |
| ------ | ---------------------------- | -------------------- |
| `POST` | `/iam.position/query`        | 查询岗位标准项。     |
| `GET`  | `/iam.position/view/{id}`    | 查看岗位标准项。     |
| `POST` | `/iam.position/insert`       | 新增岗位标准项。     |
| `POST` | `/iam.position/update/{id}`  | 更新岗位标准项。     |
| `POST` | `/iam.position/delete/{id}`  | 删除岗位标准项。     |
| `POST` | `/iam.position/enable/{id}`  | 启用岗位标准项。     |
| `POST` | `/iam.position/disable/{id}` | 停用岗位标准项。     |
| `POST` | `/iam.position/sort/{id}`    | 调整岗位标准项排序。 |

## 用户

根路径：`/iam.user`

| 方法   | URL                             | 功能                                                                   |
| ------ | ------------------------------- | ---------------------------------------------------------------------- |
| `POST` | `/iam.user/query`               | 查询用户。                                                             |
| `GET`  | `/iam.user/view/{id}`           | 查看用户。                                                             |
| `POST` | `/iam.user/insert`              | 新增用户；写入密码后服务端保存密码哈希。                               |
| `POST` | `/iam.user/update/{id}`         | 更新登录账号基础状态；人员资料不在账号表维护。                         |
| `POST` | `/iam.user/delete/{id}`         | 删除用户。                                                             |
| `POST` | `/iam.user/enable/{id}`         | 启用用户。                                                             |
| `POST` | `/iam.user/disable/{id}`        | 停用用户。                                                             |
| `POST` | `/iam.user/changePassword/{id}` | 修改用户密码；成功后撤销该用户现有 session。                           |
| `POST` | `/iam.user/resetPassword/{id}`  | 重置用户密码并返回临时密码；成功后撤销该用户现有 session。             |
| `POST` | `/iam.user/forceLogout/{id}`    | 管理员强制用户全部会话下线；保留为账号级兜底动作。                    |
| `GET`  | `/iam.user/{id}/sessions`       | 查询用户当前有效登录会话；返回登录时间、最近活跃、IP、User-Agent 等。  |
| `POST` | `/iam.user/{id}/sessions/{sessionId}/revoke` | 下线用户指定登录会话；禁止通过用户管理入口下线当前会话。 |
| `POST` | `/iam.user/{id}/sessions/revoke` | 批量下线用户指定登录会话；请求体包含 `sessionIds`。                   |
| `POST` | `/iam.user/selector/query`      | 用户选择器查询；支持按角色、账号关键字和启用状态过滤，返回轻量用户项。 |

用户列表和用户绑定职员详情可返回绑定职员摘要字段；这些摘要属于用户管理入口的读模型，权限口径跟随 `iam.user` 的查询或查看入口，不额外要求调用方具备 `iam.employee` 查看权限。

## 角色、角色绑定与授权

根路径：`/iam.role`

角色授权设计见 [角色授权设计](ROLE_AUTHORIZATION_DESIGN.md)。账号授权落到 `AccountRoleGrant`，任职授权落到 `EmploymentRoleGrant`；不开放职员级角色授权和账号级通配数据范围入口。

| 方法   | URL                                                     | 功能                                                                                      |
| ------ | ------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `POST` | `/iam.role/query`                                       | 查询角色。                                                                                |
| `GET`  | `/iam.role/view/{id}`                                   | 查看角色。                                                                                |
| `POST` | `/iam.role/insert`                                      | 新增角色。                                                                                |
| `POST` | `/iam.role/update/{id}`                                 | 更新角色。                                                                                |
| `POST` | `/iam.role/delete/{id}`                                 | 删除角色。                                                                                |
| `POST` | `/iam.role/enable/{id}`                                 | 启用角色。                                                                                |
| `POST` | `/iam.role/disable/{id}`                                | 停用角色。                                                                                |
| `POST` | `/iam.role/sort/{id}`                                   | 调整角色排序。                                                                            |
| `GET`  | `/iam.role/{roleId}/account-grants`                     | 查询账号角色授权实例。                                                                    |
| `POST` | `/iam.role/{roleId}/account-grants`                     | 给用户账号授予账号角色，可携带管理作用域。                                                |
| `POST` | `/iam.role/{roleId}/account-grants/{grantId}/delete`    | 删除账号角色授权实例。                                                                    |
| `GET`  | `/iam.role/{roleId}/employment-grants`                  | 查询任职角色授权实例。                                                                    |
| `POST` | `/iam.role/{roleId}/employment-grants`                  | 给职员任职授予任职角色、角色组或数据授权角色。                                            |
| `POST` | `/iam.role/{roleId}/employment-grants/{grantId}/delete` | 删除任职角色授权实例。                                                                    |
| `POST` | `/iam.role/{roleId}/employment-selector/query`          | 分页读取可授权任职，返回职员、机构、部门、岗位和主岗信息，供角色侧“绑定任职”选择器使用。 |
| `POST` | `/iam.role/grant/{roleId}`                              | 授予角色某个 `moduleAlias + actionCode`，可携带数据权限策略、租户范围策略和引用依赖参数。 |
| `POST` | `/iam.role/grant/{roleId}/batch`                        | 批量授予角色多个模块动作；每项请求体复用单动作授权字段。                                  |
| `POST` | `/iam.role/revoke/{roleId}`                             | 撤销角色某个模块动作授权。                                                                |
| `POST` | `/iam.role/revoke/{roleId}/batch`                       | 批量撤销角色多个模块动作授权。                                                            |
| `POST` | `/iam.role/permissionMatrix/{roleId}/replace`           | 原子提交角色动作授权矩阵草稿；每项携带目标授予状态及必要的数据范围配置。                 |
| `POST` | `/iam.role/permissionMatrix/{roleId}`                   | 按模块列表返回角色授权矩阵，用于回显可授权动作和已授权状态。                              |
| `GET`  | `/iam.role/dataScopePolicyCatalog/{roleId}`             | 返回当前角色可配置的数据范围策略；传入 `moduleAlias` 时同时返回该模块可用的引用依赖候选。 |
| `GET`  | `/iam.role/menuMatrix/{roleId}/{schemeId}`              | 按菜单方案返回菜单树和角色对模块菜单的授权状态。                                          |

角色基础字段：

| 字段             | 说明                                                                                                                                                              |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `assignmentType` | 授权层级：`account` 表示账号角色，`employment` 表示任职角色。                                                                                                     |
| `roleKind`       | 角色类型：标准角色、角色组、数据授权角色或系统角色。                                                                                                              |
| `ownerScopeType` | 角色定义归属：`platform`、`tenant`、`organization`。                                                                                                              |
| `ownerScopeId`   | 归属对象 ID。平台归属为空，租户归属为租户 ID，机构归属为机构 ID。                                                                                                 |
| `ownerScopeKey`  | 服务端派生的只读归属键，用于唯一约束和诊断，不由前端写入。                                                                                                        |
| `sharePolicy`    | 共享策略：`private`、`ownerAndChildren`、`tenant`、`platform`。平台角色只允许私有或全局公开；租户角色只允许私有或租户公开；机构角色只允许私有或本机构及下级公开。 |

账号授权请求字段：

| 字段                  | 说明                                                            |
| --------------------- | --------------------------------------------------------------- |
| `userId`              | 被授权用户账号 ID。                                             |
| `managementScopeType` | 管理作用域类型：`platform`、`tenant`、`organization`。          |
| `managementScopeId`   | 管理作用域 ID。平台级可为空，租户级为租户 ID，机构级为机构 ID。 |

任职授权请求字段：

| 字段                 | 说明                  |
| -------------------- | --------------------- |
| `employeePositionId` | 被授权的职员任职 ID。 |

授权请求中常见字段：

| 字段                  | 说明                                                                                                                                                                                                             |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `moduleAlias`         | 平台模块别名，如 `iam.user`。                                                                                                                                                                                    |
| `actionCode`          | 动作编码。标准动作和配置动作最终都归到权限动作。                                                                                                                                                                 |
| `dataScopePolicy`     | 数据范围策略，JSON 使用业务 code，如 `all`、`owner`、`organizationAndChildren`、`departmentAndChildren`、`inheritDataGrant`。账号角色动作只能使用 `none`；任职角色可按动作数据权限配置具体范围或“继承数据授权”。 |
| `tenantScopePolicy`   | 租户范围策略，当前 JSON 使用业务 code，如 `currentTenant`、`allTenants`。                                                                                                                                        |
| `scopeCondition`      | 自定义条件保留字段；当前不开放可执行自定义条件授权。                                                                                                                                                             |
| `referenceFieldId`    | 引用依赖数据权限使用的、由数据范围策略目录返回的引用字段标识。                                                                                                                                                   |
| `referenceActionCode` | 引用依赖数据权限使用的目标动作；当前目录仅开放安全的 `view`。                                                                                                                                                    |

## 菜单剪枝

菜单维护本身属于平台菜单能力。身份权限相关的当前用户入口为：

| 方法  | URL                   | 功能                                                                             |
| ----- | --------------------- | -------------------------------------------------------------------------------- |
| `GET` | `/platform.menu/mine` | 返回当前用户可见菜单树。后端按当前用户推理菜单方案，并按模块 `menu` 动作做剪枝。 |

该接口不接收前端传入的菜单方案参数；剪枝只影响返回结果，不修改菜单配置。

角色菜单授权视图使用 `/iam.role/menuMatrix/{roleId}/{schemeId}`。它不引入单独的角色-菜单权限模型：模块菜单是否可见仍由角色对目标模块的 `menu` 动作授权决定。保存菜单授权时，可使用 `/iam.role/grant/{roleId}`、`/iam.role/grant/{roleId}/batch`、`/iam.role/revoke/{roleId}` 或 `/iam.role/revoke/{roleId}/batch` 维护 `menu` 动作。

## 动态与静态动作接入线索

身份权限专题的 Web API 不单独定义动态模块 URL。动态模块仍使用动态 Web 入口，静态模块仍使用各自 Controller 入口；二者通过动作上下文和授权服务共享同一套判断：

1. 静态 Web 标准接口通过 `@ActionEndpoint` 标记查询、查看、新增、更新、删除、启停、排序、树等动作。
2. 动态 Web 标准 CRUD、动作执行、动作列表、记录动作可用性、descriptor 和 OpenAPI 复用 `moduleAlias + actionCode` 授权口径。
3. 需要数据权限的入口会把数据范围合入查询条件或校验目标记录范围。

## 留给其他专题的 URL

以下 URL 会复用当前用户、租户、动作权限或数据权限上下文，但不由身份权限专题维护接口清单：

| URL 形态                                                                                                                                                                                                                                                                            | 归属         | 说明                                                                                     |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------ | ---------------------------------------------------------------------------------------- |
| `/{moduleAlias}/query`、`/{moduleAlias}/view/{id}`、`/{moduleAlias}/insert`、`/{moduleAlias}/update/{id}`、`/{moduleAlias}/delete/{id}`、`/{moduleAlias}/enable/{id}`、`/{moduleAlias}/disable/{id}`、`/{moduleAlias}/sort/{id}`、`/{moduleAlias}/tree`、`/{moduleAlias}/tree/{id}` | 动态运行态   | 动态模块标准 Web 入口；权限专题只提供授权和数据范围裁剪。                                |
| `/{moduleAlias}/actions`、`/{moduleAlias}/actions/{recordId}`、`/{moduleAlias}/{actionCode}`、`/{moduleAlias}/{actionCode}/{recordId}`、`/{moduleAlias}/{actionCode}/batch`                                                                                                         | 动态运行态   | 动态动作目录、记录动作可用性和动作执行入口；按 `moduleAlias + actionCode` 进入权限判断。 |
| `/platform.menu/{menuId}/entry`                                                                                                                                                                                                                                                     | 页面交付     | 菜单节点页面 bootstrap；会返回权限裁剪后的 descriptor 和页面配置，但不是菜单剪枝接口。   |
| `/workflow/runtime/**`、`/workflow/runtime/admin/**`                                                                                                                                                                                                                                | 工作流与任务 | 工作流实例、任务、提交审批和管理动作入口；复用当前用户上下文，接口归工作流专题。         |
