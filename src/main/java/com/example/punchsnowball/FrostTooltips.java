package com.example.punchsnowball;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Visual-only formatter. Never changes gameplay tags, enchantments, attributes or item models. */
final class FrostTooltips {
    private record Ability(String title, String keys, String description, String cooldownPath, int defaultSeconds) {}
    private record Profile(String id, String title, String motto, String passive,
                           int colorFrom, int colorTo, Ability[] abilities) {}

    private static Profile p(String id, String title, String motto, String passive,
                            int a, int b, Ability... abilities) {
        return new Profile(id, title, motto, passive, a, b, abilities);
    }
    private static Ability a(String title, String keys, String desc, String path, int seconds) {
        return new Ability(title, keys, desc, path, seconds);
    }

    static String identify(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        var data = item.getItemMeta().getPersistentDataContainer();
        if (data.has(new NamespacedKey("frostpvp","dayblade"), PersistentDataType.BYTE))return "dayblade";
        if (data.has(new NamespacedKey("frostpvp","nightblade"), PersistentDataType.BYTE))return "nightblade";
        if (data.has(NamespacedKey.fromString("punchsnowball:punch_snowball"), PersistentDataType.BYTE)) return "snowball";
        if (data.has(NamespacedKey.fromString("punchsnowball:infinite_tnt"), PersistentDataType.BYTE)) return "tnt";
        if (data.has(NamespacedKey.fromString("punchsnowball:freeze_windcharge"), PersistentDataType.BYTE)) return "windcharge";
        if (data.has(NamespacedKey.fromString("punchsnowball:storm_trident"), PersistentDataType.BYTE)) return "grapple";
        if (data.has(NamespacedKey.fromString("punchsnowball:riptide_trident"), PersistentDataType.BYTE)) return "riptide";
        if (data.has(NamespacedKey.fromString("punchsnowball:homing_firework"), PersistentDataType.BYTE)) return "firework";
        if (data.has(NamespacedKey.fromString("punchsnowball:lava_tsunami"), PersistentDataType.BYTE)) return "lava";
        if (data.has(NamespacedKey.fromString("punchsnowball:battleaxe"), PersistentDataType.BYTE)) return "battleaxe";
        if (data.has(NamespacedKey.fromString("punchsnowball:frost_shield"), PersistentDataType.BYTE)) return "shield";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_sword"), PersistentDataType.BYTE)) return "sword";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_spear"), PersistentDataType.BYTE)) return "spear";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_fortune_pickaxe"), PersistentDataType.BYTE)
                || data.has(NamespacedKey.fromString("punchsnowball:frozen_silk_pickaxe"), PersistentDataType.BYTE)) {
            String mode = data.get(NamespacedKey.fromString("punchsnowball:pickaxe_mode"), PersistentDataType.STRING);
            if ("silk".equals(mode)) return "pickaxe_silk";
            return item.getItemMeta().hasEnchant(org.bukkit.enchantments.Enchantment.SILK_TOUCH) ? "pickaxe_silk" : "pickaxe_fortune";
        }
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_bow"), PersistentDataType.BYTE)) return "bow";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_crossbow"), PersistentDataType.BYTE)) return "crossbow";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_helmet"), PersistentDataType.BYTE)) return "helmet";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_chestplate"), PersistentDataType.BYTE)) return "chestplate";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_leggings"), PersistentDataType.BYTE)) return "leggings";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_boots"), PersistentDataType.BYTE)) return "boots";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_horn"), PersistentDataType.BYTE)) return "horn";
        if (data.has(NamespacedKey.fromString("punchsnowball:frozen_totem"), PersistentDataType.BYTE)) return "totem";
        return null;
    }

    private static Profile profile(String id) {
        return switch(id) {
            case "sword" -> p("sword","Frozen Sword","Winter bows to no one.","Permafrost: melee applies Frost Marks. Fast crescent slashes on every hit.",0x91EBFF,0x4675DC,a("Winter's Dance","F","Three short forward lunges with huge icy crescents; three hits shatter.","reborn.sword.cooldown-seconds",28));
            case "battleaxe" -> p("battleaxe","Frozen Battleaxe","The glacier remembers every impact.","Fractured Armor: consecutive hits build Fracture marks.",0x91EBFF,0x4675DC,a("Glacier Crusher","F","Leap higher and forward; slam ONLY on ground contact. Fracture boosts damage.","reborn.battleaxe.cooldown-seconds",35));
            case "spear" -> p("spear","Frozen Spear","The earth itself grows teeth.","Arctic Precision: spear strikes chill enemies.",0x91EBFF,0x4675DC,a("Northern Impalement","F","Send one visible ice-coated spear straight forward, piercing enemies.","reborn.spear.cooldown-seconds",30));
            case "bow" -> p("bow","Frozen Bow","Life blooms beneath the ice.","Crystalline Arrows: ordinary arrows briefly slow enemies.",0x91EBFF,0x4675DC,a("Crystal Bloom","F","F prepares the next arrow; impact sprouts a crystal that explodes.","reborn.bow.cooldown-seconds",35));
            case "crossbow" -> p("crossbow","Frozen Crossbow","No corner is safe from winter.","Frost Reflection: icy projectiles create slowing fragments.",0x91EBFF,0x4675DC,a("Shattered Ricochet","F","F prepares a bolt that can bounce off walls three times.","reborn.crossbow.cooldown-seconds",30));
            case "grapple" -> p("grapple","Frozen Trident of Loyalty","No prey escapes the north.","Frozen Tether: a harpoon hit marks the target for pulling.",0x91EBFF,0x4675DC,a("Glacial Harpoon","F","F prepares throw; F again after a hit pulls the enemy.","reborn.grapple.cooldown-seconds",28));
            case "riptide" -> p("riptide","Frozen Trident of the Depths","The sea remembers thunder.","Static Current: trident hits mark foes for electric bursts.",0x91EBFF,0x4675DC,a("Stormwake","F","A controllable storm dash marks enemies for delayed lightning.","reborn.riptide.cooldown-seconds",35));
            case "firework" -> p("firework","Frozen Firework of Cold Skies","Claim the skies.","Frostflare: firework hits reveal enemies with Glowing.",0x91EBFF,0x4675DC,a("Skyhunter's Barrage","F","Launch upward and aim three volleys of frost rockets.","reborn.firework.cooldown-seconds",45));
            case "shield" -> p("shield","Frozen Shield of Defiance","A perfectly timed winter never yields.","Frozen Resilience: successful blocks grant knockback resistance.",0x91EBFF,0x4675DC,a("Glacial Reprisal","F","A 0.8s parry reduces damage and unleashes a crystal counter.","reborn.shield.cooldown-seconds",28));
            case "totem" -> p("totem","Frozen Totem of Winter","Winter remembers its allies.","Winter's Blessing: damaging foes briefly grants Regeneration.",0x91EBFF,0x4675DC,a("Frost Sanctuary","F","A stationary snowflake field heals trusted allies and weakens foes.","reborn.totem.cooldown-seconds",45));
            case "horn" -> p("horn","Frozen Horn of the Hordes","Winter answers its commander.","Commander's Presence: your summoned warriors move faster.",0xE1EFFF,0x4675DC,a("Call of the Frostbound","F","Summons Frost Knight, Archer and Guardian for 12 seconds.","reborn.horn.cooldown-seconds",60));
            case "pickaxe_fortune", "pickaxe_silk" -> p(id,"Frozen Pickaxe of Duality","Two faces of the glacier.","Miner's Momentum: consecutive mining grants Haste II.",0xB8F9FF,0x3766C9,
                a("Enchantment Mode","F","Toggle Fortune V or Silk Touch.",null,0),a("Mining Mode","SHIFT + F","Toggle 3x3 or 1x1 mining.",null,0));
            case "dayblade" -> p(id,"Dayblade","When the sun rises, stand firm.","Solar Might: Sharpness V netherite-equivalent damage; VII in daylight. No knockback by day. -10% speed. Shield breaker.",0xFFE5A1,0xF89A13,
                a("Eclipse Dance","RIGHT-CLICK + BOTH BLADES","Dual-wield Nightblade for four short solar-lunar lunges and a finisher.","reborn.eclipse.cooldown-seconds",45));
            case "nightblade" -> p(id,"Nightblade","Night favors the swift.","Lunar Grace: Sharpness IV netherite-equivalent damage, +10% speed; +20% more and +1 reach at night.",0xB9BCFF,0x6744B9,
                a("Eclipse Dance","RIGHT-CLICK + BOTH BLADES","Dual-wield Dayblade for short lunar-solar lunges and a finisher.","reborn.eclipse.cooldown-seconds",45));
            case "helmet" -> p(id,"Frozen Helmet of Revelation","The still hunter sees all.","Frost Detection: crouch 5s to reveal nearby enemies for 10s; vibration trails every 30 ticks (120s cooldown).",0xD3F7FF,0x5A87CF);
            case "chestplate" -> p(id,"Frozen Chestplate of Fortitude","Endure the coldest night.","Frozen Vitality: gain five maximum hearts while worn.",0xEBFEFF,0x6179D8);
            case "leggings" -> p(id,"Frozen Leggings of Emberguard","Even fire fears winter.","Eternal Cold: permanent Fire Resistance and fall damage immunity.",0xB8F6FF,0x6083D4);
            case "boots" -> p(id,"Frozen Boots of Freedom","Stride above the snow.","Snowrunner: Speed II, +10% snow speed, +20% water movement efficiency; temporary snow trail over powder snow.",0xC9F5FF,0x4589C9);
            default -> null;
        };
    }
    private static Component noItalic(Component c) { return c.decoration(TextDecoration.ITALIC, false); }
    private static Component solid(String value, int color) {
        return noItalic(Component.text(value, TextColor.color(color)));
    }
    private static Component gradient(String value, int start, int end) {
        Component output = Component.empty();
        int steps = Math.max(1, value.length() - 1);
        for (int i = 0; i < value.length(); i++) {
            double t = (double) i / steps;
            int r = (int) Math.round(((start >> 16) & 255) * (1 - t) + ((end >> 16) & 255) * t);
            int g = (int) Math.round(((start >> 8) & 255) * (1 - t) + ((end >> 8) & 255) * t);
            int b = (int) Math.round((start & 255) * (1 - t) + (end & 255) * t);
            output = output.append(solid(String.valueOf(value.charAt(i)), (r << 16) | (g << 8) | b));
        }
        return output;
    }
    private static void addWrapped(List<Component> lore, String text, int color) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() > 0 && line.length() + word.length() + 1 > 43) {
                lore.add(solid(line.toString(), color));
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lore.add(solid(line.toString(), color));
    }

    static boolean apply(JavaPlugin plugin, ItemStack item) {
        String id = identify(item);
        if (id == null) return false;
        Profile profile = profile(id);
        if (profile == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (plugin.getConfig().getBoolean("tooltips.gradient-text", true)) {
            meta.displayName(gradient("✦ " + profile.title() + " ✦", profile.colorFrom(), profile.colorTo()));
            List<Component> lore = new ArrayList<>();
            lore.add(gradient("✧ FROSTPVP REBORN ✧", profile.colorFrom(), profile.colorTo()));
            lore.add(solid(profile.motto(), 0xB4BDCE));
            lore.add(Component.empty());
            lore.add(gradient("PASSIVE   |   WINTER'S GIFT", profile.colorFrom(), profile.colorTo()));
            addWrapped(lore, profile.passive(), 0xA8F2F9);
            for (int i = 0; i < profile.abilities().length; i++) {
                Ability ability = profile.abilities()[i];
                lore.add(Component.empty());
                lore.add(gradient((i + 1) + "  |  " + ability.title().toUpperCase(), profile.colorFrom(), profile.colorTo()));
                String detail = ability.keys();
                if (ability.cooldownPath() != null) {
                    detail += "  •  " + plugin.getConfig().getInt(ability.cooldownPath(), ability.defaultSeconds()) + "s";
                }
                lore.add(solid(detail, 0x89C7FF));
                addWrapped(lore, ability.description(), 0xD2D8E6);
            }
            lore.add(Component.empty());
            lore.add(gradient("❄ FROST CLAIMS THE WORTHY ❄", profile.colorFrom(), profile.colorTo()));
            meta.lore(lore);
        }
        // Only customizes tooltips, NEVER the item model or worn armor texture.
        meta.setTooltipStyle(NamespacedKey.fromString("frost:" + id));
        item.setItemMeta(meta);
        return true;
    }
}
