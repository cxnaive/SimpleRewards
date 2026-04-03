# SimpleRewards - Minecraft 综合奖励插件

基于 Folia/Paper 的综合奖励系统插件，支持 PlaceholderAPI。

## 项目架构

```
src/main/java/dev/user/rewards/
├── SimpleRewardsPlugin.java      # 主类
├── config/
│   └── ConfigManager.java        # 配置管理
├── database/
│   ├── DatabaseManager.java      # 数据库连接池（HikariCP）
│   └── DatabaseQueue.java        # 异步数据库队列
├── economy/
│   ├── EconomyManager.java       # 经济系统（XConomy）
│   └── PlayerPointsManager.java  # 点券系统（PlayerPoints）
└── util/
    └── MessageUtil.java          # 消息工具
```

## 基础设施（已完成）

- [x] Gradle 构建配置（shadow jar + 重定位）
- [x] 数据库连接（H2 本地 / MySQL 跨服）
- [x] 异步数据库队列（DatabaseQueue）
- [x] 经济系统对接（XConomy 软依赖）
- [x] 点券系统对接（PlayerPoints 软依赖）
- [x] 配置管理（config.yml + messages.yml）
- [x] Folia 兼容（GlobalRegionScheduler）

## 数据库表

- `player_sign` - 签到记录
- `player_online` - 在线时长记录
- `reward_log` - 奖励日志

## 开发注意事项

1. **Folia 兼容**：使用 `GlobalRegionScheduler` 和 `AsyncScheduler`
2. **异步数据库**：所有数据库操作通过 `DatabaseQueue` 异步执行
3. **经济操作**：XConomy 通过 EconomyManager 异步队列执行
4. **Shadow 重定位**：H2/MySQL/HikariCP/Gson 均重定位到 `dev.user.rewards.libs`
