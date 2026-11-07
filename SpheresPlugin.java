package me.spheres;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class SpheresPlugin extends JavaPlugin implements Listener {

    record Bonus(Attribute attr, double amount, Operation op) {}
    record Def(int id, Material material, String name, List<Bonus> bonuses) {}

    private final Map<Integer, Def> defs = new LinkedHashMap<>();
    private final Map<Attribute, NamespacedKey> keys = new LinkedHashMap<>();
    private final Map<UUID, Integer> active = new HashMap<>();

    // helpers
    private static Bonus dmg(double v)   { return new Bonus(Attribute.ATTACK_DAMAGE, v, Operation.ADD_NUMBER); }
    private static Bonus hp(double v)    { return new Bonus(Attribute.MAX_HEALTH, v, Operation.ADD_NUMBER); }
    private static Bonus armor(double v) { return new Bonus(Attribute.ARMOR, v, Operation.ADD_NUMBER); }
    private static Bonus tough(double v) { return new Bonus(Attribute.ARMOR_TOUGHNESS, v, Operation.ADD_NUMBER); }
    private static Bonus speed(double pct) { return new Bonus(Attribute.MOVEMENT_SPEED, pct / 100.0, Operation.ADD_SCALAR); }

    private void add(int id, Material m, String name, Bonus... b) {
        defs.put(id, new Def(id, m, name, List.of(b)));
    }

    @Override
    public void onEnable() {
        Material T = Material.NETHER_STAR, S = Material.HEART_OF_THE_SEA;
        add(1001, T, "Талисман Берсерка",   dmg(4), speed(15), hp(-3), armor(-2));
        add(1002, T, "Талисман Кузнеца",    armor(2), dmg(2), hp(-4));
        add(1003, T, "Талисман Доминатора", hp(4), dmg(4), armor(2), speed(15));
        add(1004, T, "Талисман Терпилы",    armor(3), tough(3), speed(-15));
        add(1005, T, "Талисман Фантома",    dmg(3), speed(10), armor(-1));
        add(1006, T, "Талисман Бегуна",     hp(2), armor(-3), speed(40));
        add(2001, S, "Сфера Разрушителя",   dmg(3), hp(-2), speed(15));
        add(2002, S, "Сфера Короля",        dmg(2), armor(2));
        add(2003, S, "Сфера Клыка",         speed(10), armor(2), hp(-2));
        add(2004, S, "Сфера Порыва",        dmg(-2), speed(25), hp(3));
        add(2005, S, "Сфера Риска",         dmg(4), armor(-3), tough(-3));
        add(2006, S, "Сфера Агонии",        hp(4), dmg(4), tough(2), speed(15));

        for (Attribute a : List.of(Attribute.ATTACK_DAMAGE, Attribute.MAX_HEALTH, Attribute.ARMOR,
                Attribute.ARMOR_TOUGHNESS, Attribute.MOVEMENT_SPEED)) {
            keys.put(a, new NamespacedKey(this, "bonus_" + a.getKey().getKey().replace('.', '_')));
        }

        getServer().getPluginManager().registerEvents(this, this);
        // проверка левой руки каждые 5 тиков — ловит любые способы смены предмета
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) refresh(p);
        }, 20L, 5L);
    }

    @Override
    public void onDisable() {
        for (Player p : getServer().getOnlinePlayers()) clear(p);
    }

    /** Определяет id предмета по материалу и custom_model_data. */
    private Integer identify(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasCustomModelDataComponent()) return null;
        List<Float> floats = meta.getCustomModelDataComponent().getFloats();
        for (Float f : floats) {
            Def d = defs.get(Math.round(f));
            if (d != null && d.material() == item.getType()) return d.id();
        }
        return null;
    }

    private void refresh(Player p) {
        Integer id = identify(p.getInventory().getItemInOffHand());
        Integer cur = active.get(p.getUniqueId());
        if (Objects.equals(id, cur)) return;
        clear(p);
        if (id != null) apply(p, defs.get(id));
    }

    private void apply(Player p, Def d) {
        for (Bonus b : d.bonuses()) {
            AttributeInstance inst = p.getAttribute(b.attr());
            if (inst == null) continue;
            inst.addModifier(new AttributeModifier(keys.get(b.attr()), b.amount(), b.op(), EquipmentSlotGroup.ANY));
        }
        active.put(p.getUniqueId(), d.id());
    }

    private void clear(Player p) {
        for (Map.Entry<Attribute, NamespacedKey> e : keys.entrySet()) {
            AttributeInstance inst = p.getAttribute(e.getKey());
            if (inst != null) inst.removeModifier(e.getValue());
        }
        AttributeInstance maxHp = p.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp != null && p.getHealth() > maxHp.getValue()) p.setHealth(maxHp.getValue());
        active.remove(p.getUniqueId());
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { clear(e.getPlayer()); refresh(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { clear(e.getPlayer()); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length < 1) return false;
        Player target = (args.length > 1) ? getServer().getPlayer(args[1])
                : (sender instanceof Player pl ? pl : null);
        if (target == null) { sender.sendMessage("Игрок не найден"); return true; }
        Def d;
        try { d = defs.get(Integer.parseInt(args[0])); } catch (NumberFormatException ex) { d = null; }
        if (d == null) { sender.sendMessage("ID: 1001-1006 или 2001-2006"); return true; }

        ItemStack item = new ItemStack(d.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(d.name(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        var cmdc = meta.getCustomModelDataComponent();
        cmdc.setFloats(List.of((float) d.id()));
        meta.setCustomModelDataComponent(cmdc);
        item.setItemMeta(meta);
        target.getInventory().addItem(item);
        sender.sendMessage("Выдано: " + d.name());
        return true;
    }
}
