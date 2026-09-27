package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.implementations.ImmutableLocatableImpl;
import games.cubi.locatables.implementations.ImmutableSpatialImpl;
import games.cubi.locatables.implementations.ThreadSafeLocatable;
import games.cubi.raycastedantiesp.core.view.BlockView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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

    @TestFactory
    Stream<DynamicTest> integerEndpointsTerminateInEveryOctantAndMajorAxis() {
        return IntStream.range(0, 24).mapToObj(index -> DynamicTest.dynamicTest(
                "axis " + index / 8 + ", signs " + index % 8,
                () -> assertIntegerEndpoint(index / 8, index % 8)));
    }

    private static void assertIntegerEndpoint(int axis, int signs) {
        Locatable start = new ImmutableLocatableImpl(UUID.randomUUID(), 0.5, 0.5, 0.5);
        double[] end = new double[3];
        for (int component = 0; component < 3; component++) {
            end[component] = (component == axis ? 30 : 1)
                    * ((signs & (1 << component)) == 0 ? -1 : 1);
        }
        assertTrue(RaycastUtil.raycast(start, new ImmutableSpatialImpl(end[0], end[1], end[2]),
                new RaycastUtil.Settings(3, 24, 48, false, boundedEmptyView(), null)));
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

    @TestFactory
    Stream<DynamicTest> nonFiniteEndpointsFailClosedWithoutTraversal() {
        return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
                .map(invalid -> DynamicTest.dynamicTest("endpoint " + invalid,
                        () -> assertNonFiniteEndpoint(invalid)));
    }

    private static void assertNonFiniteEndpoint(double invalid) {
        Locatable start = new ImmutableLocatableImpl(UUID.randomUUID(), 0.5, 0.5, 0.5);
        assertFalse(RaycastUtil.raycast(start, new ImmutableSpatialImpl(invalid, 1, 1),
                new RaycastUtil.Settings(3, 24, 48, false, boundedEmptyView(), null)));
    }

    private static BlockView boundedEmptyView() {
        AtomicInteger queries = new AtomicInteger();
        return (BlockView) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{BlockView.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isBlockOccluding") && queries.incrementAndGet() > 200) {
                        throw new AssertionError("Ray escaped its finite voxel segment; worker would keep spinning");
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }
}
