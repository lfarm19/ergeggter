package com.example.endsword;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EndSwordPlugin extends JavaPlugin implements Listener {

    // ---- Settings (change these if you like) ----
    private static final long BREATH_COOLDOWN_MS = 60_000L;   // 1 minute
    private static final long TELEPORT_COOLDOWN_MS = 300_000L; // 5 minutes
    private static final double TELEPORT_RANGE = 50.0;

    // ---- Recipe ingredients (matches the screenshot) ----
    private static final Material CORNER_ITEM = Material.NETHERITE_INGOT;
    private static final Material EDGE_ITEM = Material.DRAGON_BREATH;
    private static final Material CENTER_ITEM = Material.DRAGON_HEAD;

    private NamespacedKey swordKey;
    private NamespacedKey recipeKey;

    private final Map<UUID, Long> breathReady = new HashMap<>();
    private final Map<UUID, Long> teleportReady = new HashMap<>();
    private final java.util.Set<UUID> debugging = new java.util.HashSet<>();

    private File dataFile;
    private int craftedCount;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        craftedCount = YamlConfiguration.loadConfiguration(dataFile).getInt("crafted", 0);
        swordKey = new NamespacedKey(this, "end_sword");
        recipeKey = new NamespacedKey(this, "end_sword_recipe");

        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("EndSword enabled.");
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
    }

    // ------------------------------------------------------------------
    // Item + recipe
    // ------------------------------------------------------------------

    private ItemStack createSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();

        // Must stay a plain, unstyled name: the Majestica resource pack picks the saber model
        // by matching the item's name text exactly ("Abominable Great Saber").
        meta.displayName(Component.text("Abominable Great Saber"));
        meta.lore(List.of(
                Component.empty(),
                Component.text("Right Click: ", NamedTextColor.DARK_PURPLE)
                        .append(Component.text("Dragon Breath (1m cooldown)", NamedTextColor.GRAY))
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Shift + Right Click: ", NamedTextColor.DARK_PURPLE)
                        .append(Component.text("Teleport (5m cooldown)", NamedTextColor.GRAY))
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.setEnchantmentGlintOverride(true);

        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.addEnchant(Enchantment.KNOCKBACK, 1, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);

        // Optional resource-pack model (set in config.yml)
        String model = getConfig().getString("item-model", "");
        if (model != null && !model.isBlank()) {
            NamespacedKey modelKey = NamespacedKey.fromString(model.trim());
            if (modelKey != null) meta.setItemModel(modelKey);
        }
        String cmdString = getConfig().getString("custom-model-data-string", "");
        double cmdNumber = getConfig().getDouble("custom-model-data-number", 0);
        boolean hasString = cmdString != null && !cmdString.isBlank();
        if (hasString || cmdNumber != 0) {
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            if (hasString) cmd.setStrings(List.of(cmdString.trim()));
            if (cmdNumber != 0) cmd.setFloats(List.of((float) cmdNumber));
            meta.setCustomModelDataComponent(cmd);
        }

        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
        sword.setItemMeta(meta);
        return sword;
    }

    private boolean isEndSword(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_SWORD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    private void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createSword());
        recipe.shape("NPN", "PHP", "NPN");
        // Corners: 4 netherite ingots
        recipe.setIngredient('N', CORNER_ITEM);
        // Edges: 4 dragon's breath
        recipe.setIngredient('P', EDGE_ITEM);
        // Center: 1 dragon head
        recipe.setIngredient('H', CENTER_ITEM);
        boolean ok = Bukkit.addRecipe(recipe);
        getLogger().info("Recipe registered: " + ok);
    }

    // ------------------------------------------------------------------
    // Craft limit + announcement
    // ------------------------------------------------------------------

    private int maxCrafts() {
        return getConfig().getInt("max-crafts", 3);
    }

    private boolean limitReached() {
        int max = maxCrafts();
        return max >= 0 && craftedCount >= max;
    }

    private void saveCount() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("crafted", craftedCount);
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    /** Builds a chat message from a config key. Supports &-colors and {player} {crafted} {max} {remaining}. */
    private Component msg(String key, String playerName) {
        int max = maxCrafts();
        String raw = getConfig().getString(key, "");
        raw = raw.replace("{player}", playerName)
                .replace("{crafted}", String.valueOf(craftedCount))
                .replace("{max}", max < 0 ? "unlimited" : String.valueOf(max))
                .replace("{remaining}", max < 0 ? "unlimited" : String.valueOf(Math.max(0, max - craftedCount)));
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!isEndSword(event.getCurrentItem())) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (limitReached()) {
            event.setCancelled(true);
            player.sendMessage(msg("limit-reached-message", player.getName()));
            return;
        }
        if (event.isShiftClick()) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Click the result normally to craft it (no shift-click).", NamedTextColor.RED));
            return;
        }

        craftedCount++;
        saveCount();
        if (getConfig().getBoolean("announce-craft", true)) {
            Bukkit.getServer().broadcast(msg("craft-announcement", player.getName()));
        }
    }

    // Crafting grid check (also shows an empty result once the craft limit is reached).
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack[] m = event.getInventory().getMatrix();
        if (m.length != 9) return;

        if (!debugging.isEmpty()) debugGrid(event, m);

        for (int i : new int[]{0, 2, 6, 8}) {
            if (!isType(m[i], CORNER_ITEM)) return;
        }
        for (int i : new int[]{1, 3, 5, 7}) {
            if (!isType(m[i], EDGE_ITEM)) return;
        }
        if (!isType(m[4], CENTER_ITEM)) return;

        if (limitReached()) {
            event.getInventory().setResult(null);
            for (org.bukkit.entity.HumanEntity viewer : event.getViewers()) {
                if (viewer instanceof Player p) {
                    p.sendActionBar(msg("limit-reached-message", p.getName()));
                }
            }
            return;
        }
        event.getInventory().setResult(createSword());
    }

    // Shows what is really in the crafting grid (use /endsword debug to toggle).
    private void debugGrid(PrepareItemCraftEvent event, ItemStack[] m) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < m.length; i++) {
            ItemStack it = m[i];
            if (it == null || it.getType() == Material.AIR) {
                sb.append(i).append("=empty ");
                continue;
            }
            any = true;
            sb.append(i).append('=').append(it.getType().name());
            if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
                sb.append("(\"")
                        .append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText().serialize(it.getItemMeta().displayName()))
                        .append("\")");
            }
            sb.append(' ');
        }
        if (!any) return;
        for (org.bukkit.entity.HumanEntity viewer : event.getViewers()) {
            if (viewer instanceof Player p && debugging.contains(p.getUniqueId())) {
                p.sendMessage(Component.text("[EndSword debug] " + sb, NamedTextColor.YELLOW));
            }
        }
    }

    private boolean isType(ItemStack item, Material... types) {
        if (item == null) return false;
        for (Material t : types) {
            if (item.getType() == t) return true;
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipe(recipeKey);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        breathReady.remove(id);
        teleportReady.remove(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            player.sendMessage(Component.text("EndSword config reloaded.", NamedTextColor.YELLOW));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            craftedCount = 0;
            saveCount();
            player.sendMessage(Component.text("Craft counter reset to 0.", NamedTextColor.YELLOW));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            int max = maxCrafts();
            player.sendMessage(Component.text("Crafted: " + craftedCount + " / " + (max < 0 ? "unlimited" : max), NamedTextColor.YELLOW));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
            if (debugging.remove(player.getUniqueId())) {
                player.sendMessage(Component.text("EndSword debug OFF.", NamedTextColor.YELLOW));
            } else {
                debugging.add(player.getUniqueId());
                player.sendMessage(Component.text("EndSword debug ON. Put items in a crafting table and read the chat.", NamedTextColor.YELLOW));
            }
            return true;
        }
        player.getInventory().addItem(createSword());
        player.sendMessage(Component.text("You received the Abominable Great Saber."));
        return true;
    }

    // ------------------------------------------------------------------
    // Abilities
    // ------------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!isEndSword(player.getInventory().getItemInMainHand())) return;

        event.setCancelled(true);

        if (player.isSneaking()) {
            useTeleport(player);
        } else {
            useDragonBreath(player);
        }
    }

    private void useDragonBreath(Player player) {
        long now = System.currentTimeMillis();
        long ready = breathReady.getOrDefault(player.getUniqueId(), 0L);
        if (now < ready) {
            sendCooldown(player, ready - now);
            return;
        }
        breathReady.put(player.getUniqueId(), now + BREATH_COOLDOWN_MS);

        Vector dir = player.getEyeLocation().getDirection().normalize();
        DragonFireball fireball = player.launchProjectile(DragonFireball.class, dir.clone().multiply(1.2));
        fireball.setDirection(dir);
        fireball.setShooter(player);

        Location eye = player.getEyeLocation();
        player.getWorld().spawnParticle(Particle.DRAGON_BREATH, eye.clone().add(dir.clone().multiply(1.5)),
                40, 0.3, 0.3, 0.3, 0.02);
        player.getWorld().playSound(eye, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.0f, 1.0f);
    }

    private void useTeleport(Player player) {
        long now = System.currentTimeMillis();
        long ready = teleportReady.getOrDefault(player.getUniqueId(), 0L);
        if (now < ready) {
            sendCooldown(player, ready - now);
            return;
        }

        Location destination = findTeleportSpot(player);
        if (destination == null) {
            player.sendActionBar(Component.text("No safe spot to teleport to!", NamedTextColor.RED));
            return;
        }
        teleportReady.put(player.getUniqueId(), now + TELEPORT_COOLDOWN_MS);

        Location from = player.getLocation();
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.5);
        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);

        destination.setYaw(from.getYaw());
        destination.setPitch(from.getPitch());
        player.teleport(destination);
        player.setFallDistance(0f);

        destination.getWorld().spawnParticle(Particle.PORTAL, destination.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.5);
        destination.getWorld().playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    /** Finds a spot (feet position) where the player is looking, backing off from walls so they never end up inside blocks. */
    private Location findTeleportSpot(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        RayTraceResult result = player.getWorld().rayTraceBlocks(
                eye, dir, TELEPORT_RANGE, FluidCollisionMode.NEVER, true);

        Location start;
        if (result != null) {
            start = result.getHitPosition().toLocation(player.getWorld());
        } else {
            start = eye.clone().add(dir.clone().multiply(TELEPORT_RANGE));
        }

        for (int i = 0; i <= 80; i++) {
            Location candidate = start.clone().subtract(dir.clone().multiply(i * 0.25));
            if (candidate.getBlock().isPassable()
                    && candidate.clone().add(0, 1, 0).getBlock().isPassable()) {
                return candidate;
            }
        }
        return null;
    }

    private void sendCooldown(Player player, long remainingMs) {
        long totalSeconds = (remainingMs + 999) / 1000;
        String time = totalSeconds >= 60
                ? (totalSeconds / 60) + "m " + (totalSeconds % 60) + "s"
                : totalSeconds + "s";
        player.sendActionBar(Component.text("Cooldown: " + time, NamedTextColor.BLUE));
    }
}
