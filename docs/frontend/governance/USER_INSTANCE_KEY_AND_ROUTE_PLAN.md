# 用户管理页签、地址与标题契约

## 目标

用户列表、表单和页签使用不同的地址职责。列表永远是菜单页；新建、查看和编辑都是独立表单页。`InstanceKey` 只区分页签实例，不表示用户或操作。

## 改造结果

旧地址把列表和新建表单都放在 `/iam/users`，只能用 query 区分动作。工作台按菜单地址识别页签时，会把新建误判为列表，造成“点击新增后列表变成新建表单”。

现在 `/iam/users` 只承担列表菜单；`/iam/users/form` 和 `/iam/users/form/:userId` 专门承担表单。`action` 只表达新建、查看或编辑，`InstanceKey` 只区分页签实例。保存后替换当前页签的地址和标题，因此列表页签仍保留，且页签不会显示路由地址。

## 用户地址

| 场景 | 业务地址 | 必填状态 |
| --- | --- | --- |
| 用户列表 | `/iam/users` | 不携带 `action`。 |
| 新建用户 | `/iam/users/form?action=add` | 无 `userId`。 |
| 查看用户 | `/iam/users/form/:userId?action=view` | 必须有有效 `userId`。 |
| 编辑用户 | `/iam/users/form/:userId?action=edit` | 必须有有效 `userId`。 |

工作台打开页签时会自动追加 `InstanceKey=<UUID>`。因此实际打开的新建地址形如：

```text
/iam/users/form?action=add&InstanceKey=<UUID>
```

- `action` 必须显式出现，不以“缺少 action”猜测查看或编辑。
- `recordId`、`id`、`tenantId`、`userAction` 以及旧的 `/iam/users/:userId` 地址都不是受支持协议。
- 租户是用户的业务属性，不写入 URL。普通租户用户使用当前租户；系统管理员在新建表单内选择租户。

## 页签与导航

- 点击新建、查看、编辑和双击查看都使用 `openRoute` 打开独立页签；每次界面打开会得到新的 `InstanceKey`。
- 用户列表地址 `/iam/users` 不能承担新建或编辑状态，因此打开表单不会替换列表页签。
- 新建保存、编辑保存和取消编辑都使用 `replaceRoute` 切换到：

```text
/iam/users/form/<用户ID>?action=view&InstanceKey=<原UUID>
```

- `replaceRoute` 保留当前页签 key 和 `InstanceKey`，不会生成第二个用户页签。
- 新建取消关闭当前表单页签并回到用户列表；详情删除关闭当前用户页签并回到用户列表；列表删除只刷新列表，不进行地址导航。

## 页签标题

| 动作 | 打开或替换后的标题 |
| --- | --- |
| 新建 | `新建用户` |
| 查看 | `浏览用户：用户名` |
| 编辑 | `编辑用户：用户名` |
| 保存或取消编辑后查看 | `浏览用户：用户名` |

标题通过 `tabTitle` 传入工作台导航，不写入 URL，也不依赖 `_muyunTitle`。用户表单保存接口已返回用户数据，必须在 `replaceRoute` 时直接传入标题；不使用异步 `setTabName` 改名。

## 回归要求

- 用户列表、新建、查看、编辑的地址解析必须拒绝错误的 `action`、缺少 `userId`、旧 query 和无效 `InstanceKey`。
- 打开新建用户后，用户列表页签仍存在；连续新建可得到多个独立表单页签。
- 保存后确认地址为查看地址、`InstanceKey` 不变、页签 key 不变、标题为 `浏览用户：用户名`。
- 用户页签关闭后正确回到 `/iam/users`，不会复用或改变其他已打开用户页签。
