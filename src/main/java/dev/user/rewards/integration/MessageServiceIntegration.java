package dev.user.rewards.integration;

import dev.user.rewards.SimpleRewardsPlugin;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * MessageService 跨服消息集成
 * 用于龙击杀奖励跨服广播，降级为本地广播
 * 所有 Bukkit API 调用调度到全局区域线程，保证 Folia 线程安全
 */
public class MessageServiceIntegration {

    private final SimpleRewardsPlugin plugin;
    private Object apiInstance;
    private boolean enabled = false;

    public MessageServiceIntegration(SimpleRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            var msPlugin = Bukkit.getPluginManager().getPlugin("MessageService");
            if (msPlugin == null || !msPlugin.isEnabled()) {
                plugin.getLogger().info("MessageService 未检测到，跨服广播将降级为本地广播");
                return;
            }

            Class<?> apiClass = Class.forName("com.example.messageservice.api.MessageServiceApi");
            Method getInstance = apiClass.getMethod("getInstance");
            apiInstance = getInstance.invoke(null);

            if (apiInstance == null) {
                plugin.getLogger().warning("无法获取 MessageService API 实例");
                return;
            }

            enabled = true;
            plugin.getLogger().info("MessageService 集成已启用，跨服广播可用");
        } catch (Exception e) {
            plugin.getLogger().info("MessageService 未检测到，跨服广播将降级为本地广播");
            enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled && apiInstance != null;
    }

    /**
     * 发送消息到所有服务器
     * 调度到全局区域线程执行，保证 Folia 线程安全
     */
    public void sendToAllServers(String message) {
        // 调度到全局区域线程，避免在 DB 线程或区域线程直接调用 Bukkit API
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) return;

            if (!isEnabled()) {
                Bukkit.broadcastMessage(message);
                return;
            }

            try {
                Class<?> apiClass = apiInstance.getClass();
                Class<?> displayTypeClass = Class.forName("com.example.messageservice.models.Announcement$DisplayType");
                Object chatType = displayTypeClass.getMethod("valueOf", String.class).invoke(null, "CHAT");

                String processed = message
                        .replace("§0", "&0").replace("§1", "&1").replace("§2", "&2").replace("§3", "&3")
                        .replace("§4", "&4").replace("§5", "&5").replace("§6", "&6").replace("§7", "&7")
                        .replace("§8", "&8").replace("§9", "&9").replace("§a", "&a").replace("§b", "&b")
                        .replace("§c", "&c").replace("§d", "&d").replace("§e", "&e").replace("§f", "&f")
                        .replace("§k", "&k").replace("§l", "&l").replace("§m", "&m").replace("§n", "&n")
                        .replace("§o", "&o").replace("§r", "&r");

                Method sendTemporary = apiClass.getMethod("sendTemporary", String.class, displayTypeClass, String.class, java.util.List.class);
                sendTemporary.invoke(apiInstance, processed, chatType, null, null);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "跨服消息发送失败，降级为本地广播: " + e.getMessage());
                Bukkit.broadcastMessage(message);
            }
        });
    }
}
