package com.example.punchsnowball;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.block.BlockState;
import org.bukkit.attribute.Attribute;
import java.time.Duration;
import net.kyori.adventure.title.Title;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

/** Reborn combat controller: F to activate, normal right-clicks retained for shooting/casting.
 * All combat checks use the server's trust rules. PDC item identifiers are backward compatible.
 * No world blocks are modified by combat visuals. */
final class FrostRebornCombat implements Listener {
    private final PunchSnowballPlugin plugin;
    private final Map<UUID,Map<String,Long>> cooldowns = new HashMap<>();
    private final Map<UUID,Map<UUID,Mark>> swordMarks = new HashMap<>();
    private final Map<UUID,Map<UUID,Mark>> axeMarks = new HashMap<>();
    private final Map<UUID,Map<UUID,Mark>> conductive = new HashMap<>();
    private final Map<UUID,Long> parries = new HashMap<>(), armorBurst = new HashMap<>(), helmetReady = new HashMap<>(), helmetStart = new HashMap<>();
    private record Reveal(UUID owner, UUID target, long until) {}
    private final Map<UUID,Map<UUID,Long>> helmetReveals = new HashMap<>();
    private final Map<Location,Long> temporarySnow = new HashMap<>();
    private long tickCounter = 0;
    private static final int MAX_TEMP_SNOW = 192;
    private final NamespacedKey waterEfficiency = new NamespacedKey("frostpvp", "boots_water_efficiency");
    private final NamespacedKey underwaterSpeed = new NamespacedKey("frostpvp", "boots_underwater_speed");
    private final Map<UUID,Long> momentum = new HashMap<>(), regenReady = new HashMap<>(), shieldPassive = new HashMap<>();
    private final Set<UUID> activeGrapple = new HashSet<>();
    private final Set<UUID> preparedBow = new HashSet<>(), preparedCrossbow = new HashSet<>(), preparedTrident = new HashSet<>();
    private final Map<UUID,Shot> shots = new HashMap<>();
    private final Map<UUID,Tether> tethers = new HashMap<>();
    private final Map<UUID,Location> anchors = new HashMap<>();
    private final Map<UUID,Long> lastMining = new HashMap<>();
    private final Map<UUID,Integer> miningChain = new HashMap<>();
    private final Map<UUID,List<UUID>> summons = new HashMap<>();
    private final Map<UUID,Long> auraUntil = new HashMap<>();
    private record Mark(int count,long until) {}
    private record Shot(UUID owner,String type,int bounces) {}
    private record Tether(UUID victim,long until) {}
    private static final Map<String,Integer> DEFAULT_CD = Map.ofEntries(
        Map.entry("sword",28), Map.entry("battleaxe",35),Map.entry("spear",30),
        Map.entry("bow",35),Map.entry("crossbow",30),Map.entry("grapple",28),
        Map.entry("riptide",35),Map.entry("firework",45),Map.entry("shield",28),
        Map.entry("totem",45),Map.entry("horn",60),Map.entry("eclipse",45));
    private final NamespacedKey bonusSpeed = new NamespacedKey("frostpvp","twin_movement");
    private final NamespacedKey bonusAttackSpeed = new NamespacedKey("frostpvp","day_attack_speed");
    private final NamespacedKey bonusReach = new NamespacedKey("frostpvp","night_reach");
    private final NamespacedKey bonusBlockReach = new NamespacedKey("frostpvp","night_block_reach");
    private final NamespacedKey bonusKnockback = new NamespacedKey("frostpvp","day_knockback");
    private final NamespacedKey snowSpeed = new NamespacedKey("frostpvp","snow_speed");
    private final NamespacedKey shieldKnockback = new NamespacedKey("frostpvp","shield_knockback");
    private final Set<UUID> guardProc = new HashSet<>();
    FrostRebornCombat(PunchSnowballPlugin p) {
        plugin=p; Bukkit.getPluginManager().registerEvents(this,p);
        new BukkitRunnable(){@Override public void run(){tick();}}.runTaskTimer(p,5L,5L);
    }
    static String id(ItemStack i){return FrostTooltips.identify(i);}
    private String main(Player p){return id(p.getInventory().getItemInMainHand());}
    private String off(Player p){return id(p.getInventory().getItemInOffHand());}
    private boolean both(Player p){return ("dayblade".equals(main(p)) && "nightblade".equals(off(p)))||("nightblade".equals(main(p))&&"dayblade".equals(off(p)));}
    private boolean valid(Player caster,LivingEntity target){return caster!=target && target.isValid() && !target.isDead() && plugin.rebornTarget(caster.getUniqueId(),target);}
    private List<LivingEntity> targets(Player p,Location origin,double r){
        List<LivingEntity> out=new ArrayList<>();
        for(Entity e:origin.getWorld().getNearbyEntities(origin,r,r,r))
            if(e instanceof LivingEntity l && valid(p,l) && l.getLocation().distanceSquared(origin)<=r*r)out.add(l);
        return out;
    }
    private void hit(Player p,LivingEntity target,double damage){if(valid(p,target))target.damage(damage,p);}
    private long ms(){return System.currentTimeMillis();}
    private long remains(Player p,String type){return Math.max(0L,cooldowns.getOrDefault(p.getUniqueId(),Map.of()).getOrDefault(type,0L)-ms());}
    private boolean ready(Player p,String type){
        if(remains(p,type)>0){p.sendActionBar(Component.text(ChatColor.AQUA+type+ChatColor.GRAY+": "+String.format(Locale.US,"%.1fs",remains(p,type)/1000.0)));return false;}
        int cd=plugin.getConfig().getInt("reborn."+type+".cooldown-seconds",DEFAULT_CD.getOrDefault(type,30));
        cooldowns.computeIfAbsent(p.getUniqueId(),x->new HashMap<>()).put(type,ms()+cd*1000L);return true;
    }
    private void snow(Location l,int count){l.getWorld().spawnParticle(Particle.SNOWFLAKE,l,count,.4,.45,.4,.07);}
    private void sound(Location l,Sound s,float pitch){l.getWorld().playSound(l,s,.8f,pitch);}
    private void arc(Location origin,Vector facing,double radius,boolean strong){
        Vector flat=facing.clone().setY(0); if(flat.lengthSquared()<.01)flat=new Vector(0,0,1);flat.normalize();
        Vector side=new Vector(-flat.getZ(),0,flat.getX());
        for(int i=0;i<=24;i++){
            double t=-1.18+2.36*i/24.0;
            Location pt=origin.clone().add(flat.clone().multiply(Math.cos(t)*radius)).add(side.clone().multiply(Math.sin(t)*radius));
            pt.add(0,1.15+Math.sin((i/24.0)*Math.PI)*.85,0);
            pt.getWorld().spawnParticle(Particle.SNOWFLAKE,pt,strong?3:1,.07,.07,.07,0);
            if(strong)pt.getWorld().spawnParticle(Particle.END_ROD,pt,1,0,0,0,0);
        }
    }
    private void ring(Location l,double radius,int dots){for(int i=0;i<dots;i++){
        double a=2*Math.PI*i/dots;Location q=l.clone().add(Math.cos(a)*radius,.15,Math.sin(a)*radius);
        q.getWorld().spawnParticle(Particle.SNOWFLAKE,q,2,.04,.04,.04,0);
    }}
    private Mark apply(Map<UUID,Map<UUID,Mark>> map,Player owner,LivingEntity victim){
        Map<UUID,Mark> m=map.computeIfAbsent(owner.getUniqueId(),x->new HashMap<>());
        Mark old=m.get(victim.getUniqueId());Mark next=new Mark(old!=null&&old.until>ms()?Math.min(3,old.count+1):1,ms()+5500);
        m.put(victim.getUniqueId(),next);return next;
    }
    private int consume(Map<UUID,Map<UUID,Mark>> map,Player owner,LivingEntity victim){
        Mark x=map.getOrDefault(owner.getUniqueId(),Map.of()).remove(victim.getUniqueId());return x!=null&&x.until>ms()?x.count:0;
    }
    private boolean same(Player p,String type){return p.isOnline()&&!p.isDead()&&type.equals(main(p));}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onF(PlayerSwapHandItemsEvent e){
        Player p=e.getPlayer();
        if(tethers.containsKey(p.getUniqueId())){e.setCancelled(true);pullTether(p);return;}
        String type=main(p);
        if(!both(p) && type==null){type=off(p);}
        // Both blades equipped: F remains the normal hand-swap key.
        // Eclipse Dance is now activated from right-click, handled below.
        if(both(p)) return;
        if(type==null || !Set.of("sword","battleaxe","spear","bow","crossbow","grapple","riptide","firework","shield","totem","horn","pickaxe_fortune","pickaxe_silk").contains(type))return;
        e.setCancelled(true);if(plugin.rebornFrozen(p)||p.isDead()||p.getGameMode()==GameMode.SPECTATOR)return;
        if(type.startsWith("pickaxe")){if(p.isSneaking())plugin.rebornTogglePickaxeArea(p);else plugin.rebornTogglePickaxe(p);return;}
        if(!ready(p,type))return;
        switch(type){
            case "sword"->wintersDance(p);
            case "battleaxe"->glacierCrusher(p);
            case "spear"->northernImpalement(p);
            case "bow"->{preparedBow.add(p.getUniqueId());p.sendMessage(ChatColor.AQUA+"[Frost] Crystal Bloom prepared. Shoot your bow!");}
            case "crossbow"->{preparedCrossbow.add(p.getUniqueId());p.sendMessage(ChatColor.AQUA+"[Frost] Shattered Ricochet prepared. Fire a bolt!");}
            case "grapple"->{preparedTrident.add(p.getUniqueId());p.sendMessage(ChatColor.AQUA+"[Frost] Glacial Harpoon prepared. Throw the trident!");}
            case "riptide"->stormwake(p);
            case "firework"->skyhunter(p);
            case "shield"->reprisal(p);
            case "totem"->sanctuary(p);
            case "horn"->frostbound(p);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onEclipseRightClick(PlayerInteractEvent e) {
        if(e.getHand()!=EquipmentSlot.HAND) return; // Never double activate from the offhand event.
        if(e.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_AIR &&
           e.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Player p=e.getPlayer();
        if(!both(p)) return;
        e.setCancelled(true);
        if(plugin.rebornFrozen(p) || p.isDead() || p.getGameMode()==GameMode.SPECTATOR) return;
        if(ready(p,"eclipse")) eclipse(p);
    }

    /** Short physics-based lunges instead of the old multi-block teleports. */
    private void shortLunge(Player player, Vector direction, double power){
        Vector dir=direction.clone().setY(0);
        if(dir.lengthSquared()<.001)dir=new Vector(0,0,1);
        dir.normalize();
        Location probe=player.getLocation().clone().add(dir.clone().multiply(0.85));
        // Don't force the player through walls; ordinary collision and steering remain active.
        if(!probe.getBlock().isPassable() || !probe.clone().add(0,1,0).getBlock().isPassable())return;
        Vector current=player.getVelocity();
        player.setVelocity(dir.multiply(power).setY(player.isOnGround()?0.11:Math.max(-0.08,current.getY())));
    }
    private void wintersDance(Player p){
        sound(p.getLocation(),Sound.ENTITY_PLAYER_ATTACK_SWEEP,.8f);
        Map<UUID,Integer> sequence=new HashMap<>();
        new BukkitRunnable(){int step=0;@Override public void run(){
            if(step>=3||!same(p,"sword")){cancel();return;}
            step++;
            final int comboStep=step;
            Vector dir=p.getEyeLocation().getDirection().setY(0);
            if(dir.lengthSquared()<.01)dir=new Vector(0,0,1);
            dir.normalize();
            final Vector facing=dir.clone();
            shortLunge(p,facing,.58); // ~1-2 blocks per lunge, not a five-block teleport
            arc(p.getLocation(),facing,2.0,true);
            sound(p.getLocation(),Sound.ENTITY_PLAYER_ATTACK_SWEEP,1.0f+step*.12f);
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                if(!same(p,"sword"))return;
                Set<UUID> struckThisStep=new HashSet<>();
                Location here=p.getLocation();
                arc(here,facing,2.25,true);
                for(LivingEntity target:targets(p,here,2.35)){
                    Vector offset=target.getLocation().toVector().subtract(here.toVector());offset.setY(0);
                    if(offset.lengthSquared()>.01 && offset.normalize().dot(facing)<-0.15)continue;
                    if(!struckThisStep.add(target.getUniqueId()))continue;
                    hit(p,target,2.3);
                    snow(target.getLocation().add(0,1,0),25);
                    int hits=sequence.merge(target.getUniqueId(),1,Integer::sum);
                    if(comboStep==3 && hits>=3){
                        hit(p,target,3.0);
                        arc(target.getLocation(),facing,2.5,true);
                        snow(target.getLocation().add(0,1,0),56);
                        consume(swordMarks,p,target);
                    }
                }
            },2L);
        }}.runTaskTimer(plugin,0L,9L);
    }
    private void glacierCrusher(Player p){
        // Launch higher and forward; the actual slam happens only upon real ground contact.
        Vector forward=p.getEyeLocation().getDirection().setY(0);
        if(forward.lengthSquared()<.01)forward=new Vector(0,0,1);
        forward.normalize();
        p.setVelocity(forward.multiply(.72).setY(1.16));
        sound(p.getLocation(),Sound.BLOCK_ANVIL_USE,.65f);
        final World startingWorld=p.getWorld();
        new BukkitRunnable(){int age=0;boolean airborne=false;@Override public void run(){
            if(!p.isOnline()||p.isDead()||p.getWorld()!=startingWorld||age++>85){cancel();return;}
            if(!p.isOnGround())airborne=true;
            if(age%3==0)snow(p.getLocation().add(0,.2,0),12);
            if(!airborne||age<5||!p.isOnGround())return;
            Location center=p.getLocation().clone();
            ring(center,4.2,60);snow(center.clone().add(0,1,0),100);
            sound(center,Sound.BLOCK_GLASS_BREAK,.75f);
            for(LivingEntity t:targets(p,center,4.2)){
                int charges=consume(axeMarks,p,t);
                hit(p,t,4+Math.min(3,charges)*1.2);
                Vector knock=t.getLocation().toVector().subtract(center.toVector());
                if(knock.lengthSquared()>.1)t.setVelocity(knock.normalize().multiply(.62).setY(.49));
            }
            cancel();
        }}.runTaskTimer(plugin,1L,1L);
    }
    private void northernImpalement(Player p){
        // One visible spear flies forward; no split volley or complicated ground spikes.
        Location start=p.getEyeLocation().clone();
        Vector direction=start.getDirection().normalize();
        Location spawn=start.clone().add(direction.clone().multiply(.9));
        org.bukkit.entity.ItemDisplay spear=p.getWorld().spawn(spawn,org.bukkit.entity.ItemDisplay.class,display->{
            display.setItemStack(new ItemStack(Material.NETHERITE_SPEAR));
            display.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
            display.setGravity(false);
            display.setPersistent(false);
            display.setInvulnerable(true);
        });
        sound(spawn,Sound.ITEM_TRIDENT_THROW,1.0f);
        Set<UUID> struck=new HashSet<>();
        new BukkitRunnable(){int age=0;@Override public void run(){
            if(age++>=19||!p.isOnline()||!spear.isValid()){
                if(spear.isValid())spear.remove();cancel();return;
            }
            Location point=spawn.clone().add(direction.clone().multiply(age*1.2));
            if(!point.getBlock().isPassable()){
                snow(point,35);sound(point,Sound.BLOCK_GLASS_BREAK,1.4f);
                spear.remove();cancel();return;
            }
            point.setDirection(direction);spear.teleport(point);
            point.getWorld().spawnParticle(Particle.SNOWFLAKE,point,16,.22,.22,.22,.015);
            point.getWorld().spawnParticle(Particle.END_ROD,point,3,.12,.12,.12,.01);
            for(LivingEntity target:targets(p,point,1.1))if(struck.add(target.getUniqueId())){
                hit(p,target,5.0);snow(target.getLocation().add(0,1,0),38);
                sound(target.getLocation(),Sound.BLOCK_AMETHYST_CLUSTER_BREAK,1.0f);
                target.setVelocity(target.getVelocity().clone().setY(.32));
            }
        }}.runTaskTimer(plugin,0L,1L);
    }
    private void stormwake(Player p){
        Vector dir=p.getEyeLocation().getDirection().normalize();p.setVelocity(dir.clone().multiply(p.isInWater()?2.1:1.55).setY(Math.max(.18,dir.getY()*.95)));
        sound(p.getLocation(),Sound.ITEM_TRIDENT_RIPTIDE_3,.85f);
        Set<UUID> struck=new HashSet<>();new BukkitRunnable(){int ticks=0;@Override public void run(){
            if(ticks++>=12||!p.isOnline()){cancel();return;}
            Location current=p.getLocation();current.getWorld().spawnParticle(Particle.SPLASH,current.clone().add(0,1,0),25,.75,.6,.75,.05);
            snow(current.clone().add(0,1,0),12);
            for(LivingEntity t:targets(p,current,1.8))if(struck.add(t.getUniqueId())){
                hit(p,t,3);Location impact=t.getLocation().clone();ring(impact,1.2,24);
                Bukkit.getScheduler().runTaskLater(plugin,()->{
                    if(!p.isOnline())return;impact.getWorld().strikeLightningEffect(impact);
                    for(LivingEntity x:targets(p,impact,1.6))hit(p,x,3.0);
                },14L);
            }
        }}.runTaskTimer(plugin,0L,1L);
    }
    private void skyhunter(Player p){
        p.setVelocity(p.getVelocity().clone().setY(1.35));sound(p.getLocation(),Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,1.1f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,150,0,true,false));
        new BukkitRunnable(){int wave=0;@Override public void run(){
            if(wave++>=3||!p.isOnline()){cancel();return;}
            Block target=p.getTargetBlockExact(75);
            Location hit=target!=null?target.getLocation().add(.5,1,.5):p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(30));
            ring(hit,2.7,28);sound(hit,Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,1.25f);
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                sound(hit,Sound.ENTITY_FIREWORK_ROCKET_BLAST,1.4f);snow(hit.clone().add(0,1,0),65);
                hit.getWorld().spawnParticle(Particle.FIREWORK,hit,50,1.3,1,1.3,.1);
                for(LivingEntity t:targets(p,hit,3.2)){hit(p,t,2.0);t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,100,0));}
            },10L);
        }}.runTaskTimer(plugin,12L,34L);
    }
    private void reprisal(Player p){parries.put(p.getUniqueId(),ms()+800);ring(p.getLocation(),1.7,32);sound(p.getLocation(),Sound.ITEM_SHIELD_BLOCK,.8f);}
    private void sanctuary(Player p){
        Location center=p.getLocation().clone();ring(center,5,54);
        new BukkitRunnable(){int pulses=0;@Override public void run(){
            if(pulses++>=7||!p.isOnline()){cancel();return;}
            ring(center,5,52);snow(center.clone().add(0,1,0),30);
            for(Player ally:center.getWorld().getPlayers())if((ally.equals(p)||plugin.rebornIsTrusted(p,ally))&&ally.getLocation().distanceSquared(center)<=25){
                ally.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,35,0));
            }
            for(LivingEntity t:targets(p,center,5))t.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,36,0));
        }}.runTaskTimer(plugin,0L,20L);
    }
    private void grappleRod(Player p){
        Location target=anchors.get(p.getUniqueId());
        if(target==null || !target.getWorld().equals(p.getWorld())){
            Block b=p.getTargetBlockExact(40);if(b!=null)target=b.getLocation().add(.5,1.5,.5);
        }
        if(target==null){p.sendMessage(ChatColor.GRAY+"[Frost] Hook a solid block first.");return;}
        final Location anchor=target.clone();momentum.put(p.getUniqueId(),ms()+4000);activeGrapple.add(p.getUniqueId());
        new BukkitRunnable(){int ticks=0;@Override public void run(){
            if(ticks++>=22 || !p.isOnline() || !"rod".equals(main(p)) || !activeGrapple.contains(p.getUniqueId())){activeGrapple.remove(p.getUniqueId());cancel();return;}
            Vector delta=anchor.toVector().subtract(p.getLocation().toVector());if(delta.lengthSquared()<3){activeGrapple.remove(p.getUniqueId());cancel();return;}
            p.setVelocity(delta.normalize().multiply(Math.min(1.65,.5+delta.length()*.095)).add(p.getEyeLocation().getDirection().multiply(.16)));
            snow(p.getLocation().add(0,1,0),5);
        }}.runTaskTimer(plugin,0L,1L);
    }
    private void frostbound(Player p){
        Location base=p.getLocation();sound(base,Sound.EVENT_RAID_HORN,.8f);List<UUID> army=new ArrayList<>();
        for(int i=0;i<3;i++){
            double a=i*Math.PI*2/3;Location at=base.clone().add(Math.cos(a)*2,0,Math.sin(a)*2);
            while(at.getBlock().isPassable()&&at.getY()>base.getY()-3)at.subtract(0,1,0);at.add(0,1,0);
            Stray s=at.getWorld().spawn(at,Stray.class);s.setRemoveWhenFarAway(false);
            s.setCustomName(new String[]{ChatColor.AQUA+"Frost Knight",ChatColor.BLUE+"Frost Archer",ChatColor.WHITE+"Frost Guardian"}[i]);
            s.setCustomNameVisible(true);
            s.getEquipment().setItemInMainHand(new ItemStack(i==1?Material.BOW:i==0?Material.IRON_SWORD:Material.IRON_AXE));
            if(i==2)s.getEquipment().setItemInOffHand(new ItemStack(Material.SHIELD));
            s.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
            for(EquipmentSlot slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.HAND,EquipmentSlot.OFF_HAND))s.getEquipment().setDropChance(slot,0f);
            s.getPersistentDataContainer().set(new NamespacedKey("frostpvp","summoner"),PersistentDataType.STRING,p.getUniqueId().toString());
            army.add(s.getUniqueId());snow(at.clone().add(0,1,0),30);
            Bukkit.getScheduler().runTaskLater(plugin,()->{if(s.isValid())s.remove();},240L);
        }
        summons.put(p.getUniqueId(),army);
    }
    private void eclipse(Player p){
        // Four short controllable lunges with alternating day/night slash crescents.
        new BukkitRunnable(){int pass=0;@Override public void run(){
            if(pass>=4||!p.isOnline()||!both(p)){cancel();return;}
            pass++;
            int currentPass=pass;
            Vector dir=p.getEyeLocation().getDirection().setY(0);
            if(dir.lengthSquared()<.01)dir=new Vector(0,0,1);
            dir.normalize();
            final Vector facing=dir.clone();
            shortLunge(p,facing,.52);
            arc(p.getLocation(),facing,2.1,true);
            sound(p.getLocation(),Sound.ENTITY_PLAYER_ATTACK_SWEEP,pass%2==0?.75f:1.35f);
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                if(!p.isOnline()||!both(p))return;
                Location at=p.getLocation();
                arc(at,facing,2.4,true);
                at.getWorld().spawnParticle(currentPass%2==0?Particle.END_ROD:Particle.FLAME,
                    at.clone().add(0,1,0),24,.6,.5,.6,.015);
                for(LivingEntity t:targets(p,at,2.25)){
                    Vector offset=t.getLocation().toVector().subtract(at.toVector()).setY(0);
                    if(offset.lengthSquared()>.01&&offset.normalize().dot(facing)<-.1)continue;
                    hit(p,t,2.8);
                }
                if(currentPass==4){
                    ring(at,3,58);
                    for(LivingEntity t:targets(p,at,3))hit(p,t,4);
                    sound(at,Sound.ENTITY_ENDER_DRAGON_GROWL,.85f);
                }
            },2L);
        }}.runTaskTimer(plugin,0L,9L);
    }

    @EventHandler public void jump(com.destroystokyo.paper.event.player.PlayerJumpEvent e){
        if(activeGrapple.remove(e.getPlayer().getUniqueId()))momentum.put(e.getPlayer().getUniqueId(),ms()+3000);
    }
    @EventHandler(ignoreCancelled=true) public void onFish(PlayerFishEvent e){
        if(!"rod".equals(main(e.getPlayer())))return;
        if(e.getState()==PlayerFishEvent.State.IN_GROUND){anchors.put(e.getPlayer().getUniqueId(),e.getHook().getLocation().clone());}
    }
    @EventHandler(ignoreCancelled=true) public void launch(ProjectileLaunchEvent e){
        Projectile proj=e.getEntity();if(!(proj.getShooter() instanceof Player p))return;
        String type=main(p);
        if(proj instanceof Arrow arrow){
            if("bow".equals(type)&&preparedBow.remove(p.getUniqueId()))shots.put(arrow.getUniqueId(),new Shot(p.getUniqueId(),"bloom",0));
            if("crossbow".equals(type)&&preparedCrossbow.remove(p.getUniqueId()))shots.put(arrow.getUniqueId(),new Shot(p.getUniqueId(),"ricochet",0));
        } else if(proj instanceof Trident t && "grapple".equals(id(t.getItemStack()))
                && preparedTrident.remove(p.getUniqueId())){
            shots.put(proj.getUniqueId(),new Shot(p.getUniqueId(),"harpoon",0));
        }
    }
    @EventHandler(ignoreCancelled=true) public void onProjectileHit(ProjectileHitEvent e){
        Projectile projectile=e.getEntity();Shot s=shots.remove(projectile.getUniqueId());
        if(s==null)return;Player p=Bukkit.getPlayer(s.owner());if(p==null)return;
        Location spot=projectile.getLocation();
        if("bloom".equals(s.type())){
            final Location center=spot.clone();ring(center,2.8,36);sound(center,Sound.BLOCK_AMETHYST_BLOCK_CHIME,1.2f);
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                snow(center.clone().add(0,1,0),85);sound(center,Sound.BLOCK_GLASS_BREAK,.9f);
                for(LivingEntity victim:targets(p,center,3.5)){hit(p,victim,3.2);victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,65,0));}
            },16L);
        } else if("ricochet".equals(s.type())){
            if(e.getHitEntity() instanceof LivingEntity victim){hit(p,victim,2.0+s.bounces()*.95);snow(spot,30);}
            else if(e.getHitBlock()!=null && e.getHitBlockFace()!=null && s.bounces()<3){
                Vector v=projectile.getVelocity();Vector normal=e.getHitBlockFace().getDirection();
                Vector reflected=v.subtract(normal.clone().multiply(2*v.dot(normal))).multiply(1.08);
                if(reflected.lengthSquared()<.01)return;
                Location spawn=spot.clone().add(normal.clone().multiply(.5));
                Arrow next=p.getWorld().spawnArrow(spawn,reflected.normalize(),(float)Math.min(4.2,reflected.length()),0);
                next.setShooter(p);shots.put(next.getUniqueId(),new Shot(p.getUniqueId(),"ricochet",s.bounces()+1));
                snow(spawn,22);sound(spawn,Sound.BLOCK_AMETHYST_CLUSTER_BREAK,1.2f);
            }
        } else if("harpoon".equals(s.type()) && e.getHitEntity() instanceof LivingEntity victim && valid(p,victim)){
            tethers.put(p.getUniqueId(),new Tether(victim.getUniqueId(),ms()+3500));
            p.sendActionBar(Component.text(ChatColor.AQUA+"GLACIAL HARPOON " + ChatColor.WHITE+"Press F to pull!"));
            snow(victim.getLocation().add(0,1,0),30);
        }
    }
    private void pullTether(Player p){
        Tether t=tethers.remove(p.getUniqueId());if(t==null||t.until<ms())return;
        Entity ent=Bukkit.getEntity(t.victim());if(!(ent instanceof LivingEntity victim)||!valid(p,victim)||victim.getWorld()!=p.getWorld())return;
        Vector dir=p.getLocation().toVector().subtract(victim.getLocation().toVector());
        if(dir.lengthSquared()<1)return;victim.setVelocity(dir.normalize().multiply(Math.min(1.6,.6+dir.length()*.11)).setY(.24));
        snow(victim.getLocation().add(0,1,0),32);sound(victim.getLocation(),Sound.ENTITY_FISHING_BOBBER_RETRIEVE,.6f);
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void dayShieldBreak(EntityDamageByEntityEvent e){
        if(!(e.getDamager() instanceof Player a) || !(e.getEntity() instanceof Player d))return;
        if(!"dayblade".equals(main(a)) || !valid(a,d) || !d.isBlocking())return;
        d.setCooldown(Material.SHIELD,100);
        snow(d.getLocation().add(0,1,0),18);
        sound(d.getLocation(),Sound.ITEM_SHIELD_BREAK,.95f);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void attack(EntityDamageByEntityEvent e){
        if(e.getEntity() instanceof Player defender){
            if(parries.getOrDefault(defender.getUniqueId(),0L)>ms()){
                parries.remove(defender.getUniqueId());e.setDamage(e.getDamage()*.12);
                Location loc=defender.getLocation();snow(loc.clone().add(0,1,0),62);ring(loc,3,44);
                Entity assailant=e.getDamager() instanceof Projectile x && x.getShooter() instanceof Entity source?source:e.getDamager();
                if(assailant instanceof LivingEntity l && valid(defender,l)){
                    hit(defender,l,3.0);Vector recoil=l.getLocation().toVector().subtract(loc.toVector());
                    if(recoil.lengthSquared()>.01)l.setVelocity(recoil.normalize().multiply(.85).setY(.3));
                }
            }
            if(defender.isBlocking()&&("shield".equals(main(defender))||"shield".equals(off(defender)))){
                shieldPassive.put(defender.getUniqueId(),ms()+2000);
            }
            if(hasFullSet(defender) && armorBurst.getOrDefault(defender.getUniqueId(),0L)<ms()
                && defender.getHealth()>0 && defender.getHealth()-e.getFinalDamage()<defender.getAttribute(Attribute.MAX_HEALTH).getValue()*.4
                && defender.getHealth()-e.getFinalDamage()>0){
                armorBurst.put(defender.getUniqueId(),ms()+90000);
                Bukkit.getScheduler().runTask(plugin,()->winterAwakening(defender));
            }
        }
        Player attacker=null;
        if(e.getDamager() instanceof Player p)attacker=p;
        else if(e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p)attacker=p;
        if(attacker==null||!(e.getEntity() instanceof LivingEntity victim)||!valid(attacker,victim))return;
        String type=main(attacker);
        if(e.getDamager() instanceof Player){
            if("sword".equals(type)){
                Mark m=apply(swordMarks,attacker,victim);arc(victim.getLocation(),attacker.getLocation().getDirection(),1.15,false);
                sound(victim.getLocation(),Sound.ENTITY_PLAYER_ATTACK_SWEEP,1.4f);
                if(m.count>=2)victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,26,0));
            }
            if("battleaxe".equals(type))apply(axeMarks,attacker,victim);
            if("spear".equals(type))victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,0));
            if("riptide".equals(type)){
                Mark m=apply(conductive,attacker,victim);
                if(attacker.isRiptiding()&&m.count>=2){hit(attacker,victim,2.0);snow(victim.getLocation(),16);}
            }
            if("dayblade".equals(type)){
                // Attack damage uses an explicit adjustment to the unenchanted golden sword's 4 damage.
                double charge=attacker.getAttackCooldown();
                double scale=.2+.8*charge*charge;
                e.setDamage(e.getDamage()+ (isDay(attacker.getWorld())?8.0:7.0)*scale);
                if(victim instanceof Player v && v.isBlocking())v.setCooldown(Material.SHIELD,100);
                victim.getWorld().spawnParticle(Particle.FLAME,victim.getLocation().add(0,1,0),14,.55,.5,.55,.02);
            }
            if("nightblade".equals(type)){
                double charge=attacker.getAttackCooldown();
                e.setDamage(e.getDamage()+4.5*(.2+.8*charge*charge));
                victim.getWorld().spawnParticle(Particle.END_ROD,victim.getLocation().add(0,1,0),12,.5,.5,.5,.02);
            }
        }
        if("totem".equals(type)||"totem".equals(off(attacker))){
            if(regenReady.getOrDefault(attacker.getUniqueId(),0L)<ms()){
                regenReady.put(attacker.getUniqueId(),ms()+16000);
                attacker.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,60,0));
            }
        }
        if(e.getDamager() instanceof AbstractArrow && "bow".equals(type)){
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,0));snow(victim.getLocation().add(0,1,0),7);
        }
    }
    private boolean hasFullSet(Player p){return "helmet".equals(id(p.getInventory().getHelmet())) && "chestplate".equals(id(p.getInventory().getChestplate())) && "leggings".equals(id(p.getInventory().getLeggings())) && "boots".equals(id(p.getInventory().getBoots()));}
    private void winterAwakening(Player p){
        if(!p.isOnline()||p.isDead()||!hasFullSet(p))return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,100,2));
        Location center=p.getLocation();ring(center,4.5,72);snow(center.clone().add(0,1,0),110);
        sound(center,Sound.BLOCK_BEACON_ACTIVATE,.65f);
        for(LivingEntity t:targets(p,center,4.5)){
            hit(p,t,6);Vector k=t.getLocation().toVector().subtract(center.toVector());if(k.lengthSquared()>.01)t.setVelocity(k.normalize().multiply(1.05).setY(.38));
        }
    }
    @EventHandler(ignoreCancelled=true) public void stopFriendlySummons(EntityTargetLivingEntityEvent e){
        if(!(e.getEntity() instanceof Mob mob))return;
        String owner=mob.getPersistentDataContainer().get(new NamespacedKey("frostpvp","summoner"),PersistentDataType.STRING);
        if(owner==null)return;Player p=Bukkit.getPlayer(UUID.fromString(owner));
        if(p==null||!(e.getTarget() instanceof LivingEntity target)||!valid(p,target))e.setCancelled(true);
    }
    @EventHandler public void summonedDeath(EntityDeathEvent e){
        if(e.getEntity().getPersistentDataContainer().has(new NamespacedKey("frostpvp","summoner"),PersistentDataType.STRING)){
            e.getDrops().clear();e.setDroppedExp(0);
        }
    }
    @EventHandler(ignoreCancelled=true) public void allyProtection(EntityDamageByEntityEvent e){
        if(!(e.getDamager() instanceof Mob mob))return;
        String owner=mob.getPersistentDataContainer().get(new NamespacedKey("frostpvp","summoner"),PersistentDataType.STRING);
        if(owner==null)return;Player p=Bukkit.getPlayer(UUID.fromString(owner));
        if(p==null||!(e.getEntity() instanceof LivingEntity l)||!valid(p,l))e.setCancelled(true);
        else if(mob.getCustomName()!=null){
            if(mob.getCustomName().contains("Archer"))l.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,50,0));
            if(mob.getCustomName().contains("Guardian")){
                Vector push=l.getLocation().toVector().subtract(mob.getLocation().toVector());
                if(push.lengthSquared()>.05)l.setVelocity(push.normalize().multiply(.7).setY(.3));
            }
        }
    }
    @EventHandler(ignoreCancelled=true) public void miner(BlockBreakEvent e){
        Player p=e.getPlayer();String id=main(p);if(id==null||!id.startsWith("pickaxe"))return;
        long last=lastMining.getOrDefault(p.getUniqueId(),0L);lastMining.put(p.getUniqueId(),ms());
        int count=ms()-last<2200?miningChain.getOrDefault(p.getUniqueId(),0)+1:1;
        miningChain.put(p.getUniqueId(),count);if(count>=5){p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,65,1));miningChain.put(p.getUniqueId(),0);}
    }
    @EventHandler(ignoreCancelled=true) public void fall(EntityDamageEvent e){
        if(e.getCause()!=EntityDamageEvent.DamageCause.FALL||!(e.getEntity() instanceof Player p))return;
        if ("leggings".equals(id(p.getInventory().getLeggings()))) {
            e.setCancelled(true);
            return;
        }
        if(momentum.getOrDefault(p.getUniqueId(),0L)>ms())e.setDamage(e.getDamage()*.1);
    }
    @EventHandler public void quit(PlayerQuitEvent e){
        UUID id=e.getPlayer().getUniqueId();activeGrapple.remove(id);cooldowns.remove(id);preparedBow.remove(id);preparedCrossbow.remove(id);
        preparedTrident.remove(id);tethers.remove(id);anchors.remove(id);parries.remove(id);helmetStart.remove(id);
        helmetReveals.remove(id);
        for(Map<UUID,Long> marked : helmetReveals.values())marked.remove(id);
        clearModifier(e.getPlayer(),Attribute.MOVEMENT_SPEED,bonusSpeed);clearModifier(e.getPlayer(),Attribute.MOVEMENT_SPEED,snowSpeed);
        clearModifier(e.getPlayer(),Attribute.WATER_MOVEMENT_EFFICIENCY,waterEfficiency);
        clearModifier(e.getPlayer(),Attribute.MOVEMENT_SPEED,underwaterSpeed);
        clearModifier(e.getPlayer(),Attribute.ATTACK_SPEED,bonusAttackSpeed);
        clearModifier(e.getPlayer(),Attribute.ENTITY_INTERACTION_RANGE,bonusReach);
        clearModifier(e.getPlayer(),Attribute.BLOCK_INTERACTION_RANGE,bonusBlockReach);
        clearModifier(e.getPlayer(),Attribute.KNOCKBACK_RESISTANCE,bonusKnockback);
        clearModifier(e.getPlayer(),Attribute.KNOCKBACK_RESISTANCE,shieldKnockback);
    }
    @EventHandler public void respawn(PlayerRespawnEvent e){Bukkit.getScheduler().runTaskLater(plugin,()->updateAttributes(e.getPlayer()),3L);}
    private void clearModifier(Player p,Attribute a,NamespacedKey key){AttributeInstance inst=p.getAttribute(a);if(inst!=null)for(AttributeModifier m:List.copyOf(inst.getModifiers()))if(m.getKey().equals(key))inst.removeModifier(m);}
    private void setModifier(Player p,Attribute a,NamespacedKey key,double value,AttributeModifier.Operation op){
        AttributeInstance inst=p.getAttribute(a);if(inst==null)return;AttributeModifier existing=null;
        for(AttributeModifier m:inst.getModifiers())if(m.getKey().equals(key)){existing=m;break;}
        if(existing!=null && Math.abs(existing.getAmount()-value)<.00001)return;
        if(existing!=null)inst.removeModifier(existing);
        if(Math.abs(value)>.00001)inst.addTransientModifier(new AttributeModifier(key,value,op));
    }
    private boolean isDay(World w){long time=w.getTime();return time<12300||time>23000;}
    private void updateAttributes(Player p){
        boolean dayblade="dayblade".equals(main(p))||"dayblade".equals(off(p));
        boolean nightblade="nightblade".equals(main(p))||"nightblade".equals(off(p));
        boolean night=!isDay(p.getWorld());
        // Base-speed-relative modifiers stack additively (Day -10%, Night +10%/+20%).
        double velocity=(dayblade?-.10:0)+(nightblade?(night?.30:.10):0);
        setModifier(p,Attribute.MOVEMENT_SPEED,bonusSpeed,velocity,AttributeModifier.Operation.ADD_SCALAR);
        setModifier(p,Attribute.ATTACK_SPEED,bonusAttackSpeed,"dayblade".equals(main(p))?-.30:0,AttributeModifier.Operation.ADD_NUMBER);
        setModifier(p,Attribute.ENTITY_INTERACTION_RANGE,bonusReach,nightblade&&night?1:0,AttributeModifier.Operation.ADD_NUMBER);
        setModifier(p,Attribute.BLOCK_INTERACTION_RANGE,bonusBlockReach,nightblade&&night?1:0,AttributeModifier.Operation.ADD_NUMBER);
        setModifier(p,Attribute.KNOCKBACK_RESISTANCE,bonusKnockback,dayblade&&!night?1:0,AttributeModifier.Operation.ADD_NUMBER);
        boolean boots="boots".equals(id(p.getInventory().getBoots())) && plugin.getConfig().getBoolean("reborn.armor.boots-speed",true);
        if(boots) {
            p.setFreezeTicks(0);
            snowTrail(p, p.getLocation());
        }
        // Water movement efficiency is the 1.21.11 water-specific movement attribute.
        // This is a separate +0.20 modifier, independent of Speed II and the snow bonus.
        double underwaterBoost=plugin.getConfig().getDouble("reborn.armor.boots-underwater-efficiency",.20);
        setModifier(p,Attribute.WATER_MOVEMENT_EFFICIENCY,waterEfficiency,
                boots ? underwaterBoost : 0, AttributeModifier.Operation.ADD_NUMBER);
        // Depth Strider can already cap water movement efficiency. The speed modifier
        // ensures the +20% boost remains meaningful even at that cap.
        setModifier(p,Attribute.MOVEMENT_SPEED,underwaterSpeed,
                boots && p.isInWater() ? underwaterBoost : 0, AttributeModifier.Operation.ADD_SCALAR);
        Material beneath=p.getLocation().subtract(0,.2,0).getBlock().getType();
        boolean onSnow=beneath==Material.SNOW||beneath==Material.SNOW_BLOCK||beneath==Material.POWDER_SNOW||beneath==Material.ICE||beneath==Material.PACKED_ICE||beneath==Material.BLUE_ICE;
        setModifier(p,Attribute.MOVEMENT_SPEED,snowSpeed,boots&&onSnow?.10:0,AttributeModifier.Operation.ADD_SCALAR);
    }
    /** Physical, temporary powder-snow bridge. Restores the original block after one second. */
    private void snowTrail(Player p, Location at) {
        if (!"boots".equals(id(p.getInventory().getBoots())) || !plugin.getConfig().getBoolean("reborn.armor.boots-speed",true)) return;
        // Only modify powder snow. Never replace normal blocks, chests or player-placed blocks.
        for (double offset : new double[]{-.05,-.85,-1.05}) {
            Block block=at.clone().add(0,offset,0).getBlock();
            Location key=block.getLocation();
            if(temporarySnow.containsKey(key)){
                if(block.getType()==Material.SNOW_BLOCK)temporarySnow.put(key,ms()+1000);
                continue;
            }
            if(block.getType()!=Material.POWDER_SNOW)continue;
            if(temporarySnow.size()>=MAX_TEMP_SNOW)continue;
            // Restore movement when the player has already begun sinking.
            if(at.getY()<block.getY()+.8 && at.getY()>=block.getY()) {
                Location safeLocation=at.clone();
                safeLocation.setY(block.getY()+1.01);
                p.teleport(safeLocation);
            }
            temporarySnow.put(key,ms()+1000);
            block.setType(Material.SNOW_BLOCK,false);
            block.getWorld().spawnParticle(Particle.SNOWFLAKE,key.clone().add(.5,1.1,.5),6,.32,.05,.32,.005);
        }
    }
    private void restoreTemporarySnow(Location key){
        temporarySnow.remove(key);
        if(key.getWorld()==null)return;
        if(!key.getWorld().isChunkLoaded(key.getBlockX()>>4,key.getBlockZ()>>4))return;
        Block b=key.getBlock();
        if(b.getType()==Material.SNOW_BLOCK)b.setType(Material.POWDER_SNOW,false);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void snowFootsteps(PlayerMoveEvent event){
        if(event.getTo()==null || event.getFrom().getBlockX()==event.getTo().getBlockX() &&
                event.getFrom().getBlockZ()==event.getTo().getBlockZ())return;
        snowTrail(event.getPlayer(),event.getTo());
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void protectSnowTrail(BlockBreakEvent event){
        if(temporarySnow.containsKey(event.getBlock().getLocation()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void protectSnowPlacement(BlockPlaceEvent event){
        if(temporarySnow.containsKey(event.getBlockPlaced().getLocation()))event.setCancelled(true);
    }
    @EventHandler public void restoreSnowOnChunkUnload(ChunkUnloadEvent event){
        for(Location loc : List.copyOf(temporarySnow.keySet()))
            if(loc.getWorld()==event.getWorld() && loc.getBlockX()>>4==event.getChunk().getX()
                   && loc.getBlockZ()>>4==event.getChunk().getZ())restoreTemporarySnow(loc);
    }
    private void armorCooldownOverlay(Player p,long now) {
        if(!plugin.getConfig().getBoolean("reborn.armor.cooldown-hud",true))return;
        if(plugin.isInGrace() && plugin.recentGraceAnnouncement())return;
        boolean helmet="helmet".equals(id(p.getInventory().getHelmet()));
        boolean full=hasFullSet(p);
        if(!helmet && !full)return;
        String helmetText="";
        if(helmet){
            long remaining=Math.max(0,helmetReady.getOrDefault(p.getUniqueId(),0L)-now);
            long crouchStart=helmetStart.getOrDefault(p.getUniqueId(),0L);
            if(remaining>0)helmetText=ChatColor.AQUA+"Frost Detection "+ChatColor.WHITE+(remaining+999)/1000+"s";
            else if(p.isSneaking()&&crouchStart>0){
                long countdown=Math.max(0,plugin.getConfig().getInt("reborn.armor.helmet-crouch-seconds",5)*1000L-(now-crouchStart));
                helmetText=ChatColor.AQUA+"Frost Detection "+ChatColor.YELLOW+"Charging "+(countdown+999)/1000+"s";
            }else helmetText=ChatColor.AQUA+"Frost Detection "+ChatColor.GREEN+"READY";
        }
        String winterText="";
        if(full){
            long remaining=Math.max(0,armorBurst.getOrDefault(p.getUniqueId(),0L)-now);
            winterText=ChatColor.BLUE+"Winter's Awakening "+(remaining>0?ChatColor.WHITE+String.valueOf((remaining+999)/1000)+"s":ChatColor.GREEN+"READY");
        }
        // The vanilla subtitle area is distinct from the hotbar action-bar line.
        // Display only small text, no large central title.
        p.showTitle(Title.title(Component.empty(), Component.text(helmetText+(helmet&&!winterText.isEmpty()?ChatColor.GRAY+"  |  ":"")+winterText),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ZERO)));
    }
    private void sendHelmetVibration(Player detected, Player wearer) {
        // Identical particle type to a sculk sensor vibration, from target to wearer.
        Location from=detected.getLocation().add(0,1.1,0);
        wearer.spawnParticle(Particle.VIBRATION,from,1,
                new Vibration(new Vibration.Destination.EntityDestination(wearer),12));
    }
    private void tick(){
        tickCounter+=5;
        long now=ms();
        for(var entry : List.copyOf(temporarySnow.entrySet()))
            if(entry.getValue()<=now)restoreTemporarySnow(entry.getKey());
        for(Player p:Bukkit.getOnlinePlayers()){
            updateAttributes(p);
            if(tickCounter%20==0)armorCooldownOverlay(p,now);
            if("helmet".equals(id(p.getInventory().getHelmet())) && p.isSneaking()){
                long start=helmetStart.computeIfAbsent(p.getUniqueId(),x->now);
                int crouch=plugin.getConfig().getInt("reborn.armor.helmet-crouch-seconds",5);
                int cooldown=plugin.getConfig().getInt("reborn.armor.helmet-cooldown-seconds",120);
                double radius=plugin.getConfig().getDouble("reborn.armor.helmet-radius",10);
                if(now-start>=crouch*1000L && helmetReady.getOrDefault(p.getUniqueId(),0L)<now){
                    helmetReady.put(p.getUniqueId(),now+cooldown*1000L);helmetStart.put(p.getUniqueId(),now);
                    ring(p.getLocation(),radius,80);sound(p.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_RESONATE,1.1f);
                    for(Player other:p.getWorld().getPlayers()) {
                        if(other==p || other.getGameMode()==GameMode.SPECTATOR ||
                                !plugin.rebornTarget(p.getUniqueId(),other) ||
                                other.getLocation().distanceSquared(p.getLocation())>radius*radius)continue;
                        other.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,200,0));
                        helmetReveals.computeIfAbsent(p.getUniqueId(),k->new HashMap<>())
                                .put(other.getUniqueId(),now+10000);
                        sendHelmetVibration(other,p);
                    }
                }
            }else helmetStart.remove(p.getUniqueId());
            if(tickCounter%30==0 && "helmet".equals(id(p.getInventory().getHelmet()))) {
                Map<UUID,Long> active=helmetReveals.get(p.getUniqueId());
                if(active!=null) {
                    active.entrySet().removeIf(mark -> mark.getValue()<=now ||
                            Bukkit.getPlayer(mark.getKey())==null);
                    for(UUID marked : active.keySet()){
                        Player other=Bukkit.getPlayer(marked);
                        if(other!=null && other.getWorld()==p.getWorld() &&
                                other.getLocation().distanceSquared(p.getLocation())<=
                                Math.pow(plugin.getConfig().getDouble("reborn.armor.helmet-radius",10),2))
                            sendHelmetVibration(other,p);
                    }
                }
            }
            if("shield".equals(main(p))||"shield".equals(off(p))){
                setModifier(p,Attribute.KNOCKBACK_RESISTANCE,shieldKnockback,
                        shieldPassive.getOrDefault(p.getUniqueId(),0L)>now?.55:0,
                        AttributeModifier.Operation.ADD_NUMBER);
            }
            List<UUID> minions=summons.get(p.getUniqueId());if(minions!=null){
                for(UUID u:minions){Entity ent=Bukkit.getEntity(u);if(!(ent instanceof Mob mob)||!mob.isValid())continue;
                    LivingEntity nearest=null;double best=20*20;
                    for(LivingEntity target:targets(p,mob.getLocation(),20)){double d=mob.getLocation().distanceSquared(target.getLocation());if(d<best){nearest=target;best=d;}}
                    if(nearest!=null)mob.setTarget(nearest);
                    else if(mob.getLocation().distanceSquared(p.getLocation())>10*10)mob.getPathfinder().moveTo(p);
                    if("horn".equals(main(p)))mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,12,0,true,false));
                }
            }
        }
        shots.entrySet().removeIf(x->Bukkit.getEntity(x.getKey())==null);
        tethers.entrySet().removeIf(x->x.getValue().until<now);
    }
    void actionBar(Player p){
        if(both(p)){
            p.sendActionBar(Component.text(ChatColor.GOLD+"☀ " + ChatColor.DARK_PURPLE+"ECLIPSE DANCE " + (remains(p,"eclipse")==0?ChatColor.GREEN+"RIGHT-CLICK READY":ChatColor.AQUA+String.format(Locale.US,"%.1fs",remains(p,"eclipse")/1000.0))));
            return;
        }
        String type=main(p);if(type==null)type=off(p);
        if(type==null)return;
        if(type.startsWith("pickaxe")){
            ItemStack item=main(p)!=null&&main(p).startsWith("pickaxe")?p.getInventory().getItemInMainHand():p.getInventory().getItemInOffHand();
            String mode=item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey("punchsnowball","pickaxe_mode"),PersistentDataType.STRING);
            String area=item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey("frostpvp","mining_mode"),PersistentDataType.STRING);
            p.sendActionBar(Component.text(ChatColor.AQUA+"F "+(mode==null?"FORTUNE":mode.toUpperCase(Locale.ROOT))+"  " + ChatColor.WHITE+"SHIFT+F "+("single".equals(area)?"1×1":"3×3")));return;
        }
        if(!DEFAULT_CD.containsKey(type))return;
        p.sendActionBar(Component.text(ChatColor.AQUA+type.toUpperCase(Locale.ROOT)+"  "+(remains(p,type)==0?ChatColor.GREEN+"F READY":ChatColor.YELLOW+String.format(Locale.US,"%.1fs",remains(p,type)/1000.0))));
    }
    void shutdown(){
        for(Location loc:List.copyOf(temporarySnow.keySet()))restoreTemporarySnow(loc);
        for(List<UUID> army:summons.values())for(UUID uuid:army){Entity entity=Bukkit.getEntity(uuid);if(entity!=null)entity.remove();}
        for(Player p:Bukkit.getOnlinePlayers()){
            for(var spec:List.of(new AbstractMap.SimpleEntry<>(Attribute.MOVEMENT_SPEED,bonusSpeed),
                    new AbstractMap.SimpleEntry<>(Attribute.MOVEMENT_SPEED,snowSpeed),
                    new AbstractMap.SimpleEntry<>(Attribute.WATER_MOVEMENT_EFFICIENCY,waterEfficiency),
                    new AbstractMap.SimpleEntry<>(Attribute.MOVEMENT_SPEED,underwaterSpeed),
                    new AbstractMap.SimpleEntry<>(Attribute.ATTACK_SPEED,bonusAttackSpeed),
                    new AbstractMap.SimpleEntry<>(Attribute.ENTITY_INTERACTION_RANGE,bonusReach),
                    new AbstractMap.SimpleEntry<>(Attribute.BLOCK_INTERACTION_RANGE,bonusBlockReach),
                    new AbstractMap.SimpleEntry<>(Attribute.KNOCKBACK_RESISTANCE,bonusKnockback),
                    new AbstractMap.SimpleEntry<>(Attribute.KNOCKBACK_RESISTANCE,shieldKnockback)))clearModifier(p,spec.getKey(),spec.getValue());
        }
    }
    void clearCooldowns(Player p){cooldowns.remove(p.getUniqueId());}
}
