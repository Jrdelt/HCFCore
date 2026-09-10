package me.vertex.core.bucket;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Right-clicking with a Source Bucket triggers an immediate use -- unlike
 * Chunk Busters, there is no confirmation step. Purchases are handled directly by the ordinary
 * Raiding Materials shop page.
 *
 * <p><b>Why {@code PlayerInteractEvent} rather than {@code
 * PlayerBucketEmptyEvent}.</b> {@code PlayerBucketEmptyEvent} only fires
 * for an actual vanilla bucket {@code Material} (WATER_BUCKET/LAVA_BUCKET/
 * etc.) and its default vanilla handling -- consuming the bucket into an
 * empty one and placing a single source block -- would have to be
 * cancelled and completely re-implemented anyway to get a non-consumed,
 * multi-block, claim-aware flow. {@code sourcebuckets.yml}'s {@code
 * material} is also configurable per variant exactly like {@code
 * ChunkBusterType.material} (it need not literally be a bucket item), so
 * an event that only fires for two specific vanilla materials would not
 * even cover every configured variant. {@code PlayerInteractEvent} fires
 * for any item, is cancelled the same way {@code ChunkBusterListener}
 * already cancels it for a matching custom item (which prevents vanilla's
 * own bucket-use logic -- and therefore {@code PlayerBucketEmptyEvent} --
 * from ever running), and gives full control over targeting and placement.
 * This mirrors the most recent precedent in this codebase for "right-click
 * a custom PDC-tagged item to trigger an action" exactly.
 *
 * <p><b>Target block.</b> The flow's origin is {@code
 * clickedBlock.getRelative(blockFace)} -- the block adjacent to the
 * clicked face, i.e. standard "place a new block against this face"
 * semantics (the same convention used when placing any ordinary block
 * against a clicked face) -- rather than the clicked block itself. This is
 * simpler than replicating vanilla bucket-empty's "overwrite the clicked
 * block when it's itself replaceable/waterlogged, otherwise use the
 * relative face" nuance, and the spec's correctness-critical requirements
 * are entirely about claim boundaries and charge ordering, not
 * placement-target fidelity to vanilla buckets -- see {@code
 * docs/source-buckets.md} for the explicit call-out.
 */
public final class SourceBucketListener implements Listener {

    private final SourceBucketManager manager;
    private final Messages messages;

    public SourceBucketListener(SourceBucketManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        ItemStack item = event.getItem();
        SourceBucketType variant = manager.variantOf(item);
        if (variant == null) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);

        Block clicked = event.getClickedBlock();
        BlockFace face = event.getBlockFace();
        if (action == Action.RIGHT_CLICK_AIR || clicked == null || face == null) {
            player.sendMessage(messages.get(player, "sourcebucket.need-block-target"));
            return;
        }
        Location target = clicked.getRelative(face).getLocation();

        SourceBucketManager.UseResult result = manager.use(player, target, face, variant);
        if (result == SourceBucketManager.UseResult.OK) {
            player.sendMessage(messages.get(player, "sourcebucket.used",
                    "amount", EconomyHook.format(variant.perUseFee())));
        } else {
            player.sendMessage(messages.get(player, messageKeyFor(result)));
        }
    }

    static String messageKeyFor(SourceBucketManager.UseResult result) {
        return switch (result) {
            case TYPE_DISABLED -> "sourcebucket.type-disabled";
            case IN_COMBAT -> "sourcebucket.combat-blocked";
            case WRONG_ZONE -> "sourcebucket.wrong-zone";
            case NO_ECONOMY -> "sourcebucket.no-economy";
            case INSUFFICIENT_FUNDS -> "sourcebucket.insufficient-funds";
            case FAILED_PLACEMENT -> "sourcebucket.failed-placement";
            case OK -> "sourcebucket.used";
        };
    }

}
