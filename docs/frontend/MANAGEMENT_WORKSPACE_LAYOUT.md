# 管理工作区布局

`ManagementWorkspace` 是静态管理页中“选择区 / 树 / 列表 + 右侧主体”的轻量布局契约。它只负责列轨道和响应式边界；数据加载、选择联动、表单和动作仍由页面及现有 `RecordExplorerPanel`、`RecordDetailPanel` 负责。

## 稳定尺寸

| 语义         |  默认值 | 说明                                                           |
| ------------ | ------: | -------------------------------------------------------------- |
| 选择区列宽   | `280px` | 租户、分类、岗位等 explorer/tree/list 区使用同一列宽。         |
| 主体最小宽度 | `560px` | 详情、编辑表单和操作区的共同下限，避免双列表单控件被压出面板。 |
| 列间距       |  `12px` | 多栏管理页的统一间距。                                         |

布局以 `explorerCount` 声明选择区数量：一栏对应“选择区 + 主体”，两栏对应“树 + 列表 + 主体”，三栏对应“租户 + 树 + 列表 + 主体”。页面可以通过同名 CSS 变量做有依据的局部调整，但不应在业务页重新定义列栅格。

## 宽度与响应式规则

中等桌面宽度不压缩选择区或主体下限。工作区会保留自身最小宽度，由工作台内容区提供横向滚动，以保证内容完整、避免按钮和表单字段溢出面板。

桌面端工作区以工作台内容区的 `100%` 为高度基准，不重复估算顶栏和页签高度，避免外层出现几像素的空白滚动。`ManagementExplorerColumn` 表达选择区语义，并拉伸到该可用高度；各 explorer 继续在自身内容区滚动。右侧详情或编辑卡片保持按实际内容占高，不因选择区而被拉伸。内容超过视口时，工作区自然增高，不截断详情或编辑内容。

带常驻详情面的管理工作区在中等桌面宽度仍保留选择区和主体下限；不能为了适应较窄宿主而压缩详情表单。

`listSurface` 不含常驻详情面，主要承载“导航 explorer + 列表”场景。它在 `980px` 及以下将 explorer 与列表按可用空间弹性分配，避免工作台宿主裁切主导航；折叠 explorer 的 rail 仍由既有左侧 padding 保留，不额外占用 grid 轨道。`760px` 及以下切为上下两段，避免把导航和列表压缩到不可用。字段表单的单列降级仍由各页面或字段组件按自身语义控制。

## 使用边界

```vue
<ManagementWorkspace :explorer-count="2">
  <ManagementExplorerColumn>
    <RecordExplorerPanel title="分类">…</RecordExplorerPanel>
  </ManagementExplorerColumn>
  <ManagementExplorerColumn>
    <RecordExplorerPanel title="岗位">…</RecordExplorerPanel>
  </ManagementExplorerColumn>
  <RecordDetailPanel>…</RecordDetailPanel>
</ManagementWorkspace>
```

岗位管理是首个基准页面：租户系统用户使用三选择区，租户及以下用户使用两选择区。两栏的 `StaticManagementLayout` 同时复用这套契约；其余已有页面在调整布局时迁移，避免为了视觉统一而改变其业务交互。
