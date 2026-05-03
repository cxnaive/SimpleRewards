# 合并计划：ThwReward 奖励内容 → SimpleRewards

## 两个项目对比

| 维度 | SimpleRewards | ThwReward |
|------|--------------|-----------|
| 包名 | `dev.user.rewards` | `com.thw.reward` |
| 构建工具 | Gradle (shadow) | Maven (shade) |
| 数据库 | H2/MySQL + 异步队列(DatabaseQueue) | MySQL + 同步HikariCP |
| 经济 | XConomy + PlayerPoints | Vault Economy |
| 现有功能 | 每周在线奖励、自定义奖励(GUI管理)、首次加入奖励 | 龙击杀奖励、跨服招人、游戏奖励、反作弊 |
| 跨服消息 | 无 | MessageService |
| Folia | 支持 | 支持 |

## 目标

将 ThwReward 的**龙击杀奖励**功能合并到 SimpleRewards 中，使 SimpleRewards 成为唯一的奖励插件。

## 要合并的内容

### 1. 强化末影龙击杀奖励（核心功能）

**新增文件：**
- `src/main/java/dev/user/rewards/manager/DragonKillManager.java`
  - 基于 ThwReward 的 `DragonKillTracker.java`
  - 使用 SimpleRewards 的 `EconomyManager` 发放金币（替代 Vault 直接调用）
  - 使用 SimpleRewards 的 `DatabaseQueue` 异步持久化每日击杀次数（替代内存 Map）
  - 每日午夜重置（利用已有的调度机制）

**修改文件：**
- `PlayerListener.java` — 添加 `EntityDeathEvent` 监听，检测 `trueEnding_dragon_particlechecked` tag
- `SimpleRewardsPlugin.java` — 初始化 DragonKillManager，注册到每日重置
- `config.yml` — 添加 `dragon-kill` 配置段（reward / daily-limit / enabled）

**新增数据库表：**
- `dragon_kill_log` — 记录每日击杀次数（player_uuid, kill_count, date）

### 2. 跨服广播（MessageService 集成）

**新增文件：**
- `src/main/java/dev/user/rewards/integration/MessageServiceIntegration.java`
  - 从 ThwReward 移植，逻辑不变
  - 龙击杀时调用 `sendToAllServers()` 跨服广播

**修改文件：**
- `plugin.yml` — 添加 MessageService 到 softdepend

### 3. 不合并的内容

以下功能不属于本阶段目标，暂不合并：
- ~~GameRewardManager（游戏内击杀/胜负奖励）~~ — 依赖 NewPillar 插件
- ~~AntiCheatManager / NegativeGameDetector~~ — 游戏专用反作弊
- ~~RecruitmentSystem / ServerMessenger~~ — 跨服招人系统
- ~~RewardGUI~~ — ThwReward 自有的奖励领取 GUI（SimpleRewards 已有自己的 GUI 体系）

## 实现步骤

1. **DragonKillManager** — 创建管理器类，使用 DatabaseQueue 异步持久化击杀次数
2. **PlayerListener** — 添加 `EntityDeathEvent` 处理
3. **数据库表** — 在 DatabaseManager 中添加 `dragon_kill_log` 表初始化
4. **配置** — 在 config.yml 中添加 `dragon-kill` 段
5. **MessageService 集成** — 移植跨服广播（降级为 Bukkit.broadcastMessage）
6. **SimpleRewardsPlugin** — 串联初始化
7. **构建测试** — 确保编译通过

## 关键设计决策

- **经济发放**：使用 SimpleRewards 已有的 `EconomyManager.deposit()` 而非直接调用 Vault
- **数据持久化**：使用 `DatabaseQueue` 异步写入，保证 Folia 线程安全
- **每日重置**：在数据库中按日期字段区分，无需定时清内存
- **消息格式**：保持 ThwReward 原有的 `§6§l[挑战奖励]` 风格
