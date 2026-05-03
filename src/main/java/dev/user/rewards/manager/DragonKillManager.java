package dev.user.rewards.manager;

import dev.user.rewards.SimpleRewardsPlugin;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 强化末影龙击杀奖励管理器
 * 使用数据库按日期持久化每日击杀次数，内存缓存加速查询
 */
public class DragonKillManager {

    private final SimpleRewardsPlugin plugin;
    private final ConcurrentHashMap<UUID, AtomicInteger> dailyKillCache = new ConcurrentHashMap<>();
    private volatile String cachedDate;

    public DragonKillManager(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        cachedDate = today();
        plugin.getLogger().info("DragonKillManager 已启动");
    }

    public void shutdown() {
        dailyKillCache.clear();
    }

    /**
     * 玩家击杀强化末影龙时调用
     * EntityDeathEvent 在区域线程触发，所有 Bukkit API 需注意线程安全
     */
    public void onDragonKill(Player killer) {
        if (!plugin.getConfig().getBoolean("dragon-kill.enabled", true)) return;

        int dailyLimit = plugin.getConfig().getInt("dragon-kill.daily-limit", 3);
        int rewardAmount = plugin.getConfig().getInt("dragon-kill.reward", 200);

        ensureDateRefresh();
        UUID uuid = killer.getUniqueId();

        int currentKills = getKillCount(uuid);
        if (currentKills >= dailyLimit) return;

        int newCount = currentKills + 1;
        dailyKillCache.computeIfAbsent(uuid, k -> new AtomicInteger(0)).set(newCount);
        persistKillCount(uuid, newCount);

        // 异步发放奖励
        plugin.getEconomyManager().depositAsync(killer, rewardAmount, success -> {});

        // 跨服广播（内部已调度到全局区域线程）
        String broadcastMsg = "§6§l[挑战奖励] §e恭喜 §b" + killer.getName()
                + " §e击败 强化末影龙 获得 §6" + rewardAmount + "§e金币！";
        if (plugin.getMessageServiceIntegration() != null) {
            plugin.getMessageServiceIntegration().sendToAllServers(broadcastMsg);
        }

        // Player.sendMessage() 线程安全，可直接调用
        int remaining = dailyLimit - newCount;
        if (remaining > 0) {
            killer.sendMessage("§6§l[挑战奖励] §e今日还可获取 §a" + remaining + " §e次");
        } else {
            killer.sendMessage("§6§l[挑战奖励] §e今日获取机会已用完！");
        }
    }

    /**
     * 获取玩家今日击杀次数
     */
    public int getKillCount(UUID uuid) {
        ensureDateRefresh();
        AtomicInteger count = dailyKillCache.get(uuid);
        if (count != null) return count.get();

        // 数据库查询
        AtomicInteger holder = new AtomicInteger(0);
        plugin.getDatabaseQueue().submit("DragonKill-Load-" + uuid, conn -> {
            String sql = "SELECT kill_count FROM dragon_kill_log WHERE player_uuid = ? AND kill_date = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, cachedDate);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("kill_count");
                    }
                }
            }
            return 0;
        }, result -> {
            holder.set((Integer) result);
            dailyKillCache.put(uuid, new AtomicInteger((Integer) result));
        }, null);

        return holder.get();
    }

    /**
     * 异步加载玩家今日击杀次数到缓存
     */
    public void loadPlayerData(UUID uuid) {
        String date = today();
        plugin.getDatabaseQueue().submit("DragonKill-Load-" + uuid, conn -> {
            String sql = "SELECT kill_count FROM dragon_kill_log WHERE player_uuid = ? AND kill_date = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, date);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("kill_count");
                    }
                }
            }
            return 0;
        }, result -> dailyKillCache.put(uuid, new AtomicInteger(result)), null);
    }

    /**
     * 玩家退出时清理缓存
     */
    public void onPlayerQuit(UUID uuid) {
        dailyKillCache.remove(uuid);
    }

    private void persistKillCount(UUID uuid, int count) {
        String date = cachedDate;
        plugin.getDatabaseQueue().submit("DragonKill-Save-" + uuid, conn -> {
            String sql = "INSERT INTO dragon_kill_log (player_uuid, kill_date, kill_count) " +
                    "VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE kill_count = ?";
            // H2 使用 MERGE INTO
            if (!plugin.getDatabaseManager().isMySQL()) {
                sql = "MERGE INTO dragon_kill_log (player_uuid, kill_date, kill_count) " +
                        "KEY (player_uuid, kill_date) VALUES (?, ?, ?)";
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, date);
                ps.setInt(3, count);
                if (plugin.getDatabaseManager().isMySQL()) {
                    ps.setInt(4, count);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void ensureDateRefresh() {
        String today = today();
        if (!today.equals(cachedDate)) {
            cachedDate = today;
            dailyKillCache.clear();
        }
    }

    private String today() {
        return LocalDate.now().toString();
    }
}
