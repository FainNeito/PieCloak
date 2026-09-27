package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.implementations.ImmutableLocatableImpl;
import games.cubi.locatables.implementations.ImmutableSpatialImpl;
import games.cubi.locatables.implementations.ThreadSafeLocatable;
import games.cubi.raycastedantiesp.core.view.BlockView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RaycastTerminationTest {
    @Test
    void mixedDirectionIntegerEndpointCannotOvershootFinishedAxis() {
        Locatable start = new ImmutableLocatableImpl(UUID.randomUUID(), 0.5, 0.5, 0.5);
        assertTrue(RaycastUtil.raycast(start, new ImmutableSpatialImpl(30, -1, 0.5),
                new RaycastUtil.Settings(3, 24, 48, false, boundedEmptyView(), null)));
    }

    @Test
    void mixedDirectionIntegerEndpointAlsoTerminatesFarFromSpawn() {
        Locatable start = new ImmutableLocatableImpl(UUID.randomUUID(), -33366.5, 86.5, 55372.5);
        assertTrue(RaycastUtil.raycast(start, new ImmutableSpatialImpl(-33337, 85, 55372.5),
                new RaycastUtil.Settings(3, 24, 48, false, boundedEmptyView(), null)));
    }

    @Test
    void integerEndpointsTerminateInEveryOctantAndMajorAxis() {
        Locatable start = new ImmutableLocatableImpl(UUID.randomUUID(), 0.5, 0.5, 0.5);
        for (int axis = 0; axis < 3; axis++) {
            for (int signs = 0; signs < 8; signs++) {
                double[] end = new double[3];
                for (int component = 0; component < 3; component++) {
                    end[component] = (component == axis ? 30 : 1)
                            * ((signs & (1 << component)) == 0 ? -1 : 1);
                }
                assertTrue(RaycastUtil.raycast(start, new ImmutableSpatialImpl(end[0], end[1], end[2]),
                        new RaycastUtil.Settings(3, 24, 48, false, boundedEmptyView(), null)));
            }
        }
    }

    @Test
    void traversalDoesNotRereadMovingViewerCoordinates() {
        AtomicInteger xReads = new AtomicInteger();
        UUID world = UUID.randomUUID();
        Locatable start = new ThreadSafeLocatable(world, 0.5, 0.5, 0.5) {
            @Override
            public double x() {
                return xReads.incrementAndGet() == 1 ? 0.5 : 100000.5;
            }
        };
        assertTrue(RaycastUtil.raycast(start, new ImmutableSpatialImpl(30, -1, 0.5),
                new RaycastUtil.Settings(3, 24, 48, false, boundedEmptyView(), null)));
        assertEquals(1, xReads.get());
    }

    @Test
    void nonFiniteEndpointsFailClosedWithoutTraversal() {
        Locatable start = new ImmutableLocatableImpl(UUID.randomUUID(), 0.5, 0.5, 0.5);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(RaycastUtil.raycast(start, new ImmutableSpatialImpl(invalid, 1, 1),
                    new RaycastUtil.Settings(3, 24, 48, false, boundedEmptyView(), null)));
        }
    }

    private static BlockView boundedEmptyView() {
        AtomicInteger queries = new AtomicInteger();
        return (BlockView) Proxy.newProxyInstance(BlockView.class.getClassLoader(),
                new Class<?>[]{BlockView.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isBlockOccluding") && queries.incrementAndGet() > 200) {
                        throw new AssertionError("Ray escaped its finite voxel segment; worker would keep spinning");
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }
}
