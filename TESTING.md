# PieCloak / RaycastedAntiESP Testing Checklist

Use a normal client plus a pie-chart/base-finding client or packet capture where possible.

## Build Baseline

- Initialize all Git submodules recursively.
- Generate LeafPile sources with Java 21 before the Gradle build.
- Run `./gradlew clean verifyCurrentPlatformBaseline :platform-paper:compileJava test :platform-paper:build :platform-paper:buildSnapshot --no-daemon`.
- Confirm the shaded JAR is named `RaycastedAntiESP-*.jar`, not `Incorrectly-Compiled-Without-ShadowJar`.
- Confirm generated `plugin.yml` names `PieCloak` and declares API version `1.21.11`.
- Confirm `build-properties/platform.yml` records Minecraft `1.21.11`, Paper bundle `1.21.11-R0.1-SNAPSHOT`, Java `21`, and the exact Git revision.
- Treat `runPaper`/`runServer` as a local Paper 1.21.11 smoke-test convenience, not proof of Leaf, Geyser, or production compatibility.

## Server Behavior

- Normal mobs still spawn and tick.
- Farms still work without plugin-side spawn cancellation or AI changes.
- Villager trading halls still trade and restock.
- Iron farms still spawn golems.
- Honey farms and bees still work.
- Snow golem farms still work.
- Allays still work.
- Players are never hidden.
- Non-allowlisted entities and block entities behave normally.
- Java and Bedrock players through Geyser/Floodgate observe the same managed visibility outcomes.

## Skip-region exit and raycast progress regressions

- With player checks disabled and `piecloak-skip: allow` inside a region, repeatedly cross its boundary with two players. Both player bodies and nametags must remain visible outside the region.
- Repeat with entity checks disabled; tracked entities must not be stranded hidden on exemption exit. With checks enabled, normal occlusion must still apply outside the region.
- Outside the exempt region, approach protected shulkers and villagers, including targets whose ray endpoints lie on integer coordinates in mixed positive/negative directions. Their visibility must keep updating; diagnostic completed-tick counts must continue increasing.
- Repeat after teleport, relog, chunk reload, and on the supported Java and Geyser/Floodgate client paths. These are manual acceptance checks, not implied by passing unit tests.
- When replacing an affected build, perform a full server restart: a worker already stuck in the old traversal cannot be repaired by a configuration reload.

## Target Filter

- `villager` and `minecraft:villager` config entries both resolve to the same target.
- Missing current-version entries, such as version-dependent entities, log a warning and are skipped.
- `/raesp stats` shows resolved target counts and skipped invalid entries.
- `/raesp debugplayer <player>` shows managed target counts for the player.
- `/raesp benchmark <radius> <samples>` reports raycast timing without changing gameplay.

## Leak Checks

- Target entities do not appear in pie chart before their spawn packet should be revealed.
- Target block entities do not leak from `CHUNK_DATA`.
- Target block entities do not leak through standalone `BLOCK_ENTITY_DATA`.
- Target block entities do not leak through `BLOCK_CHANGE`.
- Target block entities do not leak through `MULTI_BLOCK_CHANGE`.

## One-Tick Cases

- Walk into render distance of a base.
- Teleport near a base.
- Log in near a base.
- Change dimensions near a base.
- Reload chunks near a base.
- Fly through chunks at high elytra speed.

## Entity Desync Regression

- Join and relog beside an allowlisted iron golem inside `always-show-radius`; it must be visible immediately.
- Grant `raycastedantiesp.bypass`, relog, and verify all managed entities remain visible and continue moving.
- Move a villager in a minecart across visible and hidden boundaries; both must hide and show together.
- Dismount the villager with an empty `SET_PASSENGERS`; the minecart and villager must resume independent visibility.
- Destroy the minecart while mounted, then repeat by removing the passenger first; neither case may leave stale visibility.
- Repeat at least 100 hide/show cycles and confirm replay state and heap usage remain bounded.
- Force one SHOW replay failure in a development build and verify other transitions still process and the failed SHOW retries.

## Block Transition Repair Regression

- Inject a write failure for a fake-block HIDE packet and verify later packed transitions still run and the failed tile retries.
- Inject a failure before the real-block SHOW packet and verify the repair restarts from the block stage.
- Inject a failure only for SHOW block-entity data and verify retry sends only the missing data rather than duplicating the real block.
- Disable tile checks while several tiles are hidden, fail one repair write, and verify every other tile is repaired while the failed tile remains queued.
- Toggle checks enabled → disabled → enabled while a repair is pending and verify the old generation is discarded.
- Saturate one viewer with more than 256 distinct failed block repairs and verify queued staged repairs are preserved, newest failures are reported, and each repair stops after eight failed attempts.
- Change worlds or disconnect with repairs pending and verify no old-world repair is sent afterward.
- Send standalone managed block-entity data without cached tile state and verify it is cancelled and replaced with the hidden block.
- Send virtual/non-managed block-entity data and verify it still reaches Java and Bedrock clients.
- Inject an unexpected `MAP_CHUNK_BULK` packet and verify it passes through without throwing while diagnostics remain bounded.
- Repeat the package-specific concurrency and retry tests at least 25 times before release.

## Entity transition failure injection

For package 03 validation, inject one outbound write failure at a time while revealing a hidden managed entity. Exercise spawn, absolute position correction, head look, each replay category (metadata, equipment, velocity, effects, and attributes), passenger state, vehicle state, leash state, and destroy.

For every stage, verify that a successful spawn or destroy is not sent a second time, later retries resume at the failed stage, and a newer opposite visibility decision cancels the stale repair. Also verify bounded terminal behavior, disconnect cleanup, respawn/world-change cleanup, entity despawn and ID reuse, minecart passenger ordering, bypass convergence, and Java plus Geyser/Floodgate clients.

These injected network-failure and live-client scenarios remain manual unless a handoff records direct server evidence.
