package id.velioragardens.velioragacha;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class VelioraGachaPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, KeyDef> keys = new HashMap<>();
    private final Map<String, CrateDef> crates = new HashMap<>();
    private final Map<String, CrateLocation> locations = new HashMap<>();
    private final Map<String, List<RewardDef>> rewards = new HashMap<>();
    private final Map<UUID, PlayerData> playerCache = new HashMap<>();
    private final Map<UUID, BukkitTask> rouletteTasks = new HashMap<>();
    private final Set<UUID> openingPlayers = new HashSet<>();

    private File cratesFile;
    private File keysFile;
    private File rewardsFile;
    private File playerDataFolder;
    private FileConfiguration cratesConfig;
    private FileConfiguration keysConfig;
    private FileConfiguration rewardsConfig;
    private Economy economy;
    private BukkitTask effectTask;
    private DecimalFormat moneyFormat;

    private org.bukkit.NamespacedKey crateItemKey;

    @Override
    public void onEnable() {
        crateItemKey = new org.bukkit.NamespacedKey(this, "crate_id");
        saveBundledResources();
        setupEconomy();
        reloadAll();

        Objects.requireNonNull(getCommand("vgcreate"), "Command vgcreate missing").setExecutor(this);
        Objects.requireNonNull(getCommand("vgcreate"), "Command vgcreate missing").setTabCompleter(this);
        Objects.requireNonNull(getCommand("key"), "Command key missing").setExecutor(this);
        Objects.requireNonNull(getCommand("key"), "Command key missing").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        startEffectTask();
        getLogger().info("VelioraGacha enabled.");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : rouletteTasks.values()) task.cancel();
        rouletteTasks.clear();
        openingPlayers.clear();
        if (effectTask != null) effectTask.cancel();
        for (PlayerData data : playerCache.values()) savePlayerData(data);
        playerCache.clear();
        getLogger().info("VelioraGacha disabled.");
    }

    private void saveBundledResources() {
        saveResourceIfMissing("config.yml");
        saveResourceIfMissing("crates.yml");
        saveResourceIfMissing("keys.yml");
        saveResourceIfMissing("rewards.yml");
        playerDataFolder = new File(getDataFolder(), "playerdata");
        if (!playerDataFolder.exists() && !playerDataFolder.mkdirs()) {
            getLogger().warning("Could not create playerdata folder.");
        }
    }

    private void saveResourceIfMissing(String path) {
        File file = new File(getDataFolder(), path);
        if (!file.exists()) saveResource(path, false);
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            economy = null;
            return;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = rsp == null ? null : rsp.getProvider();
    }

    private void reloadAll() {
        reloadConfig();
        moneyFormat = new DecimalFormat(getConfig().getString("economy.money-format", "#,###"));
        cratesFile = new File(getDataFolder(), "crates.yml");
        keysFile = new File(getDataFolder(), "keys.yml");
        rewardsFile = new File(getDataFolder(), "rewards.yml");
        cratesConfig = YamlConfiguration.loadConfiguration(cratesFile);
        keysConfig = YamlConfiguration.loadConfiguration(keysFile);
        rewardsConfig = YamlConfiguration.loadConfiguration(rewardsFile);
        loadKeys();
        loadCrates();
        loadRewards();
    }

    private void loadKeys() {
        keys.clear();
        ConfigurationSection section = keysConfig.getConfigurationSection("keys");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            String path = "keys." + id;
            String display = keysConfig.getString(path + ".display-name", id);
            Material material = safeMaterial(keysConfig.getString(path + ".material"), Material.TRIPWIRE_HOOK);
            double price = Math.max(0, keysConfig.getDouble(path + ".price", 150000));
            List<String> lore = keysConfig.getStringList(path + ".lore");
            keys.put(id.toLowerCase(Locale.ROOT), new KeyDef(id.toLowerCase(Locale.ROOT), display, material, price, lore));
        }
    }

    private void loadCrates() {
        crates.clear();
        locations.clear();
        ConfigurationSection section = cratesConfig.getConfigurationSection("crates");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String path = "crates." + id;
                if (!cratesConfig.getBoolean(path + ".enabled", true)) continue;
                Material block = safeMaterial(cratesConfig.getString(path + ".block"), Material.CHEST);
                String display = cratesConfig.getString(path + ".display", id);
                String key = cratesConfig.getString(path + ".key", id + "_key").toLowerCase(Locale.ROOT);
                String effect = cratesConfig.getString(path + ".effect", "fire_spiral");
                int slot = cratesConfig.getInt(path + ".gui-slot", 10);
                String level = cratesConfig.getString(path + ".level-reward", "Default");
                boolean hologram = cratesConfig.getBoolean(path + ".hologram-enabled", true);
                crates.put(id.toLowerCase(Locale.ROOT), new CrateDef(id.toLowerCase(Locale.ROOT), display, block, key, effect, slot, level, hologram));
            }
        }
        ConfigurationSection locSec = cratesConfig.getConfigurationSection("locations");
        if (locSec != null) {
            for (String locKey : locSec.getKeys(false)) {
                String path = "locations." + locKey;
                String crate = cratesConfig.getString(path + ".crate", "").toLowerCase(Locale.ROOT);
                String world = cratesConfig.getString(path + ".world", "world");
                int x = cratesConfig.getInt(path + ".x");
                int y = cratesConfig.getInt(path + ".y");
                int z = cratesConfig.getInt(path + ".z");
                if (crates.containsKey(crate)) locations.put(locKey, new CrateLocation(crate, world, x, y, z));
            }
        }
    }

    private void loadRewards() {
        rewards.clear();
        ConfigurationSection root = rewardsConfig.getConfigurationSection("rewards");
        if (root == null) return;
        for (String crateId : root.getKeys(false)) {
            List<RewardDef> list = new ArrayList<>();
            ConfigurationSection crateSection = root.getConfigurationSection(crateId);
            if (crateSection == null) continue;
            for (String rarity : crateSection.getKeys(false)) {
                List<Map<?, ?>> maps = rewardsConfig.getMapList("rewards." + crateId + "." + rarity);
                for (Map<?, ?> map : maps) {
                    String type = String.valueOf(map.getOrDefault("type", "ITEM")).toUpperCase(Locale.ROOT);
                    int weight = parseInt(map.get("weight"), 10);
                    if ("COMMAND".equals(type)) {
                        String command = String.valueOf(map.getOrDefault("command", ""));
                        String display = String.valueOf(map.getOrDefault("display", "Command Reward"));
                        Material material = safeMaterial(String.valueOf(map.getOrDefault("material", "COMMAND_BLOCK")), Material.COMMAND_BLOCK);
                        int amount = Math.max(1, parseInt(map.get("amount"), 1));
                        list.add(RewardDef.command(crateId, rarity, display, command, material, amount, weight));
                    } else {
                        Material material = safeMaterial(String.valueOf(map.getOrDefault("material", "STONE")), Material.STONE);
                        int amount = Math.max(1, parseInt(map.get("amount"), 1));
                        list.add(RewardDef.item(crateId, rarity, material, amount, weight));
                    }
                }
            }
            rewards.put(crateId.toLowerCase(Locale.ROOT), list);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("key")) return handleKeyCommand(sender, args);
        if (command.getName().equalsIgnoreCase("vgcreate")) return handleAdminCommand(sender, args);
        return false;
    }

    private boolean handleKeyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, message("player-only"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("shop")) {
            if (!player.hasPermission("velioragacha.shop")) {
                send(player, message("no-permission"));
                return true;
            }
            openKeyShop(player);
            return true;
        }
        send(player, "&dVelioraGacha &7» &f/key shop");
        return true;
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "&8&m--------------------------------");
            send(sender, "&d&lVelioraGacha Admin");
            send(sender, "&f/vgcreate gui &7- Buka admin GUI.");
            send(sender, "&f/vgcreate givekey <player|*> <key> <amount> &7- Beri key.");
            send(sender, "&f/vgcreate reload &7- Reload config.");
            send(sender, "&8&m--------------------------------");
            return true;
        }
        if (args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                send(sender, message("player-only"));
                return true;
            }
            if (!player.hasPermission("velioragacha.gui")) {
                send(player, message("no-permission"));
                return true;
            }
            openAdminGui(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("velioragacha.reload")) {
                send(sender, message("no-permission"));
                return true;
            }
            reloadAll();
            restartEffectTask();
            send(sender, message("reload-success"));
            return true;
        }
        if (args[0].equalsIgnoreCase("givekey")) {
            if (!sender.hasPermission("velioragacha.givekey")) {
                send(sender, message("no-permission"));
                return true;
            }
            if (args.length < 4) {
                send(sender, "&cGunakan: /vgcreate givekey <player|*> <key> <amount>");
                return true;
            }
            String target = args[1];
            String keyId = args[2].toLowerCase(Locale.ROOT);
            int amount = parseInt(args[3], 0);
            if (amount < 1) {
                send(sender, "&cAmount minimal 1.");
                return true;
            }
            KeyDef key = keys.get(keyId);
            if (key == null) {
                send(sender, message("invalid-key").replace("%key%", keyId));
                return true;
            }
            if (target.equals("*")) {
                for (Player online : Bukkit.getOnlinePlayers()) addKey(online, key.id(), amount, true);
                send(sender, message("key-given").replace("%amount%", String.valueOf(amount)).replace("%key%", plain(key.displayName())).replace("%player%", "semua player online"));
                return true;
            }
            OfflinePlayer off = Bukkit.getOfflinePlayer(target);
            PlayerData data = loadPlayerData(off.getUniqueId(), off.getName() == null ? target : off.getName());
            data.addKey(key.id(), amount);
            savePlayerData(data);
            Player online = off.getPlayer();
            if (online != null) send(online, message("key-received").replace("%amount%", String.valueOf(amount)).replace("%key%", plain(key.displayName())));
            send(sender, message("key-given").replace("%amount%", String.valueOf(amount)).replace("%key%", plain(key.displayName())).replace("%player%", target));
            return true;
        }
        send(sender, "&cSubcommand tidak dikenal.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("key")) {
            if (args.length == 1) return match(args[0], List.of("shop"));
            return Collections.emptyList();
        }
        if (args.length == 1) return match(args[0], List.of("gui", "givekey", "reload"));
        if (args.length == 3 && args[0].equalsIgnoreCase("givekey")) return match(args[2], new ArrayList<>(keys.keySet()));
        if (args.length == 4 && args[0].equalsIgnoreCase("givekey")) return match(args[3], List.of("1", "5", "10", "32", "64"));
        return Collections.emptyList();
    }

    private void openKeyShop(Player player) {
        GuiHolder holder = new GuiHolder(GuiType.KEY_SHOP, null, 1);
        Inventory inv = Bukkit.createInventory(holder, 54, color(getConfig().getString("shop.title", "&8Key Shop")));
        holder.inventory = inv;
        fill(inv);
        int[] slots = {20, 22, 24, 30, 32};
        int i = 0;
        for (KeyDef key : keys.values()) {
            if (i >= slots.length) break;
            inv.setItem(slots[i++], keyIcon(key, List.of("&7Harga: &a" + formatMoney(key.price()), "&7Klik untuk beli.")));
        }
        player.openInventory(inv);
    }

    private void openQuantityGui(Player player, String keyId, int amount) {
        KeyDef key = keys.get(keyId);
        if (key == null) return;
        int max = Math.max(1, getConfig().getInt("settings.max-shop-amount", 64));
        amount = Math.max(1, Math.min(max, amount));
        GuiHolder holder = new GuiHolder(GuiType.KEY_QUANTITY, keyId, amount);
        String title = getConfig().getString("shop.quantity-title", "&8Beli %key%").replace("%key%", plain(key.displayName()));
        Inventory inv = Bukkit.createInventory(holder, 54, color(title));
        holder.inventory = inv;
        fill(inv);
        inv.setItem(20, named(Material.RED_STAINED_GLASS_PANE, "&c-10", List.of("&7Kurangi 10.")));
        inv.setItem(21, named(Material.RED_DYE, "&c-1", List.of("&7Kurangi 1.")));
        inv.setItem(22, keyIcon(key, List.of("&7Jumlah: &f" + amount, "&7Total: &a" + formatMoney(key.price() * amount))));
        inv.setItem(23, named(Material.LIME_DYE, "&a+1", List.of("&7Tambah 1.")));
        inv.setItem(24, named(Material.LIME_STAINED_GLASS_PANE, "&a+10", List.of("&7Tambah 10.")));
        inv.setItem(30, named(Material.ARROW, "&eKembali", List.of("&7Kembali ke shop.")));
        inv.setItem(32, named(Material.EMERALD_BLOCK, "&aConfirm Beli", List.of("&7Total: &a" + formatMoney(key.price() * amount))));
        player.openInventory(inv);
    }

    private void openAdminGui(Player player) {
        GuiHolder holder = new GuiHolder(GuiType.ADMIN_MAIN, null, 1);
        Inventory inv = Bukkit.createInventory(holder, 27, color("&8VelioraGacha Admin"));
        holder.inventory = inv;
        fill(inv);
        inv.setItem(11, named(Material.TRIPWIRE_HOOK, "&dKey Manager", List.of("&7Klik untuk melihat key.", "&7Shift key: +10 key test.")));
        inv.setItem(13, named(Material.CHEST, "&6Crate Placer", List.of("&7Ambil crate item khusus.", "&7Place untuk membuat crate.")));
        inv.setItem(15, named(Material.COMPARATOR, "&eCrate Settings", List.of("&7Lihat crate dan reward editor.")));
        player.openInventory(inv);
    }

    private void openKeyManager(Player player) {
        GuiHolder holder = new GuiHolder(GuiType.KEY_MANAGER, null, 1);
        Inventory inv = Bukkit.createInventory(holder, 27, color("&8Key Manager"));
        holder.inventory = inv;
        fill(inv);
        int[] slots = {10, 11, 12, 13, 14};
        int i = 0;
        for (KeyDef key : keys.values()) {
            if (i >= slots.length) break;
            inv.setItem(slots[i++], keyIcon(key, List.of("&7Klik: beri diri sendiri 1 key.", "&7Shift klik: beri diri sendiri 10 key.", "&8/vgcreate givekey <player> " + key.id() + " <amount>")));
        }
        player.openInventory(inv);
    }

    private void openCratePlacer(Player player) {
        GuiHolder holder = new GuiHolder(GuiType.CRATE_PLACER, null, 1);
        Inventory inv = Bukkit.createInventory(holder, 27, color("&8Crate Placer"));
        holder.inventory = inv;
        fill(inv);
        int[] slots = {10, 11, 12, 13, 14};
        int i = 0;
        for (CrateDef crate : crates.values()) {
            if (i >= slots.length) break;
            inv.setItem(slots[i++], crateItem(crate));
        }
        player.openInventory(inv);
    }

    private void openCrateSettings(Player player) {
        GuiHolder holder = new GuiHolder(GuiType.CRATE_SETTINGS, null, 1);
        Inventory inv = Bukkit.createInventory(holder, 27, color("&8Crate Settings"));
        holder.inventory = inv;
        fill(inv);
        int[] slots = {10, 11, 12, 13, 14};
        int i = 0;
        for (CrateDef crate : crates.values()) {
            if (i >= slots.length) break;
            inv.setItem(slots[i++], named(crate.block(), "&e" + crate.display(), List.of("&7Effect: &f" + crate.effect(), "&7Klik untuk reward editor.")));
        }
        player.openInventory(inv);
    }

    private void openRewardEditor(Player player, String crateId) {
        GuiHolder holder = new GuiHolder(GuiType.REWARD_EDITOR, crateId, 1);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&8Reward Editor: " + crateId));
        holder.inventory = inv;
        List<RewardDef> list = rewards.getOrDefault(crateId, List.of());
        int slot = 0;
        for (RewardDef reward : list) {
            if (reward.type() == RewardType.ITEM && slot < inv.getSize()) inv.setItem(slot++, new ItemStack(reward.material(), reward.amount()));
        }
        player.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) return;
        event.setCancelled(true);
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) return;
        int slot = event.getRawSlot();
        switch (holder.type) {
            case KEY_SHOP -> {
                String keyId = readKeyFromItem(current);
                if (keyId != null) openQuantityGui(player, keyId, 1);
            }
            case KEY_QUANTITY -> handleQuantityClick(player, holder, slot);
            case ADMIN_MAIN -> {
                if (slot == 11) openKeyManager(player);
                else if (slot == 13) openCratePlacer(player);
                else if (slot == 15) openCrateSettings(player);
            }
            case KEY_MANAGER -> {
                String keyId = readKeyFromItem(current);
                if (keyId != null) addKey(player, keyId, event.isShiftClick() ? 10 : 1, true);
            }
            case CRATE_PLACER -> {
                String crateId = readCrateFromItem(current);
                if (crateId != null) player.getInventory().addItem(crateItem(crates.get(crateId)));
            }
            case CRATE_SETTINGS -> {
                String crateId = readCrateFromItem(current);
                if (crateId != null) openRewardEditor(player, crateId);
            }
            case ROULETTE -> event.setCancelled(true);
            case REWARD_EDITOR -> event.setCancelled(false);
        }
    }

    private void handleQuantityClick(Player player, GuiHolder holder, int slot) {
        String keyId = holder.payload;
        int amount = holder.amount;
        int max = Math.max(1, getConfig().getInt("settings.max-shop-amount", 64));
        if (slot == 20) amount -= 10;
        else if (slot == 21) amount -= 1;
        else if (slot == 23) amount += 1;
        else if (slot == 24) amount += 10;
        else if (slot == 30) {
            openKeyShop(player);
            return;
        } else if (slot == 32) {
            confirmBuy(player, keyId, amount);
            openQuantityGui(player, keyId, amount);
            return;
        } else return;
        openQuantityGui(player, keyId, Math.max(1, Math.min(max, amount)));
    }

    private void confirmBuy(Player player, String keyId, int amount) {
        KeyDef key = keys.get(keyId);
        if (key == null) return;
        if (economy == null || !getConfig().getBoolean("economy.enabled", true)) {
            send(player, message("economy-missing"));
            return;
        }
        double total = key.price() * amount;
        if (economy.getBalance(player) < total) {
            send(player, message("not-enough-money").replace("%price%", formatMoney(total)));
            return;
        }
        economy.withdrawPlayer(player, total);
        addKey(player, keyId, amount, false);
        send(player, message("key-bought").replace("%amount%", String.valueOf(amount)).replace("%key%", plain(key.displayName())).replace("%price%", formatMoney(total)));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) return;
        if (holder.type == GuiType.REWARD_EDITOR && holder.payload != null) saveRewardEditor(player, holder.payload, event.getInventory());
    }

    private void saveRewardEditor(Player player, String crateId, Inventory inventory) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("type", "ITEM");
            map.put("material", item.getType().name());
            map.put("amount", item.getAmount());
            map.put("weight", 10);
            list.add(map);
        }
        rewardsConfig.set("rewards." + crateId + ".common", list);
        saveYaml(rewardsConfig, rewardsFile);
        loadRewards();
        send(player, "&aReward editor tersimpan untuk crate &f" + crateId + "&a.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        String crateId = readCrateFromItem(item);
        if (crateId == null) return;
        if (!player.hasPermission("velioragacha.place")) {
            event.setCancelled(true);
            send(player, message("no-permission"));
            return;
        }
        CrateDef crate = crates.get(crateId);
        if (crate == null) {
            event.setCancelled(true);
            return;
        }
        Location loc = event.getBlockPlaced().getLocation();
        String key = locationKey(loc);
        cratesConfig.set("locations." + key + ".crate", crate.id());
        cratesConfig.set("locations." + key + ".world", loc.getWorld().getName());
        cratesConfig.set("locations." + key + ".x", loc.getBlockX());
        cratesConfig.set("locations." + key + ".y", loc.getBlockY());
        cratesConfig.set("locations." + key + ".z", loc.getBlockZ());
        saveYaml(cratesConfig, cratesFile);
        locations.put(key, new CrateLocation(crate.id(), loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        send(player, message("crate-placed").replace("%crate%", crate.display()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        String key = locationKey(event.getBlock().getLocation());
        CrateLocation location = locations.get(key);
        if (location == null) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("velioragacha.break")) {
            event.setCancelled(true);
            send(player, message("no-permission"));
            return;
        }
        CrateDef crate = crates.get(location.crateId());
        cratesConfig.set("locations." + key, null);
        saveYaml(cratesConfig, cratesFile);
        locations.remove(key);
        event.setDropItems(false);
        if (crate != null && getConfig().getBoolean("crates.drop-on-break", true)) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), crateItem(crate));
        }
        send(player, message("crate-broken").replace("%crate%", crate == null ? location.crateId() : crate.display()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> handleCrateClick(event);
            default -> {}
        }
    }

    private void handleCrateClick(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        String locKey = locationKey(block.getLocation());
        CrateLocation crateLocation = locations.get(locKey);
        if (crateLocation == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("velioragacha.open")) {
            send(player, message("no-permission"));
            return;
        }
        CrateDef crate = crates.get(crateLocation.crateId());
        if (crate == null) return;
        KeyDef key = keys.get(crate.keyId());
        if (key == null) return;
        PlayerData data = getData(player);
        if (data.getKey(crate.keyId()) <= 0) {
            noKey(player, block.getLocation(), key);
            return;
        }
        if (openingPlayers.contains(player.getUniqueId())) {
            send(player, message("animation-running"));
            return;
        }
        data.addKey(crate.keyId(), -1);
        savePlayerData(data);
        startRoulette(player, crate);
    }

    private void noKey(Player player, Location crateLoc, KeyDef key) {
        send(player, message("no-key").replace("%key_name%", plain(key.displayName())));
        Sound sound = safeSound(getConfig().getString("no-key.sound"), Sound.ENTITY_VILLAGER_NO);
        player.playSound(player.getLocation(), sound, 1f, 1f);
        if (getConfig().getBoolean("no-key.knockback.enabled", true)) {
            double strength = getConfig().getDouble("no-key.knockback.strength", 0.65);
            double y = getConfig().getDouble("no-key.knockback.y", 0.25);
            Vector vector = player.getLocation().toVector().subtract(crateLoc.toVector()).normalize().multiply(strength);
            vector.setY(y);
            player.setVelocity(vector);
        }
    }

    private void startRoulette(Player player, CrateDef crate) {
        openingPlayers.add(player.getUniqueId());
        RewardDef reward = chooseReward(crate.id()).orElse(RewardDef.item(crate.id(), "fallback", Material.EMERALD, 1, 1));
        int size = normalizeInventorySize(getConfig().getInt("animation.gui-size", 45));
        int indicator = Math.min(size - 1, Math.max(0, getConfig().getInt("animation.indicator-slot", 22)));
        GuiHolder holder = new GuiHolder(GuiType.ROULETTE, crate.id(), 1);
        Inventory inv = Bukkit.createInventory(holder, size, color(getConfig().getString("animation.title", "&8Opening %crate%").replace("%crate%", crate.display())));
        holder.inventory = inv;
        fill(inv);
        inv.setItem(Math.max(0, indicator - 9), named(Material.LIME_STAINED_GLASS_PANE, "&a▼", List.of()));
        inv.setItem(Math.min(size - 1, indicator + 9), named(Material.LIME_STAINED_GLASS_PANE, "&a▲", List.of()));
        player.openInventory(inv);

        int duration = Math.max(20, getConfig().getInt("animation.duration-ticks", 120));
        Sound tickSound = safeSound(getConfig().getString("animation.sound-tick"), Sound.BLOCK_NOTE_BLOCK_HAT);
        Sound finishSound = safeSound(getConfig().getString("animation.sound-finish"), Sound.ENTITY_PLAYER_LEVELUP);
        BukkitTask task = new BukkitRunnable() {
            int tick = 0;
            final Random random = new Random();
            @Override
            public void run() {
                if (!player.isOnline()) {
                    finishRoulette(player, reward, finishSound, false);
                    cancel();
                    return;
                }
                tick += 2;
                RewardDef shown = tick >= duration ? reward : randomRewardVisual(crate.id(), random);
                inv.setItem(indicator, shown.toIcon());
                player.playSound(player.getLocation(), tickSound, 0.35f, 1.6f);
                if (tick >= duration) {
                    finishRoulette(player, reward, finishSound, true);
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 2L);
        rouletteTasks.put(player.getUniqueId(), task);
    }

    private void finishRoulette(Player player, RewardDef reward, Sound finishSound, boolean sound) {
        BukkitTask task = rouletteTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
        openingPlayers.remove(player.getUniqueId());
        if (player.isOnline()) {
            giveReward(player, reward);
            if (sound) player.playSound(player.getLocation(), finishSound, 1f, 1f);
        }
    }

    private RewardDef randomRewardVisual(String crateId, Random random) {
        List<RewardDef> list = rewards.getOrDefault(crateId, List.of());
        if (list.isEmpty()) return RewardDef.item(crateId, "fallback", Material.EMERALD, 1, 1);
        return list.get(random.nextInt(list.size()));
    }

    private Optional<RewardDef> chooseReward(String crateId) {
        List<RewardDef> list = rewards.get(crateId);
        if (list == null || list.isEmpty()) return Optional.empty();
        int total = 0;
        for (RewardDef reward : list) total += Math.max(1, reward.weight());
        int roll = new Random().nextInt(total) + 1;
        int cursor = 0;
        for (RewardDef reward : list) {
            cursor += Math.max(1, reward.weight());
            if (roll <= cursor) return Optional.of(reward);
        }
        return Optional.of(list.get(0));
    }

    private void giveReward(Player player, RewardDef reward) {
        if (reward.type() == RewardType.COMMAND) {
            String cmd = reward.command().replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            send(player, message("reward-received").replace("%reward%", plain(reward.display())));
            return;
        }
        ItemStack item = new ItemStack(reward.material(), reward.amount());
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack left : leftover.values()) player.getWorld().dropItemNaturally(player.getLocation(), left);
        send(player, message("reward-received").replace("%reward%", reward.material().name() + " x" + reward.amount()));
    }

    private void startEffectTask() {
        if (!getConfig().getBoolean("effects.enabled", true)) return;
        int interval = Math.max(2, getConfig().getInt("effects.interval-ticks", 8));
        effectTask = new BukkitRunnable() {
            double t = 0;
            @Override public void run() {
                t += 0.35;
                for (CrateLocation loc : locations.values()) spawnCrateEffect(loc, t);
            }
        }.runTaskTimer(this, interval, interval);
    }

    private void restartEffectTask() {
        if (effectTask != null) effectTask.cancel();
        startEffectTask();
    }

    private void spawnCrateEffect(CrateLocation loc, double t) {
        World world = Bukkit.getWorld(loc.world());
        if (world == null) return;
        if (!world.isChunkLoaded(loc.x() >> 4, loc.z() >> 4)) return;
        CrateDef crate = crates.get(loc.crateId());
        if (crate == null) return;
        Particle particle = effectParticle(crate.effect());
        double radius = getConfig().getDouble("effects.radius", 1.25);
        double height = 0.35 + (Math.sin(t) + 1.0) * 0.65;
        double x = loc.x() + 0.5 + Math.cos(t) * radius;
        double z = loc.z() + 0.5 + Math.sin(t) * radius;
        Location particleLoc = new Location(world, x, loc.y() + height, z);
        world.spawnParticle(particle, particleLoc, 2, 0.03, 0.03, 0.03, 0.01);
    }

    private Particle effectParticle(String effect) {
        String e = effect == null ? "" : effect.toLowerCase(Locale.ROOT);
        if (e.contains("fire")) return safeParticle("FLAME", Particle.FLAME);
        if (e.contains("end_rod")) return safeParticle("END_ROD", Particle.END_ROD);
        if (e.contains("bubble")) return safeParticle("BUBBLE_POP", safeParticle("BUBBLE", Particle.HAPPY_VILLAGER));
        if (e.contains("smoke")) return safeParticle("SMOKE", Particle.CAMPFIRE_COSY_SMOKE);
        if (e.contains("cherry")) return safeParticle("CHERRY_LEAVES", Particle.HAPPY_VILLAGER);
        if (e.contains("oak") || e.contains("leaf")) return safeParticle("HAPPY_VILLAGER", Particle.HAPPY_VILLAGER);
        return Particle.HAPPY_VILLAGER;
    }

    private ItemStack crateItem(CrateDef crate) {
        ItemStack item = named(crate.block(), "&d" + crate.display() + " Crate", List.of("&7Place untuk membuat crate.", "&7Crate ID: &f" + crate.id()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(crateItemKey, PersistentDataType.STRING, crate.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack keyIcon(KeyDef key, List<String> extraLore) {
        List<String> lore = new ArrayList<>(key.lore());
        lore.addAll(extraLore);
        ItemStack item = named(key.material(), key.displayName(), lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "key_id"), PersistentDataType.STRING, key.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    private String readCrateFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(crateItemKey, PersistentDataType.STRING);
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    private String readKeyFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(new org.bukkit.NamespacedKey(this, "key_id"), PersistentDataType.STRING);
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    private PlayerData getData(Player player) {
        return playerCache.computeIfAbsent(player.getUniqueId(), uuid -> loadPlayerData(uuid, player.getName()));
    }

    private PlayerData loadPlayerData(UUID uuid, String name) {
        PlayerData cached = playerCache.get(uuid);
        if (cached != null) return cached;
        File file = new File(playerDataFolder, uuid + ".yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        PlayerData data = new PlayerData(uuid, name == null ? uuid.toString() : name);
        for (String key : keys.keySet()) data.keys.put(key, Math.max(0, cfg.getInt("keys." + key, 0)));
        playerCache.put(uuid, data);
        return data;
    }

    private void savePlayerData(PlayerData data) {
        File file = new File(playerDataFolder, data.uuid() + ".yml");
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("name", data.name());
        for (Map.Entry<String, Integer> entry : data.keys.entrySet()) cfg.set("keys." + entry.getKey(), entry.getValue());
        saveYaml(cfg, file);
    }

    private void addKey(Player player, String keyId, int amount, boolean notify) {
        PlayerData data = getData(player);
        data.addKey(keyId, amount);
        savePlayerData(data);
        KeyDef key = keys.get(keyId);
        if (notify && key != null) send(player, message("key-received").replace("%amount%", String.valueOf(amount)).replace("%key%", plain(key.displayName())));
    }

    private void fill(Inventory inv) {
        Material filler = safeMaterial(getConfig().getString("shop.filler"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack pane = named(filler, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(colorList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String message(String path) {
        return getConfig().getString("messages." + path, "%prefix% &cMissing message: " + path)
                .replace("%prefix%", getConfig().getString("settings.prefix", "&8[&dVelioraGacha&8] "));
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(color(text));
    }

    private String color(String text) {
        if (text == null) return "";
        String noClosingHex = text.replaceAll("</#([A-Fa-f0-9]{6})>", "");
        String noOpeningHex = noClosingHex.replaceAll("<#([A-Fa-f0-9]{6})>", "");
        return ChatColor.translateAlternateColorCodes('&', noOpeningHex);
    }

    private List<String> colorList(List<String> list) {
        List<String> out = new ArrayList<>();
        for (String line : list) out.add(color(line));
        return out;
    }

    private String plain(String text) {
        return ChatColor.stripColor(color(text));
    }

    private String formatMoney(double value) {
        return moneyFormat.format(value).replace(',', '.');
    }

    private Material safeMaterial(String name, Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }

    private Particle safeParticle(String name, Particle fallback) {
        try { return Particle.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return fallback; }
    }

    private Sound safeSound(String name, Sound fallback) {
        if (name == null || name.isBlank()) return fallback;
        try { return Sound.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return fallback; }
    }

    private int normalizeInventorySize(int size) {
        int normalized = Math.max(9, Math.min(54, size));
        return ((normalized + 8) / 9) * 9;
    }

    private int parseInt(Object value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private void saveYaml(FileConfiguration cfg, File file) {
        try { cfg.save(file); }
        catch (IOException e) { getLogger().warning("Could not save " + file.getName() + ": " + e.getMessage()); }
    }

    private String locationKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private List<String> match(String token, List<String> values) {
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(value);
        return out;
    }

    private enum GuiType { KEY_SHOP, KEY_QUANTITY, ADMIN_MAIN, KEY_MANAGER, CRATE_PLACER, CRATE_SETTINGS, REWARD_EDITOR, ROULETTE }
    private enum RewardType { ITEM, COMMAND }

    private static final class GuiHolder implements InventoryHolder {
        private final GuiType type;
        private final String payload;
        private final int amount;
        private Inventory inventory;
        private GuiHolder(GuiType type, String payload, int amount) {
            this.type = type;
            this.payload = payload;
            this.amount = amount;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private record KeyDef(String id, String displayName, Material material, double price, List<String> lore) {}
    private record CrateDef(String id, String display, Material block, String keyId, String effect, int slot, String levelReward, boolean hologram) {}
    private record CrateLocation(String crateId, String world, int x, int y, int z) {}

    private static final class PlayerData {
        private final UUID uuid;
        private final String name;
        private final Map<String, Integer> keys = new HashMap<>();
        private PlayerData(UUID uuid, String name) { this.uuid = uuid; this.name = name; }
        UUID uuid() { return uuid; }
        String name() { return name; }
        int getKey(String key) { return keys.getOrDefault(key, 0); }
        void addKey(String key, int amount) { keys.put(key, Math.max(0, getKey(key) + amount)); }
    }

    private record RewardDef(RewardType type, String crateId, String rarity, String display, Material material, int amount, int weight, String command) {
        static RewardDef item(String crateId, String rarity, Material material, int amount, int weight) {
            return new RewardDef(RewardType.ITEM, crateId, rarity, material.name() + " x" + amount, material, amount, weight, "");
        }
        static RewardDef command(String crateId, String rarity, String display, String command, Material material, int amount, int weight) {
            return new RewardDef(RewardType.COMMAND, crateId, rarity, display, material, amount, weight, command);
        }
        ItemStack toIcon() {
            ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&f" + display));
                meta.setLore(List.of(ChatColor.GRAY + "Rarity: " + rarity, ChatColor.GRAY + "Type: " + type.name()));
                item.setItemMeta(meta);
            }
            return item;
        }
    }
}
