package dev.user.rewards.economy;

import dev.user.rewards.SimpleRewardsPlugin;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * PlayerPoints 点数管理器（软依赖）
 * 处理 PlayerPoints 插件的点数操作
 */
public class PlayerPointsManager {

    private final SimpleRewardsPlugin plugin;
    private PlayerPointsAPI playerPointsAPI;
    private boolean enabled = false;

    public PlayerPointsManager(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            if (plugin.getServer().getPluginManager().getPlugin("PlayerPoints") == null) {
                plugin.getLogger().info("PlayerPoints 插件未找到，点数功能不可用");
                return;
            }

            PlayerPoints playerPoints = PlayerPoints.getInstance();
            if (playerPoints == null) {
                plugin.getLogger().info("PlayerPoints 实例未找到，点数功能不可用");
                return;
            }

            this.playerPointsAPI = playerPoints.getAPI();
            if (this.playerPointsAPI == null) {
                plugin.getLogger().info("PlayerPoints API 未找到，点数功能不可用");
                return;
            }

            this.enabled = true;
            plugin.getLogger().info("已连接到 PlayerPoints 点数系统");
        } catch (Exception e) {
            plugin.getLogger().info("PlayerPoints 初始化失败: " + e.getMessage());
            this.enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== 同步方法 ====================

    public int getPoints(Player player) {
        if (!enabled || playerPointsAPI == null) return 0;
        return getPoints(player.getUniqueId());
    }

    public int getPoints(UUID playerUuid) {
        if (!enabled || playerPointsAPI == null) return 0;
        try {
            return playerPointsAPI.look(playerUuid);
        } catch (Exception e) {
            plugin.getLogger().warning("获取点数余额失败: " + e.getMessage());
            return 0;
        }
    }

    public boolean takePoints(Player player, int amount) {
        if (!enabled || playerPointsAPI == null) return false;
        return takePoints(player.getUniqueId(), amount);
    }

    public boolean takePoints(UUID playerUuid, int amount) {
        if (!enabled || playerPointsAPI == null) return false;
        if (amount <= 0) return true;
        try {
            return playerPointsAPI.take(playerUuid, amount);
        } catch (Exception e) {
            plugin.getLogger().warning("扣除点数失败: " + e.getMessage());
            return false;
        }
    }

    public boolean givePoints(Player player, int amount) {
        if (!enabled || playerPointsAPI == null) return false;
        return givePoints(player.getUniqueId(), amount);
    }

    public boolean givePoints(UUID playerUuid, int amount) {
        if (!enabled || playerPointsAPI == null) return false;
        if (amount <= 0) return true;
        try {
            return playerPointsAPI.give(playerUuid, amount);
        } catch (Exception e) {
            plugin.getLogger().warning("给予点数失败: " + e.getMessage());
            return false;
        }
    }

    public boolean hasEnoughPoints(Player player, int amount) {
        if (!enabled || amount <= 0) return true;
        return getPoints(player) >= amount;
    }

    public boolean hasEnoughPoints(UUID playerUuid, int amount) {
        if (!enabled || amount <= 0) return true;
        return getPoints(playerUuid) >= amount;
    }

    // ==================== 异步方法 ====================

    public void getPointsAsync(Player player, Consumer<Integer> callback) {
        getPointsAsync(player, callback, null);
    }

    public void getPointsAsync(Player player, Consumer<Integer> callback, Consumer<Exception> errorCallback) {
        if (!enabled) {
            callback.accept(0);
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            try {
                int result = getPoints(player);
                callback.accept(result);
            } catch (Exception e) {
                plugin.getLogger().warning("异步获取点数失败: " + e.getMessage());
                if (errorCallback != null) {
                    errorCallback.accept(e);
                } else {
                    callback.accept(0);
                }
            }
        });
    }

    public void takePointsAsync(Player player, int amount, Consumer<Boolean> callback) {
        takePointsAsync(player, amount, callback, null);
    }

    public void takePointsAsync(Player player, int amount, Consumer<Boolean> callback, Consumer<Exception> errorCallback) {
        if (!enabled) {
            callback.accept(false);
            return;
        }
        if (amount <= 0) {
            callback.accept(true);
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            try {
                int balance = getPoints(player);
                if (balance < amount) {
                    callback.accept(false);
                    return;
                }
                boolean result = takePoints(player, amount);
                callback.accept(result);
            } catch (Exception e) {
                plugin.getLogger().warning("异步扣除点数失败: " + e.getMessage());
                if (errorCallback != null) {
                    errorCallback.accept(e);
                } else {
                    callback.accept(false);
                }
            }
        });
    }

    public void givePointsAsync(Player player, int amount, Consumer<Boolean> callback) {
        givePointsAsync(player, amount, callback, null);
    }

    public void givePointsAsync(Player player, int amount, Consumer<Boolean> callback, Consumer<Exception> errorCallback) {
        if (!enabled) {
            callback.accept(false);
            return;
        }
        if (amount <= 0) {
            callback.accept(true);
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            try {
                boolean result = givePoints(player, amount);
                callback.accept(result);
            } catch (Exception e) {
                plugin.getLogger().warning("异步给予点数失败: " + e.getMessage());
                if (errorCallback != null) {
                    errorCallback.accept(e);
                } else {
                    callback.accept(false);
                }
            }
        });
    }

    public String format(int amount) {
        return String.format("%,d", amount);
    }

    public PlayerPointsAPI getAPI() {
        return playerPointsAPI;
    }
}
