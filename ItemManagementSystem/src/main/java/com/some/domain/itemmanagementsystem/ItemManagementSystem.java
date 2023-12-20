package com.some.domain.itemmanagementsystem;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class ItemManagementSystem extends JavaPlugin {

    private static ItemManagementSystem instance;
    private static ItemStack customAxe; // カスタム斧を保持するフィールド

    // プラグイン起動時に実行される
    @Override
    public void onEnable() {
        // Plugin startup logic
        instance = this;

        // カスタム斧の作成、チェストをカスタム斧で右クリックしたときの動作を登録
        saveDefaultConfig();
        customAxe = RegisterChest.createCustomAxe();
        RegisterChest.ChestClickListener clickListener = new RegisterChest.ChestClickListener();
        getServer().getPluginManager().registerEvents(clickListener, this);

        // チェストが更新されたときの動作を登録
        RegisterItem.ChestUpdateListener updateListener = new RegisterItem.ChestUpdateListener();
        getServer().getPluginManager().registerEvents(updateListener, this);

        // コマンドを登録
        Command command = new Command(clickListener);
        this.getCommand("imsystem").setExecutor(command);

        new BukkitRunnable() {
            @Override
            public void run() {
                // プレイヤーが1人以上オンラインの場合にメソッドを実行
                if (!Bukkit.getOnlinePlayers().isEmpty()) {
                    DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                    dbManager.processAndSaveAllChestItems();
                    UpdateItemTime uiTime = new UpdateItemTime();
                    uiTime.saveAllItemsLog();
                }
            }
        }.runTaskTimer(this, 0L, 12000L);
    }

    // プラグイン終了時に実行される
    @Override
    public void onDisable() {
        // Plugin shutdown logic
        DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
        dbManager.processAndSaveAllChestItems();
        UpdateItemTime uiTime = new UpdateItemTime();
        uiTime.saveAllItemsLog();
    }

    // その他のメソッド
    public static ItemManagementSystem getInstance() {
        return instance;
    }

    public static ItemStack getCustomAxe() {
        return customAxe;
    }

    private static class UpdateItemTime {

        private static final String FILE_PATH = "./plugins/ItemManagementSystem/updateItemTableTime.txt";
        private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        public void saveAllItemsLog() {
            String formattedTime = DATE_FORMAT.format(new Date());
            writeTimeToFile(formattedTime);
        }

        private void writeTimeToFile(String time) {
            File file = new File(FILE_PATH);

            // ファイルが存在しない場合、新規作成
            if (!file.exists()) {
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }

            // ファイルに時間を書き込む（既存の内容は上書きされる）
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
                writer.write(String.valueOf(time));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
