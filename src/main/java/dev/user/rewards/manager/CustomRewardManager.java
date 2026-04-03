package dev.user.rewards.manager;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.model.CustomReward;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 自定义奖励管理器
 * <ul>
 *   管理自定义奖励配置（创建/编辑/删除）
 *   处理玩家领取请求（事务防重复）
 *   内存缓存奖励配置和领取记录
 * </ul>
 */
public class CustomRewardManager {

    private final SimpleRewardsPlugin plugin;

    // 奖励配置缓存
    private final ConcurrentHashMap<String, CustomReward> rewardsCache = new ConcurrentHashMap<>();
    // 玩家领取记录缓存: UUID -> (rewardId -> claimCount)
    private final ConcurrentHashMap<UUID, Map<String, Integer>> playerClaimCache = new ConcurrentHashMap<>();
    // 定期刷新缓存任务
    private ScheduledTask refreshTask;
    // 刷新间隔（秒）
    private static final int REFRESH_INTERVAL_SECONDS = 300; // 5分钟

    public CustomRewardManager(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        loadAllRewards();
        startCacheRefresh();
        plugin.getLogger().info("自定义奖励系统已启动");
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        rewardsCache.clear();
        playerClaimCache.clear();
    }

    /**
     * 定期从数据库刷新奖励配置缓存（跨服同步）
     */
    private void startCacheRefresh() {
        refreshTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            loadAllRewards();
        }, REFRESH_INTERVAL_SECONDS * 20L, REFRESH_INTERVAL_SECONDS * 20L);
    }

    // ==================== 奖励查询 ====================

    public CustomReward getReward(String rewardId) {
        return rewardsCache.get(rewardId);
    }

    public List<CustomReward> getAllRewards() {
        return new ArrayList<>(rewardsCache.values());
    }

    /**
     * 获取玩家可领取的奖励列表
     */
    public List<CustomReward> getAvailableRewardsForPlayer(UUID uuid) {
        List<CustomReward> available = new ArrayList<>();
        Map<String, Integer> claims = playerClaimCache.getOrDefault(uuid, Collections.emptyMap());

        for (CustomReward reward : rewardsCache.values()) {
            int claimCount = claims.getOrDefault(reward.getRewardId(), 0);
            if (reward.canClaim(claimCount)) {
                available.add(reward);
            }
        }
        return available;
    }

    /**
     * 获取玩家已领取次数
     */
    public int getClaimCount(UUID uuid, String rewardId) {
        Map<String, Integer> claims = playerClaimCache.getOrDefault(uuid, Collections.emptyMap());
        return claims.getOrDefault(rewardId, 0);
    }

    /**
     * 获取玩家所有领取记录
     */
    public Map<String, Integer> getPlayerClaims(UUID uuid) {
        return new HashMap<>(playerClaimCache.getOrDefault(uuid, Collections.emptyMap()));
    }

    // ==================== 奖励管理 ====================

    /**
     * 创建新奖励
     */
    public void createReward(CustomReward reward, Consumer<Boolean> callback) {
        // 输入验证
        if (reward.getRewardId() == null || reward.getRewardId().isBlank()) {
            plugin.getLogger().warning("创建奖励失败: rewardId 不能为空");
            callback.accept(false);
            return;
        }
        if (reward.getRewardId().length() > 64) {
            plugin.getLogger().warning("创建奖励失败: rewardId 长度不能超过64字符");
            callback.accept(false);
            return;
        }
        if (reward.getMoney() < 0) {
            plugin.getLogger().warning("创建奖励失败: 金币不能为负数");
            callback.accept(false);
            return;
        }
        if (reward.getPoints() < 0) {
            plugin.getLogger().warning("创建奖励失败: 点券不能为负数");
            callback.accept(false);
            return;
        }

        final String rewardId = reward.getRewardId();

        plugin.getDatabaseQueue().submit("CustomReward-Create", conn -> {
            // 先在数据库层检查是否已存在（防止跨服竞态）
            String checkSql = "SELECT 1 FROM custom_rewards WHERE reward_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, rewardId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Boolean.FALSE; // 已存在
                }
            }

            String sql = "INSERT INTO custom_rewards (reward_id, display_name, description, money, points, " +
                         "max_claim_count, expire_at, created_at, created_by, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, reward.getRewardId());
                ps.setString(2, reward.getDisplayName());
                ps.setString(3, reward.getDescription());
                ps.setDouble(4, reward.getMoney());
                ps.setInt(5, reward.getPoints());
                ps.setInt(6, reward.getMaxClaimCount());
                ps.setLong(7, reward.getExpireAt());
                ps.setLong(8, reward.getCreatedAt());
                ps.setString(9, reward.getCreatedBy());
                ps.setBoolean(10, reward.isEnabled());
                ps.executeUpdate();
            }
            return Boolean.TRUE;
        }, result -> {
            if (result) {
                rewardsCache.put(reward.getRewardId(), reward);
            }
            callback.accept(result);
        }, error -> {
            plugin.getLogger().warning("创建奖励失败: " + error.getMessage());
            callback.accept(false);
        });
    }

    /**
     * 更新奖励配置
     */
    public void updateReward(CustomReward reward, Consumer<Boolean> callback) {
        plugin.getDatabaseQueue().submit("CustomReward-Update", conn -> {
            String sql = "UPDATE custom_rewards SET display_name = ?, description = ?, money = ?, points = ?, " +
                         "max_claim_count = ?, expire_at = ?, enabled = ? WHERE reward_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, reward.getDisplayName());
                ps.setString(2, reward.getDescription());
                ps.setDouble(3, reward.getMoney());
                ps.setInt(4, reward.getPoints());
                ps.setInt(5, reward.getMaxClaimCount());
                ps.setLong(6, reward.getExpireAt());
                ps.setBoolean(7, reward.isEnabled());
                ps.setString(8, reward.getRewardId());
                int rows = ps.executeUpdate();
                return rows > 0;
            }
        }, result -> {
            if (result) {
                rewardsCache.put(reward.getRewardId(), reward);
            }
            callback.accept(result);
        }, error -> {
            plugin.getLogger().warning("更新奖励失败: " + error.getMessage());
            callback.accept(false);
        });
    }

    /**
     * 删除奖励
     */
    public void deleteReward(String rewardId, Consumer<Boolean> callback) {
        plugin.getDatabaseQueue().submit("CustomReward-Delete", conn -> {
            try {
                conn.setAutoCommit(false);

                // 先删除领取记录
                String deleteClaims = "DELETE FROM custom_reward_claims WHERE reward_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deleteClaims)) {
                    ps.setString(1, rewardId);
                    ps.executeUpdate();
                }

                // 再删除奖励配置
                String deleteReward = "DELETE FROM custom_rewards WHERE reward_id = ?";
                int rows;
                try (PreparedStatement ps = conn.prepareStatement(deleteReward)) {
                    ps.setString(1, rewardId);
                    rows = ps.executeUpdate();
                }

                conn.commit();
                return rows > 0;
            } catch (Exception e) {
                try { conn.rollback(); } catch (Exception ignored) {}
                plugin.getLogger().warning("删除奖励事务失败: " + e.getMessage());
                return false;
            } finally {
                try { conn.setAutoCommit(true); } catch (Exception ignored) {}
            }
        }, result -> {
            if (result) {
                rewardsCache.remove(rewardId);
                // 清理玩家缓存中的记录
                for (Map<String, Integer> claims : playerClaimCache.values()) {
                    claims.remove(rewardId);
                }
            }
            callback.accept(result);
        }, error -> {
            plugin.getLogger().warning("删除奖励失败: " + error.getMessage());
            callback.accept(false);
        });
    }

    // ==================== 领取操作 ====================

    /**
     * 领取结果枚举
     */
    public enum ClaimResult {
        SUCCESS, NOT_FOUND, EXPIRED, LIMIT_REACHED, DISABLED, ERROR
    }

    /**
     * 领取奖励（事务保证）
     */
    public void claimReward(UUID uuid, String rewardId, Consumer<ClaimResult> callback) {
        CustomReward reward = rewardsCache.get(rewardId);
        if (reward == null) {
            callback.accept(ClaimResult.NOT_FOUND);
            return;
        }
        if (!reward.isEnabled()) {
            callback.accept(ClaimResult.DISABLED);
            return;
        }
        if (reward.isExpired()) {
            callback.accept(ClaimResult.EXPIRED);
            return;
        }

        // 拷贝奖励数据，防止异步回调时引用失效
        final double rewardMoney = reward.getMoney();
        final int rewardPoints = reward.getPoints();
        final String rewardDisplayName = reward.getDisplayName();

        plugin.getDatabaseQueue().submit("CustomReward-Claim-" + uuid, conn -> {
            try {
                conn.setAutoCommit(false);

                // 查询当前领取次数（加锁）
                String selectSql = "SELECT claim_count FROM custom_reward_claims " +
                                   "WHERE player_uuid = ? AND reward_id = ? FOR UPDATE";
                int currentCount = 0;
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, rewardId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            currentCount = rs.getInt("claim_count");
                        }
                    }
                }

                // 检查是否可领取（使用缓存的判断结果）
                if (currentCount >= (reward.getMaxClaimCount() < 0 ? Integer.MAX_VALUE : reward.getMaxClaimCount())) {
                    conn.rollback();
                    return ClaimResult.LIMIT_REACHED;
                }

                // 更新或插入领取记录
                long now = System.currentTimeMillis();
                if (currentCount == 0) {
                    String insertSql = "INSERT INTO custom_reward_claims " +
                        "(player_uuid, reward_id, claim_count, first_claim_at, last_claim_at) " +
                        "VALUES (?, ?, 1, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, rewardId);
                        ps.setLong(3, now);
                        ps.setLong(4, now);
                        ps.executeUpdate();
                    }
                } else {
                    String updateSql = "UPDATE custom_reward_claims " +
                        "SET claim_count = claim_count + 1, last_claim_at = ? " +
                        "WHERE player_uuid = ? AND reward_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        ps.setLong(1, now);
                        ps.setString(2, uuid.toString());
                        ps.setString(3, rewardId);
                        ps.executeUpdate();
                    }
                }

                conn.commit();
                return ClaimResult.SUCCESS;
            } catch (Exception e) {
                try { conn.rollback(); } catch (Exception ignored) {}
                plugin.getLogger().warning("领取奖励事务失败: " + e.getMessage());
                return ClaimResult.ERROR;
            } finally {
                try { conn.setAutoCommit(true); } catch (Exception ignored) {}
            }
        }, result -> {
            if (result == ClaimResult.SUCCESS) {
                // 更新内存缓存
                playerClaimCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                    .merge(rewardId, 1, Integer::sum);
                // 发放奖励（使用拷贝的数据）
                grantReward(uuid, rewardId, rewardDisplayName, rewardMoney, rewardPoints);
            }
            callback.accept(result);
        }, error -> {
            plugin.getLogger().warning("领取奖励失败: " + error.getMessage());
            callback.accept(ClaimResult.ERROR);
        });
    }

    /**
     * 发放奖励物品
     */
    private void grantReward(UUID uuid, String rewardId, String displayName, double money, int points) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) return;

            player.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-success",
                    "reward", displayName));

            // 金币
            if (money > 0 && plugin.getEconomyManager().isEnabled()) {
                boolean success = plugin.getEconomyManager().deposit(player, money);
                if (success) {
                    player.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-money",
                            "money", String.format("%.0f", money)));
                }
            }

            // 点券
            if (points > 0 && plugin.getPlayerPointsManager().isEnabled()) {
                plugin.getPlayerPointsManager().givePoints(uuid, points);
                player.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-points",
                        "points", String.valueOf(points)));
            }

            plugin.getLogger().info("玩家 " + player.getName() + " 领取自定义奖励: " + rewardId);
        });
    }

    // ==================== 玩家加入/退出 ====================

    public void onPlayerJoin(UUID uuid) {
        loadPlayerClaims(uuid);
    }

    public void onPlayerQuit(UUID uuid) {
        playerClaimCache.remove(uuid);
    }

    // ==================== 数据加载 ====================

    private void loadAllRewards() {
        plugin.getDatabaseQueue().submit("CustomReward-LoadAll", conn -> {
            String sql = "SELECT * FROM custom_rewards";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<CustomReward> rewards = new ArrayList<>();
                while (rs.next()) {
                    CustomReward reward = new CustomReward(
                            rs.getString("reward_id"),
                            rs.getString("display_name"),
                            rs.getString("description"),
                            rs.getDouble("money"),
                            rs.getInt("points"),
                            rs.getInt("max_claim_count"),
                            rs.getLong("expire_at"),
                            rs.getLong("created_at"),
                            rs.getString("created_by"),
                            rs.getBoolean("enabled")
                    );
                    rewards.add(reward);
                }
                return rewards;
            }
        }, rewards -> {
            // 清除已被其他服务器删除的奖励
            Set<String> loadedIds = new HashSet<>();
            for (CustomReward reward : rewards) {
                loadedIds.add(reward.getRewardId());
                rewardsCache.put(reward.getRewardId(), reward);
            }
            rewardsCache.keySet().retainAll(loadedIds);
            plugin.getLogger().info("已加载 " + rewards.size() + " 个自定义奖励");
        }, error -> {
            plugin.getLogger().warning("加载自定义奖励失败: " + error.getMessage());
        });
    }

    private void loadPlayerClaims(UUID uuid) {
        plugin.getDatabaseQueue().submit("CustomReward-LoadClaims-" + uuid, conn -> {
            String sql = "SELECT reward_id, claim_count FROM custom_reward_claims WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, Integer> claims = new HashMap<>();
                    while (rs.next()) {
                        claims.put(rs.getString("reward_id"), rs.getInt("claim_count"));
                    }
                    return claims;
                }
            }
        }, claims -> {
            playerClaimCache.put(uuid, new ConcurrentHashMap<>(claims));
        }, error -> {
            plugin.getLogger().warning("加载玩家领取记录失败 [" + uuid + "]: " + error.getMessage());
        });
    }
}