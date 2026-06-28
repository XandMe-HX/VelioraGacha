package id.velioragardens.velioragacha;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

public final class VelioraGachaPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, KeyDef> keys = new LinkedHashMap<>();
    private final Map<String, CrateDef> crates = new LinkedHashMap<>();
    private final Map<String, CrateLoc> locations = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> keyCache = new HashMap<>();
    private final Map<UUID, BukkitTask> opening = new HashMap<>();
    private File cratesFile, keysFile, playerDir;
    private FileConfiguration cratesYml, keysYml;
    private Economy economy;
    private DecimalFormat money = new DecimalFormat("#,###");
    private org.bukkit.NamespacedKey crateKey, keyKey;
    private BukkitTask effectTask;

    @Override public void onEnable() {
        crateKey = new org.bukkit.NamespacedKey(this, "crate_id");
        keyKey = new org.bukkit.NamespacedKey(this, "key_id");
        saveMissing("config.yml"); saveMissing("crates.yml"); saveMissing("keys.yml"); saveMissing("rewards.yml");
        playerDir = new File(getDataFolder(), "playerdata"); if (!playerDir.exists()) playerDir.mkdirs();
        hookVault(); loadAll();
        getCommand("vgcreate").setExecutor(this); getCommand("vgcreate").setTabCompleter(this);
        getCommand("key").setExecutor(this); getCommand("key").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this); startEffects();
    }

    @Override public void onDisable() {
        opening.values().forEach(BukkitTask::cancel); opening.clear();
        if (effectTask != null) effectTask.cancel();
        for (UUID uuid : keyCache.keySet()) savePlayer(uuid);
    }

    private void saveMissing(String name) { if (!new File(getDataFolder(), name).exists()) saveResource(name, false); }
    private void hookVault() { RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class); economy = rsp == null ? null : rsp.getProvider(); }
    private void loadAll() { reloadConfig(); money = new DecimalFormat(getConfig().getString("economy.money-format", "#,###")); cratesFile = new File(getDataFolder(), "crates.yml"); keysFile = new File(getDataFolder(), "keys.yml"); cratesYml = YamlConfiguration.loadConfiguration(cratesFile); keysYml = YamlConfiguration.loadConfiguration(keysFile); loadKeys(); loadCrates(); }

    private void loadKeys() {
        keys.clear(); ConfigurationSection s = keysYml.getConfigurationSection("keys"); if (s == null) return;
        for (String id : s.getKeys(false)) { String p = "keys." + id; keys.put(id.toLowerCase(Locale.ROOT), new KeyDef(id.toLowerCase(Locale.ROOT), keysYml.getString(p + ".display-name", id), mat(keysYml.getString(p + ".material"), Material.TRIPWIRE_HOOK), keysYml.getDouble(p + ".price", 150000))); }
    }

    private void loadCrates() {
        crates.clear(); locations.clear(); ConfigurationSection s = cratesYml.getConfigurationSection("crates");
        if (s != null) for (String id : s.getKeys(false)) { String p = "crates." + id; if (!cratesYml.getBoolean(p + ".enabled", true)) continue; crates.put(id.toLowerCase(Locale.ROOT), new CrateDef(id.toLowerCase(Locale.ROOT), cratesYml.getString(p + ".display", id), mat(cratesYml.getString(p + ".block"), Material.CHEST), cratesYml.getString(p + ".key", id + "_key").toLowerCase(Locale.ROOT), cratesYml.getString(p + ".effect", "fire_spiral"))); }
        ConfigurationSection l = cratesYml.getConfigurationSection("locations");
        if (l != null) for (String k : l.getKeys(false)) locations.put(k, new CrateLoc(cratesYml.getString("locations." + k + ".crate", ""), cratesYml.getString("locations." + k + ".world", "world"), cratesYml.getInt("locations." + k + ".x"), cratesYml.getInt("locations." + k + ".y"), cratesYml.getInt("locations." + k + ".z")));
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("key")) { if (sender instanceof Player p && args.length == 1 && args[0].equalsIgnoreCase("shop")) openShop(p); else msg(sender, "&f/key shop"); return true; }
        if (args.length == 0) { msg(sender, "&d/vgcreate gui &7| &d/vgcreate givekey <player|*> <key> <amount> &7| &d/vgcreate reload"); return true; }
        if (args[0].equalsIgnoreCase("reload")) { if (!sender.hasPermission("velioragacha.reload")) return deny(sender); loadAll(); restartEffects(); msg(sender, "&aVelioraGacha berhasil direload."); return true; }
        if (args[0].equalsIgnoreCase("gui")) { if (!(sender instanceof Player p)) return true; if (!p.hasPermission("velioragacha.gui")) return deny(p); openAdmin(p); return true; }
        if (args[0].equalsIgnoreCase("givekey")) return giveKey(sender, args);
        return true;
    }

    private boolean giveKey(CommandSender sender, String[] args) {
        if (!sender.hasPermission("velioragacha.givekey")) return deny(sender); if (args.length < 4) { msg(sender, "&c/vgcreate givekey <player|*> <key> <amount>"); return true; }
        String key = args[2].toLowerCase(Locale.ROOT); int amount = parse(args[3]); if (!keys.containsKey(key) || amount < 1) { msg(sender, "&cKey/amount tidak valid."); return true; }
        if (args[1].equals("*")) { for (Player p : Bukkit.getOnlinePlayers()) addKey(p.getUniqueId(), key, amount); msg(sender, "&aKey diberikan ke semua player online."); return true; }
        OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]); addKey(off.getUniqueId(), key, amount); msg(sender, "&aKey diberikan ke &f" + args[1]); return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) { if (c.getName().equalsIgnoreCase("key")) return args.length == 1 ? List.of("shop") : List.of(); if (args.length == 1) return List.of("gui", "givekey", "reload"); if (args.length == 3 && args[0].equalsIgnoreCase("givekey")) return new ArrayList<>(keys.keySet()); if (args.length == 4) return List.of("1", "5", "10", "32", "64"); return List.of(); }

    private void openShop(Player p) { Holder h = new Holder("shop", null, 1); Inventory inv = gui(h, 54, "&8Key Shop"); fill(inv); int[] slots = {20,22,24,30,32}; int i=0; for (KeyDef k: keys.values()) if (i<slots.length) inv.setItem(slots[i++], keyIcon(k)); p.openInventory(inv); }
    private void openQty(Player p, String key, int amount) { KeyDef k=keys.get(key); if(k==null)return; int max=getConfig().getInt("settings.max-shop-amount",64); amount=Math.max(1,Math.min(max,amount)); Holder h=new Holder("qty",key,amount); Inventory inv=gui(h,54,"&8Beli "+plain(k.name)); fill(inv); inv.setItem(20,item(Material.RED_STAINED_GLASS_PANE,"&c-10")); inv.setItem(21,item(Material.RED_DYE,"&c-1")); inv.setItem(22,item(k.icon,"&f"+plain(k.name)+" &7x"+amount+" &a"+cash(k.price*amount))); inv.setItem(23,item(Material.LIME_DYE,"&a+1")); inv.setItem(24,item(Material.LIME_STAINED_GLASS_PANE,"&a+10")); inv.setItem(30,item(Material.ARROW,"&eBack")); inv.setItem(32,item(Material.EMERALD_BLOCK,"&aConfirm")); p.openInventory(inv); }
    private void openAdmin(Player p) { Holder h=new Holder("admin",null,1); Inventory inv=gui(h,27,"&8VelioraGacha Admin"); fill(inv); inv.setItem(11,item(Material.TRIPWIRE_HOOK,"&dKey Manager")); inv.setItem(13,item(Material.CHEST,"&6Crate Placer")); inv.setItem(15,item(Material.COMPARATOR,"&eCrate Settings")); p.openInventory(inv); }
    private void openPlacer(Player p) { Holder h=new Holder("placer",null,1); Inventory inv=gui(h,27,"&8Crate Placer"); fill(inv); int slot=10; for(CrateDef c:crates.values()) inv.setItem(slot++, crateItem(c)); p.openInventory(inv); }
    private void openKeys(Player p) { Holder h=new Holder("keys",null,1); Inventory inv=gui(h,27,"&8Key Manager"); fill(inv); int slot=10; for(KeyDef k:keys.values()) inv.setItem(slot++, keyIcon(k)); p.openInventory(inv); }
    private void openSettings(Player p) { Holder h=new Holder("settings",null,1); Inventory inv=gui(h,27,"&8Crate Settings"); fill(inv); int slot=10; for(CrateDef c:crates.values()) inv.setItem(slot++, item(c.block,"&e"+c.name)); p.openInventory(inv); }

    @EventHandler public void click(InventoryClickEvent e) { if (!(e.getWhoClicked() instanceof Player p) || !(e.getInventory().getHolder() instanceof Holder h)) return; e.setCancelled(true); ItemStack it=e.getCurrentItem(); if(it==null)return; int slot=e.getRawSlot(); if(h.type.equals("shop")){String k=pdc(it,keyKey); if(k!=null)openQty(p,k,1);} else if(h.type.equals("qty")){int a=h.amount; if(slot==20)a-=10; else if(slot==21)a--; else if(slot==23)a++; else if(slot==24)a+=10; else if(slot==30){openShop(p);return;} else if(slot==32){buy(p,h.id,h.amount);return;} else return; openQty(p,h.id,a);} else if(h.type.equals("admin")){if(slot==11)openKeys(p); else if(slot==13)openPlacer(p); else if(slot==15)openSettings(p);} else if(h.type.equals("keys")){String k=pdc(it,keyKey); if(k!=null)addKey(p.getUniqueId(),k,e.isShiftClick()?10:1);} else if(h.type.equals("placer")){String c=pdc(it,crateKey); if(c!=null)p.getInventory().addItem(crateItem(crates.get(c)));} }
    private void buy(Player p,String key,int amount){KeyDef k=keys.get(key); if(k==null)return; if(economy==null){msg(p,"&cEconomy tidak tersedia.");return;} double total=k.price*amount; if(economy.getBalance(p)<total){msg(p,"&cUang kurang. Butuh &f"+cash(total));return;} economy.withdrawPlayer(p,total); addKey(p.getUniqueId(),key,amount); msg(p,"&aBerhasil membeli key x&f"+amount); openQty(p,key,amount);}

    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){String id=pdc(e.getItemInHand(),crateKey); if(id==null)return; if(!e.getPlayer().hasPermission("velioragacha.place")){e.setCancelled(true);return;} Location l=e.getBlockPlaced().getLocation(); String k=locKey(l); cratesYml.set("locations."+k+".crate",id); cratesYml.set("locations."+k+".world",l.getWorld().getName()); cratesYml.set("locations."+k+".x",l.getBlockX()); cratesYml.set("locations."+k+".y",l.getBlockY()); cratesYml.set("locations."+k+".z",l.getBlockZ()); save(cratesYml,cratesFile); locations.put(k,new CrateLoc(id,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ())); msg(e.getPlayer(),"&aCrate dibuat.");}
    @EventHandler(priority=EventPriority.HIGHEST) public void br(BlockBreakEvent e){CrateLoc l=locations.get(locKey(e.getBlock().getLocation())); if(l==null)return; if(!e.getPlayer().hasPermission("velioragacha.break")){e.setCancelled(true);return;} cratesYml.set("locations."+locKey(e.getBlock().getLocation()),null); save(cratesYml,cratesFile); locations.remove(locKey(e.getBlock().getLocation())); e.setDropItems(false); if(crates.containsKey(l.crate))e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(),crateItem(crates.get(l.crate)));}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){Block b=e.getClickedBlock(); if(b==null||!e.getAction().name().equals("RIGHT_CLICK_BLOCK"))return; CrateLoc l=locations.get(locKey(b.getLocation())); if(l==null)return; e.setCancelled(true); Player p=e.getPlayer(); CrateDef c=crates.get(l.crate); if(c==null)return; if(getKeys(p.getUniqueId()).getOrDefault(c.key,0)<=0){noKey(p,b.getLocation());return;} addKey(p.getUniqueId(),c.key,-1); roulette(p,c);}
    private void noKey(Player p,Location loc){msg(p,"&cKamu butuh key untuk membuka crate ini. Beli di &f/key shop&c."); p.playSound(p.getLocation(),Sound.ENTITY_VILLAGER_NO,1,1); Vector v=p.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(.65); v.setY(.25); p.setVelocity(v);}
    private void roulette(Player p,CrateDef c){if(opening.containsKey(p.getUniqueId()))return; Holder h=new Holder("roulette",c.id,1); Inventory inv=gui(h,45,"&8Opening "+c.name); fill(inv); Reward r=randomReward(); BukkitTask task=new BukkitRunnable(){int t=0;public void run(){t+=2;inv.setItem(22,t>=80?r.icon():randomReward().icon()); if(t>=80){give(p,r); opening.remove(p.getUniqueId());cancel();}}}.runTaskTimer(this,1,2); opening.put(p.getUniqueId(),task); p.openInventory(inv);}
    private Reward randomReward(){List<Material> mats=List.of(Material.IRON_INGOT,Material.EMERALD,Material.GOLDEN_APPLE,Material.DIAMOND); Material m=mats.get(new Random().nextInt(mats.size())); return new Reward(m, m==Material.DIAMOND?1:4);} private void give(Player p,Reward r){p.getInventory().addItem(new ItemStack(r.mat,r.amount)); msg(p,"&aKamu mendapat &f"+r.mat.name()+" x"+r.amount);}

    private void startEffects(){effectTask=new BukkitRunnable(){double t=0;public void run(){t+=.4;for(CrateLoc l:locations.values()){World w=Bukkit.getWorld(l.world); if(w==null)continue; w.spawnParticle(Particle.FLAME,l.x+.5+Math.cos(t),l.y+1,l.z+.5+Math.sin(t),1,0,0,0,0);}}}.runTaskTimer(this,8,8);} private void restartEffects(){if(effectTask!=null)effectTask.cancel();startEffects();}
    private Inventory gui(Holder h,int s,String title){Inventory inv=Bukkit.createInventory(h,s,color(title));h.inv=inv;return inv;} private void fill(Inventory inv){ItemStack f=item(Material.BLACK_STAINED_GLASS_PANE," "); for(int i=0;i<inv.getSize();i++)inv.setItem(i,f);} private ItemStack item(Material m,String name){ItemStack it=new ItemStack(m); ItemMeta im=it.getItemMeta(); if(im!=null){im.setDisplayName(color(name));it.setItemMeta(im);}return it;} private ItemStack keyIcon(KeyDef k){ItemStack it=item(k.icon,k.name); ItemMeta im=it.getItemMeta(); im.getPersistentDataContainer().set(keyKey,PersistentDataType.STRING,k.id); it.setItemMeta(im); return it;} private ItemStack crateItem(CrateDef c){ItemStack it=item(c.block,"&d"+c.name+" Crate"); ItemMeta im=it.getItemMeta(); im.getPersistentDataContainer().set(crateKey,PersistentDataType.STRING,c.id); it.setItemMeta(im); return it;}
    private Material mat(String n,Material f){Material m=n==null?null:Material.matchMaterial(n);return m==null?f:m;} private String pdc(ItemStack it,org.bukkit.NamespacedKey k){if(it==null||!it.hasItemMeta())return null;return it.getItemMeta().getPersistentDataContainer().get(k,PersistentDataType.STRING);} private void msg(CommandSender s,String m){s.sendMessage(color(m));} private boolean deny(CommandSender s){msg(s,"&cKamu tidak punya izin.");return true;} private String color(String s){return ChatColor.translateAlternateColorCodes('&',s.replaceAll("<#([A-Fa-f0-9]{6})>","").replaceAll("</#([A-Fa-f0-9]{6})>",""));} private String plain(String s){return ChatColor.stripColor(color(s));} private String cash(double d){return money.format(d).replace(',','.');} private int parse(String s){try{return Integer.parseInt(s);}catch(Exception e){return 0;}} private String locKey(Location l){return l.getWorld().getName()+","+l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ();} private void save(FileConfiguration y,File f){try{y.save(f);}catch(IOException ignored){}}
    private Map<String,Integer> getKeys(UUID u){return keyCache.computeIfAbsent(u,id->{FileConfiguration y=YamlConfiguration.loadConfiguration(new File(playerDir,id+".yml"));Map<String,Integer> m=new HashMap<>();for(String k:keys.keySet())m.put(k,y.getInt("keys."+k,0));return m;});} private void addKey(UUID u,String k,int a){Map<String,Integer> m=getKeys(u);m.put(k,Math.max(0,m.getOrDefault(k,0)+a));savePlayer(u);} private void savePlayer(UUID u){FileConfiguration y=new YamlConfiguration();getKeys(u).forEach((k,v)->y.set("keys."+k,v));save(y,new File(playerDir,u+".yml"));}
    private record KeyDef(String id,String name,Material icon,double price){} private record CrateDef(String id,String name,Material block,String key,String effect){} private record CrateLoc(String crate,String world,int x,int y,int z){} private record Reward(Material mat,int amount){ItemStack icon(){return new ItemStack(mat,amount);}} private static final class Holder implements InventoryHolder{final String type,id;final int amount;Inventory inv;Holder(String t,String i,int a){type=t;id=i;amount=a;}public Inventory getInventory(){return inv;}}
}
