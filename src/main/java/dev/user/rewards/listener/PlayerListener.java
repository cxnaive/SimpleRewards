package dev.user.rewards.listener;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.manager.EditSessionManager;
import dev.user.rewards.manager.EditSessionManager.EditField;
import dev.user.rewards.manager.EditSessionManager.Session;
import dev.user.rewards.util.MessageUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * 玩家事件监听器
 * 处理玩家加入/退出时的在线时长数据加载/保存
 * 处理编辑会话的聊天输入
 */
public class PlayerListener implements Listener {

    private final SimpleRewardsPlugin plugin;

    public PlayerListener(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (plugin.getWeeklyOnlineManager() != null) {
            plugin.getWeeklyOnlineManager().onPlayerJoin(uuid);
        }

        if (plugin.getCustomRewardManager() != null) {
            plugin.getCustomRewardManager().onPlayerJoin(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (plugin.getWeeklyOnlineManager() != null) {
            plugin.getWeeklyOnlineManager().onPlayerQuit(uuid);
        }

        if (plugin.getCustomRewardManager() != null) {
            plugin.getCustomRewardManager().onPlayerQuit(uuid);
        }

        // 清理编辑会话
        if (plugin.getEditSessionManager() != null) {
            plugin.getEditSessionManager().removeSession(uuid);
        }
    }

    /**
     * 处理 Paper API 的聊天事件（主处理逻辑）
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        EditSessionManager sessionManager = plugin.getEditSessionManager();

        if (sessionManager == null) return;

        Session session = sessionManager.getSession(player.getUniqueId());
        if (session == null) return;

        // 取消事件，防止消息显示给其他玩家
        event.setCancelled(true);
        event.viewers().clear(); // 清空观众列表

        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        plugin.getLogger().fine("[EditSession] 玩家 " + player.getName() + " 输入: " + message);

        // 处理取消命令
        if (message.equalsIgnoreCase("取消") || message.equalsIgnoreCase("cancel")) {
            sessionManager.removeSession(player.getUniqueId());
            MessageUtil.send(player, "&c已取消编辑");
            return;
        }

        // 根据字段类型处理输入
        EditField field = session.getField();
        switch (field) {
            case REWARD_ID -> handleRewardIdInput(player, message, session, sessionManager);
            case DISPLAY_NAME -> handleDisplayNameInput(player, message, session, sessionManager);
            case DESCRIPTION -> handleDescriptionInput(player, message, session, sessionManager);
            case MONEY -> handleMoneyInput(player, message, session, sessionManager);
            case POINTS -> handlePointsInput(player, message, session, sessionManager);
        }
    }

    /**
     * 监听旧版聊天事件（兼容性，仅取消事件）
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        EditSessionManager sessionManager = plugin.getEditSessionManager();

        if (sessionManager == null) return;

        Session session = sessionManager.getSession(player.getUniqueId());
        if (session == null) return;

        // 取消事件，清空接收者
        event.setCancelled(true);
        event.getRecipients().clear();
        // 实际处理逻辑在 AsyncChatEvent 中
    }

    /**
     * 处理奖励ID输入
     */
    private void handleRewardIdInput(Player player, String input, Session session, EditSessionManager sessionManager) {
        String cleaned = input.toLowerCase().replaceAll("[^a-z0-9_-]", "");

        if (cleaned.isEmpty()) {
            MessageUtil.send(player, "&c奖励ID无效，请使用字母、数字、下划线或连字符");
            MessageUtil.send(player, "&7请重新输入，或输入 \"取消\" 退出");
            return;
        }

        if (cleaned.length() > 64) {
            MessageUtil.send(player, "&c奖励ID长度不能超过64字符");
            MessageUtil.send(player, "&7请重新输入，或输入 \"取消\" 退出");
            return;
        }

        // 移除会话并执行回调
        sessionManager.removeSession(player.getUniqueId());
        session.getCallback().onComplete(player, cleaned);
    }

    /**
     * 处理显示名称输入
     */
    private void handleDisplayNameInput(Player player, String input, Session session, EditSessionManager sessionManager) {
        if (input.isEmpty()) {
            MessageUtil.send(player, "&c显示名称不能为空");
            MessageUtil.send(player, "&7请重新输入，或输入 \"取消\" 退出");
            return;
        }

        if (input.length() > 128) {
            MessageUtil.send(player, "&c显示名称长度不能超过128字符");
            MessageUtil.send(player, "&7请重新输入，或输入 \"取消\" 退出");
            return;
        }

        // 移除会话并执行回调
        sessionManager.removeSession(player.getUniqueId());
        session.getCallback().onComplete(player, input);
    }

    /**
     * 处理描述输入
     */
    private void handleDescriptionInput(Player player, String input, Session session, EditSessionManager sessionManager) {
        if (input.length() > 256) {
            MessageUtil.send(player, "&c描述长度不能超过256字符");
            MessageUtil.send(player, "&7请重新输入，或输入 \"取消\" 退出");
            return;
        }

        // 移除会话并执行回调
        sessionManager.removeSession(player.getUniqueId());
        session.getCallback().onComplete(player, input);
    }

    /**
     * 处理金币数量输入
     */
    private void handleMoneyInput(Player player, String input, Session session, EditSessionManager sessionManager) {
        double value;
        try {
            value = Double.parseDouble(input);
        } catch (NumberFormatException e) {
            MessageUtil.send(player, "&c请输入有效的数字");
            MessageUtil.send(player, "&7请重新输入，或输入 \"取消\" 退出");
            return;
        }

        if (value < 0) {
            MessageUtil.send(player, "&c金币数量不能为负数");
            MessageUtil.send(player, "&7请重新输入，或输入 \"取消\" 退出");
            return;
        }

        sessionManager.removeSession(player.getUniqueId());
        session.getCallback().onComplete(player, String.valueOf(value));
    }

    /**
     * 处理点券数量输入
     */
    private void handlePointsInput(Player player, String input, Session session, EditSessionManager sessionManager) {
        int value;
        try {
            value = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            MessageUtil.send(player, "&c请输入有效的整数");
            MessageUtil.send(player, "&7请重新输入，或输入 \"取消\" 退出");
            return;
        }

        if (value < 0) {
            MessageUtil.send(player, "&c点券数量不能为负数");
            MessageUtil.send(player, "&7请重新输入，或输入 \"取消\" 退出");
            return;
        }

        sessionManager.removeSession(player.getUniqueId());
        session.getCallback().onComplete(player, String.valueOf(value));
    }
}