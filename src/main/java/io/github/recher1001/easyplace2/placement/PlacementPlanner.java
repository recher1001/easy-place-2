package io.github.recher1001.easyplace2.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.mixin.BlockItemInvoker;

/**
 * Sucht einen normalen Vanilla-Klick, der den Schematic-Block an der Zielposition erzeugt.
 * <p>
 * Ein Kandidat ist immer ein Klick auf eine echte Blockflaeche, die vom Auge des Spielers aus
 * in Reichweite und ohne Hindernis sichtbar ist. Fuer jeden Kandidaten wird mit Vanillas eigener
 * Platzierungslogik (mit der echten Blickrichtung des Spielers) ausgerechnet, was entstehen wuerde.
 * Nur wenn das zur Schematic passt, wird der Kandidat genommen.
 */
public class PlacementPlanner
{
    /** Punkte auf einer Blockflaeche, die ausprobiert werden (Mitte zuerst, dann obere/untere bzw. seitliche Bereiche). */
    private static final double[] SAMPLE_OFFSETS = { 0.5, 0.15, 0.85 };

    public record Result(@Nullable BlockHitResult hit, String reason)
    {
        public static Result success(BlockHitResult hit)
        {
            return new Result(hit, "");
        }

        public static Result fail(String reason)
        {
            return new Result(null, reason);
        }
    }

    private final LocalPlayer player;
    private final Level level;
    private final BlockPos target;
    private final BlockState targetState;
    private final InteractionHand hand;
    private final ItemStack stack;
    private final BlockItem item;
    private final Vec3 eye;
    private final double reach;

    // Gruende fuer die Debug-Ausgabe (der "beste" gewinnt)
    @Nullable private String stateMismatch;
    @Nullable private String vanillaRefused;
    private boolean onlyInteractive;
    private boolean anyVisibleFace;

    public PlacementPlanner(LocalPlayer player, Level level, BlockPos target, BlockState targetState,
                            InteractionHand hand, ItemStack stack, BlockItem item)
    {
        this.player = player;
        this.level = level;
        this.target = target;
        this.targetState = targetState;
        this.hand = hand;
        this.stack = stack;
        this.item = item;
        this.eye = player.getEyePosition();
        this.reach = player.blockInteractionRange();
    }

    /**
     * @param crosshairHit Vanillas eigenes Fadenkreuz-Ziel (der Block hinter dem Geisterblock), falls vorhanden
     * @param strict nur das echte Fadenkreuz-Ziel verwenden, keine anderen Nachbarflaechen
     */
    public Result plan(@Nullable BlockHitResult crosshairHit, boolean strict)
    {
        // 1. Genau das, was ein normaler Rechtsklick an dieser Stelle machen wuerde
        if (crosshairHit != null && crosshairHit.getType() == HitResult.Type.BLOCK)
        {
            this.anyVisibleFace = true;

            if (this.test(crosshairHit))
            {
                return Result.success(crosshairHit);
            }
        }

        if (strict)
        {
            return Result.fail(this.failReason(true));
        }

        // 2. Andere sichtbare Flaechen, die an die Zielposition grenzen
        for (BlockHitResult hit : this.collectCandidates())
        {
            if (this.test(hit))
            {
                return Result.success(hit);
            }
        }

        return Result.fail(this.failReason(false));
    }

    /**
     * Prueft einen einzelnen Klick: wuerde Vanilla damit genau an der Zielposition den richtigen Zustand setzen?
     */
    public boolean test(BlockHitResult hit)
    {
        BlockState clickedState = this.level.getBlockState(hit.getBlockPos());

        // Kisten, Tueren usw. wuerden sich oeffnen statt dass platziert wird (ausser man schleicht selbst)
        if (InteractiveBlocks.isInteractive(clickedState.getBlock()) && !this.player.isSecondaryUseActive())
        {
            this.onlyInteractive = true;
            return false;
        }

        BlockPlaceContext ctx = this.item.updatePlacementContext(new BlockPlaceContext(this.player, this.hand, this.stack, hit));

        if (ctx == null || !ctx.canPlace())
        {
            this.vanillaRefused = "Vanilla erlaubt hier keine Platzierung";
            return false;
        }

        if (!ctx.getClickedPos().equals(this.target))
        {
            return false;
        }

        BlockState simulated = ((BlockItemInvoker) this.item).easyplace2$getPlacementState(ctx);

        if (simulated == null)
        {
            this.vanillaRefused = "Vanilla erlaubt hier keine Platzierung (kein Halt oder blockiert)";
            return false;
        }

        String mismatch = StateMatcher.findMismatch(simulated, this.targetState);

        if (mismatch != null)
        {
            this.stateMismatch = mismatch;
            return false;
        }

        return true;
    }

    private List<BlockHitResult> collectCandidates()
    {
        List<BlockHitResult> list = new ArrayList<>();
        Vec3 look = this.player.getViewVector(1.0f);

        // Flaechen der Nachbarbloecke, die zur Zielposition zeigen
        for (Direction dir : Direction.values())
        {
            BlockPos neighbor = this.target.relative(dir);

            if (this.level.getBlockState(neighbor).isAir())
            {
                continue;
            }

            this.addFaceCandidates(list, dir, neighbor);
        }

        // Wenn an der Zielposition schon etwas steht, das Vanilla ersetzen/ergaenzen kann
        // (Halbplatte -> doppelte Halbplatte, Gras, Kerzen, ...), auch darauf klicken
        if (!this.level.getBlockState(this.target).isAir())
        {
            for (Direction dir : Direction.values())
            {
                this.addFaceCandidates(list, dir, this.target);
            }
        }

        // Flaechen bevorzugen, die moeglichst nah an der Blickrichtung liegen
        list.sort(Comparator.comparingDouble(hit -> -hit.getLocation().subtract(this.eye).normalize().dot(look)));
        return list;
    }

    /**
     * Probiert einige Punkte auf der Grenzflaeche der Zielposition in Richtung {@code dir} aus.
     * Jeder Punkt wird mit einem echten Strahl vom Auge aus geprueft; gezaehlt wird nur, wenn der
     * Strahl innerhalb der Reichweite genau den erwarteten Block trifft.
     */
    private void addFaceCandidates(List<BlockHitResult> list, Direction dir, BlockPos expectedPos)
    {
        Direction.Axis axis = dir.getAxis();
        double cx = this.target.getX() + 0.5 + dir.getStepX() * 0.5;
        double cy = this.target.getY() + 0.5 + dir.getStepY() * 0.5;
        double cz = this.target.getZ() + 0.5 + dir.getStepZ() * 0.5;

        for (double u : SAMPLE_OFFSETS)
        {
            for (double v : SAMPLE_OFFSETS)
            {
                double x = axis == Direction.Axis.X ? cx : this.target.getX() + u;
                double y = axis == Direction.Axis.Y ? cy : this.target.getY() + v;
                double z = axis == Direction.Axis.Z ? cz : this.target.getZ() + (axis == Direction.Axis.X ? u : v);
                Vec3 point = new Vec3(x, y, z);
                Vec3 delta = point.subtract(this.eye);

                if (delta.lengthSqr() < 1.0E-6)
                {
                    continue;
                }

                Vec3 end = this.eye.add(delta.normalize().scale(this.reach + 1.0));
                BlockHitResult hit = this.level.clip(new ClipContext(this.eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this.player));

                if (hit.getType() == HitResult.Type.BLOCK &&
                    hit.getBlockPos().equals(expectedPos) &&
                    hit.getLocation().distanceTo(this.eye) <= this.reach)
                {
                    this.anyVisibleFace = true;
                    list.add(hit);
                }
            }
        }
    }

    private String failReason(boolean strict)
    {
        if (this.stateMismatch != null)
        {
            return "Vanilla wuerde " + this.stateMismatch + " setzen (Blickrichtung/Klickstelle passt nicht)";
        }

        if (this.vanillaRefused != null)
        {
            return this.vanillaRefused;
        }

        if (this.onlyInteractive)
        {
            return "Nachbarblock reagiert selbst auf Rechtsklick (zum Platzieren selbst schleichen)";
        }

        if (strict)
        {
            return "Fadenkreuz zeigt auf keine passende Nachbarflaeche (strenger Modus)";
        }

        return this.anyVisibleFace ? "keine passende Nachbarflaeche" : "keine sichtbare Nachbarflaeche in Reichweite (schwebender Block?)";
    }
}
