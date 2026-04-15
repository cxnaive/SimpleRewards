# SimpleRewards

Minecraft 综合奖励系统插件，基于 Paper/Folia 1.21+ 开发。

## 功能

- **每周在线时长奖励** — 自动统计玩家在线时长，达成里程碑后自动发放或通知玩家手动领取
- **自定义奖励管理** — 管理员通过 GUI 创建/编辑/删除奖励，支持过期时间、领取次数限制
- **PlaceholderAPI 支持** — 提供在线时长占位符，可接入排行榜、Tab 列表等
- **跨服支持** — MySQL 模式下多服务器共享数据
- **Folia 兼容** — 使用 GlobalRegionScheduler 和 per-player scheduler

## 依赖

| 插件 | 类型 | 说明 |
|------|------|------|
| PlaceholderAPI | 硬依赖 | 占位符支持 |
| XConomy | 软依赖 | 金币系统 |
| PlayerPoints | 软依赖 | 点券系统 |

## 安装

1. 从 [Releases](../../releases) 下载最新 jar
2. 放入服务器的 `plugins/` 目录
3. 确保已安装 PlaceholderAPI
4. 启动服务器，编辑 `plugins/SimpleRewards/config.yml`

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/rewards` | simplerewards.use | 打开每周在线奖励 GUI |
| `/rewards online` | simplerewards.use | 同上 |
| `/rewards custom` | simplerewards.use | 打开自定义奖励 GUI |
| `/rewards custom <id>` | simplerewards.use | 直接领取指定奖励 |
| `/rewards admin` | simplerewards.admin | 管理自定义奖励 |
| `/rewards lookup <玩家>` | simplerewards.lookup | 查询玩家在线时长 |
| `/rewards reset milestones` | simplerewards.admin | 清空所有里程碑领取记录 |
| `/rewards reload` | simplerewards.reload | 重载配置 |

## 占位符

| 占位符 | 说明 |
|--------|------|
| `%simplerewards_weekly_online_minutes%` | 本周在线时长（分钟） |
| `%simplerewards_weekly_online_hours%` | 本周在线时长（小时） |
| `%simplerewards_total_online_hours%` | 总在线时长（小时） |

占位符同时支持在线和离线玩家。

## 配置

### 数据库

```yaml
database:
  type: h2          # h2（本地）或 mysql（跨服）
  h2:
    filename: rewards
  mysql:
    host: localhost
    port: 3306
    database: simplerewards
    username: root
    password: password
    pool-size: 5
```

### 每周在线奖励

```yaml
weekly-online:
  enabled: true
  week-start-day: MONDAY       # 每周起始日
  check-interval: 60           # 检查间隔（秒）
  auto-grant: true             # true=自动发放, false=手动领取+通知
  reminder-mode: once          # once / interval / none（仅 auto-grant: false 生效）
  reminder-interval: 60        # 重复提醒间隔（分钟，仅 interval 模式）
  milestones:
    120:                       # 在线 120 分钟
      money: 100.0
      points: 10
      description: "本周累计在线2小时"
    300:
      money: 200.0
      points: 20
      description: "本周累计在线5小时"
```

## 构建

```bash
./gradlew shadowJar
```

构建产物在 `build/libs/` 目录下。

## 许可

MIT
