package dev.user.rewards;

import dev.user.rewards.command.RewardsCommand;
import dev.user.rewards.config.ConfigManager;
import dev.user.rewards.database.DatabaseManager;
import dev.user.rewards.database.DatabaseQueue;
import dev.user.rewards.economy.EconomyManager;
import dev.user.rewards.economy.PlayerPointsManager;
import dev.user.rewards.gui.GUIManager;
import dev.user.rewards.integration.MessageServiceIntegration;
import dev.user.rewards.listener.GUIListener;
import dev.user.rewards.listener.PlayerListener;
import dev.user.rewards.manager.CustomRewardManager;
import dev.user.rewards.manager.DragonKillManager;
import dev.user.rewards.manager.EditSessionManager;
import dev.user.rewards.manager.GameRewardManager;
import dev.user.rewards.manager.WeeklyOnlineManager;
import dev.user.rewards.placeholder.RewardsExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SimpleRewards 插件主类
 * 综合奖励系统
 */
public class SimpleRewardsPlugin extends JavaPlugin {

    private static SimpleRewardsPlugin instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private DatabaseQueue databaseQueue;
    private EconomyManager economyManager;
    private PlayerPointsManager playerPointsManager;
    private WeeklyOnlineManager weeklyOnlineManager;
    private CustomRewardManager customRewardManager;
    private EditSessionManager editSessionManager;
    private DragonKillManager dragonKillManager;
    private GameRewardManager gameRewardManager;
    private MessageServiceIntegration messageServiceIntegration;
    private RewardsExpansion rewardsExpansion;

    private int lastCheckInterval;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        configManager.load();

        this.databaseManager = new DatabaseManager(this);
        if (!databaseManager.init()) {
            getLogger().severe("数据库初始化失败，插件将禁用！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (configManager.getDatabaseType().equalsIgnoreCase("h2")) {
            getLogger().warning("============================================");
            getLogger().warning("当前使用 H2 数据库（本地文件模式）");
            getLogger().warning("H2 不支持多服务器同时访问！");
            getLogger().warning("如需跨服部署，请改用 MySQL 数据库");
            getLogger().warning("============================================");
        } else {
            getLogger().info("使用 MySQL 数据库， 攌持跨服部署");
        }

        this.databaseQueue = new DatabaseQueue(this);

        this.economyManager = new EconomyManager(this);
        economyManager.init();

        this.playerPointsManager = new PlayerPointsManager(this);
        playerPointsManager.init();

        // 每周在线时长管理器
        if (configManager.isWeeklyOnlineEnabled()) {
            this.weeklyOnlineManager = new WeeklyOnlineManager(this);
            weeklyOnlineManager.start();
            lastCheckInterval = configManager.getWeeklyOnlineCheckInterval();

            for (Player player : getServer().getOnlinePlayers()) {
                weeklyOnlineManager.onPlayerJoin(player.getUniqueId());
            }
        }

        // 自定义奖励管理器
        this.customRewardManager = new CustomRewardManager(this);
        customRewardManager.start();
        for (Player player : getServer().getOnlinePlayers()) {
            customRewardManager.onPlayerJoin(player.getUniqueId());
        }

        // 编辑会话管理器
        this.editSessionManager = new EditSessionManager(this);

        // 强化末影龙击杀奖励
        this.dragonKillManager = new DragonKillManager(this);
        dragonKillManager.start();

        // 幸运之柱游戏奖励
        if (getConfig().getBoolean("game-reward.enabled", true)) {
            this.gameRewardManager = new GameRewardManager(this);
            gameRewardManager.start();
        }

        // 跨服消息集成
        this.messageServiceIntegration = new MessageServiceIntegration(this);
        messageServiceIntegration.init();

        // 加载在线玩家数据
        for (Player player : getServer().getOnlinePlayers()) {
            if (gameRewardManager != null) {
                gameRewardManager.loadPlayerData(player.getUniqueId());
            }
        }

        // 事件监听器（始终注册，编辑会话和自定义奖励也需要）
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIListener(), this);

        // 注册命令
        RewardsCommand cmdExecutor = new RewardsCommand(this);
        getCommand("rewards").setExecutor(cmdExecutor);
        getCommand("rewards").setTabCompleter(cmdExecutor);

        // PlaceholderAPI 扩展（硬依赖）
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.rewardsExpansion = new RewardsExpansion(this);
            if (rewardsExpansion.register()) {
                getLogger().info("PlaceholderAPI 扩展已注册！");
            } else {
                getLogger().severe("PlaceholderAPI 扩展注册失败！");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        } else {
            getLogger().severe("未找到 PlaceholderAPI，插件无法运行！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("SimpleRewards 插件已启用！");
    }

    @Override
    public void onDisable() {
        if (rewardsExpansion != null) {
            rewardsExpansion.unregister();
        }

        if (weeklyOnlineManager != null) {
            weeklyOnlineManager.shutdown();
        }

        if (customRewardManager != null) {
            customRewardManager.shutdown();
        }

        if (editSessionManager != null) {
            editSessionManager.shutdown();
        }

        if (dragonKillManager != null) {
            dragonKillManager.shutdown();
        }

        if (gameRewardManager != null) {
            gameRewardManager.shutdown();
        }

        if (economyManager != null) {
            economyManager.shutdown();
        }

        if (databaseQueue != null) {
            databaseQueue.shutdown();
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("SimpleRewards 插件已禁用！");
        instance = null;
    }

    public void reload() {
        configManager.load();

        // 如果 check-interval 变化，重启定时任务
        if (weeklyOnlineManager != null) {
            int newInterval = configManager.getWeeklyOnlineCheckInterval();
            if (newInterval != lastCheckInterval) {
                weeklyOnlineManager.restart();
                lastCheckInterval = newInterval;
            }
        }

        getLogger().info("配置已重载！");
    }

    // ==================== Getters ====================

    public static SimpleRewardsPlugin getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public DatabaseQueue getDatabaseQueue() { return databaseQueue; }
    public EconomyManager getEconomyManager() { return economyManager; }
    public PlayerPointsManager getPlayerPointsManager() { return playerPointsManager; }
    public WeeklyOnlineManager getWeeklyOnlineManager() { return weeklyOnlineManager; }
    public CustomRewardManager getCustomRewardManager() { return customRewardManager; }
    public EditSessionManager getEditSessionManager() { return editSessionManager; }
    public DragonKillManager getDragonKillManager() { return dragonKillManager; }
    public GameRewardManager getGameRewardManager() { return gameRewardManager; }
    public MessageServiceIntegration getMessageServiceIntegration() { return messageServiceIntegration; }
}
