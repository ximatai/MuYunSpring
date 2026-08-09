# MuYun Web 视觉基线

本文定义 MuYun Web 的稳定视觉取舍。配套的 [`设计规范.pen`](设计规范.pen) 是可继续维护的设计源文件；当展示画板与变量表存在差异时，以变量表为准。

本文约束视觉语义和令牌取值，不替代 `vue-ui-antdv` 的组件契约，也不因此提前冻结尚未建设的组件规格。进入具体组件实现时，应先将这里稳定的取舍映射为 adapter 内部令牌，再由业务页面通过 MuYun 组件消费。

## 1. 品牌与视觉方向

### 项目定位

- 产品类型：0-Code Platform
- 业务语气：专业、前沿、权威
- 核心表达：Engineered Clarity

### 核心原则

#### 通透

留白充足、层级清晰、背景轻而不飘。界面应先让人看懂，再让人感到高级。

#### 细腻

通过字体、线条、边界与节奏的精确控制体现质感，不依赖大面积装饰堆砌。

#### 秩序

保持栅格稳定、关系明确、主次分明。重点应通过结构被看见，而不是通过视觉噪音争夺注意力。

### 禁止方向

- 廉价：避免未经约束的高饱和色、大面积装饰和夸张效果。
- 杂乱：避免层级冲突、间距失序和不必要的视觉元素。
- 粗糙：避免不一致的边界、字体、圆角、阴影与对齐方式。
- 边框滥用：禁止通过连续描边、重复包裹或框套框表达可点击性、分组关系和视觉层级。
- 感性化发散：色彩只能服务于专业、前沿、权威的产品语气。

## 2. 色彩系统

### 使用原则

- 本节变量为当前规范值，也是颜色取值的唯一依据。
- 交互状态必须使用对应的 `hover`、`active`、`disabled`、`focus` 或 `light` 变量，不得临时计算或任意调整。
- `light` 用于轻量背景和交互状态承载，不替代主要操作色。
- 多值但未标注主题的源变量采用第一项作为当前值。
- 除明确列出的文本颜色外，不推断暗色主题颜色。

### 品牌色

| 变量                          | 当前值    | 用途                     |
| ----------------------------- | --------- | ------------------------ |
| `palette_brand_base`          | `#0052D9` | 品牌主色、主要操作       |
| `palette_brand_hover`         | `#266FE8` | 品牌色悬停状态           |
| `palette_brand_active`        | `#0034B5` | 品牌色按下或激活状态     |
| `palette_brand_disabled`      | `#BBD3FB` | 品牌色禁用背景           |
| `palette_brand_disabled_text` | `#4E72C6` | 品牌色禁用文字           |
| `palette_brand_focus`         | `#D4E3FC` | 品牌色聚焦背景或焦点提示 |
| `palette_brand_focus_text`    | `#5A7BC3` | 品牌色聚焦文字           |
| `palette_brand_light`         | `#ECF2FE` | 品牌浅色交互背景         |
| `palette_brand_light_text`    | `#6482C2` | 品牌浅色背景上的文字     |

### 错误与危险色

| 变量                           | 当前值    | 用途                 |
| ------------------------------ | --------- | -------------------- |
| `palette_danger_base`          | `#E34D59` | 错误、危险主色       |
| `palette_danger_hover`         | `#F36D78` | 错误色悬停状态       |
| `palette_danger_active`        | `#C9353F` | 错误色按下或激活状态 |
| `palette_danger_disabled`      | `#F8B9BE` | 错误色禁用背景       |
| `palette_danger_disabled_text` | `#CC6974` | 错误色禁用文字       |
| `palette_danger_focus`         | `#F9D7D9` | 错误色聚焦背景       |
| `palette_danger_focus_text`    | `#D57381` | 错误色聚焦文字       |
| `palette_danger_light`         | `#FDECEE` | 错误浅色交互背景     |
| `palette_danger_light_text`    | `#D8848F` | 错误浅色背景上的文字 |

### 警示色

| 变量                            | 当前值    | 用途                 |
| ------------------------------- | --------- | -------------------- |
| `palette_warning_base`          | `#ED7B2F` | 警示主色             |
| `palette_warning_hover`         | `#F2995F` | 警示色悬停状态       |
| `palette_warning_active`        | `#D35A21` | 警示色按下或激活状态 |
| `palette_warning_disabled`      | `#F7C797` | 警示色禁用背景       |
| `palette_warning_disabled_text` | `#D98943` | 警示色禁用文字       |
| `palette_warning_focus`         | `#F9E0C7` | 警示色聚焦背景       |
| `palette_warning_focus_text`    | `#D79353` | 警示色聚焦文字       |
| `palette_warning_light`         | `#FEF3E6` | 警示浅色交互背景     |
| `palette_warning_light_text`    | `#DE9D63` | 警示浅色背景上的文字 |

### 成功色

| 变量                            | 当前值    | 用途                 |
| ------------------------------- | --------- | -------------------- |
| `palette_success_base`          | `#00A870` | 成功主色             |
| `palette_success_hover`         | `#31C48D` | 成功色悬停状态       |
| `palette_success_active`        | `#00875A` | 成功色按下或激活状态 |
| `palette_success_disabled`      | `#A7E5CF` | 成功色禁用背景       |
| `palette_success_disabled_text` | `#2C9B77` | 成功色禁用文字       |
| `palette_success_focus`         | `#D3F3E7` | 成功色聚焦背景       |
| `palette_success_focus_text`    | `#38A383` | 成功色聚焦文字       |
| `palette_success_light`         | `#EDF9F5` | 成功浅色交互背景     |
| `palette_success_light_text`    | `#4AAA8B` | 成功浅色背景上的文字 |

### 文本色

| 变量                     | Default   | Dark      | 用途                         |
| ------------------------ | --------- | --------- | ---------------------------- |
| `palette_text_title`     | `#171F2A` | `#FFFFFF` | 标题和最高层级文字           |
| `palette_text_body`      | `#354255` | `#CCCCCC` | 正文文字                     |
| `palette_text_secondary` | `#566577` | `#B2B2B2` | 次级说明文字                 |
| `palette_text_strong`    | `#171F2A` | 未定义    | 强调文字                     |
| `palette_text_muted`     | `#566577` | 未定义    | 弱化说明文字                 |
| `palette_text_helper`    | `#7B8898` | 未定义    | 辅助与提示文字               |
| `palette_text_weaker`    | `#A1ACB8` | 未定义    | 更弱层级文字                 |
| `palette_text_kicker`    | `#68788B` | 未定义    | 眉题、阶段标签等小型提示文字 |
| `palette_text_link`      | `#0052D9` | 未定义    | 链接文字                     |
| `palette_text_inverse`   | `#FFFFFF` | 未定义    | 反色文字                     |
| `palette_text_on_dark`   | `#FFFFFF` | 未定义    | 深色背景上的文字             |

> 暗色主题只定义了标题、正文和次级说明文字。其他暗色值必须在设计规范补充后才能使用。

### 表面色

| 变量                         | 当前值    | 用途               |
| ---------------------------- | --------- | ------------------ |
| `palette_surface_page`       | `#FFFFFF` | 页面基础背景       |
| `palette_surface_base`       | `#FFFFFF` | 组件基础表面       |
| `palette_surface_canvas`     | `#F5F7FB` | 画布或应用底层背景 |
| `palette_surface_subtle`     | `#F7F9FC` | 轻量分区背景       |
| `palette_surface_elevated`   | `#FBFCFF` | 抬升表面           |
| `palette_surface_panel`      | `#FCFDFE` | 面板背景           |
| `palette_surface_disabled`   | `#F1F4F8` | 禁用表面           |
| `palette_surface_brand_tint` | `#ECF2FE` | 品牌浅色表面       |

### 边框色

| 变量                     | 当前值    | 用途           |
| ------------------------ | --------- | -------------- |
| `palette_border_default` | `#E6ECF4` | 默认边框       |
| `palette_border_soft`    | `#E6ECF4` | 柔和边框       |
| `palette_border_subtle`  | `#EEF2F7` | 最弱分隔边框   |
| `palette_border_strong`  | `#CBD5E1` | 强调边框       |
| `palette_border_focus`   | `#7AA7FF` | 聚焦边框       |
| `palette_border_brand`   | `#E8EEF9` | 品牌相关边框   |
| `palette_border_danger`  | `#F5E5E7` | 错误或危险边框 |
| `palette_border_warning` | `#F7E8D9` | 警示边框       |
| `palette_border_success` | `#E3F3EC` | 成功边框       |

> 边框变量只定义允许使用的颜色，不代表所有组件都应添加边框。是否使用边框必须遵循“边界与容器”规则。

### 图标与评审色

| 变量                     | 当前值    | 用途           |
| ------------------------ | --------- | -------------- |
| `palette_icon_primary`   | `#425266` | 主要图标       |
| `palette_icon_secondary` | `#7B8898` | 次级图标       |
| `palette_icon_disabled`  | `#B0BAC5` | 禁用图标       |
| `palette_review_text`    | `#5A6676` | 评审或批注文字 |

### 语义别名

| 变量                   | 引用                   | 当前解析值 |
| ---------------------- | ---------------------- | ---------- |
| `semantic_info_base`   | `palette_brand_base`   | `#0052D9`  |
| `semantic_info_hover`  | `palette_brand_hover`  | `#266FE8`  |
| `semantic_info_active` | `palette_brand_active` | `#0034B5`  |
| `semantic_info_light`  | `palette_brand_light`  | `#ECF2FE`  |

## 3. 字体系统

### 字体家族

| 内容类型           | 字体            | 可用字形             |
| ------------------ | --------------- | -------------------- |
| 中文与一般界面文字 | Alibaba PuHuiTi | Light、Regular、Bold |
| 数字与数据         | D-DIN           | Regular、Bold        |

本仓库不分发上述字体文件，也不通过 `@font-face` 隐式声明它们。设计源文件可以使用这些字体表达视觉意图；运行时实现必须提供系统字体回退，并在字体授权、交付方式和包体积明确后，才可以将字体作为产品资产引入。

### 字号与层级

- 正文默认且统一使用 `12px`。
- 标题字号为 `14px` 或 `16px`，只有在设计中明确指定字号时才应用。
- 未明确指定字号的标题不得自动使用 `14px` 或 `16px`，应保持默认 `12px`。
- 不得仅根据元素名称、标题标签或内容层级自动放大字号。
- 字级保持克制，优先通过字重、留白和颜色建立层级，不使用过多字号制造噪音。
- 正文使用 Regular；辅助标签可使用 500 或 600；标题和重要数据使用 Bold（700）。
- 数字、指标和强调数据优先使用 D-DIN；中文与普通界面文字使用 Alibaba PuHuiTi。
- 设计稿中的超大展示字号仅用于规范画板展示，不作为产品界面字号令牌。

## 4. 圆角系统

圆角只保留两级，不新增中间值或按页面任意变化。

| 圆角  | 适用范围                               |
| ----- | -------------------------------------- |
| `4px` | 按钮、输入框、标签、表格单元等基础组件 |
| `8px` | 卡片、分组面板、抽屉、弹窗等容器       |

## 5. 边界与容器

本节是适用于所有页面、布局和组件的全局强制规则，不是局部风格建议。

### 禁止规则

- 禁止为图标、文字、头像、状态或普通操作项默认添加独立描边容器。
- 禁止连续排列多个同等视觉权重的描边元素，形成“方框串”。
- 禁止父容器与内部元素重复描边，形成“框套框”。
- 禁止仅依赖边框表达可点击性、分组关系或视觉层级。
- 禁止让同一组中的所有控件拥有相同的描边和视觉重量，造成层级扁平化。

### 允许使用边框的场景

边框仅允许用于以下具有明确功能或结构语义的场景：

- 输入控件的可编辑边界
- 明确的选择状态
- 焦点、错误、警示或校验状态
- 必要的数据分隔
- 具有真实结构边界的容器

### 层级表达

- 相邻元素优先通过留白、对齐、间距、字体层级和表面色建立关系。
- 可点击性优先通过明确的操作语义以及 `hover`、`active`、`focus` 状态表达。
- 同一视觉层级原则上只保留一层结构边界。
- 没有明确功能或结构语义的边框必须删除。

## 6. 阴影与抬升

阴影需要表达清晰的层级抬升，但不能过软或过厚。以下参数按 `x y blur spread color` 记录，均为外阴影。

### Shadow 1 / 低层

用于卡片悬停和操作反馈，对应 Ant `shadow-1-down`。

```css
box-shadow:
  0 1px 2px -2px #00000029,
  0 3px 6px 0 #0000001f,
  0 5px 12px 4px #00000017;
```

### Shadow 2 / 中层

用于下拉菜单和跟随展开面板，对应 Ant `shadow-2-down`。

```css
box-shadow:
  0 3px 6px -4px #0000001f,
  0 6px 16px 0 #00000014,
  0 9px 28px 8px #0000000d;
```

### Shadow 3 / 高层

用于抽屉、弹窗和独立浮层，对应 Ant `shadow-3-down`。

```css
box-shadow:
  0 6px 16px -8px #00000014,
  0 9px 28px 0 #0000000d,
  0 12px 48px 16px #00000008;
```

## 7. 栅格与间距

### 栅格

- 页面使用 12 栏栅格。
- 栏距固定为 `16px`。
- 内容宽度与单栏宽度随容器自适应。

### 间距层级

| 层级      | 间距   | 用途                               |
| --------- | ------ | ---------------------------------- |
| Main      | `16px` | 页面主体的主要节奏                 |
| Compact   | `12px` | 紧凑分组和密集信息区               |
| Internal  | `8px`  | 组件内部元素间距                   |
| Exception | `5px`  | 仅限组件库已有例外，不作为通用间距 |

页面结构应优先从 `16px`、`12px`、`8px` 三档中选取间距。除既有组件库例外外，不引入新的基础间距。

## 8. 当前未定义项

本版规范未定义以下内容，不应根据画板示例自行扩展：

- 组件高度、宽度与密度规格
- 响应式断点和移动端适配规则
- 动画时长、缓动曲线和转场方式
- 图标库、图标尺寸和描边规范
- 表单、表格、导航等组件的完整状态模型
- 暗色主题中除标题、正文、次级说明外的颜色
- 无障碍对比度目标和键盘交互规范
