package dev.user.rewards.manager;

import dev.user.rewards.SimpleRewardsPlugin;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幸运之柱游戏奖励管理器
 * 移植自 ThwReward 的 RewardSystem + GameRewardManager
 * 使用 SimpleRewards 的 DatabaseQueue 异步持久化，EconomyManager 发放金币
 */
public class GameRewardManager {

    private final SimpleRewardsPlugin plugin;

    // 玩家数据缓存
    private final Map<UUID, PlayerGameData> playerDataMap = new ConcurrentHashMap<>();

    // 本局游戏数据
    private final Map<UUID, GameSessionData> sessionDataMap = new ConcurrentHashMap<>();
    private int currentGameId = 0;
    private boolean gameRunning = false;
    private long gameStartTime = 0;

    public GameRewardManager(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getLogger().info("GameRewardManager 已启动（幸运之柱奖励系统）");
    }

    public void shutdown() {
        // 保存所有在线玩家数据
        for (Map.Entry<UUID, PlayerGameData> entry : playerDataMap.entrySet()) {
            savePlayerDataAsync(entry.getValue());
        }
        playerDataMap.clear();
        sessionDataMap.clear();
    }

    // ==================== 游戏事件 ====================

    public void onGameStart(int gameId) {
        this.currentGameId = gameId;
        this.gameRunning = true;
        this.gameStartTime = System.currentTimeMillis();
        this.sessionDataMap.clear();
        plugin.getLogger().info("[幸运之柱] 游戏 #" + gameId + " 开始");
    }

    public void onGameEnd(int gameId, boolean isWin, List<UUID> winners, List<UUID> participants) {
        if (this.currentGameId != gameId) return;
        this.gameRunning = false;

        int winReward = plugin.getConfig().getInt("game-reward.rewards.win", 100);
        int lossReward = plugin.getConfig().getInt("game-reward.rewards.loss", 50);
        int dailyLimit = plugin.getConfig().getInt("game-reward.limits.daily", 2500);
        int weeklyLimit = plugin.getConfig().getInt("game-reward.limits.weekly", 5000);

        for (UUID playerUuid : participants) {
            Player player = plugin.getServer().getPlayer(playerUuid);
            if (player == null) continue;

            GameSessionData session = sessionDataMap.computeIfAbsent(playerUuid,
                    uuid -> new GameSessionData(uuid, gameStartTime));
            if (session.isEliminated()) continue;

            session.setGameEndTime(System.currentTimeMillis());
            boolean isWinner = winners != null && winners.contains(playerUuid);
            int victoryReward = isWinner ? winReward : lossReward;
            session.setVictoryReward(victoryReward);
            session.setWin(isWinner);

            int totalReward = session.getKillRewards() + victoryReward;

            // 记录到待领取
            PlayerGameData data = getPlayerData(playerUuid);
            if (data.canEarn(totalReward, dailyLimit, weeklyLimit)) {
                data.addCoins(totalReward, dailyLimit, weeklyLimit);
                List<String> details = new ArrayList<>(session.getKillDetails());
                if (victoryReward > 0) {
                    details.add((isWinner ? "胜利奖励" : "失败奖励") + " +" + victoryReward);
                }
                savePendingRewardAsync(playerUuid, session.getKillRewards(), victoryReward,
                        isWinner, details, session.getGameStartTime(), session.getGameEndTime());
                savePlayerDataAsync(data);
            }

            String msg = isWinner ? "§6§l胜利！金币+%amount%" : "§7失败。金币+%amount%";
            player.sendMessage(msg.replace("%amount%", String.valueOf(victoryReward)));
            player.sendMessage("§6§l本局总计: §e" + totalReward + " §6金币");
            player.sendMessage("§7使用 §f/rewards §7查看和领取奖励");
        }

        sessionDataMap.clear();
        plugin.getLogger().info("[幸运之柱] 游戏 #" + gameId + " 结束，奖励已记录");
    }

    public void onPlayerKill(Player killer, Player victim) {
        if (!gameRunning) return;

        UUID killerUuid = killer.getUniqueId();
        int killReward = plugin.getConfig().getInt("game-reward.rewards.kill", 50);
        int killMaxPerGame = plugin.getConfig().getInt("game-reward.rewards.kill-max-per-game", 300);
        int dailyLimit = plugin.getConfig().getInt("game-reward.limits.daily", 2500);
        int weeklyLimit = plugin.getConfig().getInt("game-reward.limits.weekly", 5000);

        GameSessionData session = sessionDataMap.computeIfAbsent(killerUuid,
                uuid -> new GameSessionData(uuid, gameStartTime));

        if (session.getKillRewards() >= killMaxPerGame) {
            killer.sendMessage("§c本局击杀奖励已达上限！");
            return;
        }

        int actualReward = Math.min(killReward, killMaxPerGame - session.getKillRewards());

        PlayerGameData data = getPlayerData(killerUuid);
        if (!data.canEarn(actualReward, dailyLimit, weeklyLimit)) {
            killer.sendMessage("§c你已达到金币获取上限，本次击杀不获得金币");
            return;
        }

        session.addKillReward(actualReward, victim.getName());

        // 实时记录击杀奖励到待领取
        List<String> details = new ArrayList<>();
        details.add("击杀 " + victim.getName() + " +" + actualReward);
        data.addCoins(actualReward, dailyLimit, weeklyLimit);

        savePendingRewardAsync(killerUuid, actualReward, 0, false, details, gameStartTime, System.currentTimeMillis());
        savePlayerDataAsync(data);

        killer.sendMessage("§a击杀！金币+" + actualReward);
        killer.sendActionBar(net.kyori.adventure.text.Component.text("§6本局: " + session.getTotalReward() + " 金币"));
    }

    public void onPlayerEliminated(Player player) {
        if (!gameRunning) return;

        UUID playerUuid = player.getUniqueId();
        GameSessionData session = sessionDataMap.computeIfAbsent(playerUuid,
                uuid -> new GameSessionData(uuid, gameStartTime));

        if (session.isEliminated()) return;
        session.setEliminated(true);
        session.setGameEndTime(System.currentTimeMillis());

        int lossReward = plugin.getConfig().getInt("game-reward.rewards.loss", 50);
        int dailyLimit = plugin.getConfig().getInt("game-reward.limits.daily", 2500);
        int weeklyLimit = plugin.getConfig().getInt("game-reward.limits.weekly", 5000);

        if (lossReward > 0) {
            PlayerGameData data = getPlayerData(playerUuid);
            if (data.canEarn(lossReward, dailyLimit, weeklyLimit)) {
                data.addCoins(lossReward, dailyLimit, weeklyLimit);
                List<String> details = new ArrayList<>();
                details.add("对局失败奖励 +" + lossReward);
                savePendingRewardAsync(playerUuid, 0, lossReward, false, details,
                        session.getGameStartTime(), session.getGameEndTime());
                savePlayerDataAsync(data);
            } else {
                player.sendMessage("§c你已达到金币获取上限，失败奖励未发放");
            }
        }

        int totalKillRewards = session.getKillRewards();
        int totalReward = totalKillRewards + lossReward;
        player.sendMessage("§e你已出局！ §7本局击杀收益: §6" + totalKillRewards + " §7金币（已发放）");
        player.sendMessage("§6§l本局总计: §e" + totalReward + " §6金币");
        player.sendMessage("§7使用 §f/rewards §7查看和领取奖励");
    }

    public void onPlayerQuitGame(Player player) {
        if (!gameRunning) return;

        UUID playerUuid = player.getUniqueId();
        GameSessionData session = sessionDataMap.get(playerUuid);
        if (session == null || session.isEliminated()) return;

        // 未被淘汰的玩家提前退出，收回击杀收益
        int killRewards = session.getKillRewards();
        if (killRewards > 0) {
            PlayerGameData data = getPlayerData(playerUuid);
            data.deductCoins(killRewards);
            removePendingKillRewardsAsync(playerUuid, gameStartTime);
            savePlayerDataAsync(data);
            plugin.getLogger().info("玩家 " + player.getName() + " 未被淘汰即退出，收回击杀收益: " + killRewards);
        }
        session.clearRewards();
    }

    // ==================== 玩家数据管理 ====================

    public PlayerGameData getPlayerData(UUID uuid) {
        return playerDataMap.computeIfAbsent(uuid, this::loadPlayerDataSync);
    }

    private PlayerGameData loadPlayerDataSync(UUID uuid) {
        PlayerGameData data = new PlayerGameData(uuid);
        // 尝试从缓存获取同步加载结果
        plugin.getDatabaseQueue().submit("GameReward-Load-" + uuid, conn -> {
            String sql = "SELECT daily_earned, weekly_earned, last_reset_day, last_reset_week " +
                    "FROM player_game_rewards WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        data.dailyEarned = rs.getDouble("daily_earned");
                        data.weeklyEarned = rs.getDouble("weekly_earned");
                        data.lastResetDay = rs.getLong("last_reset_day");
                        data.lastResetWeek = rs.getLong("last_reset_week");
                    }
                }
            }
            return null;
        });
        return data;
    }

    public void loadPlayerData(UUID uuid) {
        plugin.getDatabaseQueue().submit("GameReward-Load-" + uuid, conn -> {
            PlayerGameData data = new PlayerGameData(uuid);
            String sql = "SELECT daily_earned, weekly_earned, last_reset_day, last_reset_week " +
                    "FROM player_game_rewards WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        data.dailyEarned = rs.getDouble("daily_earned");
                        data.weeklyEarned = rs.getDouble("weekly_earned");
                        data.lastResetDay = rs.getLong("last_reset_day");
                        data.lastResetWeek = rs.getLong("last_reset_week");
                    }
                }
            }
            return data;
        }, result -> playerDataMap.put(uuid, result), null);
    }

    public void onPlayerQuit(UUID uuid) {
        PlayerGameData data = playerDataMap.remove(uuid);
        if (data != null) savePlayerDataAsync(data);
    }

    // ==================== 领取奖励 ====================

    public void claimRewards(Player player, Runnable callback) {
        UUID uuid = player.getUniqueId();
        plugin.getDatabaseQueue().submit("GameReward-Claim-" + uuid, conn -> {
            // 查询未领取奖励总额
            String selectSql = "SELECT id, kill_rewards, victory_reward FROM pending_game_rewards " +
                    "WHERE player_uuid = ? AND claimed = false";
            List<Long> ids = new ArrayList<>();
            int total = 0;

            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong("id"));
                        total += rs.getInt("kill_rewards") + rs.getInt("victory_reward");
                    }
                }
            }

            if (total == 0) return 0;

            // 标记为已领取
            String updateSql;
            if (plugin.getDatabaseManager().isMySQL()) {
                updateSql = "UPDATE pending_game_rewards SET claimed = true WHERE id = ? AND claimed = false";
            } else {
                updateSql = "UPDATE pending_game_rewards SET claimed = true WHERE id = ? AND claimed = false";
            }
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                for (long id : ids) {
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            return total;
        }, total -> {
            int amount = (Integer) total;
            if (amount > 0) {
                // DB 队列线程：异步发放金币，消息调度到玩家线程
                plugin.getEconomyManager().depositAsync(player, amount, success -> {});
                player.sendMessage("§a成功领取 §6" + amount + " §a金币！");
                plugin.getLogger().info("[幸运之柱] " + player.getName() + " 领取了 " + amount + " 金币");
            }
            callback.run();
        }, error -> {
            player.sendMessage("§c领取失败，请稍后再试");
            callback.run();
        });
    }

    public void getPendingInfo(UUID uuid, PendingInfoCallback callback) {
        plugin.getDatabaseQueue().submit("GameReward-PendingInfo-" + uuid, conn -> {
            int total = 0;
            int count = 0;
            String selectSql = "SELECT kill_rewards, victory_reward FROM pending_game_rewards " +
                    "WHERE player_uuid = ? AND claimed = false";
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        total += rs.getInt("kill_rewards") + rs.getInt("victory_reward");
                        count++;
                    }
                }
            }

            PlayerGameData data = getPlayerData(uuid);
            int dailyLimit = plugin.getConfig().getInt("game-reward.limits.daily", 2500);
            int weeklyLimit = plugin.getConfig().getInt("game-reward.limits.weekly", 5000);
            return new PendingInfo(total, count, (int) data.dailyEarned, (int) data.weeklyEarned, dailyLimit, weeklyLimit);
        }, info -> callback.onResult(info), null);
    }

    public interface PendingInfoCallback {
        void onResult(PendingInfo info);
    }

    public record PendingInfo(int total, int count, int dailyEarned, int weeklyEarned, int dailyLimit, int weeklyLimit) {}

    // ==================== 异步持久化 ====================

    private void savePlayerDataAsync(PlayerGameData data) {
        plugin.getDatabaseQueue().submit("GameReward-Save-" + data.uuid, conn -> {
            String sql;
            if (plugin.getDatabaseManager().isMySQL()) {
                sql = "INSERT INTO player_game_rewards (player_uuid, daily_earned, weekly_earned, last_reset_day, last_reset_week) " +
                        "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                        "daily_earned=VALUES(daily_earned), weekly_earned=VALUES(weekly_earned), " +
                        "last_reset_day=VALUES(last_reset_day), last_reset_week=VALUES(last_reset_week)";
            } else {
                sql = "MERGE INTO player_game_rewards (player_uuid, daily_earned, weekly_earned, last_reset_day, last_reset_week) " +
                        "KEY (player_uuid) VALUES (?, ?, ?, ?, ?)";
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, data.uuid.toString());
                ps.setDouble(2, data.dailyEarned);
                ps.setDouble(3, data.weeklyEarned);
                ps.setLong(4, data.lastResetDay);
                ps.setLong(5, data.lastResetWeek);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void savePendingRewardAsync(UUID uuid, int killRewards, int victoryReward, boolean isWin,
                                        List<String> killDetails, long gameStartTime, long gameEndTime) {
        String detailsStr = String.join(";", killDetails);
        plugin.getDatabaseQueue().submit("GameReward-Pending-" + uuid, conn -> {
            String sql = "INSERT INTO pending_game_rewards (player_uuid, kill_rewards, victory_reward, is_win, details, created_at, claimed) " +
                    "VALUES (?, ?, ?, ?, ?, ?, false)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, killRewards);
                ps.setInt(3, victoryReward);
                ps.setBoolean(4, isWin);
                ps.setString(5, detailsStr);
                ps.setLong(6, gameEndTime);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void removePendingKillRewardsAsync(UUID uuid, long gameStartTime) {
        plugin.getDatabaseQueue().submit("GameReward-RemovePending-" + uuid, conn -> {
            String sql = "DELETE FROM pending_game_rewards WHERE player_uuid = ? AND victory_reward = 0 AND claimed = false";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ==================== 内部数据类 ====================

    public static class PlayerGameData {
        final UUID uuid;
        double dailyEarned;
        double weeklyEarned;
        long lastResetDay;
        long lastResetWeek;

        public PlayerGameData(UUID uuid) {
            this.uuid = uuid;
            this.lastResetDay = System.currentTimeMillis();
            this.lastResetWeek = System.currentTimeMillis();
        }

        public void checkAndResetLimits() {
            long now = System.currentTimeMillis();
            long currentDay = now / (24 * 60 * 60 * 1000);
            long lastDay = lastResetDay / (24 * 60 * 60 * 1000);
            if (currentDay > lastDay) {
                dailyEarned = 0;
                lastResetDay = now;
            }
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(lastResetWeek);
            int lastWeek = cal.get(Calendar.WEEK_OF_YEAR);
            int lastYear = cal.get(Calendar.YEAR);
            cal.setTimeInMillis(now);
            int currentWeek = cal.get(Calendar.WEEK_OF_YEAR);
            int currentYear = cal.get(Calendar.YEAR);
            if (lastYear != currentYear || lastWeek != currentWeek) {
                weeklyEarned = 0;
                lastResetWeek = now;
            }
        }

        public boolean canEarn(int amount, int dailyLimit, int weeklyLimit) {
            checkAndResetLimits();
            return dailyEarned + amount <= dailyLimit && weeklyEarned + amount <= weeklyLimit;
        }

        public void addCoins(int amount, int dailyLimit, int weeklyLimit) {
            checkAndResetLimits();
            int canAdd = Math.min(amount, dailyLimit - (int) dailyEarned);
            canAdd = Math.min(canAdd, weeklyLimit - (int) weeklyEarned);
            dailyEarned += canAdd;
            weeklyEarned += canAdd;
        }

        public void deductCoins(int amount) {
            checkAndResetLimits();
            dailyEarned = Math.max(0, dailyEarned - amount);
            weeklyEarned = Math.max(0, weeklyEarned - amount);
        }
    }

    public static class GameSessionData {
        private final UUID playerUuid;
        private int killRewards = 0;
        private int victoryReward = 0;
        private boolean isWin = false;
        private boolean eliminated = false;
        private final List<String> killDetails = new ArrayList<>();
        private final long gameStartTime;
        private long gameEndTime;

        public GameSessionData(UUID playerUuid, long gameStartTime) {
            this.playerUuid = playerUuid;
            this.gameStartTime = gameStartTime;
            this.gameEndTime = System.currentTimeMillis();
        }

        public void addKillReward(int amount, String victimName) {
            this.killRewards += amount;
            this.killDetails.add("击杀 " + victimName + " +" + amount);
        }

        public int getTotalReward() { return killRewards + victoryReward; }

        public void clearRewards() {
            this.killRewards = 0;
            this.victoryReward = 0;
            this.killDetails.clear();
        }

        public int getKillRewards() { return killRewards; }
        public int getVictoryReward() { return victoryReward; }
        public void setVictoryReward(int v) { this.victoryReward = v; }
        public boolean isWin() { return isWin; }
        public void setWin(boolean w) { isWin = w; }
        public boolean isEliminated() { return eliminated; }
        public void setEliminated(boolean e) { eliminated = e; }
        public List<String> getKillDetails() { return new ArrayList<>(killDetails); }
        public long getGameStartTime() { return gameStartTime; }
        public long getGameEndTime() { return gameEndTime; }
        public void setGameEndTime(long t) { this.gameEndTime = t; }
    }
}
