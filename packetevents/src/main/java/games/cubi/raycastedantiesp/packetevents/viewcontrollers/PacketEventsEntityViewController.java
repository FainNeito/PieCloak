/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.raycastedantiesp.packetevents.viewcontrollers;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import games.cubi.raycastedantiesp.core.config.ConfigManager;
import games.cubi.raycastedantiesp.core.config.raycast.EntityConfig;
import games.cubi.raycastedantiesp.core.config.raycast.PlayerConfig;
import games.cubi.logs.Logger;
import games.cubi.raycastedantiesp.core.config.raycast.EntityTypeExclusions;
import games.cubi.raycastedantiesp.core.entity.EntityBypassRegistry;
import games.cubi.raycastedantiesp.core.tracked.NettyEntity;
import games.cubi.raycastedantiesp.core.tracked.TrackedEntity;
import games.cubi.raycastedantiesp.core.players.PlayerData;
import games.cubi.raycastedantiesp.core.players.PlayerRegistry;
import games.cubi.raycastedantiesp.core.policy.VisibilityExemptionPolicy;
import games.cubi.raycastedantiesp.core.utils.PrimitiveIntArrayList;
import games.cubi.raycastedantiesp.core.view.EntityView;
import games.cubi.raycastedantiesp.core.view.EntityViewTransition;
import games.cubi.raycastedantiesp.core.view.controller.PacketEntityViewController;
import games.cubi.raycastedantiesp.packetevents.tracked.PacketEventsEntity;
import games.cubi.raycastedantiesp.packetevents.replaydata.PacketEventsEntityReplayData;
import games.cubi.raycastedantiesp.packetevents.target.PacketEventsTargetFilter;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

import static games.cubi.raycastedantiesp.core.tracked.NettyEntity.NO_LEASHER;
import static games.cubi.raycastedantiesp.core.tracked.NettyEntity.NO_VEHICLE;

public abstract class PacketEventsEntityViewController extends PacketEntityViewController<PacketWrapper<?>> implements PacketListener {
    private static final int NO_ENTITY = -1; // HackyEntityIDGuard prevents -1 from ever being assigned to a real entity.
    private static final byte PROTOCOL_FLAG_SNEAKING = 0x02;
    private static final byte PROTOCOL_FLAG_GLOWING = 0x40;

    enum ClientTransitionAction {
        NONE,
        DESTROY,
        SPAWN_AND_SYNC,
        SYNC
    }

    private final IntSupplier CURRENT_TICK_SUPPLIER;
    private final PacketEventsCommonViewController COMMON;
    private final PacketEventsTargetFilter targetFilter;
    private final EntityTransitionRetryQueue<PacketWrapper<?>> transitionRetries = new EntityTransitionRetryQueue<>();
    private static PacketEventsEntityViewController SELF; //TODO Switch to LazyConstant once out of preview (see https://openjdk.org/jeps/526)

    public static PacketEventsEntityViewController get() {
        if (SELF == null) {
            SELF = (PacketEventsEntityViewController) PacketEntityViewController.get();
        }
        return SELF;
    }

    protected PacketEventsEntityViewController(IntSupplier currentTickSupplier, PacketEventsTargetFilter targetFilter) {
        this(currentTickSupplier, targetFilter, VisibilityExemptionPolicy.DISABLED);
    }

    protected PacketEventsEntityViewController(
            IntSupplier currentTickSupplier, PacketEventsTargetFilter targetFilter,
            VisibilityExemptionPolicy visibilityExemptionPolicy) {
        super(visibilityExemptionPolicy);
        this.CURRENT_TICK_SUPPLIER = currentTickSupplier;
        this.targetFilter = targetFilter == null ? PacketEventsTargetFilter.DISABLED : targetFilter;
        COMMON = PacketEventsCommonViewController.get(currentTickSupplier);
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        UUID viewerUUID = event.getUser().getUUID();
        if (viewerUUID != null) {
            transitionRetries.clear(viewerUUID);
        }
        handlePlayerDisconnect(viewerUUID);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        User viewer = event.getUser();
        UUID viewerUUID = viewer.getUUID();
        if (viewerUUID == null) {
            return;
        }

        PlayerData playerData = resolvePlayerData(event, viewerUUID);
        if (playerData == null) {
            return;
        }

        refreshVisibilityConfigs();
        int currentTick = CURRENT_TICK_SUPPLIER.getAsInt();
        processViewerPacket(event, viewer, playerData, currentTick);
        boolean withinBundle = updatePacketBundleState(event, playerData);
        schedulePendingTransitions(event, viewer, playerData, viewerUUID, withinBundle);
        playerData.nettyData().evictPendingPostSpawnTasksIfRequired(currentTick);
    }

    private static boolean updatePacketBundleState(PacketSendEvent event, PlayerData playerData) {
        if (event.getPacketType() == PacketType.Play.Server.BUNDLE) {
            return playerData.nettyData().togglePacketBundleState();
        }
        return playerData.nettyData().packetsAreWithinBundle();
    }

    private PlayerData resolvePlayerData(PacketSendEvent event, UUID viewerUUID) {
        if (event.getPacketType() != PacketType.Play.Server.JOIN_GAME) {
            return PlayerRegistry.getInstance().getPlayerData(viewerUUID);
        }
        transitionRetries.clear(viewerUUID);
        WrapperPlayServerJoinGame packet = new WrapperPlayServerJoinGame(event);
        int currentTick = CURRENT_TICK_SUPPLIER.getAsInt();
        PlayerData playerData = handlePlayPhaseLoginPacket(packet.getEntityId(), viewerUUID, currentTick);
        String worldName = packet.getWorldName();
        handleWorldStatePacket(viewerUUID, worldName, COMMON.resolveWorldUUID(worldName),
                packet.getDimensionType().getMinY(), currentTick);
        return playerData;
    }

    private void refreshVisibilityConfigs() {
        EntityConfig currentEntityConfig = ConfigManager.get().getEntityConfig();
        if (currentEntityConfig != entityConfig) {
            entityConfig = currentEntityConfig;
            hideOnSpawnEntityDistanceSquared =
                    currentEntityConfig.hideOnSpawnDistance() * currentEntityConfig.hideOnSpawnDistance();
        }
        PlayerConfig currentPlayerConfig = ConfigManager.get().getPlayerConfig();
        if (currentPlayerConfig != playerConfig) {
            playerConfig = currentPlayerConfig;
            hideOnSpawnPlayerDistanceSquared =
                    currentPlayerConfig.hideOnSpawnDistance() * currentPlayerConfig.hideOnSpawnDistance();
        }
    }

    private void processViewerPacket(
            PacketSendEvent event, User viewer, PlayerData playerData, int currentTick) {
        if (shouldProcessManagedPackets(playerData.hasBypassPermission())) {
            UUID world = COMMON.resolvePacketWorld(playerData, viewer);
            handleEntityPackets(event, viewer, playerData, world, currentTick);
            return;
        }
        handleBypassPacketLifecycle(event, playerData, currentTick);
        enableBypass(playerData, currentTick);
    }

    private void schedulePendingTransitions(
            PacketSendEvent event,
            User viewer,
            PlayerData playerData,
            UUID viewerUUID,
            boolean withinBundle) {
        if (withinBundle || !hasPendingTransitions(playerData, viewerUUID)) {
            return;
        }
        event.getTasksAfterSend().add(() -> {
            processDeferredDirectVisibility(playerData);
            processPendingEntityTransitions(playerData, viewer);
        });
    }

    private boolean hasPendingTransitions(PlayerData playerData, UUID viewerUUID) {
        return playerData.nettyData().hasDeferredDirectVisibilityEntities()
                || playerData.entityView().hasPendingTransitions()
                || playerData.playerView().hasPendingTransitions()
                || transitionRetries.hasPending(viewerUUID);
    }

    private void processDeferredDirectVisibility(PlayerData playerData) {
        int[] entityIDs = playerData.nettyData().drainDeferredDirectVisibilityEntityIDs();
        if (entityIDs.length == 0) {
            return;
        }
        int worldEpoch = playerData.acquireWorldEpoch();
        for (int entityID : entityIDs) {
            processDeferredDirectVisibilityEntity(playerData, entityID, worldEpoch);
        }
    }

    private void processDeferredDirectVisibilityEntity(
            PlayerData playerData, int entityID, int worldEpoch) {
        EntityView<?> view = deferredDirectVisibilityView(playerData, entityID);
        if (view == null) {
            return;
        }
        NettyEntity<?> entity = (NettyEntity<?>) view.getEntity(entityID);
        if (entity == null || entity.isSelfEntity()) {
            return;
        }
        processDirectEntityVisibilityNow(
                playerData,
                view,
                entity,
                worldEpoch,
                entity.visible() ? EntityViewTransition.Type.SHOW : EntityViewTransition.Type.HIDE);
    }

    private EntityView<?> deferredDirectVisibilityView(PlayerData playerData, int entityID) {
        if (playerData.entityView().exists(entityID)) {
            return playerData.entityView();
        }
        if (playerData.playerView().exists(entityID)) {
            return playerData.playerView();
        }
        return null;
    }

    private void processPendingEntityTransitions(PlayerData data, User viewer) {
        if (data.entityView().hasPendingTransitions()) {
            processEntityTransitions(data, viewer, cast(data.entityView()));
        }

        if (data.playerView().hasPendingTransitions()) {
            processEntityTransitions(data, viewer, cast(data.playerView()));
        }

        processTransitionRetries(data, viewer);
    }

    private void processTransitionRetries(PlayerData data, User viewer) {
        UUID viewerUUID = data.getPlayerUUID();
        int currentTick = CURRENT_TICK_SUPPLIER.getAsInt();
        for (EntityTransitionWork<PacketWrapper<?>> work : transitionRetries.drainDue(viewerUUID, currentTick)) {
            processEntityWorkSafely(data, viewer, work, currentTick);
        }
    }

    static boolean shouldProcessManagedPackets(boolean hasBypassPermission) {
        return !hasBypassPermission;
    }

    public void enableBypass(PlayerData playerData, int currentTick) {
        int worldEpoch = playerData.acquireWorldEpoch();
        revealBypassedView(playerData, playerData.entityView(), currentTick, worldEpoch);
        revealBypassedView(playerData, playerData.playerView(), currentTick, worldEpoch);
    }

    private void revealBypassedView(PlayerData playerData, EntityView<?> view, int currentTick, int worldEpoch) {
        for (UUID entityUUID : view.getKnownEntities()) {
            NettyEntity<?> entity = (NettyEntity<?>) view.getEntity(entityUUID);
            if (!requiresBypassReveal(entity)) {
                continue;
            }
            boolean recorded = view.recordDirectVisibility(entity, true, currentTick, worldEpoch);
            if (recorded || entity.visible() && !entity.clientVisible()) {
                processDirectEntityShow(playerData, view, entity, worldEpoch);
            }
        }
    }

    private static boolean requiresBypassReveal(NettyEntity<?> entity) {
        return entity != null && !entity.isSelfEntity() && (!entity.visible() || !entity.clientVisible());
    }

    private void handleBypassPacketLifecycle(PacketSendEvent event, PlayerData playerData, int currentTick) {
        if (event.getPacketType() == PacketType.Play.Server.DESTROY_ENTITIES) {
            handleDestroyEntities(new WrapperPlayServerDestroyEntities(event).getEntityIds(), playerData, currentTick);
        } else if (event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            transitionRetries.clear(playerData.getPlayerUUID());
        }
    }

    private void handleEntityPackets(PacketSendEvent event, User viewer, PlayerData playerData, UUID world, int currentTick) {
        //code readability helper constant
        final boolean REQUIRE_EVENT_CANCELLATION = true;
        switch (event.getPacketType()) {
            case PacketType.Play.Server.SPAWN_LIVING_ENTITY -> {
                Logger.error("Received spawn living entity packet. This packet type should not be used in modern Minecraft versions, and its presence likely indicates a protocol mapping issue. Viewer=" + viewer.getUUID() + " tick=" + currentTick, 2, PacketEventsEntityViewController.class);
                throw new RuntimeException("Spawn Living Entity packet appeared. This shouldn't exist");
                //if (handleLivingEntitySpawn(new WrapperPlayServerSpawnLivingEntity(event), playerData, world, currentTick) == REQUIRE_EVENT_CANCELLATION)
                 //   event.setCancelled(true);
            }
            case PacketType.Play.Server.SPAWN_ENTITY -> {
                WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(event);
                boolean isPlayer = packet.getEntityType().isInstanceOf(EntityTypes.PLAYER);
                if (shouldBypassSpawn(packet, isPlayer)) {
                    int entityID = packet.getEntityId();
                    handleBypassedEntitySpawn(entityID, playerData, currentTick);
                    event.getTasksAfterSend().add(() -> replayBypassedEntityRelationships(viewer, playerData, entityID));
                    return;
                }
                Logger.debug("Spawning entity for player " + viewer.getUUID() + " entity #" + packet.getEntityId() + " tick=" + currentTick + " type=" + packet.getEntityType().getName());
                int entityID = packet.getEntityId();
                if (handleEntitySpawn(packet, entityID, isPlayer, playerData, world, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
                event.getTasksAfterSend().add(() -> replayTrackedEntityRelationships(viewer, playerData, entityID));
            }
            case PacketType.Play.Server.COLLECT_ITEM -> {
                WrapperPlayServerCollectItem packet = new WrapperPlayServerCollectItem(event);
                if (shouldSuppressCollectItem(
                        playerData, packet.getCollectedEntityId(), packet.getCollectorEntityId())) {
                    event.setCancelled(true);
                }
            }
            case PacketType.Play.Server.ENTITY_ANIMATION -> {
                WrapperPlayServerEntityAnimation packet = new WrapperPlayServerEntityAnimation(event);
                if (!isBypassed(packet.getEntityId()) && handleEntityAnimation(packet.getEntityId(), playerData) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_STATUS -> {
                WrapperPlayServerEntityStatus packet = new WrapperPlayServerEntityStatus(event);
                if (!isBypassed(packet.getEntityId()) && handleEntityEvent(packet.getEntityId(), playerData) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.HURT_ANIMATION -> {
                WrapperPlayServerHurtAnimation packet = new WrapperPlayServerHurtAnimation(event);
                if (!isBypassed(packet.getEntityId()) && handleHurtAnimation(packet.getEntityId(), playerData) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.SPAWN_PAINTING -> {
                throw new RuntimeException("Spawn Painting packet appeared. This shouldn't exist");
                //if (handlePaintingSpawn(new WrapperPlayServerSpawnPainting(event), playerData, world, currentTick) == REQUIRE_EVENT_CANCELLATION)
                //    event.setCancelled(true);
            }
            case PacketType.Play.Server.SPAWN_PLAYER -> {
                Logger.error("Received spawn player entity packet. This packet type should not be used in modern Minecraft versions, and its presence likely indicates a protocol mapping issue. Viewer=" + viewer.getUUID() + " tick=" + currentTick, 2, PacketEventsEntityViewController.class);
                throw new RuntimeException("Spawn Player packet appeared. This shouldn't exist");
                //if (handlePlayerSpawn(new WrapperPlayServerSpawnPlayer(event), playerData, world, currentTick) == REQUIRE_EVENT_CANCELLATION)
                //    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_RELATIVE_MOVE -> {
                WrapperPlayServerEntityRelativeMove packet = new WrapperPlayServerEntityRelativeMove(event);
                if (!isBypassed(packet.getEntityId()) && handleRelativeMove(packet, playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION -> {
                WrapperPlayServerEntityRelativeMoveAndRotation packet = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
                if (!isBypassed(packet.getEntityId()) && handleRelativeMoveAndRotation(packet, playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_TELEPORT -> {
                WrapperPlayServerEntityTeleport packet = new WrapperPlayServerEntityTeleport(event);
                if (!isBypassed(packet.getEntityId()) && handleTeleport(packet, playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_POSITION_SYNC -> {
                WrapperPlayServerEntityPositionSync packet = new WrapperPlayServerEntityPositionSync(event);
                if (!isBypassed(packet.getId()) && handlePositionSync(packet, playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_ROTATION -> {
                WrapperPlayServerEntityRotation packet = new WrapperPlayServerEntityRotation(event);
                if (!isBypassed(packet.getEntityId()) && handleEntityRotation(packet, playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_HEAD_LOOK -> {
                WrapperPlayServerEntityHeadLook packet = new WrapperPlayServerEntityHeadLook(event);
                if (!isBypassed(packet.getEntityId()) && handleEntityHeadLook(packet, playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_METADATA -> {
                WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
                if (!isBypassed(packet.getEntityId()) && handleEntityMetadata(packet, packet.getEntityId(), playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.REMOVE_ENTITY_EFFECT -> {
                WrapperPlayServerRemoveEntityEffect packet = new WrapperPlayServerRemoveEntityEffect(event);
                if (!isBypassed(packet.getEntityId()) && handleRemoveEntityEffect(packet, packet.getEntityId(), playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_EQUIPMENT -> {
                WrapperPlayServerEntityEquipment packet = new WrapperPlayServerEntityEquipment(event);
                if (!isBypassed(packet.getEntityId()) && handleEntityEquipment(packet, packet.getEntityId(), playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_VELOCITY -> {
                WrapperPlayServerEntityVelocity packet = new WrapperPlayServerEntityVelocity(event);
                if (!isBypassed(packet.getEntityId()) && handleEntityVelocity(packet, packet.getEntityId(), playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ENTITY_EFFECT -> {
                WrapperPlayServerEntityEffect packet = new WrapperPlayServerEntityEffect(event);
                if (!isBypassed(packet.getEntityId()) && handleEntityEffect(packet, packet.getEntityId(), playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.SET_PASSENGERS -> {
                WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
                if (handleEntityPassengers(packet.getEntityId(), packet.getPassengers(), playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.DESTROY_ENTITIES -> {
                WrapperPlayServerDestroyEntities packet = new WrapperPlayServerDestroyEntities(event);
                transitionRetries.clearEntities(playerData.getPlayerUUID(), packet.getEntityIds());
                handleDestroyEntities(packet.getEntityIds(), playerData, currentTick);
            }
            case PacketType.Play.Server.UPDATE_ATTRIBUTES -> {
                WrapperPlayServerUpdateAttributes wrapper = new WrapperPlayServerUpdateAttributes(event);
                if (!isBypassed(wrapper.getEntityId()) && handleAttributeUpdate(wrapper, wrapper.getEntityId(), playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.ATTACH_ENTITY -> {
                WrapperPlayServerAttachEntity wrapper = new WrapperPlayServerAttachEntity(event);
                int holderEntityID = normalizeLeashHolderEntityID(wrapper.getHoldingId());
                if (handleLeashEntity(wrapper.getAttachedId(), holderEntityID, playerData, currentTick) == REQUIRE_EVENT_CANCELLATION)
                    event.setCancelled(true);
            }
            case PacketType.Play.Server.RESPAWN -> transitionRetries.clear(playerData.getPlayerUUID());
            default -> {}
        }
    }

    static int normalizeLeashHolderEntityID(int holderEntityID) {
        // Vanilla writes 0 when the holder is null:
        // https://mcsrc.dev/1/26.2/net/minecraft/network/protocol/game/ClientboundSetEntityLinkPacket#L19
        return holderEntityID == 0 || holderEntityID == -1 ? NO_LEASHER : holderEntityID;
    }

    private boolean shouldBypassSpawn(WrapperPlayServerSpawnEntity packet, boolean isPlayer) {
        int entityID = packet.getEntityId();
        if (EntityBypassRegistry.isRelationshipSupportEntity(entityID)) {
            return false;
        }
        if (isBypassed(entityID)) {
            return true;
        }
        if (shouldTrackRelationshipSupport(isPlayer, isPassengerVehicle(packet.getEntityType()))) {
            EntityBypassRegistry.addRelationshipSupportEntity(entityID);
            return false;
        }
        boolean excludedByUpstream = EntityTypeExclusions.excludes(getPrimitiveEntityType(packet.getEntityType()));
        boolean managedByPieCloak = targetFilter.shouldCullEntity(packet.getEntityType(), isPlayer);
        if (shouldManageSpawnTarget(isPlayer, managedByPieCloak, excludedByUpstream)) {
            return false;
        }
        EntityBypassRegistry.addEntity(entityID);
        return true;
    }

    static boolean shouldManageSpawnTarget(boolean isPlayer, boolean managedByPieCloak, boolean excludedByUpstream) {
        return isPlayer || managedByPieCloak && !excludedByUpstream;
    }

    static boolean isBypassed(int entityID) {
        return EntityBypassRegistry.isBypassed(entityID);
    }

    static boolean isPassengerVehicle(EntityType entityType) {
        // Track excluded vehicles so their movement can update managed passengers.
        // PacketEvents uses BOAT as the parent for wood boats and bamboo rafts.
        return entityType != null && (entityType.isInstanceOf(EntityTypes.MINECART_ABSTRACT)
                || entityType.isInstanceOf(EntityTypes.BOAT));
    }

    static boolean shouldTrackRelationshipSupport(boolean isPlayer, boolean passengerVehicle) {
        return !isPlayer && passengerVehicle;
    }

    protected NettyEntity<?> createSelfEntity(PlayerData ownData, int entityID, UUID playerUUID) {
        return PacketEventsEntity.createSelfEntity(ownData, entityID, playerUUID);
    }

    @Override
    protected void processDirectEntityShow(PlayerData playerData, EntityView<?> view, NettyEntity<?> entity, int worldEpoch) {
        processDirectEntityVisibility(playerData, view, entity, worldEpoch, EntityViewTransition.Type.SHOW);
    }

    @Override
    protected void processDirectEntityHide(PlayerData playerData, EntityView<?> view, NettyEntity<?> entity, int worldEpoch) {
        processDirectEntityVisibility(playerData, view, entity, worldEpoch, EntityViewTransition.Type.HIDE);
    }

    private void processDirectEntityVisibility(
            PlayerData playerData, EntityView<?> view, NettyEntity<?> entity,
            int worldEpoch, EntityViewTransition.Type type) {
        if (playerData.nettyData().packetsAreWithinBundle()) {
            playerData.nettyData().deferDirectVisibilityEntity(entity.entityID());
            return;
        }
        processDirectEntityVisibilityNow(playerData, view, entity, worldEpoch, type);
    }

    private void processDirectEntityVisibilityNow(
            PlayerData playerData, EntityView<?> view, NettyEntity<?> entity,
            int worldEpoch, EntityViewTransition.Type type) {
        Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(playerData.getPlayerUUID());
        if (channel == null) {
            return;
        }
        User viewer = PacketEvents.getAPI().getProtocolManager().getUser(channel);
        if (viewer == null) {
            return;
        }
        beginEntityTransition(
                playerData,
                viewer,
                cast(view),
                type,
                entity,
                worldEpoch,
                CURRENT_TICK_SUPPLIER.getAsInt()
        );
    }

    @Override
    protected @NotNull NettyEntity<?> processEntitySpawn(PlayerData playerData, PacketWrapper<?> packetWrapper, UUID world, int currentTick) {
        WrapperPlayServerSpawnEntity packet = (WrapperPlayServerSpawnEntity) packetWrapper;
        if (packet.getUUID().isEmpty()) {
            Logger.errorAndReturn(new RuntimeException("Entity UUID null when handling spawn entity packet, id=" + packet.getEntityId() + " tick=" + currentTick), 2, PacketEventsEntityViewController.class);
            throw new IllegalStateException("This statement should be unreachable. Logger.errorAndReturn failed to fire.");
        }
        UUID entityUUID = packet.getUUID().get();

        PacketEventsEntity entity = trackEntitySpawn(playerData, entityUUID, packet.getEntityId(), world,
                packet.getPosition().getX(), packet.getPosition().getY(), packet.getPosition().getZ(), packet.getEntityType());
        Vector3d velocity = packet.getVelocity().orElseGet(Vector3d::zero);
        entity.setEntityData(packet.getData())
                .setYaw(packet.getYaw())
                .setPitch(packet.getPitch())
                .setHeadYaw(packet.getHeadYaw())
                .setVelocity(velocity.getX(), velocity.getY(), velocity.getZ());
        return entity;
    }

    @Override
    protected int processRelativeMovePacket(PacketWrapper<?> packetWrapper, PlayerData playerData, int currentTick) {
        WrapperPlayServerEntityRelativeMove packet = (WrapperPlayServerEntityRelativeMove) packetWrapper;
        int entityID = packet.getEntityId();

        NettyEntity<?> entity = playerData.entityFromID(entityID);
        if (entity == null) {
            Logger.warning("Received relative move packet for unknown entity, id=" + entityID + ". Queuing retry.", 6, PacketEventsEntityViewController.class);
            playerData.nettyData().addPostEntitySpawnTask(entityID, PEEntityStateReconciliationTask.relativeMove(
                    playerData, entityID, packet.getDeltaX(), packet.getDeltaY(), packet.getDeltaZ(), packet.isOnGround(), currentTick));
            return entityID;
        }
        applyRelativeMove(entity, packet.getDeltaX(), packet.getDeltaY(), packet.getDeltaZ(), packet.isOnGround());

        return entityID;
    }

    @Override
    protected int processRelativeMoveAndRotationPacket(PacketWrapper<?> packet, PlayerData playerData, int currentTick) {
        WrapperPlayServerEntityRelativeMoveAndRotation packetWrapper = (WrapperPlayServerEntityRelativeMoveAndRotation) packet;
        int entityID = packetWrapper.getEntityId();

        NettyEntity<?> entity = playerData.entityFromID(entityID);
        if (entity == null) {
            Logger.warning("Received relative move and rotation packet for unknown entity, id=" + entityID + ". Queuing retry.", 6, PacketEventsEntityViewController.class);
            playerData.nettyData().addPostEntitySpawnTask(entityID, PEEntityStateReconciliationTask.relativeMoveAndRotation(
                    playerData, entityID, packetWrapper.getDeltaX(), packetWrapper.getDeltaY(), packetWrapper.getDeltaZ(),
                    packetWrapper.getYaw(), packetWrapper.getPitch(), packetWrapper.isOnGround(), currentTick));
            return entityID;
        }
        applyRelativeMoveAndRotation(entity, packetWrapper.getDeltaX(), packetWrapper.getDeltaY(), packetWrapper.getDeltaZ(),
                packetWrapper.getYaw(), packetWrapper.getPitch(), packetWrapper.isOnGround());

        return entityID;
    }

    @Override
    protected int processTeleportPacket(PacketWrapper<?> packet, PlayerData playerData, int currentTick) {
        WrapperPlayServerEntityTeleport packetWrapper = (WrapperPlayServerEntityTeleport) packet;
        int entityID = packetWrapper.getEntityId();

        Vector3d position = packetWrapper.getPosition();
        Vector3d velocity = packetWrapper.getDeltaMovement();
        NettyEntity<?> entity = playerData.entityFromID(entityID);
        if (entity == null) {
            Logger.warning("Received teleport packet for unknown entity, id=" + entityID + ". Queuing retry.", 6, PacketEventsEntityViewController.class);
            playerData.nettyData().addPostEntitySpawnTask(entityID, PEEntityStateReconciliationTask.teleport(
                    playerData, entityID, position.getX(), position.getY(), position.getZ(), packetWrapper.getYaw(), packetWrapper.getPitch(),
                    velocity.x, velocity.y, velocity.z, packetWrapper.isOnGround(), currentTick));
            return entityID;
        }
        applyTeleport(entity, position.getX(), position.getY(), position.getZ(), packetWrapper.getYaw(), packetWrapper.getPitch(),
                velocity.x, velocity.y, velocity.z, packetWrapper.isOnGround());

        return entityID;
    }

    @Override
    protected int processPositionSyncPacket(PacketWrapper<?> packet, PlayerData playerData, int currentTick) {
        WrapperPlayServerEntityPositionSync packetWrapper = (WrapperPlayServerEntityPositionSync) packet;
        int entityID = packetWrapper.getId();

        EntityPositionData values = packetWrapper.getValues();
        Vector3d position = values.getPosition();
        Vector3d velocity = values.getDeltaMovement();
        NettyEntity<?> entity = playerData.entityFromID(entityID);
        if (entity == null) {
            Logger.warning("Received position sync packet for unknown entity, id=" + entityID + ". Queuing retry.", 6, PacketEventsEntityViewController.class);
            playerData.nettyData().addPostEntitySpawnTask(entityID, PEEntityStateReconciliationTask.positionSync(
                    playerData, entityID, position.getX(), position.getY(), position.getZ(), values.getYaw(), values.getPitch(),
                    velocity.x, velocity.y, velocity.z, packetWrapper.isOnGround(), currentTick));
            return entityID;
        }
        applyPositionSync(entity, position.getX(), position.getY(), position.getZ(), values.getYaw(), values.getPitch(),
                velocity.x, velocity.y, velocity.z, packetWrapper.isOnGround());

        return entityID;
    }

    @Override
    protected void processTrackedMetadata(PacketWrapper<?> packet, NettyEntity<?> entity) {
        WrapperPlayServerEntityMetadata metadataPacket = (WrapperPlayServerEntityMetadata) packet;
        applyTrackedMetadata(entity, metadataPacket.getEntityMetadata());
    }

    static boolean shouldSuppressCollectItem(
            PlayerData playerData, int collectedEntityID, int collectorEntityID) {
        return isHiddenCollectItemReference(playerData, collectedEntityID)
                || isHiddenCollectItemReference(playerData, collectorEntityID);
    }

    private static boolean isHiddenCollectItemReference(PlayerData playerData, int entityID) {
        if (playerData.nettyData().isSelfEntityID(entityID)) {
            return false;
        }
        NettyEntity<?> entity = null;
        if (playerData.entityView().exists(entityID)) {
            entity = (NettyEntity<?>) playerData.entityView().getEntity(entityID);
        } else if (playerData.playerView().exists(entityID)) {
            entity = (NettyEntity<?>) playerData.playerView().getEntity(entityID);
        }
        return shouldSuppressCollectItemReference(entity);
    }

    static boolean shouldSuppressCollectItemReference(NettyEntity<?> entity) {
        return entity != null && (!entity.visible() || !entity.clientVisible());
    }

    static void applyTrackedMetadata(NettyEntity<?> entity, List<EntityData<?>> metadata) {
        if (metadata == null) {
            return;
        }
        for (EntityData<?> value : metadata) {
            if (value.getIndex() != 0) {
                continue;
            }
            if (!(value.getValue() instanceof Byte protocolFlags)) {
                Logger.warning("Entity metadata index 0 did not contain a byte for entity id=" + entity.entityID() + ".", 4, PacketEventsEntityViewController.class);
                return;
            }
            entity.setSneaking((protocolFlags & PROTOCOL_FLAG_SNEAKING) != 0);
            entity.setGlowing((protocolFlags & PROTOCOL_FLAG_GLOWING) != 0);
            return;
        }
    }

    static void applyRelativeMove(NettyEntity<?> entity, double deltaX, double deltaY, double deltaZ, boolean onGround) {
        entity.add(deltaX, deltaY, deltaZ);
        entity.setOnGround(onGround);
    }

    static void applyRelativeMoveAndRotation(NettyEntity<?> entity, double deltaX, double deltaY, double deltaZ,
                                             float yaw, float pitch, boolean onGround) {
        entity.add(deltaX, deltaY, deltaZ);
        entity.setYaw(yaw).setPitch(pitch).setOnGround(onGround);
    }

    static void applyTeleport(NettyEntity<?> entity, double x, double y, double z, float yaw, float pitch,
                              double velocityX, double velocityY, double velocityZ, boolean onGround) {
        entity.setPosition(x, y, z);
        entity.setYaw(yaw).setPitch(pitch).setVelocity(velocityX, velocityY, velocityZ).setOnGround(onGround);
    }

    static void applyPositionSync(NettyEntity<?> entity, double x, double y, double z, float yaw, float pitch,
                                  double velocityX, double velocityY, double velocityZ, boolean onGround) {
        entity.setPosition(x, y, z);
        entity.setYaw(yaw).setPitch(pitch).setVelocity(velocityX, velocityY, velocityZ).setOnGround(onGround);
    }

    static void applyRotation(NettyEntity<?> entity, float yaw, float pitch, boolean onGround) {
        entity.setYaw(yaw).setPitch(pitch).setOnGround(onGround);
    }

    static void applyHeadLook(NettyEntity<?> entity, float headYaw) {
        entity.setHeadYaw(headYaw);
    }

    static void applyVelocity(NettyEntity<?> entity, double velocityX, double velocityY, double velocityZ) {
        entity.setVelocity(velocityX, velocityY, velocityZ);
    }

    @Override
    protected void cachePacket(PacketWrapper<?> packet, int entityID, PlayerData playerData, int currentTick) {
        if (playerData.nettyData().isSelfEntityID(entityID)) {
            return;
        }
        NettyEntity<?> entity = playerData.entityFromID(entityID);
        if (entity == null) {
            Logger.warning("Attempted to cache packet for unknown entity, id=" + entityID + " packet=" + packet.getClass().getSimpleName() + ". Queuing retry.", 6, PacketEventsEntityViewController.class);
            playerData.nettyData().addPostEntitySpawnTask(entityID, new PECacheablePacketReconciliationTask(playerData, entityID, packet, currentTick));
            return;
        }
        ensureReplayData((PacketEventsEntity) entity).addPacket(packet);
    }

    @Override
    protected int processRotationPacket(PacketWrapper<?> packet, PlayerData playerData, int currentTick) {
        WrapperPlayServerEntityRotation packetWrapper = (WrapperPlayServerEntityRotation) packet;
        int entityID = packetWrapper.getEntityId();

        NettyEntity<?> entity = playerData.entityFromID(entityID);
        if (entity == null) {
            Logger.warning("Received rotation packet for unknown entity, id=" + entityID + ". Queuing retry.", 6, PacketEventsEntityViewController.class);
            playerData.nettyData().addPostEntitySpawnTask(entityID, PEEntityStateReconciliationTask.rotation(
                    playerData, entityID, packetWrapper.getYaw(), packetWrapper.getPitch(), packetWrapper.isOnGround(), currentTick));
            return entityID;
        }
        applyRotation(entity, packetWrapper.getYaw(), packetWrapper.getPitch(), packetWrapper.isOnGround());

        return entityID;
    }

    @Override
    protected int processHeadLookPacket(PacketWrapper<?> packet, PlayerData playerData, int currentTick) {
        WrapperPlayServerEntityHeadLook packetWrapper = (WrapperPlayServerEntityHeadLook) packet;
        int entityID = packetWrapper.getEntityId();

        NettyEntity<?> entity = playerData.entityFromID(entityID);
        if (entity == null) {
            Logger.warning("Received head look packet for unknown entity, id=" + entityID + ". Queuing retry.", 6, PacketEventsEntityViewController.class);
            playerData.nettyData().addPostEntitySpawnTask(entityID, PEEntityStateReconciliationTask.headLook(
                    playerData, entityID, packetWrapper.getHeadYaw(), currentTick));
            return entityID;
        }
        applyHeadLook(entity, packetWrapper.getHeadYaw());

        return entityID;
    }

    @Override
    protected int processEntityVelocityPacket(PacketWrapper<?> packet, PlayerData playerData, int currentTick) {
        WrapperPlayServerEntityVelocity packetWrapper = (WrapperPlayServerEntityVelocity) packet;
        int entityID = packetWrapper.getEntityId();

        NettyEntity<?> entity = playerData.entityFromID(entityID);
        if (entity == null) {
            Logger.warning("Received velocity packet for unknown entity, id=" + entityID + ". Queuing retry.", 6, PacketEventsEntityViewController.class);
            playerData.nettyData().addPostEntitySpawnTask(entityID, PEEntityStateReconciliationTask.velocity(
                    playerData, entityID, packetWrapper.getVelocity().getX(), packetWrapper.getVelocity().getY(), packetWrapper.getVelocity().getZ(), currentTick));
            return entityID;
        }
        applyVelocity(entity, packetWrapper.getVelocity().getX(), packetWrapper.getVelocity().getY(), packetWrapper.getVelocity().getZ());

        return entityID;
    }

    @Override
    protected void sendEntityPassengerPacket(int vehicle, IntArrayList passengers, PlayerData playerData) {
        NettyEntity<?> entity = playerData.entityFromID(vehicle);
        if (entity == null) {
            Logger.error("Attempted to send passenger packet for unknown entity, id=" + vehicle, 2, PacketEventsEntityViewController.class);
            return;
        }
        WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(vehicle, passengers.toIntArray());
        Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(playerData.getPlayerUUID());
        PacketEvents.getAPI().getProtocolManager().getUser(channel).writePacketSilently(packet);
    }

    private PacketEventsEntity trackEntitySpawn(PlayerData playerData, UUID entityUUID, int entityID, UUID world, double x, double y, double z, EntityType entityType) {
        PacketEventsEntity entity = new PacketEventsEntity(playerData, x, y, z, entityID, entityUUID, false, getPrimitiveEntityType(entityType), true /*default value as this is handled in PacketEntityViewController*/);
        ensureReplayData(entity);
        return entity;
    }

    private void processEntityTransitions(PlayerData data, User viewer, EntityView<PacketEventsEntity> entityView) {
        entityView.drainTransitions((type, transitionEntity, transitionWorldEpoch) ->
                beginEntityTransition(
                        data,
                        viewer,
                        entityView,
                        type,
                        transitionEntity,
                        transitionWorldEpoch,
                        CURRENT_TICK_SUPPLIER.getAsInt()
                ));
    }

    private void beginEntityTransition(
            PlayerData data,
            User viewer,
            EntityView<PacketEventsEntity> entityView,
            EntityViewTransition.Type type,
            TrackedEntity<?> transitionEntity,
            int transitionWorldEpoch,
            int currentTick
    ) {
        EntityTransitionWork<PacketWrapper<?>> superseded = transitionRetries.cancel(
                data.getPlayerUUID(), entityView, transitionEntity.entityUUID()
        );
        Boolean confirmedClientVisibility = superseded != null
                && superseded.entity() == transitionEntity
                && superseded.worldEpoch() == transitionWorldEpoch
                ? superseded.confirmedClientVisibility()
                : null;
        EntityTransitionWork<PacketWrapper<?>> work = createEntityTransitionWork(
                data, entityView, type, transitionEntity, transitionWorldEpoch, confirmedClientVisibility
        );
        if (work != null) {
            processEntityWorkSafely(data, viewer, work, currentTick);
        }
    }

    private @Nullable EntityTransitionWork<PacketWrapper<?>> createEntityTransitionWork(
            PlayerData data,
            EntityView<PacketEventsEntity> entityView,
            EntityViewTransition.Type type,
            TrackedEntity<?> transitionEntity,
            int transitionWorldEpoch,
            @Nullable Boolean confirmedClientVisibility
    ) {
        int worldEpoch = data.acquireWorldEpoch();
        PacketEventsEntity entity = currentTransitionEntity(
                entityView, transitionEntity, transitionWorldEpoch, worldEpoch);
        if (entity == null || !transitionMatchesCurrentVisibility(type, entity.visible())) {
            return null;
        }
        if (entity.isSelfEntity()) {
            logSkippedSelfTransition(data, entity);
            return null;
        }

        applySupersededClientVisibility(entity, confirmedClientVisibility);
        EntityTransitionPlan<PacketWrapper<?>> plan = buildTransitionPlan(data, entityView, type, entity);
        if (plan.isEmpty()) {
            return null;
        }
        return new EntityTransitionWork<>(
                data.getPlayerUUID(), entityView, type, entity, worldEpoch, plan);
    }

    private static @Nullable PacketEventsEntity currentTransitionEntity(
            EntityView<PacketEventsEntity> entityView,
            TrackedEntity<?> transitionEntity,
            int transitionWorldEpoch,
            int currentWorldEpoch) {
        if (!(transitionEntity instanceof PacketEventsEntity entity)
                || transitionWorldEpoch != currentWorldEpoch
                || entityView.getEntity(entity.entityUUID()) != entity) {
            return null;
        }
        return entity;
    }

    @SuppressWarnings("PMD.GuardLogStatement") // CubiLogging performs its own level filtering.
    private static void logSkippedSelfTransition(PlayerData data, PacketEventsEntity entity) {
        Logger.warning("PacketEvents.processEntityTransitions skipped self entity viewer=" + data.getPlayerUUID()
                + " target=" + entity.entityUUID(), 2, PacketEventsEntityViewController.class);
    }

    private static void applySupersededClientVisibility(
            PacketEventsEntity entity, @Nullable Boolean confirmedClientVisibility) {
        if (confirmedClientVisibility != null) {
            // The superseded repair already committed spawn/destroy to the client. Repair local state
            // before resolving the opposite transition so it cannot omit a required spawn or destroy.
            entity.setClientVisible(confirmedClientVisibility);
        }
    }

    private EntityTransitionPlan<PacketWrapper<?>> buildTransitionPlan(
            PlayerData data,
            EntityView<PacketEventsEntity> entityView,
            EntityViewTransition.Type type,
            PacketEventsEntity entity) {
        ClientTransitionAction action = resolveClientTransitionAction(
                type,
                entity.clientVisible(),
                getCorrectConfig(entityView).keepClientEntityWhenHidden()
        );
        return switch (action) {
            case DESTROY -> EntityTransitionPlan.hide(
                    new WrapperPlayServerDestroyEntities(entity.entityID()));
            case SPAWN_AND_SYNC -> buildEntityShowPlan(data, entity, true);
            case SYNC -> buildEntityShowPlan(data, entity, false);
            case NONE -> EntityTransitionPlan.empty();
        };
    }

    private boolean entityTransitionIsCurrent(PlayerData data, EntityTransitionWork<PacketWrapper<?>> work) {
        if (!data.isConnected() || data.acquireWorldEpoch() != work.worldEpoch()) {
            return false;
        }
        EntityView<?> currentView = work.view().isPlayerView() ? data.playerView() : data.entityView();
        return currentView == work.view()
                && work.view().getEntity(work.entity().entityUUID()) == work.entity()
                && work.view().getEntity(work.entity().entityID()) == work.entity()
                && !work.entity().isSelfEntity()
                && transitionMatchesCurrentVisibility(work.type(), work.entity().visible());
    }

    private void processEntityWorkSafely(
            PlayerData data,
            User viewer,
            EntityTransitionWork<PacketWrapper<?>> work,
            int currentTick
    ) {
        if (!entityTransitionIsCurrent(data, work)) {
            return;
        }
        try {
            work.execute(
                    packet -> viewer.writePacketSilently(packet),
                    visible -> work.entity().setClientVisible(visible)
            );
        } catch (Exception exception) {
            handleEntityWorkFailure(data, work, currentTick, exception);
        }
    }

    private void handleEntityWorkFailure(
            PlayerData data,
            EntityTransitionWork<PacketWrapper<?>> work,
            int currentTick,
            Exception exception) {
        if (!entityTransitionIsCurrent(data, work)) {
            return;
        }
        if (!work.recordFailure(currentTick)) {
            logEntityWorkFailure(data, work, exception, "abandoned at the retry bound");
            return;
        }

        EntityTransitionRetryQueue.RetryResult retryResult = transitionRetries.retry(work);
        if (retryResult == EntityTransitionRetryQueue.RetryResult.SUPERSEDED) {
            return;
        }
        boolean capacityRejected = retryResult == EntityTransitionRetryQueue.RetryResult.CAPACITY_REJECTED;
        if (work.failures() == 1 || capacityRejected) {
            String outcome = capacityRejected
                    ? "abandoned because retry capacity was exhausted"
                    : "queued with bounded backoff";
            logEntityWorkFailure(data, work, exception, outcome);
        }
    }

    @SuppressWarnings("PMD.GuardLogStatement") // CubiLogging performs its own level filtering.
    private static void logEntityWorkFailure(
            PlayerData data,
            EntityTransitionWork<PacketWrapper<?>> work,
            Exception exception,
            String outcome) {
        Logger.error("Entity visibility reconciliation failed and was " + outcome
                + ". viewer=" + data.getPlayerUUID()
                + " entityUUID=" + work.entity().entityUUID()
                + " entityID=" + work.entity().entityID()
                + " transition=" + work.type()
                + " nextStage=" + work.nextStage()
                + " failures=" + work.failures(), exception, 1, PacketEventsEntityViewController.class);
    }

    static boolean transitionMatchesCurrentVisibility(EntityViewTransition.Type type, boolean visible) {
        return switch (type) {
            case SHOW -> visible;
            case HIDE -> !visible;
            case FORGET -> true;
        };
    }

    static ClientTransitionAction resolveClientTransitionAction(EntityViewTransition.Type type, boolean clientVisible,
                                                                  boolean keepClientEntityWhenHidden) {
        return switch (type) {
            case HIDE -> clientVisible && !keepClientEntityWhenHidden ? ClientTransitionAction.DESTROY : ClientTransitionAction.NONE;
            case SHOW -> clientVisible ? ClientTransitionAction.SYNC : ClientTransitionAction.SPAWN_AND_SYNC;
            case FORGET -> ClientTransitionAction.NONE;
        };
    }

    private PacketWrapper<?> buildSpawnPacket(PacketEventsEntity entity) {
        if (entity.isSelfEntity()) {
            Logger.errorAndReturn(new RuntimeException("Should not build spawn packet for self entity"), 1, PacketEventsEntityViewController.class);
            throw new IllegalStateException("This statement should be unreachable. Logger.errorAndReturn failed to fire.");
        }
        return new WrapperPlayServerSpawnEntity(
                    entity.entityID(),
                    Optional.of(entity.entityUUID()),
                    getObjectEntityType(entity.entityType()),
                    new Vector3d(entity.x(), entity.y(), entity.z()),
                    entity.pitch(),
                    entity.yaw(),
                    entity.headYaw(),
                    entity.entityData(),
                    Optional.of(new Vector3d(entity.velocityX(), entity.velocityY(), entity.velocityZ()))
            );
    }

    private final ClientVersion version = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();

    private int getPrimitiveEntityType(EntityType entityType) {
        return entityType.getId(version);
    }

    private EntityType getObjectEntityType(int entityType) {
        return EntityTypes.getById(version, entityType);
    }

    private WrapperPlayServerSetPassengers buildPassengersPacket(@Nullable NettyEntity<?> entity, PlayerData playerData, int entityBeingShownID) {
        if (entity == null) {
            return null;
        }
        if (!clientAndEngineVisibleOrBeingShown(entity, entityBeingShownID)) {
            return null;
        }
        int[] passengerIDs = entity.passengerIDs();
        if (passengerIDs == null || passengerIDs.length == 0) {
            return null;
        }
        return new WrapperPlayServerSetPassengers(entity.entityID(), collectClientVisiblePassengers(passengerIDs, playerData, entityBeingShownID).toIntArray());
    }

    /**
     * Builds passenger state for a vehicle which intentionally has no tracked entity. Its retained
     * unresolved state is the only authoritative passenger list available to the packet layer.
     */
    private WrapperPlayServerSetPassengers buildBypassedVehiclePassengersPacket(int vehicleID, PlayerData playerData, int entityBeingShownID) {
        if (!isBypassed(vehicleID)) {
            return null;
        }
        int[] passengerIDs = playerData.nettyData().getUnresolvedPassengers(vehicleID);
        if (PrimitiveIntArrayList.isEmpty(passengerIDs)) {
            return null;
        }
        return new WrapperPlayServerSetPassengers(vehicleID, collectClientVisiblePassengers(passengerIDs, playerData, entityBeingShownID).toIntArray());
    }

    /**
     * Replays relationships in which the newly spawned bypassed entity may be either endpoint.
     * Earlier relationship packets may have been ignored by the client while that endpoint did
     * not exist.
     */
    private void replayBypassedEntityRelationships(User viewer, PlayerData playerData, int entityID) {
        // Rebuild mount state for both possible roles: first as the vehicle, then as a passenger.
        COMMON.writeIfPresent(viewer, buildBypassedVehiclePassengersPacket(entityID, playerData, NO_ENTITY));

        int vehicleID = playerData.nettyData().getUnresolvedVehicleForPassenger(entityID);
        if (vehicleID != NO_VEHICLE && vehicleID != entityID) {
            WrapperPlayServerSetPassengers vehiclePassengers = isBypassed(vehicleID)
                    ? buildBypassedVehiclePassengersPacket(vehicleID, playerData, NO_ENTITY)
                    : buildPassengersPacket(playerData.entityFromID(vehicleID), playerData, NO_ENTITY);
            COMMON.writeIfPresent(viewer, vehiclePassengers);
        }

        // Likewise, the bypassed entity may be either the leashed entity or the leash holder.
        int holderEntityID = playerData.nettyData().getUnresolvedHolderForLeashedEntity(entityID);
        if (holderEntityID != NO_LEASHER && relationshipEndpointIsVisible(holderEntityID, playerData)) {
            viewer.writePacketSilently(new WrapperPlayServerAttachEntity(entityID, holderEntityID, true));
        }
        int[] leashedEntityIDs = playerData.nettyData().getUnresolvedLeashes(entityID);
        if (!PrimitiveIntArrayList.isEmpty(leashedEntityIDs)) {
            for (int leashedEntityID : leashedEntityIDs) {
                if (relationshipEndpointIsVisible(leashedEntityID, playerData)) {
                    viewer.writePacketSilently(new WrapperPlayServerAttachEntity(leashedEntityID, entityID, true));
                }
            }
        }
        // All relationship repairs are silent writes, so release the batch explicitly.
        viewer.flushPackets();
    }

    private boolean relationshipEndpointIsVisible(int entityID, PlayerData playerData) {
        if (isBypassed(entityID)) {
            // Bypassed entities are forwarded untouched, so they have no tracked visibility state.
            return true;
        }
        NettyEntity<?> entity = playerData.entityFromID(entityID);
        return entity != null && clientAndEngineVisibleOrBeingShown(entity, null);
    }

    private @Nullable WrapperPlayServerAttachEntity[] buildLeashPackets(
            PacketEventsEntity entity, PlayerData playerData, int entityBeingShownID) {
        int[] leashedIDs = entity.leashedEntityIDsOrNull();
        WrapperPlayServerAttachEntity leashingShow = buildLeashingShow(
                entity, playerData, entityBeingShownID);
        if (leashedIDs == null || leashedIDs.length == 0) {
            return singleLeashPacketOrNull(leashingShow);
        }
        WrapperPlayServerAttachEntity[] packets = new WrapperPlayServerAttachEntity[
                leashedIDs.length + (leashingShow == null ? 0 : 1)];
        int startIndex = insertLeashingShow(packets, leashingShow);
        insertLeashedEntityPackets(
                packets, startIndex, leashedIDs, entity, playerData, entityBeingShownID);
        return packets;
    }

    private @Nullable WrapperPlayServerAttachEntity buildLeashingShow(
            PacketEventsEntity entity, PlayerData playerData, int entityBeingShownID) {
        int leashingID = entity.leashingEntity();
        if (leashingID == NO_LEASHER) {
            return null;
        }
        NettyEntity<?> leashHolder = playerData.entityFromID(leashingID);
        if (!isBypassed(leashingID)
                && (leashHolder == null
                || !clientAndEngineVisibleOrBeingShown(leashHolder, entityBeingShownID))) {
            return null;
        }
        return new WrapperPlayServerAttachEntity(entity.entityID(), leashingID, true);
    }

    private static @Nullable WrapperPlayServerAttachEntity[] singleLeashPacketOrNull(
            @Nullable WrapperPlayServerAttachEntity leashingShow) {
        return leashingShow == null
                ? null
                : new WrapperPlayServerAttachEntity[]{leashingShow};
    }

    private static int insertLeashingShow(
            WrapperPlayServerAttachEntity[] packets,
            @Nullable WrapperPlayServerAttachEntity leashingShow) {
        if (leashingShow == null) {
            return 0;
        }
        packets[0] = leashingShow;
        return 1;
    }

    private void insertLeashedEntityPackets(
            WrapperPlayServerAttachEntity[] packets,
            int startIndex,
            int[] leashedIDs,
            PacketEventsEntity entity,
            PlayerData playerData,
            int entityBeingShownID) {
        int index = startIndex;
        for (int leashedID : leashedIDs) {
            NettyEntity<?> leashedEntity = playerData.entityFromID(leashedID);
            if (isBypassed(leashedID)
                    || leashedEntity != null
                    && clientAndEngineVisibleOrBeingShown(leashedEntity, entityBeingShownID)) {
                packets[index++] = new WrapperPlayServerAttachEntity(
                        leashedID, entity.entityID(), true);
            }
        }
    }

    private boolean clientAndEngineVisibleOrBeingShown(NettyEntity<?> entity, @Nullable Integer entityBeingShownID) {
        return (entity.clientVisible() && entity.visible())
                || entityBeingShownID != null && entity.entityID() == entityBeingShownID;
    }

    private WrapperPlayServerEntityEffect copyEffectPacket(WrapperPlayServerEntityEffect effect) {
        WrapperPlayServerEntityEffect copy = new WrapperPlayServerEntityEffect(
                effect.getEntityId(),
                effect.getPotionType(),
                effect.getEffectAmplifier(),
                effect.getEffectDurationTicks(),
                buildEffectFlags(effect.isAmbient(), effect.isVisible(), effect.isShowIcon())
        );
        copy.setFactorData(effect.getFactorData());
        return copy;
    }

    private WrapperPlayServerEntityMetadata copyEntityMetadataPacket(WrapperPlayServerEntityMetadata packet) {
        return new WrapperPlayServerEntityMetadata(
                packet.getEntityId(),
                copyEntityMetadata(packet.getEntityMetadata())
        );
    }

    private WrapperPlayServerEntityEquipment copyEntityEquipmentPacket(WrapperPlayServerEntityEquipment packet) {
        return new WrapperPlayServerEntityEquipment(
                packet.getEntityId(),
                copyEquipment(packet.getEquipment())
        );
    }

    private WrapperPlayServerEntityVelocity copyEntityVelocityPacket(WrapperPlayServerEntityVelocity packet) {
        return new WrapperPlayServerEntityVelocity(
                packet.getEntityId(),
                new Vector3d(packet.getVelocity().getX(), packet.getVelocity().getY(), packet.getVelocity().getZ())
        );
    }

    private WrapperPlayServerRemoveEntityEffect copyRemoveEntityEffectPacket(WrapperPlayServerRemoveEntityEffect packet) {
        return new WrapperPlayServerRemoveEntityEffect(
                packet.getEntityId(),
                packet.getPotionType()
        );
    }

    private List<EntityData<?>> copyEntityMetadata(List<EntityData<?>> metadata) {
        return metadata == null ? List.of() : List.copyOf(metadata);
    }

    private List<Equipment> copyEquipment(List<Equipment> equipment) {
        return equipment == null ? List.of() : List.copyOf(equipment);
    }

    private byte buildEffectFlags(boolean ambient, boolean visible, boolean showIcon) {
        byte flags = 0;
        if (ambient) {
            flags |= 1;
        }
        if (visible) {
            flags |= 2;
        }
        if (showIcon) {
            flags |= 4;
        }
        return flags;
    }
    
    @SuppressWarnings("unchecked")
    public  <T> T cast(Object value) {
        return (T) value;
    }

    private PacketEventsEntity getTrackedEntity(EntityView<PacketEventsEntity> entityView, UUID entityUUID) {
        return entityView.getEntity(entityUUID);
    }

    private PacketEventsEntity getTrackedEntity(EntityView<PacketEventsEntity> entityView, int entityID) {
        return entityView.getEntity(entityID);
    }

    PacketEventsEntityReplayData ensureReplayData(PacketEventsEntity entity) {
        PacketEventsEntityReplayData replayData = entity.packetReplayData();
        if (replayData == null) {
            replayData = PacketEventsEntityReplayData.create();
            entity.setPacketReplayData(replayData);
        }
        return replayData;
    }

    @SuppressWarnings("PMD.NullAssignment") // Null means an existing client entity needs no spawn packet.
    private EntityTransitionPlan<PacketWrapper<?>> buildEntityShowPlan(
            PlayerData data,
            PacketEventsEntity entity,
            boolean sendSpawnPacket
    ) {
        PacketEventsEntityReplayData replayData = ensureReplayData(entity);
        WrapperPlayServerSetPassengers passengerPacket =
                buildPassengersPacket(entity, data, entity.entityID());

        WrapperPlayServerSetPassengers vehiclePacket = buildVehiclePassengersPacket(data, entity);

        return EntityTransitionPlan.show(
                sendSpawnPacket ? buildSpawnPacket(entity) : null,
                buildEntityPositionCorrection(entity),
                buildEntityHeadLookCorrection(entity),
                replayData.snapshotPackets(entity.entityID()),
                passengerPacket,
                vehiclePacket,
                buildLeashPacketList(entity, data, entity.entityID())
        );
    }

    private WrapperPlayServerSetPassengers buildVehiclePassengersPacket(
            PlayerData data, PacketEventsEntity entity) {
        int vehicleID = entity.vehicleID();
        if (vehicleID == NO_VEHICLE) {
            return null;
        }
        return isBypassed(vehicleID)
                ? buildBypassedVehiclePassengersPacket(vehicleID, data, entity.entityID())
                : buildPassengersPacket(data.entityFromID(vehicleID), data, entity.entityID());
    }

    private List<PacketWrapper<?>> buildLeashPacketList(
            PacketEventsEntity entity,
            PlayerData data,
            int entityBeingShownID
    ) {
        WrapperPlayServerAttachEntity[] leashPackets =
                buildLeashPackets(entity, data, entityBeingShownID);
        if (leashPackets == null || leashPackets.length == 0) {
            return List.of();
        }
        List<PacketWrapper<?>> packets = new ArrayList<>(leashPackets.length);
        for (WrapperPlayServerAttachEntity packet : leashPackets) {
            if (packet != null) {
                packets.add(packet);
            }
        }
        return List.copyOf(packets);
    }

    private void writeEntityRelationships(User viewer, PlayerData data, PacketEventsEntity entity, int entityBeingShownID) {
        COMMON.writeIfPresent(viewer, buildPassengersPacket(entity, data, entityBeingShownID));
        int vehicleID = entity.vehicleID();
        if (vehicleID != NO_VEHICLE) {
            COMMON.writeIfPresent(viewer, isBypassed(vehicleID)
                    ? buildBypassedVehiclePassengersPacket(vehicleID, data, entityBeingShownID)
                    : buildPassengersPacket(data.entityFromID(vehicleID), data, entityBeingShownID));
        }
        WrapperPlayServerAttachEntity[] leashPackets = buildLeashPackets(entity, data, entityBeingShownID);
        if (leashPackets == null) {
            return;
        }
        for (WrapperPlayServerAttachEntity leashPacket : leashPackets) {
            if (leashPacket != null) {
                viewer.writePacketSilently(leashPacket);
            }
        }
    }

    private PacketWrapper<?> buildEntityPositionCorrection(PacketEventsEntity entity) {
        return new WrapperPlayServerEntityPositionSync(
                entity.entityID(),
                new EntityPositionData(
                        new Vector3d(entity.x(), entity.y(), entity.z()),
                        new Vector3d(entity.velocityX(), entity.velocityY(), entity.velocityZ()),
                        entity.yaw(),
                        entity.pitch()
                ),
                entity.onGround()
        );
    }

    private PacketWrapper<?> buildEntityHeadLookCorrection(PacketEventsEntity entity) {
        return new WrapperPlayServerEntityHeadLook(entity.entityID(), entity.headYaw());
    }

    protected void insertEntityToPlayerView(NettyEntity<?> entity, PlayerData playerData, UUID world) {
        playerData.playerView().insertEntity(world, entity.cast());
        // Passenger relationships can arrive before spawn/pairing completes, so resolve them as soon as the entity becomes known.
        reconcileUnresolvedPassengers(entity, playerData);
        reconcileUnresolvedLeashes(entity, playerData);
    }

    protected void insertEntityToEntityView(NettyEntity<?> entity, PlayerData playerData, UUID world) {
        playerData.entityView().insertEntity(world, entity.cast()); //todo: no need to put here, move to abstract packet view controller
        // Passenger relationships can arrive before spawn/pairing completes, so resolve them as soon as the entity becomes known.
        reconcileUnresolvedPassengers(entity, playerData);
        reconcileUnresolvedLeashes(entity, playerData);
    }

    private void replayTrackedEntityRelationships(User viewer, PlayerData playerData, int entityID) {
        NettyEntity<?> entity = playerData.entityFromID(entityID);
        if (!(entity instanceof PacketEventsEntity packetEntity)
                || !entity.clientVisible()
                || !entity.visible()) {
            return;
        }
        writeEntityRelationships(viewer, playerData, packetEntity, NO_ENTITY);
        viewer.flushPackets();
    }
}
