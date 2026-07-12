# Routine Bags

[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-62B47A)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-26.1.2.x-EA5C2B)](https://neoforged.net/)
[![Fabric](https://img.shields.io/badge/Fabric-Loader%200.19.3-DBD0B4)](https://fabricmc.net/)
[![Release](https://img.shields.io/github/v/release/Akanyi/RoutineBags)](https://github.com/Akanyi/RoutineBags/releases/latest)
[![License](https://img.shields.io/github/license/Akanyi/RoutineBags)](LICENSE)

把散落在多个收纳袋里的物品，合并成一个可搜索、可整理、可直接存取的统一终端。

Routine Bags 是面向 Minecraft `26.1.2` 的客户端模组，同时提供 NeoForge 与 Fabric 版本。只安装客户端即可连接原版或第三方服务器使用；如果服务器安装可选的增强组件，还可以把整理和智能存入交给服务端完成。

> Unified bundle storage terminal for Minecraft 26.1.2, available for NeoForge and Fabric. Client-only usage works on ordinary servers; optional server components provide faster, more reliable operations.

## 下载

前往 [GitHub Releases](https://github.com/Akanyi/RoutineBags/releases/latest) 下载。客户端只选择一种加载器版本，不要混装。

| 文件 | 用途 | 运行位置 |
|---|---|---|
| `routinebags-0.2.0.jar` | NeoForge 模组 | NeoForge 客户端；也可选装到 NeoForge 服务端 |
| `routinebags-fabric-0.2.0.jar` | Fabric 模组 | Fabric 客户端，需要 Fabric API |
| `routinebagkkit-0.2.0.jar` | RoutineBagkkit 插件 | Paper `26.1.2` 服务端，可选 |

## 主要功能

- **统一收纳终端**：按 `B` 打开，将玩家背包和副手中的所有收纳袋聚合到一个界面。
- **跨袋聚合计数**：同种物品只显示一个条目，并展示它在各个袋子中的来源和总数。
- **搜索与筛选**：按物品显示名称或注册名搜索，也可以只查看某一个袋子的内容。
- **跨袋取出**：左键拿一组、右键拿半组、`Shift + 左键` 直接放进玩家背包；数量不足时会自动从多个袋子凑齐。
- **跨袋存入**：光标持物点击聚合网格即可自动分配到多个有剩余容量的袋子。
- **智能整理**：合并同类物品并压缩袋子空间，支持创造模式顺序、注册名、名称和数量四种排序方式。
- **容量可视化**：显示每个收纳袋的原版重量占用，以及所有可写袋子的总容量。
- **容器界面挂载**：打开箱子、工作台等容器时，可在侧边展开轻量 RTbags 面板；不会移动或改写原容器布局。
- **只读容器浏览**：可选展示潜影盒等带 `minecraft:container` 组件的物品内容。
- **中英文界面**：内置简体中文和英文翻译。

## 兼容矩阵

| 组件 | 版本 | 必需 |
|---|---|---|
| Minecraft | `26.1.2` | 是 |
| Java | `25` | 是 |
| NeoForge | `26.1.2.x` | NeoForge 客户端需要 |
| Fabric Loader | `0.19.3` 或更高兼容版本 | Fabric 客户端需要 |
| Fabric API | `0.154.2+26.1.2` 或更高兼容版本 | Fabric 客户端需要 |
| Paper | `26.1.2` | 仅 RoutineBagkkit 需要 |

Routine Bags 通过原版数据组件识别收纳物品，而不是维护物品 ID 白名单：

- `minecraft:bundle_contents`：作为可交互的收纳袋处理。
- `minecraft:container`：作为只读容器处理；空的普通容器默认不会显示，空潜影盒仍可显示。

因此，其他模组只要复用这些原版组件，通常也能被自动识别。

## 安装

### NeoForge 客户端

1. 安装 Minecraft `26.1.2` 对应的 NeoForge。
2. 下载 `routinebags-0.2.0.jar`。
3. 将 jar 放入客户端的 `mods` 文件夹。

### Fabric 客户端

1. 安装 Fabric Loader `0.19.3` 和适用于 Minecraft `26.1.2` 的 Fabric API。
2. 下载 `routinebags-fabric-0.2.0.jar`。
3. 将 Routine Bags 和 Fabric API 一起放入客户端的 `mods` 文件夹。

### 可选服务端增强

普通服务器不需要安装任何东西。需要服务端增强时，可以选择：

- **NeoForge 服务端**：安装同一个 `routinebags-0.2.0.jar`，提供服务端整理。
- **Paper 服务端**：安装 `routinebagkkit-0.2.0.jar`，提供服务端整理和跨袋智能存入。

Fabric 专用服务端组件目前尚未提供；Fabric 客户端仍可使用完整的客户端脚本模式。

## 快速上手

1. 默认按 `B` 打开完整统一终端。
2. 在 `选项 -> 控制 -> 按键绑定 -> 收纳袋管理` 中可以修改快捷键。
3. 打开箱子或工作台时，点击侧边的 `RT` 标签展开挂载面板；再次按绑定快捷键也可以切换。
4. 配方书展开时，挂载面板会暂时隐藏，关闭配方书后自动恢复。

### 聚合终端操作

| 区域 | 操作 | 效果 |
|---|---|---|
| 聚合网格 | 空光标左键 | 跨袋拿取一组 |
| 聚合网格 | 空光标右键 | 跨袋拿取半组 |
| 聚合网格 | `Shift + 左键` | 跨袋拿取一组并放入玩家背包 |
| 聚合网格 | 光标持普通物品左键 | 跨多个袋子存入整堆 |
| 聚合网格 | 光标持普通物品右键 | 存入一个物品 |
| 玩家背包 | 左键 / 右键 | 使用原版拿取和放置语义 |
| 玩家背包 | `Shift + 左键` | 将该槽位中的物品智能存入袋子 |
| 袋子列表 | 空光标左键 | 仅显示该袋内容；再次点击取消筛选 |
| 袋子列表 | 空光标右键 | 取出当前选中的袋内条目 |
| 袋子列表 | 滚轮 | 切换收纳袋当前选中的条目 |
| 袋子列表 | 光标持物左键 | 按原版语义存入指定袋子 |

## 工作模式

### 客户端脚本模式

服务器没有安装兼容组件时，所有写操作都会拆成节流的原版容器点击：

- 不直接修改服务端物品数据。
- 不发送超出原版容器交互语义的库存操作。
- 可以通过 `stepDelayTicks` 降低高延迟服务器或反作弊插件造成的误中止。

### 服务端增强模式

客户端收到兼容服务端的能力声明后，会自动启用可用功能：

- NeoForge 服务端模组：服务端整理。
- RoutineBagkkit：服务端整理和服务端智能存入。

界面右下角会显示当前模式和能力提供者。服务端可分别关闭整理或存入能力，客户端会自动回退到本地脚本。

## 已知边界

- **潜影盒等物品形态容器只读**：原版协议没有在物品未放置时修改其内部槽位的合法交互，因此它们只参与浏览、搜索和容量统计。
- **聚合显示不改变堆叠上限**：终端可以显示跨袋总数，但底层物品堆仍遵守服务器的原版上限。
- **不可堆叠物品不可拆分容量**：工具、钓竿等通常单个占满 64 个收纳袋单位；两个各剩 32 单位的袋子仍放不下一件工具。
- **自动存入不会处理收纳袋本身**：原版允许袋套袋，但自动操作容易产生反直觉结果。Routine Bags 会拦截这条路径；需要嵌套时请使用原版槽位操作手动完成。
- **挂载面板只操作当前菜单可访问的玩家槽位**：某些特殊容器界面无法访问副手时，副手袋子不会出现在挂载面板中，但完整终端仍可使用。

## 客户端配置

- NeoForge：`config/routinebags-client.toml`
- Fabric：`config/routinebags-client.properties`

| 配置项 | 默认值 | 范围 | 说明 |
|---|---:|---:|---|
| `opsPerTick` | `2` | `1..10` | 每 tick 最多执行的模拟点击数 |
| `stepDelayTicks` | `1` | `0..10` | 脚本步骤之间等待的 tick 数 |
| `maxSortSteps` | `400` | `50..5000` | 单次整理的安全步数上限 |
| `showReadOnlyContainers` | `true` | - | 是否展示只读容器内容 |
| `mountInContainerScreens` | `true` | - | 是否在容器界面旁挂载 RTbags 面板 |
| `mountedPanelOpenByDefault` | `false` | - | 挂载面板是否默认展开 |
| `sortMode` | `BY_CREATIVE` | 枚举 | 默认排序方式 |

如果第三方服务器经常提示背包状态发生意外变化，优先将 `stepDelayTicks` 调到 `2` 或 `3`，而不是盲目提高 `opsPerTick`。

## RoutineBagkkit

Paper 插件配置位于 `plugins/RoutineBagkkit/config.yml`：

```yaml
features:
  serverSort: true
  serverStore: true

limits:
  cooldownMillis: 750
  maxBags: 36
  maxItemsPerRequest: 4096
```

权限节点默认允许所有玩家使用：

- `routinebagkkit.use`
- `routinebagkkit.sort`
- `routinebagkkit.store`

## 兼容协议

第三方服务端实现可以通过以下 custom payload 通道提供兼容能力：

| 通道 | 方向 | 内容 |
|---|---|---|
| `routinebags:hello` | 服务端 -> 客户端 | `provider`、`serverSort`、`serverStore` |
| `routinebags:sort_request` | 客户端 -> 服务端 | 排序模式序号 |
| `routinebags:sort_result` | 服务端 -> 客户端 | `success`、`moves`、`messageKey` |
| `routinebags:store_request` | 客户端 -> 服务端 | 来源 InventoryMenu 槽位 |
| `routinebags:store_result` | 服务端 -> 客户端 | `success`、`moved`、`messageKey` |

所有整数使用 Minecraft VarInt，字符串使用 VarInt 长度前缀的 UTF-8 编码。

## 从源码构建

需要 JDK `25`。

### NeoForge 与 RoutineBagkkit

```powershell
.\gradlew.bat clean build
```

产物：

- `build/libs/routinebags-<version>.jar`
- `routinebagkkit/build/libs/routinebagkkit-<version>.jar`

### Fabric

```powershell
Push-Location .\fabric
.\gradlew.bat clean build
Pop-Location
```

产物：`fabric/build/libs/routinebags-fabric-<version>.jar`

Linux 和 macOS 可将 `gradlew.bat` 替换为 `./gradlew`。

## 项目结构

```text
src/main/          NeoForge 入口、服务端实现与共享业务源码
fabric/            Fabric 平台适配和独立 Loom 构建
routinebagkkit/    Paper 服务端增强插件
```

Fabric 构建会直接复用根项目中与加载器无关的业务源码。修改共享逻辑时，请避免引入 NeoForge 或 Fabric 专属 API。

## 反馈与贡献

- Bug、兼容性问题和功能建议请提交到 [Issues](https://github.com/Akanyi/RoutineBags/issues)。
- 报告问题时请附上 Minecraft 版本、加载器及版本、服务器类型、复现步骤和相关日志。
- Pull Request 请保持改动聚焦，并至少验证受影响的 NeoForge、Fabric 或 RoutineBagkkit 构建。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
