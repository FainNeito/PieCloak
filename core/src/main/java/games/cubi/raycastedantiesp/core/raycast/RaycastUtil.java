package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.BlockSpatial;
import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.api.Spatial;
import games.cubi.locatables.implementations.ImmutableSpatialImpl;
import games.cubi.locatables.implementations.ImmutableLocatableImpl;
import games.cubi.logs.Logger;
import games.cubi.raycastedantiesp.core.view.BlockView;

public final class RaycastUtil {
    private static final double AXIS_TIE_EPSILON = 1.0E-12;

    private RaycastUtil() {
    }

    /**
     * Immutable policy and debug context reused across rays which share the same visibility rules.
     */
    public record Settings(
            int maxOccluding,
            int alwaysShowRadius,
            int maxRaycastRadius,
            boolean debug,
            BlockView blockView,
            ParticleSpawner particleSpawner) {
    }

    // True: has line-of-sight.
    // Traverse every voxel whose interior the centre ray enters. The start and target voxels are
    // intentionally excluded so the viewer's own block and the target block cannot occlude themselves.
    public static boolean raycast(Locatable start, Spatial end, Settings settings) {
        return raycast(start, end, settings, 0f);
    }

    /**
     * Raycasts to a non-block target with an optional vertical aim offset. BlockSpatial targets are
     * always aimed at their block centre; an entity-style Y offset must never move a block target.
     * Keeping that invariant here prevents call-site argument migrations from silently shifting
     * block-entity visibility rays above or below the actual block.
     */
    public static boolean raycast(Locatable start, Spatial end, Settings settings, float yOffsetEnd) {
        // The viewer location is updated concurrently. Geometry and traversal must use
        // the same coordinates, even if the viewer moves while this ray is checked.
        Locatable origin = new ImmutableLocatableImpl(start.world(), start.x(), start.y(), start.z());
        RayGeometry geometry = RayGeometry.between(origin, end, yOffsetEnd);
        if (!Double.isFinite(geometry.distance())) {
            return false;
        }
        if (geometry.distance() <= settings.alwaysShowRadius()) {
            return true;
        }
        if (geometry.distance() > settings.maxRaycastRadius()) {
            return false;
        }
        validateDebugContext(settings);
        return new VoxelTraversal(origin, geometry, settings).hasLineOfSight();
    }

    private static void validateDebugContext(Settings settings) {
        if (settings.debug() && settings.particleSpawner() == null) {
            Logger.errorAndReturn(new RuntimeException(
                    "raycast called with debug enabled but no ParticleSpawner supplied"),
                    2, RaycastUtil.class);
        }
    }

    private static int floorBlock(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private static double axisDelta(double delta) {
        return delta == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / delta);
    }

    private static double firstBoundaryT(double start, double delta, int block, int step) {
        if (step > 0) {
            return (block + 1.0 - start) / delta;
        }
        if (step < 0) {
            return (start - block) / -delta;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static void spawnVoxelParticle(Locatable start, ParticleSpawner particleSpawner,
            int x, int y, int z, ParticleSpawner.Colour colour) {
        particleSpawner.spawnParticleAt(start.world(),
                new ImmutableSpatialImpl(x + 0.5, y + 0.5, z + 0.5), colour);
    }

    private record RayGeometry(
            double endX,
            double endY,
            double endZ,
            double deltaX,
            double deltaY,
            double deltaZ,
            double distance) {
        private static RayGeometry between(Locatable start, Spatial end, float yOffsetEnd) {
            boolean blockTarget = end instanceof BlockSpatial;
            double endOffset = blockTarget ? 0.5 : 0.0;
            double effectiveYOffset = blockTarget ? 0.0 : yOffsetEnd;
            double endX = end.x() + endOffset;
            double endY = end.y() + endOffset + effectiveYOffset;
            double endZ = end.z() + endOffset;
            double deltaX = endX - start.x();
            double deltaY = endY - start.y();
            double deltaZ = endZ - start.z();
            double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
            return new RayGeometry(endX, endY, endZ, deltaX, deltaY, deltaZ, distance);
        }
    }

    private static final class VoxelTraversal {
        private final Locatable start;
        private final BlockView blockView;
        private final ParticleSpawner particleSpawner;
        private final boolean debug;
        private final int maxOccluding;
        private final int targetX;
        private final int targetY;
        private final int targetZ;
        private final int stepX;
        private final int stepY;
        private final int stepZ;
        private final double tDeltaX;
        private final double tDeltaY;
        private final double tDeltaZ;
        private int x;
        private int y;
        private int z;
        private double tMaxX;
        private double tMaxY;
        private double tMaxZ;
        private int occludingCount;

        private VoxelTraversal(Locatable start, RayGeometry geometry, Settings settings) {
            this.start = start;
            blockView = settings.blockView();
            particleSpawner = settings.particleSpawner();
            debug = settings.debug();
            maxOccluding = settings.maxOccluding();
            x = floorBlock(start.x());
            y = floorBlock(start.y());
            z = floorBlock(start.z());
            targetX = floorBlock(geometry.endX());
            targetY = floorBlock(geometry.endY());
            targetZ = floorBlock(geometry.endZ());
            stepX = Integer.compare(targetX, x);
            stepY = Integer.compare(targetY, y);
            stepZ = Integer.compare(targetZ, z);
            tDeltaX = axisDelta(geometry.deltaX());
            tDeltaY = axisDelta(geometry.deltaY());
            tDeltaZ = axisDelta(geometry.deltaZ());
            tMaxX = firstBoundaryT(start.x(), geometry.deltaX(), x, stepX);
            tMaxY = firstBoundaryT(start.y(), geometry.deltaY(), y, stepY);
            tMaxZ = firstBoundaryT(start.z(), geometry.deltaZ(), z, stepZ);
        }

        private boolean hasLineOfSight() {
            long remainingSteps = Math.abs((long) targetX - x)
                    + Math.abs((long) targetY - y) + Math.abs((long) targetZ - z);
            while (!atTarget()) {
                if (remainingSteps == 0) {
                    // Fail closed if malformed geometry ever prevents forward progress.
                    return false;
                }
                remainingSteps--;
                advanceToNextVoxel();
                if (atTarget()) {
                    return true;
                }
                if (currentVoxelBlocksLineOfSight()) {
                    return false;
                }
            }
            return true;
        }

        private void advanceToNextVoxel() {
            // A negative-direction ray ending on an integer boundary reaches that
            // axis's target voxel before t=1. Do not step out of it while another
            // axis is still approaching the endpoint (including corner ties).
            if (x == targetX) tMaxX = Double.POSITIVE_INFINITY;
            if (y == targetY) tMaxY = Double.POSITIVE_INFINITY;
            if (z == targetZ) tMaxZ = Double.POSITIVE_INFINITY;
            double nextBoundary = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (x != targetX && tMaxX <= nextBoundary + AXIS_TIE_EPSILON) {
                x += stepX;
                tMaxX += tDeltaX;
            }
            if (y != targetY && tMaxY <= nextBoundary + AXIS_TIE_EPSILON) {
                y += stepY;
                tMaxY += tDeltaY;
            }
            if (z != targetZ && tMaxZ <= nextBoundary + AXIS_TIE_EPSILON) {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }

        private boolean currentVoxelBlocksLineOfSight() {
            boolean occluding = blockView.isBlockOccluding(x, y, z);
            spawnDebugParticle(occluding);
            if (!occluding) {
                return false;
            }
            occludingCount++;
            return occludingCount >= maxOccluding;
        }

        private boolean atTarget() {
            return x == targetX && y == targetY && z == targetZ;
        }

        private void spawnDebugParticle(boolean occluding) {
            if (!debug) {
                return;
            }
            ParticleSpawner.Colour colour = occluding
                    ? ParticleSpawner.Colour.RED
                    : ParticleSpawner.Colour.GREEN;
            spawnVoxelParticle(start, particleSpawner, x, y, z, colour);
        }
    }
}
