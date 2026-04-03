package dev.user.rewards.gui;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.manager.CustomRewardManager;
import dev.user.rewards.manager.EditSessionManager;
import dev.user.rewards.manager.EditSessionManager.EditField;
import dev.user.rewards.model.CustomReward;
import dev.user.rewards.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;

/**
 * 编辑自定义奖励 GUI
 * 支持创建新奖励和编辑现有奖励
 */
public class CustomRewardEditGUI extends AbstractGUI {

    private static final int SIZE = 45; // 5行
    private final boolean isCreating;
    private final String originalRewardId;
    private boolean deleteConfirmPending = false; // 删除确认状态

    // 编辑状态缓存
    private String rewardId;
    private String displayName;
    private String description;
    private double money;
    private int points;
    private int maxClaimCount;
    private long expireAt;
    private long createdAt;  // 保留原始创建时间
    private boolean enabled;

    public CustomRewardEditGUI(SimpleRewardsPlugin plugin, Player player, CustomReward reward) {
        super(plugin, player, plugin.getConfigManager().getMessage("custom-reward-gui-edit-title",
                "reward", reward.getDisplayName()), SIZE);
        this.isCreating = false;
        this.originalRewardId = reward.getRewardId();
        this.rewardId = reward.getRewardId();
        this.displayName = reward.getDisplayName();
        this.description = reward.getDescription();
        this.money = reward.getMoney();
        this.points = reward.getPoints();
        this.maxClaimCount = reward.getMaxClaimCount();
        this.expireAt = reward.getExpireAt();
        this.createdAt = reward.getCreatedAt();
        this.enabled = reward.isEnabled();
    }

    public CustomRewardEditGUI(SimpleRewardsPlugin plugin, Player player) {
        super(plugin, player, plugin.getConfigManager().getMessage("custom-reward-gui-create-title"), SIZE);
        this.isCreating = true;
        this.originalRewardId = null;
        this.rewardId = "";
        this.displayName = "新奖励";
        this.description = "";
        this.money = 0;
        this.points = 0;
        this.maxClaimCount = 1;
        this.expireAt = -1;
        this.createdAt = System.currentTimeMillis();
        this.enabled = true;
    }

    public static void open(SimpleRewardsPlugin plugin, Player player, CustomReward reward) {
        new CustomRewardEditGUI(plugin, player, reward).open();
    }

    public static void openCreate(SimpleRewardsPlugin plugin, Player player) {
        new CustomRewardEditGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        ItemStack border = createBorderItem();
        fillBorder(border);

        // Row 1: ID (不可编辑), 名称, 描述
        if (!isCreating) {
            setItem(10, createInfoItem(Material.PAPER, "奖励ID", rewardId));
        } else {
            setItem(10, createEditItem(Material.PAPER, "奖励ID", rewardId, "点击输入"), (p, e) -> requestInput(p, EditField.REWARD_ID));
        }
        setItem(12, createEditItem(Material.NAME_TAG, "显示名称", displayName, "点击修改"), (p, e) -> requestInput(p, EditField.DISPLAY_NAME));
        setItem(14, createEditItem(Material.WRITABLE_BOOK, "描述", description.isEmpty() ? "(无)" : description, "点击修改"), (p, e) -> requestInput(p, EditField.DESCRIPTION));

        // Row 2: 金币, 点券
        setItem(20, createEditItem(Material.GOLD_INGOT, "金币", String.format("%.0f", money), "点击修改"), (p, e) -> {
            requestInput(p, EditField.MONEY);
        });
        setItem(24, createEditItem(Material.DIAMOND, "点券", String.valueOf(points), "点击修改"), (p, e) -> {
            requestInput(p, EditField.POINTS);
        });

        // Row 3: 领取次数限制
        setItem(28, createEditItem(Material.PAPER, "领取限制 (+1)", maxClaimCount < 0 ? "无限" : maxClaimCount + "次", "点击增加"), (p, e) -> {
            if (maxClaimCount < 0) maxClaimCount = 1;
            else maxClaimCount++;
            refresh();
        });
        setItem(29, createEditItem(Material.PAPER, "领取限制 (-1)", maxClaimCount < 0 ? "无限" : maxClaimCount + "次", "点击减少"), (p, e) -> {
            if (maxClaimCount <= 1) maxClaimCount = -1;
            else maxClaimCount--;
            refresh();
        });

        // 过期时间按钮组：+1天、+7天、+30天、永久
        String expireDisplay = expireAt < 0 ? "永不过期" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(expireAt);
        setItem(31, createEditItem(Material.CLOCK, "过期时间 (+1天)", expireDisplay, "点击设为1天后过期"), (p, e) -> {
            expireAt = System.currentTimeMillis() + 1L * 24 * 60 * 60 * 1000;
            refresh();
        });
        setItem(32, createEditItem(Material.CLOCK, "过期时间 (+7天)", expireDisplay, "点击设为7天后过期"), (p, e) -> {
            expireAt = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000;
            refresh();
        });
        setItem(33, createEditItem(Material.CLOCK, "过期时间 (+30天)", expireDisplay, "点击设为30天后过期"), (p, e) -> {
            expireAt = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000;
            refresh();
        });
        setItem(34, createEditItem(Material.BARRIER, "过期时间 (永久)", expireDisplay, "点击设为永不过期"), (p, e) -> {
            expireAt = -1;
            refresh();
        });

        // Row 4: 启用状态, 删除
        setItem(37, createToggleItem(enabled ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                enabled ? "已启用" : "已禁用", "点击切换"), (p, e) -> {
            enabled = !enabled;
            refresh();
        });
        if (!isCreating) {
            setItem(39, createDeleteItem(deleteConfirmPending), (p, e) -> {
                if (deleteConfirmPending) {
                    deleteReward(p);
                } else {
                    deleteConfirmPending = true;
                    refresh();
                }
            });
        }

        // Row 5: 保存, 返回
        setItem(41, createSaveItem(), (p, e) -> saveReward(p));
        setItem(43, createBackItem(), (p, e) -> goBack(p));
    }

    private void refresh() {
        deleteConfirmPending = false;
        inventory.clear();
        initialize();
    }

    private void requestInput(Player p, EditField field) {
        close();

        EditSessionManager sessionManager = plugin.getEditSessionManager();
        if (sessionManager == null) {
            MessageUtil.send(p, "&c编辑系统未启用");
            return;
        }

        // 创建编辑会话
        sessionManager.createSession(p, field, (player, input) -> {
            // 回调：更新字段值
            switch (field) {
                case REWARD_ID -> rewardId = input;
                case DISPLAY_NAME -> displayName = input;
                case DESCRIPTION -> description = input;
                case MONEY -> money = Double.parseDouble(input);
                case POINTS -> points = Integer.parseInt(input);
            }
            MessageUtil.send(player, "&a已更新");
            // 重新打开 GUI
            player.getScheduler().execute(plugin, () -> open(), () -> {}, 0L);
        });

        // 显示输入提示
        int remaining = sessionManager.getRemainingSeconds(p.getUniqueId());
        MessageUtil.send(p, "&e请在聊天中输入新值");
        MessageUtil.send(p, "&7输入 \"取消\" 可退出，超时时间: " + remaining + "秒");
    }

    private void saveReward(Player p) {
        if (isCreating && rewardId.isEmpty()) {
            p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-edit-id-required"));
            return;
        }

        long finalCreatedAt = isCreating ? System.currentTimeMillis() : this.createdAt;

        CustomReward reward = new CustomReward(
                isCreating ? rewardId : originalRewardId,
                displayName,
                description,
                money,
                points,
                maxClaimCount,
                expireAt,
                finalCreatedAt,
                p.getUniqueId().toString(),
                enabled
        );

        CustomRewardManager manager = plugin.getCustomRewardManager();
        if (isCreating) {
            manager.createReward(reward, success -> {
                p.getScheduler().execute(plugin, () -> {
                    if (success) {
                        p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-create-success",
                                "reward", rewardId));
                    } else {
                        p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-create-exists",
                                "reward", rewardId));
                    }
                    goBack(p);
                }, () -> {}, 0L);
            });
        } else {
            manager.updateReward(reward, success -> {
                p.getScheduler().execute(plugin, () -> {
                    if (success) {
                        p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-update-success"));
                    } else {
                        p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-update-failed"));
                    }
                    goBack(p);
                }, () -> {}, 0L);
            });
        }
        close();
    }

    private ItemStack createDeleteItem(boolean confirmPending) {
        if (confirmPending) {
            ItemStack item = new ItemStack(Material.RED_WOOL);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;

            meta.displayName(Component.text("⚠ 确认删除？再次点击确认")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("此操作不可撤销！")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
            return item;
        } else {
            return createDangerItem(Material.TNT, "删除奖励", "点击进行删除");
        }
    }

    private void deleteReward(Player p) {
        close();
        plugin.getCustomRewardManager().deleteReward(originalRewardId, success -> {
            p.getScheduler().execute(plugin, () -> {
                if (success) {
                    p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-delete-success",
                            "reward", originalRewardId));
                } else {
                    p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-delete-not-found",
                            "reward", originalRewardId));
                }
                CustomRewardAdminGUI.open(plugin, p);
            }, () -> {}, 0L);
        });
    }

    private void goBack(Player p) {
        close();
        CustomRewardAdminGUI.open(plugin, p);
    }

    // ==================== 辅助方法 ====================

    private ItemStack createBorderItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfoItem(Material material, String name, String value) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text(name)
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(value)
                .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEditItem(Material material, String name, String value, String hint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text(name)
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("当前: " + value)
                .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(hint)
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createToggleItem(Material material, String name, String hint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text(name)
                .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(hint)
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createDangerItem(Material material, String name, String hint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text(name)
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(hint)
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSaveItem() {
        ItemStack item = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("保存")
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("点击保存更改")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("返回")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("点击返回上级")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
