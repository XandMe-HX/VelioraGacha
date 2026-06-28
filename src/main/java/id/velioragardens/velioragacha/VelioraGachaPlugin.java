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
    private final Map<String, List<Reward>> rewards = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> keyCache = new HashMap<>();
    private final Map<UUID, BukkitTask> opening = new HashMap<>();
    private final Map<String, UUID> holograms = new HashMap<>();
    private File cratesFile, keysFile, rewardsFile, playerDir;
    private FileConfiguration cratesYml, keysYml, rewardsYml;
    private Economy economy;
    private DecimalFormat money = new DecimalFormat("#,###");
    private org.bukkit.NamespacedKey crateKey, keyKey;
    private BukkitTask effectTask;
    private final Random random = new Random();

    @Override public void onEnable() {
        crateKey = new org.bukkit.NamespacedKey(this, "crate_id"); keyKey = new org.bukkit.NamespacedKey(this, "key_id");
        saveMissing("config.yml"); saveMissing("crates.yml"); saveMissing("keys.yml"); saveMissing("rewards.yml");
        playerDir = new File(getDataFolder(), "playerdata"); if (!playerDir.exists()) playerDir.mkdirs();
        hookVault(); loadAll(); cleanupHolograms(); spawnAllHolograms();
        Objects.requireNonNull(getCommand("vgcreate")).setExecutor(this); Objects.requireNonNull(getCommand("vgcreate")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("key")).setExecutor(this); Objects.requireNonNull(getCommand("key")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this); startEffects();
    }

    @Override public void onDisable() {
        opening.values().forEach(BukkitTask::cancel); opening.clear();
        if (effectTask != null) effectTask.cancel(); removeHolograms();
        for (UUID uuid : new ArrayList<>(keyCache.keySet())) savePlayer(uuid);
    }

    private void saveMissing(String name) { if (!new File(getDataFolder(), name).exists()) saveResource(name, false); }
    private void hookVault() { RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class); economy = rsp == null ? null : rsp.getProvider(); }
    private void loadAll() { reloadConfig(); money = new DecimalFormat(getConfig().getString("economy.money-format", "#,###")); cratesFile = new File(getDataFolder(), "crates.yml"); keysFile = new File(getDataFolder(), "keys.yml"); rewardsFile = new File(getDataFolder(), "rewards.yml"); cratesYml = YamlConfiguration.loadConfiguration(cratesFile); keysYml = YamlConfiguration.loadConfiguration(keysFile); rewardsYml = YamlConfiguration.loadConfiguration(rewardsFile); loadKeys(); loadCrates(); loadRewards(); }

    private void loadKeys() {
        keys.clear(); ConfigurationSection s = keysYml.getConfigurationSection("keys"); if (s == null) return;
        for (String id : s.getKeys(false)) { String p = "keys." + id; keys.put(id.toLowerCase(Locale.ROOT), new KeyDef(id.toLowerCase(Locale.ROOT), keysYml.getString(p + ".display-name", id), mat(keysYml.getString(p + ".material"), Material.TRIPWIRE_HOOK), keysYml.getDouble(p + ".price", 150000))); }
    }

    private void loadCrates() {
        crates.clear(); locations.clear(); ConfigurationSection s = cratesYml.getConfigurationSection("crates");
        if (s != null) for (String id : s.getKeys(false)) { String p = "crates." + id; if (!cratesYml.getBoolean(p + ".enabled", true)) continue; crates.put(id.toLowerCase(Locale.ROOT), new CrateDef(id.toLowerCase(Locale.ROOT), cratesYml.getString(p + ".display", id), mat(cratesYml.getString(p + ".block"), Material.CHEST), cratesYml.getString(p + ".key", id + "_key").toLowerCase(Locale.ROOT), cratesYml.getString(p + ".effect", "fire_spiral"), cratesYml.getString(p + ".level-reward", "Default"))); }
        ConfigurationSection l = cratesYml.getConfigurationSection("locations");
        if (l != null) for (String k : l.getKeys(false)) locations.put(k, new CrateLoc(cratesYml.getString("locations." + k + ".crate", ""), cratesYml.getString("locations." + k + ".world", "world"), cratesYml.getInt("locations." + k + ".x"), cratesYml.getInt("locations." + k + ".y"), cratesYml.getInt("locations." + k + ".z")));
    }

    private void loadRewards() {
        rewards.clear(); ConfigurationSection root = rewardsYml.getConfigurationSection("rewards"); if (root == null) return;
        for (String crate : root.getKeys(false)) { List<Reward> list = new ArrayList<>(); ConfigurationSection c = root.getConfigurationSection(crate); if (c == null) continue;
            for (String rarity : c.getKeys(false)) for (Map<?, ?> m : rewardsYml.getMapList("rewards." + crate + "." + rarity)) {
                String type = str(m, "type", "ITEM").toUpperCase(Locale.ROOT); int amount = Math.max(1, num(m, "amount", 1)); int weight = Math.max(1, num(m, "weight", 10));
                Material material = mat(str(m, "material", type.equals("COMMAND") ? "COMMAND_BLOCK" : "STONE"), type.equals("COMMAND") ? Material.COMMAND_BLOCK : Material.STONE);
                if (type.equals("COMMAND")) list.add(new Reward(RewardType.COMMAND, str(m, "display", "Command Reward"), material, amount, weight, commands(m)));
                else list.add(new Reward(RewardType.ITEM, str(m, "display", material.name() + " x" + amount), material, amount, weight, List.of()));
            }
            rewards.put(crate.toLowerCase(Locale.ROOT), list);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("key")) { if (!(sender instanceof Player p)) return true; if (args.length == 1 && args[0].equalsIgnoreCase("shop")) { if (!p.hasPermission("velioragacha.shop")) return deny(p); openShop(p); } else msg(sender, "&f/key shop"); return true; }
        if (args.length == 0) { msg(sender, "&d/vgcreate gui &7| &d/vgcreate givekey <player|*> <key> <amount> &7| &d/vgcreate reload"); return true; }
        if (args[0].equalsIgnoreCase("reload")) { if (!sender.hasPermission("velioragacha.reload")) return deny(sender); removeHolograms(); loadAll(); spawnAllHolograms(); restartEffects(); msg(sender, "&aVelioraGacha berhasil direload."); return true; }
        if (args[0].equalsIgnoreCase("gui")) { if (!(sender instanceof Player p)) return true; if (!p.hasPermission("velioragacha.gui")) return deny(p); openAdmin(p); return true; }
        if (args[0].equalsIgnoreCase("givekey")) return giveKey(sender, args); return true;
    }

    private boolean giveKey(CommandSender sender, String[] args) {
        if (!sender.hasPermission("velioragacha.givekey")) return deny(sender); if (args.length < 4) { msg(sender, "&c/vgcreate givekey <player|*> <key> <amount>"); return true; }
        String key = args[2].toLowerCase(Locale.ROOT); int amount = parse(args[3]); if (!keys.containsKey(key) || amount < 1) { msg(sender, "&cKey/amount tidak valid."); return true; }
        if (args[1].equals("*")) { for (Player p : Bukkit.getOnlinePlayers()) addKey(p.getUniqueId(), key, amount); msg(sender, "&aKey diberikan ke semua player online."); return true; }
        OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]); addKey(off.getUniqueId(), key, amount); msg(sender, "&aKey diberikan ke &f" + args[1]); return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) { if (c.getName().equalsIgnoreCase("key")) return args.length == 1 ? List.of("shop") : List.of(); if (args.length == 1) return List.of("gui", "givekey", "reload"); if (args.length == 3 && args[0].equalsIgnoreCase("givekey")) return new ArrayList<>(keys.keySet()); if (args.length == 4) return List.of("1", "5", "10", "32", "64"); return List.of(); }

    private void openShop(Player p) { Holder h = new Holder("shop", null, 1); Inventory inv = gui(h, 54, getConfig().getString("shop.title", "&8Key Shop")); fill(inv); int[] slots = {20,22,24,30,32}; int i=0; for (KeyDef k: keys.values()) if (i<slots.length) inv.setItem(slots[i++], keyIcon(k)); p.openInventory(inv); }
    private void openQty(Player p, String key, int amount) { KeyDef k=keys.get(key); if(k==null)return; int max=getConfig().getInt("settings.max-shop-amount",64); amount=Math.max(1,Math.min(max,amount)); Holder h=new Holder("qty",key,amount); Inventory inv=gui(h,54,getConfig().getString("shop.quantity-title", "&8Beli %key%").replace("%key%", plain(k.name))); fill(inv); inv.setItem(20,item(Material.RED_STAINED_GLASS_PANE,"&c-10")); inv.setItem(21,item(Material.RED_DYE,"&c-1")); inv.setItem(22,item(k.icon,"&f"+plain(k.name)+" &7x"+amount+" &a"+cash(k.price*amount))); inv.setItem(23,item(Material.LIME_DYE,"&a+1")); inv.setItem(24,item(Material.LIME_STAINED_GLASS_PANE,"&a+10")); inv.setItem(30,item(Material.ARROW,"&eBack")); inv.setItem(32,item(Material.EMERALD_BLOCK,"&aConfirm")); p.openInventory(inv); }
    private void openAdmin(Player p) { Holder h=new Holder("admin",null,1); Inventory inv=gui(h,27,"&8VelioraGacha Admin"); fill(inv); inv.setItem(11,item(Material.TRIPWIRE_HOOK,"&dKey Manager")); inv.setItem(13,item(Material.CHEST,"&6Crate Placer")); inv.setItem(15,item(Material.COMPARATOR,"&eCrate Settings")); p.openInventory(inv); }
    private void openPlacer(Player p) { Holder h=new Holder("placer",null,1); Inventory inv=gui(h,27,"&8Crate Placer"); fill(inv); int slot=10; for(CrateDef c:crates.values()) inv.setItem(slot++, crateItem(c)); p.openInventory(inv); }
    private void openKeys(Player p) { Holder h=new Holder("keys",null,1); Inventory inv=gui(h,27,"&8Key Manager"); fill(inv); int slot=10; for(KeyDef k:keys.values()) inv.setItem(slot++, keyIcon(k)); p.openInventory(inv); }
    private void openSettings(Player p) { Holder h=new Holder("settings",null,1); Inventory inv=gui(h,27,"&8Crate Settings"); fill(inv); int slot=10; for(CrateDef c:crates.values()) inv.setItem(slot++, item(c.block,"&e"+c.name)); p.openInventory(inv); }

    @EventHandler public void click(InventoryClickEvent e) { if (!(e.getWhoClicked() instanceof Player p) || !(e.getInventory().getHolder() instanceof Holder h)) return; e.setCancelled(true); ItemStack it=e.getCurrentItem(); if(it==null)return; int slot=e.getRawSlot(); if(h.type.equals("shop")){String k=pdc(it,keyKey); if(k!=null)openQty(p,k,1);} else if(h.type.equals("qty")){int a=h.amount; if(slot==20)a-=10; else if(slot==21)a--; else if(slot==23)a++; else if(slot==24)a+=10; else if(slot==30){openShop(p);return;} else if(slot==32){buy(p,h.id,h.amount);return;} else return; openQty(p,h.id,a);} else if(h.type.equals("admin")){if(slot==11)openKeys(p); else if(slot==13)openPlacer(p); else if(slot==15)openSettings(p);} else if(h.type.equals("keys")){String k=pdc(it,keyKey); if(k!=null)addKey(p.getUniqueId(),k,e.isShiftClick()?10:1);} else if(h.type.equals("placer")){String c=pdc(it,crateKey); if(c!=null&&crates.containsKey(c))p.getInventory().addItem(crateItem(crates.get(c)));} }
    private void buy(Player p,String key,int amount){KeyDef k=keys.get(key); if(k==null)return; int max=getConfig().getInt("settings.max-shop-amount",64); amount=Math.max(1,Math.min(max,amount)); if(economy==null){msg(p,"&cEconomy tidak tersedia.");return;} double total=k.price*amount; if(economy.getBalance(p)<total){msg(p,"&cUang kurang. Butuh &f"+cash(total));return;} economy.withdrawPlayer(p,total); addKey(p.getUniqueId(),key,amount); msg(p,"&aBerhasil membeli key x&f"+amount); openQty(p,key,amount);}

    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){String id=pdc(e.getItemInHand(),crateKey); if(id==null)return; if(!e.getPlayer().hasPermission("velioragacha.place")){e.setCancelled(true);deny(e.getPlayer());return;} Location l=e.getBlockPlaced().getLocation(); String k=locKey(l); cratesYml.set("locations."+k+".crate",id); cratesYml.set("locations."+k+".world",l.getWorld().getName()); cratesYml.set("locations."+k+".x",l.getBlockX()); cratesYml.set("locations."+k+".y",l.getBlockY()); cratesYml.set("locations."+k+".z",l.getBlockZ()); save(cratesYml,cratesFile); locations.put(k,new CrateLoc(id,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ())); spawnHologram(k,locations.get(k)); msg(e.getPlayer(),"&aCrate dibuat.");}
    @EventHandler(priority=EventPriority.HIGHEST) public void br(BlockBreakEvent e){String lk=locKey(e.getBlock().getLocation()); CrateLoc l=locations.get(lk); if(l==null)return; if(!e.getPlayer().hasPermission("velioragacha.break")){e.setCancelled(true);deny(e.getPlayer());return;} removeHologram(lk); cratesYml.set("locations."+lk,null); save(cratesYml,cratesFile); locations.remove(lk); e.setDropItems(false); if(crates.containsKey(l.crate))e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(),crateItem(crates.get(l.crate)));}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){Block b=e.getClickedBlock(); if(b==null||!e.getAction().name().equals("RIGHT_CLICK_BLOCK"))return; CrateLoc l=locations.get(locKey(b.getLocation())); if(l==null)return; e.setCancelled(true); Player p=e.getPlayer(); if(!p.hasPermission("velioragacha.open")){deny(p);return;} CrateDef c=crates.get(l.crate); if(c==null)return; if(getKeys(p.getUniqueId()).getOrDefault(c.key,0)<=0){noKey(p,b.getLocation());return;} addKey(p.getUniqueId(),c.key,-1); roulette(p,c);}
    private void noKey(Player p,Location loc){msg(p,"&cKamu butuh key untuk membuka crate ini. Beli di &f/key shop&c."); p.playSound(p.getLocation(),sound(getConfig().getString("no-key.sound"),Sound.ENTITY_VILLAGER_NO),1,1); Vector v=p.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(getConfig().getDouble("no-key.knockback.strength",.65)); v.setY(getConfig().getDouble("no-key.knockback.y",.25)); p.setVelocity(v);}

    private void roulette(Player p,CrateDef c){if(opening.containsKey(p.getUniqueId()))return; Reward finalReward=chooseReward(c.id); int size=invSize(getConfig().getInt("animation.gui-size",45)); int slot=Math.min(size-1,Math.max(0,getConfig().getInt("animation.indicator-slot",22))); Holder h=new Holder("roulette",c.id,1); Inventory inv=gui(h,size,getConfig().getString("animation.title","&8Opening %crate%").replace("%crate%",c.name)); fill(inv); p.openInventory(inv); int duration=Math.max(20,getConfig().getInt("animation.duration-ticks",120)); int start=Math.max(1,getConfig().getInt("animation.start-speed-ticks",2)); int end=Math.max(start,getConfig().getInt("animation.end-speed-ticks",10)); Sound tickSound=sound(getConfig().getString("animation.sound-tick"),Sound.BLOCK_NOTE_BLOCK_HAT); BukkitTask task=new BukkitRunnable(){int ticks=0,next=0;public void run(){ticks++; if(ticks>=next){double progress=Math.min(1.0,(double)ticks/duration); int delay=start+(int)Math.round((end-start)*progress); next=ticks+delay; inv.setItem(slot,(ticks>=duration?finalReward:chooseReward(c.id)).itemIcon()); p.playSound(p.getLocation(),tickSound,.35f,1.5f);} if(ticks>=duration){finish(p,finalReward,c);cancel();}}}.runTaskTimer(this,1,1); opening.put(p.getUniqueId(),task);}
    private void finish(Player p,Reward r,CrateDef c){BukkitTask t=opening.remove(p.getUniqueId()); if(t!=null)t.cancel(); if(!p.isOnline())return; if(r.type==RewardType.COMMAND){for(String cmd:r.commands)Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd.replace("%player%",p.getName()).replace("%crate%",c.id).replace("%reward%",plain(r.display)).replace("%key%",c.key));} else p.getInventory().addItem(new ItemStack(r.material,r.amount)); p.playSound(p.getLocation(),sound(getConfig().getString("animation.sound-finish"),Sound.ENTITY_PLAYER_LEVELUP),1,1); msg(p,"&aKamu mendapat &f"+plain(r.display));}
    private Reward chooseReward(String crate){List<Reward> list=rewards.getOrDefault(crate,List.of()); if(list.isEmpty())return fallbackReward(); int total=list.stream().mapToInt(r->Math.max(1,r.weight)).sum(), roll=random.nextInt(total)+1, cur=0; for(Reward r:list){cur+=Math.max(1,r.weight); if(roll<=cur)return r;} return list.get(0);} private Reward fallbackReward(){return new Reward(RewardType.ITEM,"EMERALD x4",Material.EMERALD,4,10,List.of());}

    private void spawnAllHolograms(){if(!getConfig().getBoolean("hologram.enabled",true))return; for(Map.Entry<String,CrateLoc> e:locations.entrySet())spawnHologram(e.getKey(),e.getValue());}
    private void spawnHologram(String key,CrateLoc loc){if(!getConfig().getBoolean("hologram.enabled",true))return; removeHologram(key); World w=Bukkit.getWorld(loc.world); CrateDef c=crates.get(loc.crate); if(w==null||c==null)return; Location l=new Location(w,loc.x+.5,loc.y+getConfig().getDouble("hologram.y-offset",1.7),loc.z+.5); TextDisplay d=w.spawn(l,TextDisplay.class); d.addScoreboardTag("velioragacha_hologram"); d.setPersistent(false); d.setGravity(false); d.setBillboard(Display.Billboard.CENTER); d.setText(color(holoText(c,loc))); holograms.put(key,d.getUniqueId());}
    private String holoText(CrateDef c,CrateLoc loc){String keysText="-"; Player near=nearest(new Location(Bukkit.getWorld(loc.world),loc.x+.5,loc.y+.5,loc.z+.5)); if(near!=null)keysText=String.valueOf(getKeys(near.getUniqueId()).getOrDefault(c.key,0)); KeyDef k=keys.get(c.key); List<String> lines=getConfig().getStringList("hologram.lines"); if(lines.isEmpty())lines=List.of("&d%crate_display% Crate","&fKeys: %keys%"); String out=String.join("\n",lines); return out.replace("%crate%",c.id).replace("%crate_display%",c.name).replace("%key%",c.key).replace("%key_name%",k==null?c.key:plain(k.name)).replace("%keys%",keysText).replace("%level_reward%",c.level);}
    private Player nearest(Location l){if(l.getWorld()==null)return null; Player best=null; double bd=64; for(Player p:l.getWorld().getPlayers()){double d=p.getLocation().distanceSquared(l); if(d<bd){bd=d;best=p;}} return best;}
    private void removeHologram(String key){UUID id=holograms.remove(key); if(id==null)return; for(World w:Bukkit.getWorlds())for(Entity e:w.getEntities())if(e.getUniqueId().equals(id)){e.remove();return;}}
    private void removeHolograms(){new ArrayList<>(holograms.keySet()).forEach(this::removeHologram);} private void cleanupHolograms(){for(World w:Bukkit.getWorlds())for(Entity e:w.getEntities())if(e.getScoreboardTags().contains("velioragacha_hologram"))e.remove();}

    private void startEffects(){if(!getConfig().getBoolean("effects.enabled",true))return; int interval=Math.max(2,getConfig().getInt("effects.interval-ticks",8)); effectTask=new BukkitRunnable(){double t=0;public void run(){t+=.4;for(CrateLoc l:locations.values())effect(l,t);}}.runTaskTimer(this,interval,interval);} private void restartEffects(){if(effectTask!=null)effectTask.cancel();startEffects();}
    private void effect(CrateLoc l,double t){World w=Bukkit.getWorld(l.world); CrateDef c=crates.get(l.crate); if(w==null||c==null||!w.isChunkLoaded(l.x>>4,l.z>>4))return; double r=getConfig().getDouble("effects.radius",1.25),x=l.x+.5+Math.cos(t)*r,z=l.z+.5+Math.sin(t)*r,y=l.y+.7+Math.sin(t*.6)*.45; Particle p=effectParticle(c.effect); w.spawnParticle(p,x,y,z,2,.03,.03,.03,.01); if(c.effect.toLowerCase(Locale.ROOT).contains("fire"))w.spawnParticle(particle("LAVA",Particle.FLAME),x,y,z,1,.02,.02,.02,0);}
    private Particle effectParticle(String e){String s=e.toLowerCase(Locale.ROOT); if(s.contains("cherry"))return particle("CHERRY_LEAVES",Particle.HAPPY_VILLAGER); if(s.contains("end"))return particle("END_ROD",Particle.FLAME); if(s.contains("oak")||s.contains("leaf"))return Particle.HAPPY_VILLAGER; if(s.contains("smoke"))return particle("CAMPFIRE_COSY_SMOKE",particle("SMOKE",Particle.CLOUD)); if(s.contains("bubble"))return particle("BUBBLE_POP",particle("SPLASH",Particle.HAPPY_VILLAGER)); return Particle.FLAME;}

    private Inventory gui(Holder h,int s,String title){Inventory inv=Bukkit.createInventory(h,s,color(title));h.inv=inv;return inv;} private void fill(Inventory inv){ItemStack f=item(Material.BLACK_STAINED_GLASS_PANE," "); for(int i=0;i<inv.getSize();i++)inv.setItem(i,f);} private ItemStack item(Material m,String name){ItemStack it=new ItemStack(m); ItemMeta im=it.getItemMeta(); if(im!=null){im.setDisplayName(color(name));it.setItemMeta(im);}return it;} private ItemStack keyIcon(KeyDef k){ItemStack it=item(k.icon,k.name); ItemMeta im=it.getItemMeta(); im.getPersistentDataContainer().set(keyKey,PersistentDataType.STRING,k.id); it.setItemMeta(im); return it;} private ItemStack crateItem(CrateDef c){ItemStack it=item(c.block,"&d"+c.name+" Crate"); ItemMeta im=it.getItemMeta(); im.getPersistentDataContainer().set(crateKey,PersistentDataType.STRING,c.id); it.setItemMeta(im); return it;}
    private Material mat(String n,Material f){Material m=n==null?null:Material.matchMaterial(n.toUpperCase(Locale.ROOT));return m==null?f:m;} private Particle particle(String n,Particle f){try{return Particle.valueOf(n);}catch(Exception ex){return f;}} private Sound sound(String n,Sound f){try{return Sound.valueOf(String.valueOf(n).toUpperCase(Locale.ROOT));}catch(Exception ex){return f;}} private String pdc(ItemStack it,org.bukkit.NamespacedKey k){if(it==null||!it.hasItemMeta())return null;return it.getItemMeta().getPersistentDataContainer().get(k,PersistentDataType.STRING);} private void msg(CommandSender s,String m){s.sendMessage(color(m));} private boolean deny(CommandSender s){msg(s,"&cKamu tidak punya izin.");return true;} private String color(String s){return ChatColor.translateAlternateColorCodes('&',s.replaceAll("<#([A-Fa-f0-9]{6})>","").replaceAll("</#([A-Fa-f0-9]{6})>",""));} private String plain(String s){return ChatColor.stripColor(color(s));} private String cash(double d){return money.format(d).replace(',','.');} private int parse(String s){try{return Integer.parseInt(s);}catch(Exception e){return 0;}} private int invSize(int size){int s=Math.max(9,Math.min(54,size));return ((s+8)/9)*9;} private String locKey(Location l){return l.getWorld().getName()+","+l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ();} private void save(FileConfiguration y,File f){try{y.save(f);}catch(IOException ignored){}}
    private Object val(Map<?,?>m,String k){return m.get(k);} private String str(Map<?,?>m,String k,String f){Object v=val(m,k);return v==null?f:String.valueOf(v);} private int num(Map<?,?>m,String k,int f){try{return Integer.parseInt(String.valueOf(val(m,k)));}catch(Exception e){return f;}} private List<String> commands(Map<?,?>m){Object one=val(m,"command"), many=val(m,"commands"); List<String> out=new ArrayList<>(); if(one!=null&&!String.valueOf(one).isBlank())out.add(String.valueOf(one)); if(many instanceof List<?> l) for(Object o:l) if(o!=null&&!String.valueOf(o).isBlank())out.add(String.valueOf(o)); return out;}
    private Map<String,Integer> getKeys(UUID u){return keyCache.computeIfAbsent(u,id->{FileConfiguration y=YamlConfiguration.loadConfiguration(new File(playerDir,id+".yml"));Map<String,Integer> m=new HashMap<>();for(String k:keys.keySet())m.put(k,y.getInt("keys."+k,0));return m;});} private void addKey(UUID u,String k,int a){Map<String,Integer> m=getKeys(u);m.put(k,Math.max(0,m.getOrDefault(k,0)+a));savePlayer(u);} private void savePlayer(UUID u){FileConfiguration y=new YamlConfiguration();getKeys(u).forEach((k,v)->y.set("keys."+k,v));save(y,new File(playerDir,u+".yml"));}
    private enum RewardType{ITEM,COMMAND} private record KeyDef(String id,String name,Material icon,double price){} private record CrateDef(String id,String name,Material block,String key,String effect,String level){} private record CrateLoc(String crate,String world,int x,int y,int z){} private record Reward(RewardType type,String display,Material material,int amount,int weight,List<String> commands){ItemStack itemIcon(){ItemStack it=new ItemStack(material,Math.max(1,Math.min(64,amount)));ItemMeta im=it.getItemMeta();if(im!=null){im.setDisplayName(ChatColor.WHITE+ChatColor.stripColor(display));it.setItemMeta(im);}return it;}} private static final class Holder implements InventoryHolder{final String type,id;final int amount;Inventory inv;Holder(String t,String i,int a){type=t;id=i;amount=a;}public Inventory getInventory(){return inv;}}
}
