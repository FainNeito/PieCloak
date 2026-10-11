/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.raycastedantiesp.packetevents.viewcontrollers;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import games.cubi.raycastedantiesp.core.view.EntityViewTransition;
import games.cubi.raycastedantiesp.packetevents.tracked.PacketEventsEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.UUID;

import static games.cubi.raycastedantiesp.core.tracked.NettyEntity.NO_LEASHER;
import static games.cubi.raycastedantiesp.packetevents.viewcontrollers.PacketEventsEntityViewController.ClientTransitionAction.DESTROY;
import static games.cubi.raycastedantiesp.packetevents.viewcontrollers.PacketEventsEntityViewController.ClientTransitionAction.NONE;
import static games.cubi.raycastedantiesp.packetevents.viewcontrollers.PacketEventsEntityViewController.ClientTransitionAction.SPAWN_AND_SYNC;
import static games.cubi.raycastedantiesp.packetevents.viewcontrollers.PacketEventsEntityViewController.ClientTransitionAction.SYNC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketEventsEntityViewControllerTest {
    @BeforeAll
    static void initialiseEntityTypeRegistry() {
        PacketEvents.setAPI(new EntityTypeTestAPI());
    }

    private static final class EntityTypeTestAPI extends PacketEventsAPI<Object> {
        @Override public void load() {}
        @Override public boolean isLoaded() { return true; }
        @Override public void init() {}
        @Override public boolean isInitialized() { return true; }
        @Override public void terminate() {}
        @Override public boolean isTerminated() { return false; }
        @Override public Object getPlugin() { return this; }
        @Override public ServerManager getServerManager() { return () -> ServerVersion.V_1_21_11; }
        @Override public com.github.retrooper.packetevents.manager.protocol.ProtocolManager getProtocolManager() { return null; }
        @Override public com.github.retrooper.packetevents.manager.player.PlayerManager getPlayerManager() { return null; }
        @Override public com.github.retrooper.packetevents.netty.NettyManager getNettyManager() { return null; }
        @Override public com.github.retrooper.packetevents.injector.ChannelInjector getInjector() { return null; }
    }

    @Test
    void allBoatAndRaftVariantsMustUsePassengerPositionTracking() {
        List<EntityType> boats = List.of(
                EntityTypes.BOAT, EntityTypes.CHEST_BOAT,
                EntityTypes.ACACIA_BOAT, EntityTypes.ACACIA_CHEST_BOAT,
                EntityTypes.BAMBOO_RAFT, EntityTypes.BAMBOO_CHEST_RAFT,
                EntityTypes.BIRCH_BOAT, EntityTypes.BIRCH_CHEST_BOAT,
                EntityTypes.CHERRY_BOAT, EntityTypes.CHERRY_CHEST_BOAT,
                EntityTypes.DARK_OAK_BOAT, EntityTypes.DARK_OAK_CHEST_BOAT,
                EntityTypes.JUNGLE_BOAT, EntityTypes.JUNGLE_CHEST_BOAT,
                EntityTypes.MANGROVE_BOAT, EntityTypes.MANGROVE_CHEST_BOAT,
                EntityTypes.OAK_BOAT, EntityTypes.OAK_CHEST_BOAT,
                EntityTypes.PALE_OAK_BOAT, EntityTypes.PALE_OAK_CHEST_BOAT,
                EntityTypes.SPRUCE_BOAT, EntityTypes.SPRUCE_CHEST_BOAT);
        for (EntityType boat : boats) {
            assertTrue(PacketEventsEntityViewController.isPassengerVehicle(boat), boat.getName().toString());
        }
    }

    @Test
    void bypassViewersSkipManagedPacketHandling() {
        assertFalse(PacketEventsEntityViewController.shouldProcessManagedPackets(true));
        assertTrue(PacketEventsEntityViewController.shouldProcessManagedPackets(false));
    }

    @Test
    void onlyRespawnPacketsTriggerClientStateInvalidation() {
        assertTrue(PacketEventsRespawnStateInvalidator.isRespawnPacket(
                PacketType.Play.Server.RESPAWN));
        assertFalse(PacketEventsRespawnStateInvalidator.isRespawnPacket(
                PacketType.Play.Server.JOIN_GAME));
        assertFalse(PacketEventsRespawnStateInvalidator.isRespawnPacket(
                PacketType.Play.Server.SPAWN_ENTITY));
    }

    @Test
    void passengerVehicleClassificationPreservesMinecartsAndExcludesUnrelatedEntities() {
        assertTrue(PacketEventsEntityViewController.isPassengerVehicle(EntityTypes.MINECART));
        assertTrue(PacketEventsEntityViewController.isPassengerVehicle(EntityTypes.CHEST_MINECART));
        assertFalse(PacketEventsEntityViewController.isPassengerVehicle(EntityTypes.VILLAGER));
        assertFalse(PacketEventsEntityViewController.isPassengerVehicle(EntityTypes.PLAYER));
        assertFalse(PacketEventsEntityViewController.isPassengerVehicle(EntityTypes.HORSE));
        assertFalse(PacketEventsEntityViewController.isPassengerVehicle(null));
        assertTrue(PacketEventsEntityViewController.shouldTrackRelationshipSupport(false, true));
        assertFalse(PacketEventsEntityViewController.shouldTrackRelationshipSupport(true, true));
        assertFalse(PacketEventsEntityViewController.shouldTrackRelationshipSupport(false, false));
    }

    @Test
    void playerSpawnsRemainManagedOutsideEntityTargetFiltering() {
        assertTrue(PacketEventsEntityViewController.shouldManageSpawnTarget(true, false, false));
        assertTrue(PacketEventsEntityViewController.shouldManageSpawnTarget(true, false, true));
        assertTrue(PacketEventsEntityViewController.shouldManageSpawnTarget(false, true, false));
        assertFalse(PacketEventsEntityViewController.shouldManageSpawnTarget(false, true, true));
        assertFalse(PacketEventsEntityViewController.shouldManageSpawnTarget(false, false, false));
    }

    @Test
    void retainingSeenEntitySuppressesHideWithoutDestroying() {
        assertEquals(NONE, PacketEventsEntityViewController.resolveClientTransitionAction(
                EntityViewTransition.Type.HIDE, true, true));
        assertEquals(SYNC, PacketEventsEntityViewController.resolveClientTransitionAction(
                EntityViewTransition.Type.SHOW, true, true));
    }

    @Test
    void disablingRetentionUsesDestroyAndRespawn() {
        assertEquals(DESTROY, PacketEventsEntityViewController.resolveClientTransitionAction(
                EntityViewTransition.Type.HIDE, true, false));
        assertEquals(SPAWN_AND_SYNC, PacketEventsEntityViewController.resolveClientTransitionAction(
                EntityViewTransition.Type.SHOW, false, false));
    }

    @Test
    void unseenEntityIsNeverDestroyedAndRequiresFirstSpawn() {
        assertEquals(NONE, PacketEventsEntityViewController.resolveClientTransitionAction(
                EntityViewTransition.Type.HIDE, false, true));
        assertEquals(SPAWN_AND_SYNC, PacketEventsEntityViewController.resolveClientTransitionAction(
                EntityViewTransition.Type.SHOW, false, true));
    }

    @Test
    void forgetTransitionDoesNotWriteClientPackets() {
        assertEquals(NONE, PacketEventsEntityViewController.resolveClientTransitionAction(
                EntityViewTransition.Type.FORGET, true, false));
    }

    @Test
    void staleVisibilityTransitionsAreIgnored() {
        assertFalse(PacketEventsEntityViewController.transitionMatchesCurrentVisibility(
                EntityViewTransition.Type.SHOW, false));
        assertFalse(PacketEventsEntityViewController.transitionMatchesCurrentVisibility(
                EntityViewTransition.Type.HIDE, true));
        assertTrue(PacketEventsEntityViewController.transitionMatchesCurrentVisibility(
                EntityViewTransition.Type.SHOW, true));
        assertTrue(PacketEventsEntityViewController.transitionMatchesCurrentVisibility(
                EntityViewTransition.Type.HIDE, false));
    }

    @Test
    void collectItemSuppressesHiddenOrNotYetClientVisibleReferences() {
        PacketEventsEntity entity = entity();
        entity.setVisible(true);
        entity.setClientVisible(true);
        assertFalse(PacketEventsEntityViewController.shouldSuppressCollectItemReference(entity));

        entity.setVisible(false);
        assertTrue(PacketEventsEntityViewController.shouldSuppressCollectItemReference(entity));

        entity.setVisible(true);
        entity.setClientVisible(false);
        assertTrue(PacketEventsEntityViewController.shouldSuppressCollectItemReference(entity));

        assertFalse(PacketEventsEntityViewController.shouldSuppressCollectItemReference(null));
    }

    @Test
    void sharedEntityFlagsUpdateSneakingAndGlowingState() {
        PacketEventsEntity entity = entity();

        PacketEventsEntityViewController.applyTrackedMetadata(entity, List.of(
                new EntityData<>(0, null, (byte) 0x42)
        ));

        assertTrue(entity.sneaking());
        assertTrue(entity.glowing());

        PacketEventsEntityViewController.applyTrackedMetadata(entity, List.of(
                new EntityData<>(0, null, (byte) 0)
        ));

        assertFalse(entity.sneaking());
        assertFalse(entity.glowing());
    }

    @Test
    void metadataWithoutSharedFlagsPreservesTrackedState() {
        PacketEventsEntity entity = entity();
        entity.setSneaking(true);
        entity.setGlowing(true);

        PacketEventsEntityViewController.applyTrackedMetadata(entity, List.of(
                new EntityData<>(1, null, (byte) 0)
        ));

        assertTrue(entity.sneaking());
        assertTrue(entity.glowing());
    }

    private static PacketEventsEntity entity() {
        return new PacketEventsEntity(null, 0, 0, 0, 1, UUID.randomUUID(), false, 0, true);
    }

    @Test
    void normalizesVanillaAndCompatibilityUnleashSentinels() {
        assertEquals(NO_LEASHER, PacketEventsEntityViewController.normalizeLeashHolderEntityID(0));
        assertEquals(NO_LEASHER, PacketEventsEntityViewController.normalizeLeashHolderEntityID(-1));
        assertEquals(1, PacketEventsEntityViewController.normalizeLeashHolderEntityID(1));
        assertEquals(-3, PacketEventsEntityViewController.normalizeLeashHolderEntityID(-3));
    }

}
