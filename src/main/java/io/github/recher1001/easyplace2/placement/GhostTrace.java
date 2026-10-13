package io.github.recher1001.easyplace2.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import fi.dy.masa.litematica.data.DataManager;

/**
 * Welcher Geisterblock liegt im Fadenkreuz?
 * <p>
 * Steht der Spieler selbst in der Schematic, beginnt der Blick-Strahl <i>in</i> dem Geisterblock, in dem
 * sein Kopf (oder seine Fuesse) steckt, und Minecraft zaehlt das als Treffer. Dann wuerde Easy Place 2.0
 * versuchen, genau dort zu bauen, wo der Spieler steht, statt dort, wo er hinschaut. Solche Bloecke kann
 * man ohnehin nicht setzen, deshalb werden sie hier uebersprungen.
 * <p>
 * Liegen mehrere fehlende Bloecke hintereinander im Blick, wird der hinterste zuerst gebaut (der direkt
 * vor dem echten Block, den das Fadenkreuz trifft). So verdecken die vorderen spaeter nichts.
 */
public final class GhostTrace
{
    private static final double EPSILON = 1.0E-4;

    private GhostTrace()
    {
    }

    /**
     * Alle Geisterbloecke im Fadenkreuz bis zum ersten echten Block (innerhalb der Reichweite), der
     * <b>hinterste zuerst</b>. Bloecke, in denen der Spieler selbst steht, und Bloecke ausserhalb des
     * sichtbaren Litematica-Schichtbereichs zaehlen nicht.
     */
    public static List<BlockHitResult> ghostsInView(ClientLevel level, Level schematic, Entity camera, Entity player, double range)
    {
        Vec3 eye = camera.getEyePosition();
        Vec3 end = eye.add(camera.getViewVector(1.0f).scale(range));
        BlockHitResult real = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));

        // Nur bis zum echten Block: was dahinter liegt, kann man nicht anklicken
        Vec3 limit = real.getType() == HitResult.Type.BLOCK ? real.getLocation() : end;
        List<BlockHitResult> list = ghostsOnSegment(schematic, player, eye, limit);
        Collections.reverse(list);
        return list;
    }

    /** Der hinterste Geisterblock auf dem Strahl {@code from → to}, oder null. */
    @Nullable
    public static BlockHitResult lastGhost(Level schematic, Entity player, Vec3 from, Vec3 to)
    {
        List<BlockHitResult> list = ghostsOnSegment(schematic, player, from, to);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /** Alle Geisterbloecke auf dem Strahl {@code from → to}, der vorderste zuerst. */
    private static List<BlockHitResult> ghostsOnSegment(Level schematic, Entity player, Vec3 from, Vec3 to)
    {
        AABB body = bodyBox(player);
        List<BlockHitResult> list = new ArrayList<>();
        // Ein klein wenig ueber das Ende hinaus, damit der Block direkt vor der angeklickten Flaeche mitzaehlt
        Vec3 delta = to.subtract(from);
        Vec3 until = delta.lengthSqr() < 1.0E-8 ? to : to.add(delta.normalize().scale(0.01));
        // Was erst am Ende (oder dahinter) beginnt, zaehlt nicht: z. B. der angeklickte echte Block selbst,
        // wenn er schon fertig zur Schematic gehoert
        double maxDist = delta.length() - EPSILON;

        BlockGetter.traverseBlocks(from, until, schematic, (world, pos) ->
        {
            if (body.intersects(new AABB(pos)) || !DataManager.getRenderLayerRange().isPositionWithinRange(pos))
            {
                return null;
            }

            BlockState state = world.getBlockState(pos);

            // pos ist ein veraenderliches Objekt, das beim Weiterlaufen ueberschrieben wird: immer kopieren
            if (!state.isAir())
            {
                VoxelShape shape = state.getShape(world, pos);
                BlockHitResult hit = shape.isEmpty() ? null : shape.clip(from, until, pos.immutable());

                if (hit != null && hit.getLocation().distanceTo(from) < maxDist)
                {
                    list.add(hit);
                }
            }

            // null = weitersuchen bis zum Ende des Strahls
            return null;
        }, world -> null);

        return list;
    }

    /**
     * Der erste Geisterblock auf dem Strahl {@code from → to} (nur innerhalb des sichtbaren
     * Litematica-Schichtbereichs), ohne die Bloecke, in denen {@code player} gerade steht.
     */
    @Nullable
    public static BlockHitResult firstGhost(Level schematic, Entity player, Vec3 from, Vec3 to)
    {
        AABB body = bodyBox(player);
        Predicate<BlockPos> skip = pos -> body.intersects(new AABB(pos)) ||
                                          !DataManager.getRenderLayerRange().isPositionWithinRange(pos);

        return BlockGetter.traverseBlocks(from, to, schematic, (world, pos) ->
        {
            if (skip.test(pos))
            {
                return null;
            }

            BlockState state = world.getBlockState(pos);

            if (state.isAir())
            {
                return null;
            }

            VoxelShape shape = state.getShape(world, pos);
            return shape.isEmpty() ? null : shape.clip(from, to, pos.immutable());
        }, world -> null);
    }

    /** Steckt der Spieler (Kopf oder Fuesse) in diesem Block? */
    public static boolean occupiedBy(Entity player, BlockPos pos)
    {
        return bodyBox(player).intersects(new AABB(pos));
    }

    /**
     * Der Koerper des Spielers an seiner aktuellen Position. Selbst berechnet, weil der Printer die
     * Position beim Planen nur kurz im Speicher verschiebt und die Hitbox dabei nicht mitwandert.
     */
    private static AABB bodyBox(Entity player)
    {
        return player.getDimensions(player.getPose()).makeBoundingBox(player.position()).deflate(EPSILON);
    }
}
