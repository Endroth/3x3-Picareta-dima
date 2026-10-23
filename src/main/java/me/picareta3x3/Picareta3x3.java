package me.picareta3x3;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Picareta3x3 extends JavaPlugin implements Listener {

    private NamespacedKey toolKey;
    private NamespacedKey recipeKey;
    private final Set<UUID> quebrando = new HashSet<>();

    @Override
    public void onEnable() {
        toolKey = new NamespacedKey(this, "picareta_3x3");
        recipeKey = new NamespacedKey(this, "picareta_3x3_recipe");

        registrarReceita();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Picareta 3x3 ativada!");
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
    }

    // ---------- Item e receita ----------

    private ItemStack criarPicareta() {
        ItemStack item = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§bPicareta 3x3");
        meta.setLore(List.of("§7Minera uma área de 3x3 blocos."));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean ehPicareta3x3(ItemStack item) {
   if (item == null || !item.hasItemMeta()) return false;
   if (item.getType() != Material.DIAMOND_PICKAXE && item.getType() != Material.NETHERITE_PICKAXE) return false;
    }

    private void registrarReceita() {
        ShapedRecipe receita = new ShapedRecipe(recipeKey, criarPicareta());
        receita.shape("DDD", " S ", " S ");
        receita.setIngredient('D', new RecipeChoice.MaterialChoice(Material.DIAMOND_PICKAXE));
        receita.setIngredient('S', Material.STICK);
        Bukkit.addRecipe(receita);
    }

    @EventHandler
    public void aoEntrar(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipe(recipeKey);
    }

    // Impede usar uma picareta 3x3 como ingrediente da própria receita
    @EventHandler
    public void aoPreparar(PrepareItemCraftEvent e) {
        if (e.getRecipe() == null) return;
        if (!(e.getRecipe() instanceof ShapedRecipe sr) || !sr.getKey().equals(recipeKey)) return;
        for (ItemStack i : e.getInventory().getMatrix()) {
            if (ehPicareta3x3(i)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ---------- Mineração 3x3 ----------

    @EventHandler(ignoreCancelled = true)
    public void aoQuebrar(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack ferramenta = p.getInventory().getItemInMainHand();
        if (!ehPicareta3x3(ferramenta) || quebrando.contains(p.getUniqueId())) return;

        RayTraceResult hit = p.rayTraceBlocks(7);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) return;
        if (!hit.getHitBlock().equals(e.getBlock())) return;

        BlockFace face = hit.getHitBlockFace();
        Block centro = e.getBlock();

        quebrando.add(p.getUniqueId());
        try {
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) continue;

                    Block alvo = switch (face) {
                        case UP, DOWN -> centro.getRelative(a, 0, b);
                        case NORTH, SOUTH -> centro.getRelative(a, b, 0);
                        default -> centro.getRelative(0, a, b); // EAST, WEST
                    };

                    if (!podeQuebrar(alvo)) continue;

                    // Dispara o evento para respeitar WorldGuard, claims etc.
                    BlockBreakEvent extra = new BlockBreakEvent(alvo, p);
                    Bukkit.getPluginManager().callEvent(extra);
                    if (extra.isCancelled()) continue;

                    if (extra.isDropItems()) {
                        alvo.breakNaturally(ferramenta);
                    } else {
                        alvo.setType(Material.AIR);
                    }

                    if (!gastarDurabilidade(p, ferramenta)) return;
                }
            }
        } finally {
            quebrando.remove(p.getUniqueId());
        }
    }

    private boolean podeQuebrar(Block b) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid()) return false;
        if (m.getHardness() < 0) return false;                // bedrock, barreira etc.
        if (!Tag.MINEABLE_PICKAXE.isTagged(m)) return false;  // só o que picareta quebra
        return !(b.getState() instanceof TileState);          // não mexe em spawners, fornalhas etc.
    }

    /** Retorna false se a picareta quebrou. */
    private boolean gastarDurabilidade(Player p, ItemStack item) {
        if (p.getGameMode() == GameMode.CREATIVE) return true;
        if (!(item.getItemMeta() instanceof Damageable dano)) return true;

        int unbreaking = item.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (Math.random() >= 1.0 / (unbreaking + 1)) return true;

        dano.setDamage(dano.getDamage() + 1);
        if (dano.getDamage() >= item.getType().getMaxDurability()) {
            p.getInventory().setItemInMainHand(null);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            return false;
        }
        item.setItemMeta(dano);
        return true;
    }
}
