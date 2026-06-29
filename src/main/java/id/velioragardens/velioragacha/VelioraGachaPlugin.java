package id.velioragardens.velioragacha;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.*;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class VelioraGachaPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_ORDER = List.of("shinjuore", "tenshiyoro", "kinzai", "kagekari", "kouzan");
    private static final List<String> RARITIES = List.of("uncommon", "common", "rare", "epic", "mythic");
    private static final Map<String, String> RARITY_ALIAS = Map.of("legendary", "mythic", "legend", "mythic", "mythical", "mythic");
    private static final List<String> EFFECTS = List.of("fire_spiral", "cherry_spiral", "end_rod_spiral", "oak_leaf_spiral", "bubble_spiral", "bonemeal_star_spiral", "soul_fire_spiral", "sparkle_spiral", "portal_spiral", "crit_spiral", "note_spiral");

    private final Map<String, KeyDef> keys = new LinkedHashMap<>();
    private final Map<String, CrateDef> crates = new LinkedHashMap<>();
    private final Map<String, CrateLoc> locations = new LinkedHashMap<>();
    private final Map<String, List<Reward>> rewards = new HashMap<>();
    private final Map<UUID, Opening> openings = new HashMap<>();
    private final Map<String, UUID> holograms = new HashMap<>();
    private final Map<String, Long> messageCooldown = new HashMap<>();
    private final Set<String> warningOnce = new HashSet<>();
    private final Random random = new Random();

    private File cratesFile, keysFile, rewardsFile, rewardsGuiFile, logsDir;
    private FileConfiguration cratesYml, keysYml, rewardsYml, rewardsGuiYml;
    private NamespacedKey crateKey, keyKey;
    private Economy economy;
    private DecimalFormat moneyFormat = new DecimalFormat("#,###");
    private BukkitTask effectTask, hologramTask;

    @Override public void onEnable() {
        crateKey = new NamespacedKey(this, "velioragacha_crate_id");
        keyKey = new NamespacedKey(this, "velioragacha_key_id");
        logsDir = new File(getDataFolder(), "logs");
        if (!logsDir.exists()) logsDir.mkdirs();
        saveMissing("config.yml");
        saveMissing("crates.yml");
        saveMissing("keys.yml");
        saveMissing("rewards.yml");
        saveMissing("rewards-gui.yml");
        saveMissing("examples.yml");
        saveMissing("rewards-examples.yml");
        hookVault();
        loadAll();
        cleanupHolograms();
        spawnAllHolograms();
        startEffects();
        startHologramUpdater();
        Objects.requireNonNull(getCommand("vgcreate")).setExecutor(this);
        Objects.requireNonNull(getCommand("vgcreate")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("key")).setExecutor(this);
        Objects.requireNonNull(getCommand("key")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() {
        openings.values().forEach(Opening::cancel);
        openings.clear();
        if (effectTask != null) effectTask.cancel();
        if (hologramTask != null) hologramTask.cancel();
        removeHolograms();
    }

    private void saveMissing(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) saveResource(name, false);
    }

    private void hookVault() {
        try {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            economy = rsp == null ? null : rsp.getProvider();
        } catch (Throwable throwable) {
            economy = null;
            logError("hookVault", throwable);
        }
    }

    private void loadAll() {
        reloadConfig();
        warningOnce.clear();
        moneyFormat = new DecimalFormat(getConfig().getString("economy.money-format", "#,###"));
        cratesFile = new File(getDataFolder(), "crates.yml");
        keysFile = new File(getDataFolder(), "keys.yml");
        rewardsFile = new File(getDataFolder(), "rewards.yml");
        rewardsGuiFile = new File(getDataFolder(), "rewards-gui.yml");
        cratesYml = YamlConfiguration.loadConfiguration(cratesFile);
        keysYml = YamlConfiguration.loadConfiguration(keysFile);
        rewardsYml = YamlConfiguration.loadConfiguration(rewardsFile);
        rewardsGuiYml = YamlConfiguration.loadConfiguration(rewardsGuiFile);
        loadKeys();
        loadCrates();
        loadRewards();
    }

    private boolean showTestCrate() { return getConfig().getBoolean("settings.show-test-crate", true); }
    private boolean testRewardsEnabled() { return getConfig().getBoolean("settings.test-crate-has-default-rewards", true); }

    private void loadKeys() {
        keys.clear();
        ConfigurationSection root = keysYml.getConfigurationSection("keys");
        if (root == null) return;
        Map<String, KeyDef> tmp = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            String path = "keys." + id;
            String display = keysYml.getString(path + ".display-name", id);
            tmp.put(normal(id), new KeyDef(normal(id), display, keysYml.getString(path + ".short-name", shortKeyName(display)), keysYml.getString(path + ".prefix", ""), keysYml.getString(path + ".suffix", ""), parseMaterial(keysYml.getString(path + ".material"), Material.TRIPWIRE_HOOK, "key material " + id), keysYml.getDouble(path + ".price", 150000.0D), keysYml.getBoolean(path + ".glow", true), keysYml.getStringList(path + ".lore")));
        }
        for (String crate : PLAYER_ORDER) {
            String id = crate + "_key";
            if (tmp.containsKey(id)) keys.put(id, tmp.remove(id));
        }
        if (showTestCrate() && tmp.containsKey("test_key")) keys.put("test_key", tmp.remove("test_key")); else tmp.remove("test_key");
        keys.putAll(tmp);
    }

    private void loadCrates() {
        crates.clear();
        locations.clear();
        ConfigurationSection root = cratesYml.getConfigurationSection("crates");
        Map<String, CrateDef> tmp = new LinkedHashMap<>();
        if (root != null) {
            for (String id : root.getKeys(false)) {
                String path = "crates." + id;
                if (!cratesYml.getBoolean(path + ".enabled", true)) continue;
                tmp.put(normal(id), new CrateDef(normal(id), cratesYml.getString(path + ".display", id), parseMaterial(cratesYml.getString(path + ".block"), Material.CHEST, "crate block " + id), normal(cratesYml.getString(path + ".key", id + "_key")), normalizeEffect(cratesYml.getString(path + ".effect", "end_rod_spiral"), id), cratesYml.getString(path + ".level-reward", "Default"), cratesYml.getBoolean(path + ".hologram-enabled", true), cratesYml.getString(path + ".display-colors.prefix", ""), cratesYml.getString(path + ".display-colors.suffix", "")));
            }
        }
        for (String id : PLAYER_ORDER) if (tmp.containsKey(id)) crates.put(id, tmp.remove(id));
        if (showTestCrate() && tmp.containsKey("test")) crates.put("test", tmp.remove("test")); else tmp.remove("test");
        crates.putAll(tmp);

        ConfigurationSection locs = cratesYml.getConfigurationSection("locations");
        if (locs != null) {
            for (String key : locs.getKeys(false)) {
                locations.put(key, new CrateLoc(normal(cratesYml.getString("locations." + key + ".crate", "")), cratesYml.getString("locations." + key + ".world", "world"), cratesYml.getInt("locations." + key + ".x"), cratesYml.getInt("locations." + key + ".y"), cratesYml.getInt("locations." + key + ".z")));
            }
        }
    }

    private void loadRewards() {
        rewards.clear();
        ConfigurationSection root = rewardsYml.getConfigurationSection("rewards");
        if (root != null) {
            for (String crate : root.getKeys(false)) {
                if (normal(crate).equals("test") && !testRewardsEnabled()) continue;
                ConfigurationSection crateSection = root.getConfigurationSection(crate);
                if (crateSection == null) continue;
                for (String rawRarity : crateSection.getKeys(false)) {
                    String rarity = normalizeRarity(rawRarity);
                    for (Map<?, ?> map : rewardsYml.getMapList("rewards." + crate + "." + rawRarity)) {
                        Reward reward = manualReward(crate, rarity, map);
                        if (reward != null) rewards.computeIfAbsent(normal(crate), ignored -> new ArrayList<>()).add(reward);
                    }
                }
            }
        }
        ConfigurationSection guiRoot = rewardsGuiYml.getConfigurationSection("rewards");
        if (guiRoot != null) {
            for (String crate : guiRoot.getKeys(false)) {
                if (normal(crate).equals("test") && !testRewardsEnabled()) continue;
                ConfigurationSection crateSection = guiRoot.getConfigurationSection(crate);
                if (crateSection == null) continue;
                for (String rawRarity : crateSection.getKeys(false)) {
                    String rarity = normalizeRarity(rawRarity);
                    for (Object object : rewardsGuiYml.getList("rewards." + crate + "." + rawRarity + ".items", List.of())) {
                        if (object instanceof ItemStack item && isEditorSaveable(item)) {
                            ItemStack copy = item.clone();
                            int weight = Math.max(1, rewardsGuiYml.getInt("editor.default-weight", getConfig().getInt("editor.default-weight", 10)));
                            rewards.computeIfAbsent(normal(crate), ignored -> new ArrayList<>()).add(new Reward(RewardType.ITEM, displayName(copy), copy.getType(), Math.max(1, copy.getAmount()), weight, List.of(), copy, rarity));
                        }
                    }
                }
            }
        }
    }

    private Reward manualReward(String crate, String rarity, Map<?, ?> map) {
        try {
            String type = str(map, "type", "ITEM").toUpperCase(Locale.ROOT);
            int amount = num(map, "amount", 1);
            int weight = Math.max(1, num(map, "weight", 10));
            Material material = Material.matchMaterial(str(map, "material", type.equals("COMMAND") ? "COMMAND_BLOCK" : "STONE").toUpperCase(Locale.ROOT));
            if (material == null || material == Material.AIR) {
                logWarningOnce("invalid-material-" + crate + rarity, "Invalid reward material in crate " + crate + ", rarity " + rarity);
                return null;
            }
            String display = str(map, "display", material.name() + " x" + Math.max(1, amount));
            if (type.equals("COMMAND")) {
                List<String> commands = commands(map);
                if (commands.isEmpty()) return null;
                return new Reward(RewardType.COMMAND, display, material, Math.max(1, amount), weight, commands, item(material, display, List.of("&7Command reward", "&7Rarity: &f" + prettyRarity(rarity))), rarity);
            }
            if (amount <= 0) return null;
            ItemStack item = new ItemStack(material, Math.min(64, amount));
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (map.containsKey("display")) meta.setDisplayName(color(display));
                Object loreRaw = map.get("lore");
                if (loreRaw instanceof List<?> loreList) meta.setLore(loreList.stream().map(String::valueOf).map(this::color).toList());
                item.setItemMeta(meta);
            }
            return new Reward(RewardType.ITEM, display, material, amount, weight, List.of(), item, rarity);
        } catch (Throwable throwable) {
            logError("load reward " + crate + " " + rarity, throwable);
            return null;
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            if (cmd.getName().equalsIgnoreCase("key")) return handleKeyCommand(sender, args);
            return handleAdminCommand(sender, args);
        } catch (Throwable throwable) {
            logError("command /" + label, sender instanceof Player player ? player : null, null, throwable);
            msg(sender, "&cVelioraGacha error. Cek plugins/VelioraGacha/logs/latest.log");
            return true;
        }
    }

    private boolean handleKeyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("shop")) {
            if (!player.hasPermission("velioragacha.shop")) return deny(player);
            openShop(player);
        } else msg(sender, "&f/key shop");
        return true;
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            msg(sender, "&d/vgcreate gui &7| &d/vgcreate givekey <player|*> <key> <amount> &7| &d/vgcreate effecttest <crate> &7| &d/vgcreate reload/debug/dump");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("velioragacha.reload")) return deny(sender);
                removeHolograms();
                loadAll();
                spawnAllHolograms();
                restartEffects();
                restartHologramUpdater();
                msg(sender, msg("reload-success", "&aVelioraGacha berhasil direload."));
            }
            case "gui" -> {
                if (!(sender instanceof Player player)) return true;
                if (!player.hasPermission("velioragacha.gui")) return deny(player);
                openAdmin(player);
            }
            case "givekey" -> giveKey(sender, args);
            case "effecttest" -> effectTest(sender, args);
            case "debug" -> {
                if (!sender.hasPermission("velioragacha.debug") && !sender.hasPermission("velioragacha.admin")) return deny(sender);
                sendDebug(sender);
            }
            case "dump" -> {
                if (!sender.hasPermission("velioragacha.debug") && !sender.hasPermission("velioragacha.admin")) return deny(sender);
                createDump(sender);
            }
            default -> msg(sender, "&d/vgcreate gui &7| &d/vgcreate givekey <player|*> <key> <amount> &7| &d/vgcreate effecttest <crate> &7| &d/vgcreate reload/debug/dump");
        }
        return true;
    }

    private boolean giveKey(CommandSender sender, String[] args) {
        if (!sender.hasPermission("velioragacha.givekey")) return deny(sender);
        if (args.length < 4) {
            msg(sender, "&c/vgcreate givekey <player|*> <key> <amount>");
            return true;
        }
        KeyDef key = keys.get(normal(args[2]));
        int amount = parse(args[3]);
        if (key == null || amount < 1) {
            msg(sender, "&cKey/amount tidak valid.");
            return true;
        }
        if (args[1].equals("*")) {
            for (Player player : Bukkit.getOnlinePlayers()) giveKeyItems(player, key, amount);
            msg(sender, "&aKey fisik diberikan ke semua player online.");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(sender, "&cPlayer harus online untuk menerima key fisik.");
            return true;
        }
        giveKeyItems(target, key, amount);
        msg(sender, "&aKey fisik diberikan ke &f" + target.getName());
        return true;
    }

    private void effectTest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return;
        if (!player.hasPermission("velioragacha.debug") && !player.hasPermission("velioragacha.admin")) {
            deny(player);
            return;
        }
        String filter = args.length >= 2 ? normal(args[1]) : "";
        Map.Entry<String, CrateLoc> nearest = nearestCrate(player, filter);
        if (nearest == null) {
            msg(player, "&cTidak ada crate terpasang yang cocok.");
            return;
        }
        msg(player, "&aEffect test 3 detik untuk crate &f" + nearest.getValue().crate);
        new BukkitRunnable() {
            int ticks = 0;
            double t = 0;
            @Override public void run() {
                ticks += 3;
                t += 0.55D;
                effect(nearest.getValue(), t, true);
                if (ticks >= 60) cancel();
            }
        }.runTaskTimer(this, 1L, 3L);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("key")) return args.length == 1 ? filter(List.of("shop"), args[0]) : List.of();
        if (args.length == 1) return filter(List.of("gui", "givekey", "effecttest", "reload", "debug", "dump"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("effecttest")) return filter(new ArrayList<>(crates.keySet()), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("givekey")) return filter(new ArrayList<>(keys.keySet()), args[2]);
        if (args.length == 4 && args[0].equalsIgnoreCase("givekey")) return filter(List.of("1", "5", "10", "32", "64"), args[3]);
        return List.of();
    }

    private void openAdmin(Player player) {
        Inventory inv = gui(new Holder("admin", null, null, 1), 27, "&8VelioraGacha Admin");
        fill(inv);
        inv.setItem(11, item(Material.TRIPWIRE_HOOK, "&bKey Manager", List.of("&7Klik untuk buka Key Manager.")));
        inv.setItem(13, item(Material.CHEST, "&6Crate Placer", List.of("&7Klik untuk ambil crate placer.")));
        inv.setItem(15, item(Material.COMPARATOR, "&eCrate Settings", List.of("&7Klik untuk setting crate.")));
        player.openInventory(inv);
    }

    private void openKeys(Player player) {
        Inventory inv = gui(new Holder("keys", null, null, 1), 27, "&8Key Manager");
        fill(inv);
        int[] slots = {10, 11, 13, 15, 16, 22};
        int i = 0;
        for (CrateDef crate : adminCrates()) {
            KeyDef key = keys.get(crate.key);
            if (key != null && i < slots.length) inv.setItem(slots[i++], keyIcon(key, List.of("&7Harga: &a" + cash(key.price), "&7Crate: &f" + crate.name, "", "&eLeft click: ambil 1", "&eShift click: ambil 10", "&eRight click: ambil 64")));
        }
        player.openInventory(inv);
    }

    private void openPlacer(Player player) {
        Inventory inv = gui(new Holder("placer", null, null, 1), 27, "&8Crate Placer");
        fill(inv);
        int[] slots = {10, 11, 13, 15, 16, 22};
        int i = 0;
        for (CrateDef crate : adminCrates()) if (i < slots.length) inv.setItem(slots[i++], crateItem(crate));
        player.openInventory(inv);
    }

    private void openSettings(Player player) {
        Inventory inv = gui(new Holder("settings", null, null, 1), 27, "&8Crate Settings");
        fill(inv);
        int[] slots = {10, 11, 13, 15, 16, 22};
        int i = 0;
        for (CrateDef crate : adminCrates()) if (i < slots.length) inv.setItem(slots[i++], itemWithPdc(crate.block, "&e" + crate.name, settingsLore(crate), crateKey, crate.id));
        player.openInventory(inv);
    }

    private List<String> settingsLore(CrateDef crate) {
        int count = rewardCount(crate.id);
        List<String> rarities = rewardRarities(crate.id);
        return List.of("&7Reward status: " + (count > 0 ? "&aREADY" : "&cEMPTY / BELUM DIISI"), "&7Total reward: &f" + count, "&7Rarity isi: &f" + (rarities.isEmpty() ? "-" : String.join(", ", rarities)), "&7Effect: &f" + crate.effect, "&7Hologram: &f" + (crate.hologram ? "ON" : "OFF"), "&7Key: &f" + crate.key, "", "&eKlik untuk detail setting.");
    }

    private void openDetail(Player player, String crateId) {
        CrateDef crate = crates.get(crateId);
        if (crate == null) return;
        int count = rewardCount(crateId);
        Inventory inv = gui(new Holder("detail", crateId, null, 1), 54, "&8Setting: " + crate.name);
        fill(inv);
        inv.setItem(10, item(Material.NAME_TAG, "&bToggle Hologram", List.of("&7Status: &f" + (crate.hologram ? "ON" : "OFF"), "&eKlik untuk toggle.")));
        inv.setItem(12, item(Material.BLAZE_POWDER, "&6Effect Selector", List.of("&7Effect sekarang: &f" + crate.effect, "&eKlik untuk ganti.")));
        inv.setItem(14, item(count > 0 ? Material.CHEST : Material.REDSTONE_BLOCK, count > 0 ? "&aReward Editor" : "&cReward Editor - EMPTY", List.of("&7Klik untuk edit item reward.", count > 0 ? "&aCrate READY." : "&cIsi reward dulu.")));
        inv.setItem(16, item(Material.ENDER_EYE, "&dPreview Crate", List.of("&7Preview animasi tanpa key/reward.", count > 0 ? "&aPreview pakai reward asli." : "&ePreview memakai fallback karena reward kosong.")));
        inv.setItem(22, item(Material.ARROW, "&eBack", List.of("&7Balik ke Crate Settings.")));
        inv.setItem(28, item(count > 0 ? Material.LIME_CONCRETE : Material.RED_CONCRETE, count > 0 ? "&aReward Status: READY" : "&cReward Status: EMPTY", rewardStatusLore(crateId)));
        player.openInventory(inv);
    }

    private List<String> rewardStatusLore(String crateId) {
        int count = rewardCount(crateId);
        List<String> rarities = rewardRarities(crateId);
        List<String> lore = new ArrayList<>();
        lore.add("&7Total reward: &f" + count);
        lore.add("&7Rarity terisi: &f" + (rarities.isEmpty() ? "-" : String.join(", ", rarities)));
        if (count <= 0) lore.add("&cIsi reward dulu lewat Reward Editor atau rewards.yml.");
        return lore;
    }

    private void openEffect(Player player, String crateId) {
        CrateDef crate = crates.get(crateId);
        if (crate == null) return;
        Inventory inv = gui(new Holder("effect", crateId, null, 1), 54, "&8Effect: " + crate.name);
        fill(inv);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22};
        for (int i = 0; i < EFFECTS.size() && i < slots.length; i++) {
            String effect = EFFECTS.get(i);
            inv.setItem(slots[i], itemWithPdc(effectIcon(effect), "&e" + effect, List.of("&7Klik untuk pakai effect ini."), crateKey, effect));
        }
        inv.setItem(49, item(Material.ARROW, "&eBack"));
        player.openInventory(inv);
    }

    private void openRewardRarity(Player player, String crateId) {
        CrateDef crate = crates.get(crateId);
        if (crate == null) return;
        Inventory inv = gui(new Holder("rarity", crateId, null, 1), 27, "&8Rewards: " + crate.name);
        fill(inv);
        int[] slots = {10, 11, 13, 15, 16};
        Material[] mats = {Material.LIME_STAINED_GLASS_PANE, Material.WHITE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE};
        for (int i = 0; i < RARITIES.size(); i++) {
            int count = rewardCount(crateId, RARITIES.get(i));
            inv.setItem(slots[i], itemWithPdc(mats[i], (count > 0 ? "&a" : "&c") + prettyRarity(RARITIES.get(i)), List.of("&7Reward tersimpan: &f" + count, count > 0 ? "&aAda reward." : "&cBelum ada reward.", "&eKlik untuk edit."), keyKey, RARITIES.get(i)));
        }
        inv.setItem(22, item(Material.ARROW, "&eBack"));
        player.openInventory(inv);
    }

    private void openRewardEditor(Player player, String crateId, String rarity) {
        rarity = normalizeRarity(rarity);
        Inventory inv = gui(new Holder("rewardedit", crateId, rarity, 1), 54, "&8Edit " + crateId + " " + prettyRarity(rarity));
        int slot = 0;
        for (Object object : rewardsGuiYml.getList("rewards." + crateId + "." + rarity + ".items", List.of())) {
            if (object instanceof ItemStack stack && isEditorSaveable(stack) && slot < inv.getSize()) inv.setItem(slot++, stack.clone());
        }
        player.openInventory(inv);
    }

    private void openShop(Player player) {
        Inventory inv = gui(new Holder("shop", null, null, 1), 54, getConfig().getString("shop.title", "&8Key Shop"));
        fill(inv);
        int[] slots = {20, 21, 22, 23, 24};
        int i = 0;
        for (CrateDef crate : playerCrates()) {
            KeyDef key = keys.get(crate.key);
            if (key != null && i < slots.length) inv.setItem(slots[i++], keyIcon(key, List.of("&7Crate: &f" + crate.name, "&7Harga: &a" + cash(key.price), "", "&eKlik untuk pilih jumlah.")));
        }
        player.openInventory(inv);
    }

    private void openQty(Player player, String keyId, int amount) {
        KeyDef key = keys.get(keyId);
        if (key == null) return;
        int max = getConfig().getInt("settings.max-shop-amount", 64);
        amount = Math.max(1, Math.min(max, amount));
        Inventory inv = gui(new Holder("qty", keyId, null, amount), 54, getConfig().getString("shop.quantity-title", "&8Beli %key%").replace("%key%", plain(key.name)));
        fill(inv);
        inv.setItem(20, item(Material.RED_STAINED_GLASS_PANE, "&c-10"));
        inv.setItem(21, item(Material.RED_DYE, "&c-1"));
        inv.setItem(22, keyIcon(key, List.of("&7Amount: &f" + amount, "&7Total: &a" + cash(key.price * amount))));
        inv.setItem(23, item(Material.LIME_DYE, "&a+1"));
        inv.setItem(24, item(Material.LIME_STAINED_GLASS_PANE, "&a+10"));
        inv.setItem(30, item(Material.ARROW, "&eBack"));
        inv.setItem(32, item(Material.EMERALD_BLOCK, "&aConfirm", List.of("&7Beli key fisik.")));
        player.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        try {
            if (!(event.getWhoClicked() instanceof Player player) || !(event.getInventory().getHolder() instanceof Holder holder)) return;
            if (holder.type.equals("rewardedit")) return;
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) return;
            ItemStack clicked = event.getCurrentItem();
            int slot = event.getRawSlot();
            switch (holder.type) {
                case "admin" -> { if (slot == 11) openKeys(player); else if (slot == 13) openPlacer(player); else if (slot == 15) openSettings(player); }
                case "keys" -> { String id = pdc(clicked, keyKey); if (id != null) giveKeyItems(player, keys.get(id), event.isRightClick() ? 64 : event.isShiftClick() ? 10 : 1); }
                case "placer" -> { String id = pdc(clicked, crateKey); if (id != null && crates.containsKey(id)) player.getInventory().addItem(crateItem(crates.get(id))); }
                case "settings" -> { String id = pdc(clicked, crateKey); if (id != null) openDetail(player, id); }
                case "detail" -> detailClick(player, holder.id, slot);
                case "effect" -> effectClick(player, holder.id, clicked, slot);
                case "rarity" -> { if (slot == 22) openDetail(player, holder.id); else { String rarity = pdc(clicked, keyKey); if (rarity != null) openRewardEditor(player, holder.id, rarity); } }
                case "shop" -> { String id = pdc(clicked, keyKey); if (id != null) openQty(player, id, 1); }
                case "qty" -> qtyClick(player, holder, slot);
                case "roulette" -> { }
            }
        } catch (Throwable throwable) {
            logError("inventory click", event.getWhoClicked() instanceof Player player ? player : null, null, throwable);
        }
    }

    private void detailClick(Player player, String crateId, int slot) {
        if (slot == 10) {
            boolean current = cratesYml.getBoolean("crates." + crateId + ".hologram-enabled", true);
            cratesYml.set("crates." + crateId + ".hologram-enabled", !current);
            save(cratesYml, cratesFile);
            loadCrates();
            refreshCrateHolograms(crateId);
            openDetail(player, crateId);
        } else if (slot == 12) openEffect(player, crateId);
        else if (slot == 14) openRewardRarity(player, crateId);
        else if (slot == 16) {
            if (rewardCount(crateId) <= 0) msg(player, "&ePreview memakai fallback karena reward kosong.");
            startRoulette(player, crates.get(crateId), true);
        } else if (slot == 22) openSettings(player);
    }

    private void effectClick(Player player, String crateId, ItemStack clicked, int slot) {
        if (slot == 49) { openDetail(player, crateId); return; }
        String effect = pdc(clicked, crateKey);
        if (effect == null) return;
        cratesYml.set("crates." + crateId + ".effect", normalizeEffect(effect, crateId));
        save(cratesYml, cratesFile);
        loadCrates();
        restartEffects();
        msg(player, "&aEffect crate diubah ke &f" + effect);
        openDetail(player, crateId);
    }

    private void qtyClick(Player player, Holder holder, int slot) {
        int amount = holder.amount;
        if (slot == 20) amount -= 10;
        else if (slot == 21) amount--;
        else if (slot == 23) amount++;
        else if (slot == 24) amount += 10;
        else if (slot == 30) { openShop(player); return; }
        else if (slot == 32) { buyKey(player, holder.id, holder.amount); return; }
        else return;
        openQty(player, holder.id, amount);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        try {
            if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
            if (holder.type.equals("rewardedit")) {
                saveRewardEditor(event.getInventory(), holder.id, holder.rarity);
                if (event.getPlayer() instanceof Player player) msg(player, rewardCount(holder.id) > 0 ? "&aReward tersimpan. Crate sekarang READY." : "&eReward tersimpan, tapi crate masih EMPTY.");
            }
            if (holder.type.equals("roulette") && event.getPlayer() instanceof Player player) {
                Opening opening = openings.get(player.getUniqueId());
                if (opening != null && getConfig().getString("animation.close-behavior", "COMPLETE_REWARD").equalsIgnoreCase("COMPLETE_REWARD")) finishOpening(player, opening, false);
            }
        } catch (Throwable throwable) {
            logError("inventory close", event.getPlayer() instanceof Player player ? player : null, null, throwable);
        }
    }

    private void saveRewardEditor(Inventory inv, String crateId, String rarity) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : inv.getContents()) if (isEditorSaveable(item)) items.add(item.clone());
        rewardsGuiYml.set("rewards." + crateId + "." + normalizeRarity(rarity) + ".items", items);
        save(rewardsGuiYml, rewardsGuiFile);
        loadRewards();
    }

    private boolean isEditorSaveable(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getType() == Material.BLACK_STAINED_GLASS_PANE) return false;
        if (pdc(item, keyKey) != null || pdc(item, crateKey) != null) return false;
        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? plain(item.getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT) : "";
        return !name.equals("back") && !name.contains("kembali") && !name.contains("reward status");
    }

    private void buyKey(Player player, String keyId, int amount) {
        KeyDef key = keys.get(keyId);
        if (key == null) return;
        int max = getConfig().getInt("settings.max-shop-amount", 64);
        amount = Math.max(1, Math.min(max, amount));
        if (!canFitKey(player.getInventory(), key, amount)) { msg(player, "&cInventory penuh. Kosongkan slot dulu."); return; }
        if (economy == null) { msg(player, msg("economy-missing", "&cEconomy Vault tidak tersedia.")); return; }
        double total = key.price * amount;
        if (economy.getBalance(player) < total) { msg(player, msg("not-enough-money", "&cUang kamu kurang. Butuh &f%price%&c.").replace("%price%", cash(total))); return; }
        economy.withdrawPlayer(player, total);
        giveKeyItems(player, key, amount);
        msg(player, msg("key-bought", "&aBerhasil membeli &f%amount%x %key% &adengan harga &f%price%&a.").replace("%amount%", String.valueOf(amount)).replace("%key%", plain(key.name)).replace("%price%", cash(total)));
        openQty(player, keyId, amount);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onPlace(BlockPlaceEvent event) {
        try {
            String crateId = pdc(event.getItemInHand(), crateKey);
            if (crateId == null) return;
            if (!event.getPlayer().hasPermission("velioragacha.place")) { event.setCancelled(true); deny(event.getPlayer()); return; }
            Location loc = event.getBlockPlaced().getLocation();
            String key = locKey(loc);
            cratesYml.set("locations." + key + ".crate", crateId);
            cratesYml.set("locations." + key + ".world", loc.getWorld().getName());
            cratesYml.set("locations." + key + ".x", loc.getBlockX());
            cratesYml.set("locations." + key + ".y", loc.getBlockY());
            cratesYml.set("locations." + key + ".z", loc.getBlockZ());
            save(cratesYml, cratesFile);
            locations.put(key, new CrateLoc(crateId, loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            spawnHologram(key, locations.get(key));
            msg(event.getPlayer(), msg("crate-placed", "&aCrate &f%crate% &aberhasil dibuat.").replace("%crate%", crateName(crateId)));
        } catch (Throwable throwable) {
            logError("place crate", event.getPlayer(), null, throwable);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onBreak(BlockBreakEvent event) {
        try {
            String key = locKey(event.getBlock().getLocation());
            CrateLoc loc = locations.get(key);
            if (loc == null) return;
            if (!event.getPlayer().hasPermission("velioragacha.break")) { event.setCancelled(true); deny(event.getPlayer()); return; }
            removeHologram(key);
            cratesYml.set("locations." + key, null);
            save(cratesYml, cratesFile);
            locations.remove(key);
            event.setDropItems(false);
            CrateDef crate = crates.get(loc.crate);
            if (crate != null) event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), crateItem(crate));
            msg(event.getPlayer(), msg("crate-broken", "&eCrate &f%crate% &edihapus.").replace("%crate%", crateName(loc.crate)));
        } catch (Throwable throwable) {
            logError("break crate", event.getPlayer(), null, throwable);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onInteract(PlayerInteractEvent event) {
        try {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            Block block = event.getClickedBlock();
            if (block == null) return;
            CrateLoc loc = locations.get(locKey(block.getLocation()));
            if (loc == null) return;
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (!player.hasPermission("velioragacha.open")) { deny(player); return; }
            CrateDef crate = crates.get(loc.crate);
            if (crate == null) return;
            KeyCheck key = findKey(player, crate.key);
            if (key.state == KeyState.NONE) { noKey(player, block.getLocation(), crate); return; }
            if (key.state == KeyState.WRONG) { wrongKey(player, block.getLocation(), crate); return; }
            if (startRoulette(player, crate, false)) consumeOne(key.stack);
        } catch (Throwable throwable) {
            logError("open crate", event.getPlayer(), null, throwable);
        }
    }

    private boolean startRoulette(Player player, CrateDef crate, boolean preview) {
        if (crate == null) return false;
        if (openings.containsKey(player.getUniqueId())) { msg(player, msg("animation-running", "&cKamu sedang membuka crate.")); return false; }
        if (!preview && !hasValidRewards(crate.id)) {
            notifyCrate(player, "no-reward", crate, msg("no-reward", "&cCrate ini belum punya reward. Isi dulu di &f/vgcreate gui &c-> Crate Settings."), "&cReward Kosong", "&fIsi reward dulu di /vgcreate gui");
            logWarningOnce("empty-crate-" + crate.id, "Crate " + crate.id + " has no valid rewards.");
            return false;
        }
        Reward finalReward = chooseReward(crate.id, true);
        int size = invSize(getConfig().getInt("animation.gui-size", 54));
        Inventory inv = gui(new Holder("roulette", crate.id, null, 1), size, getConfig().getString("animation.title", "&8Opening %crate%").replace("%crate%", crate.name));
        fill(inv);
        renderMarker(inv, modeMarkerSlot(normalizeAnimationMode(getConfig().getString("animation.mode", "BOX"))));
        player.openInventory(inv);

        int duration = Math.max(20, getConfig().getInt("animation.duration-ticks", 120));
        int start = Math.max(1, getConfig().getInt("animation.start-speed-ticks", 1));
        int end = Math.max(start, getConfig().getInt("animation.end-speed-ticks", 9));
        int closeDelay = Math.max(1, getConfig().getInt("animation.result.keep-open-ticks", getConfig().getInt("animation.close-delay-ticks", 45)));
        Sound tickSound = sound(getConfig().getString("animation.sound-tick"), Sound.BLOCK_NOTE_BLOCK_HAT);
        Opening opening = new Opening(player.getUniqueId(), crate, finalReward, inv, preview);
        BukkitTask task = new BukkitRunnable() {
            int ticks = 0;
            int next = 0;
            int cursor = 0;
            @Override public void run() {
                ticks++;
                if (ticks >= next) {
                    double progress = Math.min(1.0D, (double) ticks / duration);
                    int delay = start + (int) Math.round((end - start) * progress);
                    next = ticks + delay;
                    renderAnimation(inv, crate, finalReward, ticks >= duration, cursor++, progress);
                    player.playSound(player.getLocation(), tickSound, 0.35F, 1.5F);
                }
                if (ticks >= duration) {
                    finishOpening(player, opening, true);
                    Bukkit.getScheduler().runTaskLater(VelioraGachaPlugin.this, () -> {
                        if (player.isOnline() && player.getOpenInventory().getTopInventory().equals(inv)) player.closeInventory();
                    }, closeDelay);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
        opening.task = task;
        openings.put(player.getUniqueId(), opening);
        return true;
    }

    private void renderAnimation(Inventory inv, CrateDef crate, Reward finalReward, boolean finished, int tick, double progress) {
        clearAnimationSlots(inv);
        String mode = normalizeAnimationMode(getConfig().getString("animation.mode", "BOX"));
        renderMarker(inv, modeMarkerSlot(mode));
        switch (mode) {
            case "SPIRAL" -> renderSpiral(inv, crate, finalReward, finished, tick, progress);
            case "SNAKE" -> renderSnake(inv, crate, finalReward, finished, tick, progress);
            case "SIMPLE" -> renderSimple(inv, crate, finalReward, finished, tick, progress);
            default -> renderBox(inv, crate, finalReward, finished, tick, progress);
        }
        if (finished) renderResultScreen(inv, crate, finalReward);
    }

    private void renderMarker(Inventory inv, int markerSlot) {
        Material marker = parseMaterial(getConfig().getString("animation.marker-material"), Material.TORCH, "animation marker");
        set(inv, markerSlot, item(marker, getConfig().getString("animation.marker-name", "&a▼ HADIAH ▼")));
    }

    private void renderBox(Inventory inv, CrateDef crate, Reward finalReward, boolean finished, int tick, double progress) {
        List<Integer> slots = intList("animation.box.box-slots", List.of(11, 12, 13, 14, 15, 24, 33, 42, 41, 40, 39, 38, 29, 20));
        int offset = Math.floorMod(tick, slots.size());
        for (int i = 0; i < slots.size(); i++) set(inv, slots.get(i), rewardForSpin(crate, finalReward, progress, i == offset));
        set(inv, modeResultSlot("BOX"), finished ? finalReward.icon() : item(parseMaterial(getConfig().getString("animation.highlight-material"), Material.LIME_STAINED_GLASS_PANE, "highlight"), "&aFOKUS HADIAH"));
    }

    private void renderSpiral(Inventory inv, CrateDef crate, Reward finalReward, boolean finished, int tick, double progress) {
        List<Integer> ring = intList("animation.spiral.ring-slots", List.of(12, 13, 14, 23, 32, 41, 40, 39, 30, 21));
        int offset = Math.floorMod(tick, ring.size());
        for (int i = 0; i < ring.size(); i++) set(inv, ring.get((i + offset) % ring.size()), rewardForSpin(crate, finalReward, progress, i == 0));
        int result = modeResultSlot("SPIRAL");
        set(inv, result, finished ? finalReward.icon() : item(parseMaterial(getConfig().getString("animation.highlight-material"), Material.LIME_STAINED_GLASS_PANE, "highlight"), "&aPUSAT HADIAH"));
    }

    private void renderSnake(Inventory inv, CrateDef crate, Reward finalReward, boolean finished, int tick, double progress) {
        List<Integer> path = intList("animation.snake.path-slots", List.of(10, 11, 12, 13, 14, 15, 16, 25, 34, 43, 42, 41, 40, 39, 38, 37, 28, 19, 20, 21, 22, 23, 24));
        int headIndex = Math.floorMod(tick, path.size());
        int length = Math.min(7, path.size());
        for (int i = 0; i < length; i++) set(inv, path.get(Math.floorMod(headIndex - i, path.size())), rewardForSpin(crate, finalReward, progress, i == 0));
        int head = path.get(headIndex);
        if (!finished) set(inv, head, item(parseMaterial(getConfig().getString("animation.snake.head-material"), Material.LIME_STAINED_GLASS_PANE, "snake head"), getConfig().getString("animation.snake.head-name", "&a▶")));
        set(inv, modeResultSlot("SNAKE"), finished ? finalReward.icon() : item(parseMaterial(getConfig().getString("animation.highlight-material"), Material.LIME_STAINED_GLASS_PANE, "highlight"), "&aTARGET"));
    }

    private void renderSimple(Inventory inv, CrateDef crate, Reward finalReward, boolean finished, int tick, double progress) {
        List<Integer> slots = intList("animation.simple.slots", List.of(20, 21, 22, 23, 24));
        int offset = Math.floorMod(tick, slots.size());
        for (int i = 0; i < slots.size(); i++) set(inv, slots.get(i), rewardForSpin(crate, finalReward, progress, i == offset));
        set(inv, modeResultSlot("SIMPLE"), finished ? finalReward.icon() : item(parseMaterial(getConfig().getString("animation.highlight-material"), Material.LIME_STAINED_GLASS_PANE, "highlight"), "&aHADIAH"));
    }

    private ItemStack rewardForSpin(CrateDef crate, Reward finalReward, double progress, boolean focus) {
        if (focus && progress > 0.72D && random.nextDouble() < progress) return finalReward.icon();
        return chooseReward(crate.id, true).icon();
    }

    private void clearAnimationSlots(Inventory inv) {
        Set<Integer> slots = new HashSet<>();
        slots.addAll(intList("animation.box.box-slots", List.of(11, 12, 13, 14, 15, 24, 33, 42, 41, 40, 39, 38, 29, 20)));
        slots.addAll(intList("animation.spiral.ring-slots", List.of(12, 13, 14, 23, 32, 41, 40, 39, 30, 21)));
        slots.addAll(intList("animation.snake.path-slots", List.of(10, 11, 12, 13, 14, 15, 16, 25, 34, 43, 42, 41, 40, 39, 38, 37, 28, 19, 20, 21, 22, 23, 24)));
        slots.addAll(intList("animation.simple.slots", List.of(20, 21, 22, 23, 24)));
        slots.add(getConfig().getInt("animation.marker-slot", 4));
        slots.add(getConfig().getInt("animation.result-slot", 22));
        slots.addAll(List.of(13, 31, 40));
        ItemStack empty = item(parseMaterial(getConfig().getString("animation.empty-material"), Material.BLACK_STAINED_GLASS_PANE, "animation empty"), " ");
        for (int slot : slots) set(inv, slot, empty);
    }

    private void renderResultScreen(Inventory inv, CrateDef crate, Reward finalReward) {
        fill(inv);
        renderMarker(inv, getConfig().getInt("animation.marker-slot", 4));
        set(inv, getConfig().getInt("animation.result-slot", 22), finalReward.icon());
        set(inv, 13, item(Material.LIME_STAINED_GLASS_PANE, "&aKamu Mendapatkan", List.of("&7Reward berhenti di slot hadiah.")));
        set(inv, 31, item(Material.BOOK, "&7Crate: &f" + plain(crate.name), List.of("&7Rarity: &f" + prettyRarity(finalReward.rarity))));
        set(inv, 40, item(Material.OAK_SIGN, "&eESC untuk tutup", List.of("&7Reward sudah masuk.", "&7Aman, tidak akan dobel.")));
    }

    private void finishOpening(Player player, Opening opening, boolean showResultScreen) {
        if (opening.rewarded) return;
        opening.rewarded = true;
        openings.remove(player.getUniqueId());
        opening.cancel();
        if (!player.isOnline()) return;
        if (!opening.preview) giveReward(player, opening.crate, opening.reward);
        Sound finishSound = sound(getConfig().getString("animation.result.sound", getConfig().getString("animation.sound-finish")), Sound.ENTITY_PLAYER_LEVELUP);
        player.playSound(player.getLocation(), finishSound, 1F, 1F);
        if (showResultScreen && player.getOpenInventory().getTopInventory().equals(opening.inventory)) renderResultScreen(opening.inventory, opening.crate, opening.reward);
        sendResultNotifications(player, opening);
    }

    private void sendResultNotifications(Player player, Opening opening) {
        if (!getConfig().getBoolean("animation.result.enabled", true)) {
            if (!opening.preview) msg(player, msg("reward-received", "&aKamu mendapatkan reward: &f%reward%&a.").replace("%reward%", plain(opening.reward.display)));
            return;
        }
        String title = resultText(getConfig().getString("animation.result.title", "&aKamu Mendapatkan!"), player, opening);
        String subtitle = resultText(getConfig().getString("animation.result.subtitle", "&f%reward%"), player, opening);
        String actionbar = resultText(getConfig().getString("animation.result.actionbar", "&aReward: &f%reward%"), player, opening);
        String chat = resultText(getConfig().getString("animation.result.chat", msg("reward-received", "&aKamu mendapatkan reward: &f%reward%&a.")), player, opening);
        if (getConfig().getBoolean("animation.result.show-title", true)) sendTitle(player, title, subtitle);
        if (getConfig().getBoolean("animation.result.show-actionbar", true)) sendActionBar(player, actionbar);
        if (getConfig().getBoolean("animation.result.send-chat", true) && !opening.preview) msg(player, chat);
    }

    private String resultText(String raw, Player player, Opening opening) {
        return raw.replace("%prefix%", getConfig().getString("settings.prefix", "&8[&dVelioraGacha&8] ")).replace("%player%", player.getName()).replace("%reward%", plain(opening.reward.display)).replace("%rarity%", prettyRarity(opening.reward.rarity)).replace("%crate%", opening.crate.id).replace("%crate_display%", plain(opening.crate.name));
    }

    private void giveReward(Player player, CrateDef crate, Reward reward) {
        if (reward.type == RewardType.COMMAND) {
            for (String command : reward.commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()).replace("%crate%", crate.id).replace("%crate_display%", plain(crate.name)).replace("%reward%", plain(reward.display)).replace("%key%", crate.key));
            return;
        }
        ItemStack item = reward.item == null ? new ItemStack(reward.material, reward.amount) : reward.item.clone();
        item.setAmount(Math.max(1, reward.amount));
        player.getInventory().addItem(item).values().forEach(left -> {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
            msg(player, "&eInventory penuh, sebagian reward jatuh di lokasi kamu.");
        });
    }

    private boolean hasValidRewards(String crate) { return !rewards.getOrDefault(crate, List.of()).isEmpty(); }
    private int rewardCount(String crate) { return rewards.getOrDefault(crate, List.of()).size(); }
    private int rewardCount(String crate, String rarity) { return (int) rewards.getOrDefault(crate, List.of()).stream().filter(r -> normalizeRarity(r.rarity).equals(normalizeRarity(rarity))).count(); }
    private List<String> rewardRarities(String crate) { List<String> out = new ArrayList<>(); for (String rarity : RARITIES) if (rewardCount(crate, rarity) > 0) out.add(prettyRarity(rarity)); return out; }

    private Reward chooseReward(String crate, boolean fallback) {
        List<Reward> list = rewards.getOrDefault(crate, List.of());
        if (list.isEmpty()) return fallback ? fallbackReward() : null;
        int total = list.stream().mapToInt(r -> Math.max(1, r.weight)).sum();
        int roll = random.nextInt(total) + 1;
        int current = 0;
        for (Reward reward : list) {
            current += Math.max(1, reward.weight);
            if (roll <= current) return reward;
        }
        return list.get(0);
    }

    private Reward fallbackReward() { return new Reward(RewardType.ITEM, "IRON_INGOT x8", Material.IRON_INGOT, 8, 10, List.of(), new ItemStack(Material.IRON_INGOT, 8), "common"); }

    private void noKey(Player player, Location loc, CrateDef crate) {
        KeyDef key = keys.get(crate.key);
        String keyName = key == null ? crate.key : plain(key.name);
        notifyCrate(player, "no-key", crate, msg("no-key", "&cKamu butuh &f%key_name% &cuntuk membuka crate ini. Beli di &f/key shop&c.").replace("%key_name%", keyName), "&cButuh Key", "&fPegang " + keyName + " &7untuk membuka crate ini.");
        knock(player, loc);
    }

    private void wrongKey(Player player, Location loc, CrateDef crate) {
        notifyCrate(player, "wrong-key", crate, msg("wrong-key", "&cKey ini tidak cocok untuk crate ini."), "&cKey Salah", "&fKey ini tidak cocok untuk crate ini.");
        knock(player, loc);
    }

    private void notifyCrate(Player player, String type, CrateDef crate, String message, String title, String subtitle) {
        String delivery = getConfig().getString("messages.delivery." + type, "ACTIONBAR").toUpperCase(Locale.ROOT);
        boolean delivered = false;
        if (delivery.equals("ACTIONBAR") || delivery.equals("BOTH")) delivered = sendActionBar(player, message);
        if (delivery.equals("TITLE")) { sendTitle(player, title, subtitle); delivered = true; }
        if (delivery.equals("CHAT") || delivery.equals("BOTH") || !delivered) sendChatCooldown(player, type, crate.id, message);
    }

    private void sendChatCooldown(Player player, String type, String crate, String message) {
        long seconds = getConfig().getLong("messages.cooldown." + type + "-chat-seconds", type.equals("no-reward") ? 5 : 3);
        String key = player.getUniqueId() + ":" + type + ":" + crate;
        long now = System.currentTimeMillis();
        if (now - messageCooldown.getOrDefault(key, 0L) >= seconds * 1000L) {
            messageCooldown.put(key, now);
            msg(player, message);
        }
    }

    private boolean sendActionBar(Player player, String message) {
        try {
            Method method = player.getClass().getMethod("sendActionBar", String.class);
            method.invoke(player, color(message));
            return true;
        } catch (Throwable ignored) { }
        try {
            Class<?> component = Class.forName("net.kyori.adventure.text.Component");
            Object text = component.getMethod("text", String.class).invoke(null, ChatColor.stripColor(color(message)));
            Method method = player.getClass().getMethod("sendActionBar", component);
            method.invoke(player, text);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void sendTitle(Player player, String title, String subtitle) {
        try { player.sendTitle(color(title), color(subtitle), 5, 25, 5); }
        catch (Throwable ignored) { sendChatCooldown(player, "title", "global", title + " " + subtitle); }
    }

    private void knock(Player player, Location loc) {
        player.playSound(player.getLocation(), sound(getConfig().getString("no-key.sound"), Sound.ENTITY_VILLAGER_NO), 1, 1);
        if (!getConfig().getBoolean("no-key.knockback.enabled", true)) return;
        Vector vector = player.getLocation().toVector().subtract(loc.toVector());
        if (vector.lengthSquared() == 0) vector = player.getLocation().getDirection().multiply(-1);
        vector.normalize().multiply(getConfig().getDouble("no-key.knockback.strength", .65));
        vector.setY(getConfig().getDouble("no-key.knockback.y", .25));
        player.setVelocity(vector);
    }

    private KeyCheck findKey(Player player, String required) {
        ItemStack main = player.getInventory().getItemInMainHand();
        String mainId = pdc(main, keyKey);
        if (mainId != null) return mainId.equals(required) ? new KeyCheck(KeyState.OK, main) : new KeyCheck(KeyState.WRONG, main);
        ItemStack off = player.getInventory().getItemInOffHand();
        String offId = pdc(off, keyKey);
        if (offId != null) return offId.equals(required) ? new KeyCheck(KeyState.OK, off) : new KeyCheck(KeyState.WRONG, off);
        return new KeyCheck(KeyState.NONE, null);
    }

    private void consumeOne(ItemStack stack) { if (stack != null && stack.getAmount() > 0) stack.setAmount(stack.getAmount() - 1); }

    private int countPhysicalKeys(Player player, String keyId) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) if (keyId.equals(pdc(item, keyKey))) count += item.getAmount();
        if (keyId.equals(pdc(player.getInventory().getItemInOffHand(), keyKey))) count += player.getInventory().getItemInOffHand().getAmount();
        return count;
    }

    private void spawnAllHolograms() { if (getConfig().getBoolean("hologram.enabled", true)) locations.forEach(this::spawnHologram); }

    private void spawnHologram(String key, CrateLoc loc) {
        try {
            removeHologram(key);
            CrateDef crate = crates.get(loc.crate);
            World world = Bukkit.getWorld(loc.world);
            if (world == null || crate == null || !crate.hologram || !getConfig().getBoolean("hologram.enabled", true)) return;
            Location location = new Location(world, loc.x + .5, loc.y + getConfig().getDouble("hologram.y-offset", 2.35), loc.z + .5);
            TextDisplay display = world.spawn(location, TextDisplay.class);
            display.addScoreboardTag("velioragacha_hologram");
            display.setPersistent(false);
            display.setGravity(false);
            display.setBillboard(parseBillboard(getConfig().getString("hologram.billboard", "FIXED")));
            display.setText(color(holoText(crate, loc)));
            holograms.put(key, display.getUniqueId());
        } catch (Throwable throwable) {
            logError("spawn hologram", null, loc == null ? null : loc.crate, throwable);
        }
    }

    private Display.Billboard parseBillboard(String raw) {
        try { return Display.Billboard.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT)); }
        catch (Throwable ignored) { return Display.Billboard.FIXED; }
    }

    private String holoText(CrateDef crate, CrateLoc loc) {
        String keysText = "-";
        World world = Bukkit.getWorld(loc.world);
        Player nearest = world == null ? null : nearest(new Location(world, loc.x + .5, loc.y + .5, loc.z + .5));
        if (nearest != null) keysText = String.valueOf(countPhysicalKeys(nearest, crate.key));
        KeyDef key = keys.get(crate.key);
        String keyName = key == null ? crate.key : plain(key.name);
        return String.join("\n", hologramLines()).replace("%crate%", crate.id).replace("%crate_display%", crate.name).replace("%crate_prefix%", crate.prefix).replace("%crate_suffix%", crate.suffix).replace("%key%", crate.key).replace("%key_name%", keyName).replace("%key_short_name%", key == null ? shortKeyName(keyName) : key.shortName).replace("%keys%", keysText).replace("%level_reward%", crate.level).replace("%permission_open%", "velioragacha.open").replace("%permission_shop%", "velioragacha.shop");
    }

    private List<String> hologramLines() {
        List<Map<?, ?>> pages = getConfig().getMapList("hologram.pages");
        if (!pages.isEmpty()) {
            Object rawLines = pages.get(0).get("lines");
            if (rawLines instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object row : list) {
                    if (row instanceof Map<?, ?> map) {
                        Object content = map.get("content");
                        out.add(content == null ? "" : String.valueOf(content));
                    } else out.add(String.valueOf(row));
                }
                if (!out.isEmpty()) return out;
            }
        }
        List<String> lines = getConfig().getStringList("hologram.lines");
        return lines.isEmpty() ? List.of("&d%crate_display% Crate", "&fKeys: %keys%") : lines;
    }

    private Player nearest(Location location) {
        double radius = getConfig().getDouble("hologram.nearest-player-radius", 8.0D);
        double bestDistance = radius * radius;
        Player best = null;
        for (Player player : location.getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(location);
            if (distance <= bestDistance) { bestDistance = distance; best = player; }
        }
        return best;
    }

    private void startHologramUpdater() {
        int ticks = Math.max(20, getConfig().getInt("hologram.update-ticks", 40));
        hologramTask = new BukkitRunnable() {
            @Override public void run() {
                for (Map.Entry<String, CrateLoc> entry : locations.entrySet()) {
                    Entity entity = entity(holograms.get(entry.getKey()));
                    CrateDef crate = crates.get(entry.getValue().crate);
                    if (entity instanceof TextDisplay display && crate != null) {
                        display.setBillboard(parseBillboard(getConfig().getString("hologram.billboard", "FIXED")));
                        display.setText(color(holoText(crate, entry.getValue())));
                    }
                }
            }
        }.runTaskTimer(this, ticks, ticks);
    }

    private void restartHologramUpdater() { if (hologramTask != null) hologramTask.cancel(); startHologramUpdater(); }
    private void refreshCrateHolograms(String crateId) { locations.forEach((key, loc) -> { if (loc.crate.equals(crateId)) spawnHologram(key, loc); }); }
    private void removeHologram(String key) { UUID uuid = holograms.remove(key); Entity entity = entity(uuid); if (entity != null) entity.remove(); }
    private void removeHolograms() { new ArrayList<>(holograms.keySet()).forEach(this::removeHologram); }
    private void cleanupHolograms() { for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) if (entity.getScoreboardTags().contains("velioragacha_hologram")) entity.remove(); }

    private void startEffects() {
        if (!getConfig().getBoolean("effects.enabled", true)) return;
        int interval = Math.max(2, getConfig().getInt("effects.interval-ticks", 3));
        effectTask = new BukkitRunnable() {
            double t = 0;
            @Override public void run() {
                t += getConfig().getDouble("effects.speed", 0.32D);
                int max = Math.max(1, getConfig().getInt("effects.max-crates-per-tick", 40));
                int count = 0;
                for (CrateLoc loc : locations.values()) {
                    if (count++ >= max) break;
                    effect(loc, t, false);
                }
            }
        }.runTaskTimer(this, interval, interval);
    }

    private void restartEffects() { if (effectTask != null) effectTask.cancel(); startEffects(); }

    private void effect(CrateLoc loc, double t, boolean force) {
        World world = Bukkit.getWorld(loc.world);
        CrateDef crate = crates.get(loc.crate);
        if (world == null || crate == null || !world.isChunkLoaded(loc.x >> 4, loc.z >> 4)) return;
        if (!force && !hasNearbyPlayer(world, loc)) return;
        String path = "crates." + crate.id + ".effect-settings.";
        int points = Math.max(1, cratesYml.getInt(path + "points-per-tick", getConfig().getInt("effects.points-per-tick", 5)));
        int rings = Math.max(1, cratesYml.getInt(path + "rings", getConfig().getInt("effects.rings", 3)));
        double radius = cratesYml.getDouble(path + "radius", getConfig().getDouble("effects.radius", 0.85D));
        double radiusTop = cratesYml.getDouble(path + "radius-top", getConfig().getDouble("effects.radius-top", 0.35D));
        double height = cratesYml.getDouble(path + "height", getConfig().getDouble("effects.height", 1.45D));
        for (int ring = 0; ring < rings; ring++) {
            double progress = rings == 1 ? .5D : (double) ring / (rings - 1);
            double currentRadius = radius + (radiusTop - radius) * progress;
            double y = loc.y + .25D + progress * height;
            for (int i = 0; i < points; i++) {
                double angle = t + ring * .85D + i * (Math.PI * 2D / points);
                double x = loc.x + .5D + Math.cos(angle) * currentRadius;
                double z = loc.z + .5D + Math.sin(angle) * currentRadius;
                spawnEffectParticle(world, crate.effect, new Location(world, x, y, z));
            }
        }
        if (getConfig().getBoolean("effects.center-spark", true)) spawnEffectParticle(world, crate.effect, new Location(world, loc.x + .5, loc.y + 0.95, loc.z + .5));
    }

    private boolean hasNearbyPlayer(World world, CrateLoc loc) {
        double distance = getConfig().getDouble("effects.view-distance", 32.0D);
        double max = distance * distance;
        Location center = new Location(world, loc.x + .5, loc.y + .5, loc.z + .5);
        for (Player player : world.getPlayers()) if (player.getLocation().distanceSquared(center) <= max) return true;
        return false;
    }

    private void spawnEffectParticle(World world, String effect, Location location) {
        String s = normalizeEffect(effect, "runtime");
        try {
            if (s.equals("oak_leaf_spiral")) {
                Particle falling = particle("FALLING_DUST", Particle.FLAME);
                if (falling.name().equals("FALLING_DUST")) world.spawnParticle(falling, location, 1, 0, 0, 0, 0, Bukkit.createBlockData(Material.OAK_LEAVES));
                else world.spawnParticle(particle("COMPOSTER", Particle.FLAME), location, 1, .02, .02, .02, .01);
                return;
            }
            Particle particle = effectParticle(s);
            int count = s.equals("bubble_spiral") ? 3 : s.equals("bonemeal_star_spiral") ? getConfig().getInt("effects.bonemeal-star-count", 2) : s.equals("end_rod_spiral") ? 2 : 1;
            double extra = s.equals("bonemeal_star_spiral") ? getConfig().getDouble("effects.bonemeal-star-extra", 0.02D) : .025D;
            world.spawnParticle(particle, location, count, extra, extra, extra, .01);
            if (s.equals("fire_spiral")) world.spawnParticle(particle("SMALL_FLAME", Particle.FLAME), location, 1, .015, .015, .015, .005);
        } catch (Throwable throwable) {
            logWarningOnce("particle-" + s, "Effect particle failed for " + s + ": " + throwable.getMessage());
        }
    }

    private Particle effectParticle(String effect) {
        return switch (effect) {
            case "cherry_spiral" -> particle("FALLING_CHERRY_LEAVES", particle("CHERRY_LEAVES", particle("COMPOSTER", Particle.FLAME)));
            case "end_rod_spiral" -> particle("END_ROD", Particle.FLAME);
            case "bubble_spiral" -> particle("BUBBLE_POP", particle("BUBBLE", particle("SPLASH", Particle.FLAME)));
            case "bonemeal_star_spiral" -> particle("HAPPY_VILLAGER", particle("COMPOSTER", Particle.FLAME));
            case "soul_fire_spiral" -> particle("SOUL_FIRE_FLAME", Particle.FLAME);
            case "sparkle_spiral" -> particle("FIREWORK", particle("END_ROD", Particle.FLAME));
            case "portal_spiral" -> particle("PORTAL", Particle.FLAME);
            case "crit_spiral" -> particle("CRIT", Particle.FLAME);
            case "note_spiral" -> particle("NOTE", Particle.FLAME);
            default -> Particle.FLAME;
        };
    }

    private void sendDebug(CommandSender sender) {
        msg(sender, "&8[&dVelioraGacha Debug&8]");
        msg(sender, "&7version: &f" + getDescription().getVersion());
        msg(sender, "&7server: &f" + Bukkit.getVersion());
        msg(sender, "&7java: &f" + System.getProperty("java.version"));
        msg(sender, "&7vault hooked: &f" + (economy != null));
        msg(sender, "&7animation mode: &f" + normalizeAnimationMode(getConfig().getString("animation.mode", "BOX")));
        msg(sender, "&7crate count: &f" + crates.size());
        msg(sender, "&7location count: &f" + locations.size());
        msg(sender, "&7hologram billboard: &f" + getConfig().getString("hologram.billboard", "FIXED"));
        msg(sender, "&7active hologram count: &f" + holograms.size());
        msg(sender, "&7active opening count: &f" + openings.size());
        if (sender instanceof Player player) {
            for (String permission : List.of("velioragacha.shop", "velioragacha.open", "velioragacha.gui", "velioragacha.givekey", "velioragacha.reload", "velioragacha.break", "velioragacha.place", "velioragacha.debug")) msg(sender, "&7perm " + permission + ": &f" + player.hasPermission(permission));
        }
        msg(sender, "&7latest log: &f" + new File(logsDir, "latest.log").getPath());
    }

    private void createDump(CommandSender sender) {
        File dump = new File(getDataFolder(), "debug-dump.txt");
        try (PrintWriter out = new PrintWriter(new FileWriter(dump, false))) {
            out.println("VelioraGacha Debug Dump");
            out.println("time=" + LocalDateTime.now());
            out.println("version=" + getDescription().getVersion());
            out.println("server=" + Bukkit.getVersion());
            out.println("java=" + System.getProperty("java.version"));
            out.println("vault=" + (economy != null));
            out.println("animationMode=" + normalizeAnimationMode(getConfig().getString("animation.mode", "BOX")));
            out.println("hologramBillboard=" + getConfig().getString("hologram.billboard", "FIXED"));
            out.println("crates=" + crates.keySet());
            out.println("locations=" + locations);
            out.println("keys=" + keys.keySet());
            out.println("rewards=" + rewards.keySet());
            out.println("holograms=" + holograms.size());
            out.println("activeOpenings=" + openings.size());
            msg(sender, "&aDebug dump dibuat: &f" + dump.getPath());
        } catch (IOException exception) {
            logError("debug dump", exception);
            msg(sender, "&cGagal membuat debug dump.");
        }
    }

    private void logError(String action, Throwable throwable) { logError(action, null, null, throwable); }
    private void logError(String action, Player player, String crate, Throwable throwable) {
        try {
            if (!logsDir.exists()) logsDir.mkdirs();
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            String text = "[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "] action=" + action + " player=" + (player == null ? "-" : player.getName()) + " crate=" + (crate == null ? "-" : crate) + "\n" + writer + "\n";
            writeLog(new File(logsDir, "latest.log"), text);
            writeLog(new File(logsDir, "errors-" + LocalDate.now() + ".log"), text);
            getLogger().warning("[VelioraGacha] Error action=" + action + ": " + throwable.getMessage());
        } catch (IOException ignored) { }
    }
    private void logWarningOnce(String key, String message) { if (warningOnce.add(key)) getLogger().warning(message); }
    private void writeLog(File file, String text) throws IOException { try (FileWriter writer = new FileWriter(file, true)) { writer.write(text); } }

    private Map.Entry<String, CrateLoc> nearestCrate(Player player, String filter) {
        Map.Entry<String, CrateLoc> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<String, CrateLoc> entry : locations.entrySet()) {
            if (!filter.isBlank() && !entry.getValue().crate.equals(filter)) continue;
            World world = Bukkit.getWorld(entry.getValue().world);
            if (world == null || !world.equals(player.getWorld())) continue;
            Location location = new Location(world, entry.getValue().x + .5, entry.getValue().y + .5, entry.getValue().z + .5);
            double distance = location.distanceSquared(player.getLocation());
            if (distance < bestDist) { bestDist = distance; best = entry; }
        }
        return best;
    }

    private int modeMarkerSlot(String mode) {
        return getConfig().getInt("animation." + mode.toLowerCase(Locale.ROOT) + ".marker-slot", getConfig().getInt("animation.marker-slot", 4));
    }
    private int modeResultSlot(String mode) {
        return getConfig().getInt("animation." + mode.toLowerCase(Locale.ROOT) + ".result-slot", getConfig().getInt("animation.result-slot", 22));
    }
    private List<CrateDef> playerCrates() { List<CrateDef> out = new ArrayList<>(); for (String id : PLAYER_ORDER) if (crates.containsKey(id)) out.add(crates.get(id)); return out; }
    private List<CrateDef> adminCrates() { List<CrateDef> out = playerCrates(); if (showTestCrate() && crates.containsKey("test")) out.add(crates.get("test")); return out; }
    private String crateName(String id) { CrateDef crate = crates.get(id); return crate == null ? id : plain(crate.name); }
    private String normal(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_'); }
    private String normalizeRarity(String rarity) { String key = normal(rarity); return RARITY_ALIAS.getOrDefault(key, key); }
    private String prettyRarity(String rarity) { String key = normalizeRarity(rarity); return key.isEmpty() ? "Unknown" : key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1).toLowerCase(Locale.ROOT); }
    private String shortKeyName(String display) { return plain(display == null ? "" : display).replace(" Key", "").replace("Key", "").trim(); }

    private String normalizeEffect(String effect, String crateId) {
        String value = normal(effect);
        if (value.contains("smoke")) {
            logWarningOnce("smoke-effect-" + crateId, "VelioraGacha: smoke_spiral is disabled. Fallback to end_rod_spiral for crate " + crateId);
            return "end_rod_spiral";
        }
        if (!EFFECTS.contains(value)) {
            logWarningOnce("bad-effect-" + crateId + value, "VelioraGacha: invalid effect " + value + ". Fallback to end_rod_spiral.");
            return "end_rod_spiral";
        }
        return value;
    }

    private String normalizeAnimationMode(String raw) {
        String mode = String.valueOf(raw).toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "SPIRAL", "TORNADO", "SPIN" -> "SPIRAL";
            case "SNAKE" -> "SNAKE";
            case "BOX", "WHEEL" -> "BOX";
            case "SIMPLE", "SLIDE", "SHUFFLE" -> "SIMPLE";
            default -> { logWarningOnce("bad-animation-mode-" + mode, "Invalid animation mode " + mode + ". Fallback SIMPLE."); yield "SIMPLE"; }
        };
    }

    private List<Integer> intList(String path, List<Integer> fallback) {
        List<Integer> list = getConfig().getIntegerList(path);
        return list.isEmpty() ? fallback : list;
    }

    private List<String> filter(List<String> input, String prefix) { String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT); return input.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList(); }
    private Inventory gui(Holder holder, int size, String title) { Inventory inv = Bukkit.createInventory(holder, invSize(size), color(title)); holder.inv = inv; return inv; }
    private void fill(Inventory inv) { ItemStack filler = item(parseMaterial(getConfig().getString("animation.filler-material", getConfig().getString("shop.filler")), Material.GRAY_STAINED_GLASS_PANE, "filler"), " "); for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler); }
    private void set(Inventory inv, int slot, ItemStack item) { if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, item); }
    private ItemStack item(Material material, String name) { return item(material, name, List.of()); }
    private ItemStack item(Material material, String name, List<String> lore) { ItemStack item = new ItemStack(material == null ? Material.STONE : material); ItemMeta meta = item.getItemMeta(); if (meta != null) { meta.setDisplayName(color(name)); if (!lore.isEmpty()) meta.setLore(lore.stream().map(this::color).toList()); item.setItemMeta(meta); } return item; }
    private ItemStack itemWithPdc(Material material, String name, List<String> lore, NamespacedKey key, String value) { ItemStack item = item(material, name, lore); ItemMeta meta = item.getItemMeta(); if (meta != null) { meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value); item.setItemMeta(meta); } return item; }
    private ItemStack keyIcon(KeyDef key, List<String> extraLore) { ItemStack item = createKeyItem(key, 1); ItemMeta meta = item.getItemMeta(); if (meta != null) { List<String> lore = new ArrayList<>(key.lore); lore.addAll(extraLore); meta.setLore(lore.stream().map(line -> line.replace("%key_short_name%", key.shortName).replace("%key_prefix%", key.prefix).replace("%key_suffix%", key.suffix)).map(this::color).toList()); item.setItemMeta(meta); } return item; }
    private ItemStack crateItem(CrateDef crate) { return itemWithPdc(crate.block, "&d" + crate.name + " Crate", List.of("&7Place untuk membuat crate.", "&7Crate ID: &f" + crate.id, "", "&eHanya admin yang bisa place."), crateKey, crate.id); }
    private ItemStack createKeyItem(KeyDef key, int amount) { ItemStack item = new ItemStack(key.icon, Math.max(1, Math.min(64, amount))); ItemMeta meta = item.getItemMeta(); if (meta != null) { meta.setDisplayName(color(key.prefix + key.name + key.suffix)); meta.setLore(key.lore.stream().map(line -> line.replace("%key_short_name%", key.shortName).replace("%key_prefix%", key.prefix).replace("%key_suffix%", key.suffix)).map(this::color).toList()); meta.getPersistentDataContainer().set(keyKey, PersistentDataType.STRING, key.id); if (key.glow) try { meta.getClass().getMethod("setEnchantmentGlintOverride", Boolean.class).invoke(meta, Boolean.TRUE); } catch (Throwable ignored) { } item.setItemMeta(meta); } return item; }
    private void giveKeyItems(Player player, KeyDef key, int amount) { int remaining = Math.max(1, amount); while (remaining > 0) { int give = Math.min(64, remaining); giveOrDrop(player, createKeyItem(key, give)); remaining -= give; } }
    private boolean canFitKey(PlayerInventory inv, KeyDef key, int amount) { int remaining = amount; ItemStack sample = createKeyItem(key, 1); for (ItemStack current : inv.getStorageContents()) { if (current == null || current.getType() == Material.AIR) remaining -= 64; else if (current.isSimilar(sample)) remaining -= Math.max(0, current.getMaxStackSize() - current.getAmount()); if (remaining <= 0) return true; } return false; }
    private void giveOrDrop(Player player, ItemStack item) { player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left)); }
    private String pdc(ItemStack item, NamespacedKey key) { if (item == null || !item.hasItemMeta()) return null; return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING); }
    private Entity entity(UUID uuid) { if (uuid == null) return null; for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) if (entity.getUniqueId().equals(uuid)) return entity; return null; }
    private Material effectIcon(String effect) { return switch (effect) { case "fire_spiral" -> Material.BLAZE_POWDER; case "cherry_spiral" -> Material.CHERRY_LEAVES; case "end_rod_spiral" -> Material.END_ROD; case "oak_leaf_spiral" -> Material.OAK_LEAVES; case "bubble_spiral" -> Material.WATER_BUCKET; case "bonemeal_star_spiral" -> Material.BONE_MEAL; case "soul_fire_spiral" -> Material.SOUL_TORCH; case "portal_spiral" -> Material.ENDER_EYE; case "crit_spiral" -> Material.IRON_SWORD; case "note_spiral" -> Material.NOTE_BLOCK; default -> Material.NETHER_STAR; }; }
    private Material parseMaterial(String name, Material fallback, String action) { Material material = name == null ? null : Material.matchMaterial(name.toUpperCase(Locale.ROOT)); if (material == null) { logWarningOnce("mat-" + action + "-" + name, "Invalid material for " + action + ": " + name + ", fallback " + fallback); return fallback; } return material; }
    private Particle particle(String name, Particle fallback) { try { return Particle.valueOf(String.valueOf(name).toUpperCase(Locale.ROOT)); } catch (Throwable ignored) { return fallback; } }
    private Sound sound(String name, Sound fallback) { try { return Sound.valueOf(String.valueOf(name).toUpperCase(Locale.ROOT)); } catch (Throwable ignored) { return fallback; } }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', (text == null ? "" : text).replaceAll("<#([A-Fa-f0-9]{6})>", "").replaceAll("</#([A-Fa-f0-9]{6})>", "")); }
    private String plain(String text) { return ChatColor.stripColor(color(text)); }
    private String displayName(ItemStack item) { return item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : item.getType().name() + " x" + item.getAmount(); }
    private String cash(double value) { return moneyFormat.format(value).replace(',', '.'); }
    private int parse(String raw) { try { return Integer.parseInt(raw); } catch (Exception ignored) { return 0; } }
    private int invSize(int size) { int safe = Math.max(9, Math.min(54, size)); return ((safe + 8) / 9) * 9; }
    private String locKey(Location loc) { return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ(); }
    private void save(FileConfiguration yaml, File file) { try { yaml.save(file); } catch (IOException exception) { logError("save " + file.getName(), exception); } }
    private Object val(Map<?, ?> map, String key) { return map.get(key); }
    private String str(Map<?, ?> map, String key, String fallback) { Object value = val(map, key); return value == null ? fallback : String.valueOf(value); }
    private int num(Map<?, ?> map, String key, int fallback) { try { return Integer.parseInt(String.valueOf(val(map, key))); } catch (Exception ignored) { return fallback; } }
    private List<String> commands(Map<?, ?> map) { List<String> out = new ArrayList<>(); Object one = val(map, "command"), many = val(map, "commands"); if (one != null && !String.valueOf(one).isBlank()) out.add(String.valueOf(one)); if (many instanceof List<?> list) for (Object o : list) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o)); return out; }
    private String msg(String path, String fallback) { return getConfig().getString("messages." + path, fallback).replace("%prefix%", getConfig().getString("settings.prefix", "&8[&dVelioraGacha&8] ")); }
    private void msg(CommandSender sender, String message) { sender.sendMessage(color(message)); }
    private boolean deny(CommandSender sender) { msg(sender, msg("no-permission", "&cKamu tidak punya izin.")); return true; }

    private enum RewardType { ITEM, COMMAND }
    private enum KeyState { OK, WRONG, NONE }
    private record KeyDef(String id, String name, String shortName, String prefix, String suffix, Material icon, double price, boolean glow, List<String> lore) { }
    private record CrateDef(String id, String name, Material block, String key, String effect, String level, boolean hologram, String prefix, String suffix) { }
    private record CrateLoc(String crate, String world, int x, int y, int z) { }
    private record Reward(RewardType type, String display, Material material, int amount, int weight, List<String> commands, ItemStack item, String rarity) { ItemStack icon() { ItemStack copy = item == null ? new ItemStack(material, Math.max(1, Math.min(64, amount))) : item.clone(); copy.setAmount(Math.max(1, Math.min(64, copy.getAmount()))); ItemMeta meta = copy.getItemMeta(); if (meta != null && !meta.hasDisplayName()) { meta.setDisplayName(ChatColor.WHITE + ChatColor.stripColor(display)); copy.setItemMeta(meta); } return copy; } }
    private record KeyCheck(KeyState state, ItemStack stack) { }
    private static final class Opening { final UUID player; final CrateDef crate; final Reward reward; final Inventory inventory; final boolean preview; BukkitTask task; boolean rewarded; Opening(UUID player, CrateDef crate, Reward reward, Inventory inventory, boolean preview) { this.player = player; this.crate = crate; this.reward = reward; this.inventory = inventory; this.preview = preview; } void cancel() { if (task != null) task.cancel(); } }
    private static final class Holder implements InventoryHolder { final String type, id, rarity; final int amount; Inventory inv; Holder(String type, String id, String rarity, int amount) { this.type = type; this.id = id; this.rarity = rarity; this.amount = amount; } @Override public Inventory getInventory() { return inv; } }
}
