package dev.user.rewards.gui;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.manager.CustomRewardManager;
import dev.user.rewards.model.CustomReward;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 玩家自定义奖励 GUI
 * 显示所有可领取的奖励
 */
public class CustomRewardPlayerGUI extends AbstractGUI {

    private static final int SIZE = 45; // 5行

    public CustomRewardPlayerGUI(SimpleRewardsPlugin plugin, Player player) {
        super(plugin, player, plugin.getConfigManager().getMessage("custom-reward-gui-player-title"), SIZE);
    }

    public static void open(SimpleRewardsPlugin plugin, Player player) {
        new CustomRewardPlayerGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        ItemStack border = createBorderItem();
        fillBorder(border);

        CustomRewardManager manager = plugin.getCustomRewardManager();
        if (manager == null) {
            setItem(22, createErrorItem("自定义奖励系统未启用"));
            setItem(40, createCloseItem(), (p, e) -> close());
            return;
        }

        List<CustomReward> rewards = manager.getAllRewards();
        Map<String, Integer> claims = manager.getPlayerClaims(player.getUniqueId());

        // 奖励列表槽位 (Row 1-3, 避开边框)
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

        int i = 0;
        for (CustomReward reward : rewards) {
            if (i >= slots.length) break;

            int claimCount = claims.getOrDefault(reward.getRewardId(), 0);
            boolean canClaim = reward.canClaim(claimCount);

            ItemStack item = createRewardItem(reward, claimCount, canClaim);
            if (canClaim) {
                setItem(slots[i], item, (p, e) -> claimReward(p, reward));
            } else {
                setItem(slots[i], item);
            }
            i++;
        }

        // 关闭按钮
        setItem(40, createCloseItem(), (p, e) -> close());
    }

    private ItemStack createRewardItem(CustomReward reward, int claimCount, boolean canClaim) {
        Material material;
        Component statusLine;

        if (!reward.isEnabled()) {
            material = Material.REDSTONE_BLOCK;
            statusLine = Component.text("已禁用").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false);
        } else if (reward.isExpired()) {
            material = Material.BARRIER;
            statusLine = Component.text("已过期").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false);
        } else if (!canClaim) {
            material = Material.DIAMOND_BLOCK;
            int remaining = reward.getRemainingClaims(claimCount);
            String statusText = remaining < 0 ? "已领取" : "已领完 (" + claimCount + "/" + reward.getMaxClaimCount() + ")";
            statusLine = Component.text(statusText).color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false);
        } else {
            material = Material.GOLD_BLOCK;
            statusLine = Component.text("可领取").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false);
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text(reward.getDisplayName())
                .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(statusLine);
        lore.add(Component.text("ID: " + reward.getRewardId())
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (!reward.getDescription().isEmpty()) {
            lore.add(Component.text(reward.getDescription())
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        if (reward.getMoney() > 0) {
            lore.add(Component.text("金币: " + String.format("%.0f", reward.getMoney()))
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        if (reward.getPoints() > 0) {
            lore.add(Component.text("点券: " + reward.getPoints())
                    .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        if (reward.getMaxClaimCount() > 0) {
            int remaining = reward.getRemainingClaims(claimCount);
            String remainStr = remaining < 0 ? "无限" : String.valueOf(remaining);
            lore.add(Component.text("剩余次数: " + remainStr)
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        if (reward.getExpireAt() > 0) {
            long days = (reward.getExpireAt() - System.currentTimeMillis()) / (24 * 60 * 60 * 1000);
            lore.add(Component.text("剩余时间: " + (days > 0 ? days + "天" : "即将过期"))
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        if (canClaim) {
            lore.add(Component.text("点击领取")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);

        if (canClaim) {
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        item.setItemMeta(meta);
        return item;
    }

    private void claimReward(Player p, CustomReward reward) {
        close();
        plugin.getCustomRewardManager().claimReward(p.getUniqueId(), reward.getRewardId(), result -> {
            p.getScheduler().execute(plugin, () -> {
                switch (result) {
                    // SUCCESS 消息由 grantReward 内部发送，这里不再重复
                    case SUCCESS -> CustomRewardPlayerGUI.open(plugin, p);
                    case LIMIT_REACHED -> p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-limit"));
                    case EXPIRED -> p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-expired"));
                    case NOT_FOUND -> p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-not-found"));
                    case DISABLED -> p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-disabled"));
                    case ERROR -> p.sendMessage(plugin.getConfigManager().getMessage("custom-reward-claim-error"));
                }
            }, () -> {}, 0L);
        });
    }

    private ItemStack createBorderItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createCloseItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("关闭")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createErrorItem(String message) {
        ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text(message)
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
