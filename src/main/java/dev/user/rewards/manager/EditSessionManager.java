package dev.user.rewards.manager;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.util.MessageUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编辑会话管理器
 * 管理玩家编辑自定义奖励时的多步输入流程
 */
public class EditSessionManager {

    private final SimpleRewardsPlugin plugin;

    // 玩家会话缓存: playerUuid -> Session
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    // 超时检查任务
    private ScheduledTask timeoutTask;

    // 默认超时时间（秒）
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    public EditSessionManager(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
        startTimeoutChecker();
    }

    /**
     * 启动超时检查定时任务
     */
    private void startTimeoutChecker() {
        timeoutTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            checkAndNotifyTimeouts();
        }, 20L, 20L); // 每1秒检查
    }

    /**
     * 检查并清理超时会话
     */
    private void checkAndNotifyTimeouts() {
        if (sessions.isEmpty()) return;

        long now = System.currentTimeMillis();
        long timeoutMillis = DEFAULT_TIMEOUT_SECONDS * 1000L;

        sessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            if (now - session.getCreateTime() > timeoutMillis) {
                Player player = Bukkit.getPlayer(session.getPlayerUuid());
                if (player != null && player.isOnline()) {
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                        MessageUtil.send(player, "&c编辑会话已超时，请重新操作");
                    });
                }
                return true;
            }
            return false;
        });
    }

    /**
     * 停止定时任务
     */
    public void shutdown() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
        sessions.clear();
    }

    /**
     * 创建新会话
     */
    public void createSession(Player player, EditField field, EditCallback callback) {
        Session session = new Session(
                player.getUniqueId(),
                field,
                callback,
                System.currentTimeMillis()
        );
        sessions.put(player.getUniqueId(), session);
    }

    /**
     * 获取玩家当前会话
     */
    public Session getSession(UUID playerUuid) {
        Session session = sessions.get(playerUuid);
        if (session == null) return null;

        // 检查超时
        if (System.currentTimeMillis() - session.getCreateTime() > DEFAULT_TIMEOUT_SECONDS * 1000L) {
            sessions.remove(playerUuid);
            return null;
        }
        return session;
    }

    /**
     * 检查玩家是否在会话中
     */
    public boolean hasSession(UUID playerUuid) {
        return getSession(playerUuid) != null;
    }

    /**
     * 移除会话
     */
    public void removeSession(UUID playerUuid) {
        sessions.remove(playerUuid);
    }

    /**
     * 获取会话剩余时间（秒）
     */
    public int getRemainingSeconds(UUID playerUuid) {
        Session session = sessions.get(playerUuid);
        if (session == null) return 0;
        long elapsed = System.currentTimeMillis() - session.getCreateTime();
        int remaining = DEFAULT_TIMEOUT_SECONDS - (int) (elapsed / 1000);
        return Math.max(0, remaining);
    }

    /**
     * 编辑字段类型
     */
    public enum EditField {
        REWARD_ID,      // 奖励ID（仅创建时）
        DISPLAY_NAME,   // 显示名称
        DESCRIPTION,    // 描述
        MONEY,          // 金币
        POINTS          // 点券
    }

    /**
     * 编辑回调接口
     */
    @FunctionalInterface
    public interface EditCallback {
        void onComplete(Player player, String input);
    }

    /**
     * 会话数据类
     */
    public static class Session {
        private final UUID playerUuid;
        private final EditField field;
        private final EditCallback callback;
        private final long createTime;

        public Session(UUID playerUuid, EditField field, EditCallback callback, long createTime) {
            this.playerUuid = playerUuid;
            this.field = field;
            this.callback = callback;
            this.createTime = createTime;
        }

        public UUID getPlayerUuid() { return playerUuid; }
        public EditField getField() { return field; }
        public EditCallback getCallback() { return callback; }
        public long getCreateTime() { return createTime; }
    }
}