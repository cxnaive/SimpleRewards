package dev.user.rewards.manager;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.config.ConfigManager;
import dev.user.rewards.database.DatabaseQueue;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每周在线时长管理器
 * <ul>
 * 定时累加在线时长（内存缓存） -> 定时刷入数据库
 * 检测周重置，自动清零本周时长
 * 检测里程碑达成并发放奖励
 */
public class WeeklyOnlineManager {

    private final SimpleRewardsPlugin plugin;
    private volatile boolean running = true;
    private ScheduledTask checkTask;

    private final ConcurrentHashMap<UUID, Integer> weeklySecondsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> totalSecondsCache = new ConcurrentHashMap<>();
    /** 已领取的里程碑分钟数（线程安全 Set） */
    private final ConcurrentHashMap<UUID, Set<Integer>> claimedCache = new ConcurrentHashMap<>();
    /** 已通知过的里程碑（用于 ONCE 模式） */
    private final ConcurrentHashMap<UUID, Set<Integer>> notifiedMilestones = new ConcurrentHashMap<>();
    /** 上次提醒时间（毫秒，用于 INTERVAL 模式） */
    private final ConcurrentHashMap<UUID, Long> lastReminderTime = new ConcurrentHashMap<>();
    private volatile String currentWeekStart;

    // 离线玩家临时缓存（PAPI 查询用）
    private final ConcurrentHashMap<UUID, OfflineCacheEntry> offlineCache = new ConcurrentHashMap<>();
    private final Set<UUID> loadingOffline = ConcurrentHashMap.newKeySet();

    private static class OfflineCacheEntry {
        final int weeklySeconds;
        final long totalSeconds;
        final long expiresAt;

        OfflineCacheEntry(int weeklySeconds, long totalSeconds, long expiresAt) {
            this.weeklySeconds = weeklySeconds;
            this.totalSeconds = totalSeconds;
            this.expiresAt = expiresAt;
        }
    }

    public WeeklyOnlineManager(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        this.currentWeekStart = computeWeekStart();
        startCheckTask();
        plugin.getLogger().info("每周在线时长统计已启动，检查间隔: " +
                plugin.getConfigManager().getWeeklyOnlineCheckInterval() + "s， 周起始日: " +
                plugin.getConfigManager().getWeekStartDay());
    }

    public void shutdown() {
        running = false;
        if (checkTask != null) {
            checkTask.cancel();
        }
        flushAll();
    }

    /**
     * 重启定时任务（reload 时 check-interval 变化调用）
     */
    public void restart() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        startCheckTask();
        plugin.getLogger().info("每周在线时长统计已重启，检查间隔: " +
                plugin.getConfigManager().getWeeklyOnlineCheckInterval() + "s");
    }

    // ==================== 定时累加 ====================

    private void startCheckTask() {
        int intervalSec = plugin.getConfigManager().getWeeklyOnlineCheckInterval();
        long intervalTicks = intervalSec * 20L;

        checkTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!running) return;

            // 检测周重置
            String newWeek = computeWeekStart();
            if (!newWeek.equals(currentWeekStart)) {
                plugin.getLogger().info("检测到新的一周 (" + newWeek + ")， 重置在线时长数据");
                weeklySecondsCache.clear();
                claimedCache.clear();
                notifiedMilestones.clear();
                lastReminderTime.clear();
                offlineCache.clear();
                currentWeekStart = newWeek;
            }

            // 累加所有在线玩家
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                weeklySecondsCache.merge(uuid, intervalSec, Integer::sum);
                totalSecondsCache.merge(uuid, (long) intervalSec, Long::sum);
            }

            // 刷入数据库 + 检测里程碑
            flushAll();

            // 清理过期离线缓存
            long now = System.currentTimeMillis();
            offlineCache.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        }, intervalTicks, intervalTicks);
    }

    // ==================== 刷入数据库 ====================

    private void flushAll() {
        for (UUID uuid : Set.copyOf(weeklySecondsCache.keySet())) {
            // 原子读取：如果 onPlayerQuit 已移除则跳过，避免写入 0 覆盖 quit 的 flush
            Integer weeklySec = weeklySecondsCache.get(uuid);
            if (weeklySec == null) continue;
            Long totalSec = totalSecondsCache.get(uuid);
            if (totalSec == null) continue;
            if (weeklySec > 0 || totalSec > 0) {
                flushPlayer(uuid, weeklySec, totalSec);
            }
        }
    }

    /**
     * 刷入在线时长数据到数据库
     * 只更新时间字段，不覆盖 claimed_milestones（由 claimMilestone 独占管理）
     */
    private void flushPlayer(UUID uuid, int weeklySeconds, long totalSeconds) {
        DatabaseQueue dbQueue = plugin.getDatabaseQueue();
        String weekStart = currentWeekStart;
        boolean isMySQL = plugin.getDatabaseManager().isMySQL();

        dbQueue.submit("WeeklyOnline-Flush-" + uuid, (Connection conn) -> {
            if (isMySQL) {
                // MySQL: INSERT ON DUPLICATE KEY UPDATE，只更新时间字段
                String sql = "INSERT INTO player_weekly_online (player_uuid, week_start_date, week_online_seconds, total_online_seconds, claimed_milestones) " +
                             "VALUES (?, ?, ?, ?, '') " +
                             "ON DUPLICATE KEY UPDATE " +
                             "claimed_milestones = CASE WHEN week_start_date != VALUES(week_start_date) THEN '' ELSE claimed_milestones END, " +
                             "week_start_date = VALUES(week_start_date), " +
                             "week_online_seconds = VALUES(week_online_seconds), " +
                             "total_online_seconds = VALUES(total_online_seconds)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, weekStart);
                    ps.setInt(3, weeklySeconds);
                    ps.setLong(4, totalSeconds);
                    ps.executeUpdate();
                }
            } else {
                // H2: 先 UPDATE，无行则 INSERT
                String updateSql = "UPDATE player_weekly_online SET claimed_milestones = CASE WHEN week_start_date != ? THEN '' ELSE claimed_milestones END, week_start_date = ?, week_online_seconds = ?, total_online_seconds = ? WHERE player_uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, weekStart);
                    ps.setString(2, weekStart);
                    ps.setInt(3, weeklySeconds);
                    ps.setLong(4, totalSeconds);
                    ps.setString(5, uuid.toString());
                    int rows = ps.executeUpdate();
                    if (rows > 0) return null;
                }
                String insertSql = "INSERT INTO player_weekly_online (player_uuid, week_start_date, week_online_seconds, total_online_seconds, claimed_milestones) VALUES (?, ?, ?, ?, '')";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, weekStart);
                    ps.setInt(3, weeklySeconds);
                    ps.setLong(4, totalSeconds);
                    ps.executeUpdate();
                }
            }
            return null;
        }, result -> {
            // 回调时如果已跨周，跳过里程碑检测（避免旧周数据触发重复发放）
            if (weekStart.equals(currentWeekStart)) {
                checkMilestones(uuid, weeklySeconds);
            }
        }, error -> {
            plugin.getLogger().warning("刷入在线数据失败 [" + uuid + "]: " + error.getMessage());
        });
    }

    // ==================== 里程碑检测 ====================

    private void checkMilestones(UUID uuid, int weeklySeconds) {
        int weeklyMinutes = weeklySeconds / 60;
        Set<Integer> claimed = claimedCache.getOrDefault(uuid, Collections.emptySet());
        ConfigManager.ReminderMode reminderMode = plugin.getConfigManager().getReminderMode();

        for (ConfigManager.Milestone ms : plugin.getConfigManager().getMilestones().values()) {
            if (weeklyMinutes >= ms.getMinutes() && !claimed.contains(ms.getMinutes())) {
                if (plugin.getConfigManager().isAutoGrant()) {
                    claimMilestone(uuid, ms.getMinutes(), success -> {
                        if (!success) {
                            plugin.getLogger().warning("自动领取里程碑失败 [" + uuid + "]: " + ms.getMinutes() + " 分钟");
                        }
                    });
                } else if (shouldNotify(uuid, ms.getMinutes(), reminderMode)) {
                    // 通知玩家可以领取
                    notifyPlayer(uuid, ms);
                }
            }
        }
    }

    /**
     * 判断是否应该发送提醒
     */
    private boolean shouldNotify(UUID uuid, int milestoneMinutes, ConfigManager.ReminderMode mode) {
        if (mode == ConfigManager.ReminderMode.NONE) {
            return false;
        }

        Set<Integer> notified = notifiedMilestones.getOrDefault(uuid, Collections.emptySet());

        if (mode == ConfigManager.ReminderMode.ONCE) {
            // 仅提醒一次：未通知过则发送
            return !notified.contains(milestoneMinutes);
        }

        // INTERVAL 模式：检查间隔
        long now = System.currentTimeMillis();
        long lastTime = lastReminderTime.getOrDefault(uuid, 0L);
        int intervalMs = plugin.getConfigManager().getReminderInterval() * 60 * 1000;
        return now - lastTime >= intervalMs;
    }

    /**
     * 发送提醒并更新记录
     */
    private void notifyPlayer(UUID uuid, ConfigManager.Milestone ms) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.sendMessage(plugin.getConfigManager().getMessage("milestone-can-claim",
                        "description", ms.getDescription()));
            }
        });

        // 更新通知记录
        notifiedMilestones.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(ms.getMinutes());
        lastReminderTime.put(uuid, System.currentTimeMillis());
    }

    private void grantMilestone(UUID uuid, ConfigManager.Milestone ms) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) return;

            // 里程碑达成提示
            player.sendMessage(plugin.getConfigManager().getMessage("milestone-reached",
                    "description", ms.getDescription(),
                    "minutes", String.valueOf(ms.getMinutes())));

            // 金币奖励
            if (ms.getMoney() > 0 && plugin.getEconomyManager().isEnabled()) {
                boolean success = plugin.getEconomyManager().deposit(player, ms.getMoney());
                if (success) {
                    player.sendMessage(plugin.getConfigManager().getMessage("milestone-reward-money",
                            "money", String.format("%.0f", ms.getMoney())));
                }
            }

            // 点券奖励
            if (ms.getPoints() > 0 && plugin.getPlayerPointsManager().isEnabled()) {
                plugin.getPlayerPointsManager().givePoints(uuid, ms.getPoints());
                player.sendMessage(plugin.getConfigManager().getMessage("milestone-reward-points",
                        "points", String.valueOf(ms.getPoints())));
            }
        });

        plugin.getLogger().info("玩家 " + uuid + " 达成每周在线里程碑: " + ms.getMinutes() + " 分钟");
    }

    // ==================== 玩家加入/退出 ====================

    public void onPlayerJoin(UUID uuid) {
        offlineCache.remove(uuid); // 上线后不再需要离线缓存

        plugin.getDatabaseQueue().submit("WeeklyOnline-Load-" + uuid, (Connection conn) -> {
            String sql = "SELECT week_online_seconds, total_online_seconds, claimed_milestones, week_start_date FROM player_weekly_online WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String dbWeekStart = rs.getString("week_start_date");
                        if (currentWeekStart.equals(dbWeekStart)) {
                            // merge(sum): DB基础值 + tick已累加值，不覆盖
                            weeklySecondsCache.merge(uuid, rs.getInt("week_online_seconds"), Integer::sum);
                            claimedCache.put(uuid, parseClaimed(rs.getString("claimed_milestones")));
                        }
                        totalSecondsCache.merge(uuid, rs.getLong("total_online_seconds"), Long::sum);
                    }
                }
            }
            return null;
        });
    }

    public void onPlayerQuit(UUID uuid) {
        // 原子读取并移除，避免 flushAll 并发读取到已失效的数据
        Integer weeklySec = weeklySecondsCache.remove(uuid);
        Long totalSec = totalSecondsCache.remove(uuid);
        claimedCache.remove(uuid);

        int ws = weeklySec != null ? weeklySec : 0;
        long ts = totalSec != null ? totalSec : 0L;
        if (ws > 0 || ts > 0) {
            flushPlayer(uuid, ws, ts);
        }
    }

    // ==================== 在线玩家查询 ====================

    public int getWeeklyMinutes(UUID uuid) {
        return weeklySecondsCache.getOrDefault(uuid, 0) / 60;
    }

    public double getTotalHours(UUID uuid) {
        return totalSecondsCache.getOrDefault(uuid, 0L) / 3600.0;
    }

    public Set<Integer> getClaimedMilestones(UUID uuid) {
        return Collections.unmodifiableSet(claimedCache.getOrDefault(uuid, Collections.emptySet()));
    }

    public Set<Integer> getUnclaimedMilestones(UUID uuid) {
        int weeklyMinutes = getWeeklyMinutes(uuid);
        Set<Integer> claimed = claimedCache.getOrDefault(uuid, Collections.emptySet());
        Set<Integer> unclaimed = new HashSet<>();
        for (ConfigManager.Milestone ms : plugin.getConfigManager().getMilestones().values()) {
            if (weeklyMinutes >= ms.getMinutes() && !claimed.contains(ms.getMinutes())) {
                unclaimed.add(ms.getMinutes());
            }
        }
        return unclaimed;
    }

    /**
     * 清空所有玩家的里程碑领取记录
     */
    public void resetAllMilestones(java.util.function.Consumer<Boolean> callback) {
        // 清空内存缓存
        claimedCache.clear();

        // 清空数据库
        plugin.getDatabaseQueue().submit("ResetAllMilestones", conn -> {
            String sql = "UPDATE player_weekly_online SET claimed_milestones = ''";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        }, result -> callback.accept(true), error -> {
            plugin.getLogger().warning("清空里程碑记录失败: " + error.getMessage());
            callback.accept(false);
        });
    }

    // ==================== 离线玩家查询（PAPI 用） ====================

    public int getOfflineWeeklyMinutes(UUID uuid) {
        OfflineCacheEntry entry = offlineCache.get(uuid);
        if (entry != null && System.currentTimeMillis() < entry.expiresAt) {
            return entry.weeklySeconds / 60;
        }
        triggerOfflineLoad(uuid);
        return 0;
    }

    public double getOfflineTotalHours(UUID uuid) {
        OfflineCacheEntry entry = offlineCache.get(uuid);
        if (entry != null && System.currentTimeMillis() < entry.expiresAt) {
            return entry.totalSeconds / 3600.0;
        }
        triggerOfflineLoad(uuid);
        return 0.0;
    }

    private void triggerOfflineLoad(UUID uuid) {
        if (!loadingOffline.add(uuid)) return; // 已在加载中

        int checkInterval = plugin.getConfigManager().getWeeklyOnlineCheckInterval();
        String weekStart = currentWeekStart;
        long expiry = System.currentTimeMillis() + checkInterval * 1000L;

        plugin.getDatabaseQueue().submit("WeeklyOnline-Offline-" + uuid, (Connection conn) -> {
            String sql = "SELECT week_online_seconds, total_online_seconds, week_start_date FROM player_weekly_online WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String dbWeekStart = rs.getString("week_start_date");
                        int weeklySec = weekStart.equals(dbWeekStart) ? rs.getInt("week_online_seconds") : 0;
                        long totalSec = rs.getLong("total_online_seconds");
                        return new OfflineCacheEntry(weeklySec, totalSec, expiry);
                    }
                }
            }
            // 无数据也缓存，避免反复查询
            return new OfflineCacheEntry(0, 0, expiry);
        }, result -> {
            offlineCache.put(uuid, result);
            loadingOffline.remove(uuid);
        }, error -> {
            loadingOffline.remove(uuid);
        });
    }

    // ==================== 领取里程碑 ====================

    /**
     * 领取里程碑奖励（GUI 调用 / 自动领取）
     * 通过数据库事务确认未领取后写入，避免跨服重复领取
     */
    public void claimMilestone(UUID uuid, int milestoneMinutes, java.util.function.Consumer<Boolean> callback) {
        ConfigManager.Milestone ms = plugin.getConfigManager().getMilestones().get(milestoneMinutes);
        if (ms == null) {
            callback.accept(false);
            return;
        }

        plugin.getDatabaseQueue().submit("WeeklyOnline-Claim-" + uuid, (Connection conn) -> {
            try {
                conn.setAutoCommit(false);

                String selectSql = "SELECT claimed_milestones FROM player_weekly_online WHERE player_uuid = ? FOR UPDATE";
                Set<Integer> dbClaimed;
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            dbClaimed = parseClaimed(rs.getString("claimed_milestones"));
                        } else {
                            conn.rollback();
                            return false;
                        }
                    }
                }

                if (dbClaimed.contains(milestoneMinutes)) {
                    conn.rollback();
                    return false;
                }

                dbClaimed.add(milestoneMinutes);
                String claimedStr = dbClaimed.isEmpty() ? "" : String.join(",", dbClaimed.stream().map(String::valueOf).toList());
                String updateSql = "UPDATE player_weekly_online SET claimed_milestones = ? WHERE player_uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, claimedStr);
                    ps.setString(2, uuid.toString());
                    int rows = ps.executeUpdate();
                    if (rows == 0) {
                        conn.rollback();
                        return false;
                    }
                }

                conn.commit();
                return true;
            } catch (Exception e) {
                try { conn.rollback(); } catch (Exception ignored) {}
                plugin.getLogger().warning("领取里程碑事务失败 [" + uuid + "]: " + e.getMessage());
                return false;
            } finally {
                try { conn.setAutoCommit(true); } catch (Exception ignored) {}
            }
        }, result -> {
            boolean success = (Boolean) result;
            if (success) {
                claimedCache.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(milestoneMinutes);
                grantMilestone(uuid, ms);
            }
            callback.accept(success);
        }, error -> {
            plugin.getLogger().warning("领取里程碑失败 [" + uuid + "]: " + error.getMessage());
            callback.accept(false);
        });
    }

    // ==================== 工具方法 ====================

    private String computeWeekStart() {
        DayOfWeek startDay = plugin.getConfigManager().getWeekStartDay();
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(startDay)).toString();
    }

    private Set<Integer> parseClaimed(String str) {
        Set<Integer> set = ConcurrentHashMap.newKeySet();
        if (str == null || str.isBlank()) return set;
        for (String part : str.split(",")) {
            try {
                set.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return set;
    }
}
