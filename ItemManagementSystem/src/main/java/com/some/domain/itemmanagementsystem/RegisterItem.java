package com.some.domain.itemmanagementsystem;

import org.bukkit.Location;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

// アイテムをデータベースに登録する
public class RegisterItem {

    // チェスト内容更新時にアイテム情報をデータベースに登録する
    public static class ChestUpdateListener implements Listener {
        private final Map<Location, ItemStack[]> inventorySnapshots = new HashMap<>();

        // チェストがあいたときにその中身を記憶
        @EventHandler
        public void onChestOpen(InventoryOpenEvent event) {
            Inventory inventory = event.getInventory();
            if (inventory.getHolder() instanceof org.bukkit.block.Chest) {
                org.bukkit.block.Chest chest = (org.bukkit.block.Chest) inventory.getHolder();
                Location location = chest.getLocation();
                // スナップショットを保存
                inventorySnapshots.put(location, inventory.getContents().clone());
            } else if (inventory.getHolder() instanceof DoubleChest) {
                DoubleChest doublechest = (DoubleChest) inventory.getHolder();
                org.bukkit.block.Chest rightChest = (org.bukkit.block.Chest) doublechest.getRightSide();
                Location rightLocation = rightChest.getLocation();
                // スナップショットを保存

                inventorySnapshots.put(rightLocation, inventory.getContents().clone());
            }
        }

        // チェストが閉じたときに変更されていたらアイテム情報を登録
        @EventHandler
        public void onChestClose(InventoryCloseEvent event) {
            Inventory inventory = event.getInventory();
            if (inventory.getHolder() instanceof org.bukkit.block.Chest) {
                org.bukkit.block.Chest chest = (org.bukkit.block.Chest) inventory.getHolder();
                Location location = chest.getLocation();

                // 変更を確認
                ItemStack[] originalContents = inventorySnapshots.get(location);
                ItemStack[] modifiedContents = inventory.getContents();
                if (!Arrays.equals(originalContents, modifiedContents)) {
                    DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                    dbManager.updateItemTableForChest(location);
                }

                // スナップショットを削除
                inventorySnapshots.remove(location);
            } else if (inventory.getHolder() instanceof DoubleChest) {
                org.bukkit.block.DoubleChest doublechest = (org.bukkit.block.DoubleChest) inventory.getHolder();
                org.bukkit.block.Chest leftChest = (org.bukkit.block.Chest) doublechest.getLeftSide();
                org.bukkit.block.Chest rightChest = (org.bukkit.block.Chest) doublechest.getRightSide();

                Location leftLocation = leftChest.getLocation();
                Location rightLocation = rightChest.getLocation();

                // 変更を確認
                ItemStack[] originalContents = inventorySnapshots.get(rightLocation);
                if (!Arrays.equals(originalContents, inventory.getContents())) {
                    // インベントリに変更があった場合の処理
                    DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                    dbManager.updateItemTableForChest(leftLocation);
                    dbManager.updateItemTableForChest(rightLocation);
                }

                // スナップショットを削除
                inventorySnapshots.remove(rightLocation);
            }
        }
    }
}
