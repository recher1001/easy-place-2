package io.github.recher1001.easyplace2.placement;

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
import fi.dy.masa.litematica.util.RayTraceUtils;

/**
 * Welcher Geisterblock liegt im Fadenkreuz?
 * <p>
 * Steht der Spieler selbst in der Schematic, beginnt der Blick-Strahl <i>in</i> dem Geisterblock, in dem
 * sein Kopf (oder seine Fuesse) steckt, und Minecraft zaehlt das als Treffer. Dann wuerde Easy Place 2.0
 * versuchen, genau dort zu bauen, wo der Spieler steht, statt dort, wo er hinschaut. Solche Bloecke kann
 * man ohnehin nicht setzen, deshalb werden sie hier uebersprungen.
 */
public final class GhostTrace
{
    private static final double EPSILON = 1.0E-4;

    private GhostTrace()
    {
    }

    /**
     * Der Geisterblock im Fadenkreuz, wie Litematica ihn sieht, aber ohne die Bloecke, in denen der
     * Spieler selbst steht.
     *
     * @return der Treffer, oder null wenn das Fadenkreuz auf keinen Geisterblock zeigt (normaler Klick)
     */
    @Nullable
    public static BlockHitResult target(ClientLevel level, Level schematic, Entity camera, Entity player, double range)
    {
        RayTraceUtils.RayTraceWrapper trace = RayTraceUtils.getGenericTrace(level, camera, range);

        if (trace == null || trace.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK)
        {
            return null;
        }

        BlockHitResult hit = trace.getBlockHitResult();

        if (!occupiedBy(player, hit.getBlockPos()))
        {
            return hit;
        }

        // Der erste Treffer ist der Block am eigenen Kopf/Fuss: weiter hinten suchen
        Vec3 eye = camera.getEyePosition();
        Vec3 end = eye.add(camera.getViewVector(1.0f).scale(range));
        BlockHitResult ghost = firstGhost(schematic, player, eye, end);

        if (ghost == null)
        {
            return null;
        }

        // Steht ein echter Block davor, ist es ein normaler Klick auf diesen Block
        BlockHitResult real = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));

        if (real.getType() == HitResult.Type.BLOCK &&
            real.getLocation().distanceToSqr(eye) + EPSILON < ghost.getLocation().distanceToSqr(eye))
        {
            return null;
        }

        return ghost;
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
            return shape.isEmpty() ? null : shape.clip(from, to, pos);
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
