# Boat passenger visibility

## Spec

- When a non-player boat, chest boat, raft or minecart carries a tracked passenger, PieCloak shall track the vehicle's movement and propagate it to the passenger's visibility position.
- When a passenger dismounts or its vehicle is destroyed, PieCloak shall clear the vehicle relationship and stop applying the old vehicle's movement to that passenger.
- While a managed passenger is occluded, PieCloak shall preserve normal hiding and relationship replay instead of forcing the passenger visible.

## Scope and baseline

Owner requested this repair without production changes. Base: authoritative main `af92ee0`. PR 11 is merged, and no open PR overlaps this work; its older coordination records are stale and are not rewritten. Existing Java 21 / Minecraft 1.21.11 build baseline and PacketEvents API 2.12.0 remain unchanged. Production currently uses PacketEvents 2.14.0; binary compatibility must be checked separately.

## Prove / engine / arch / refine

The installed production binary recognizes only minecarts as relationship-support vehicles. Boats excluded by the target allowlist are bypassed, so their movement does not update mounted villagers' tracked positions. The source repair extends the same support classification to boats and rafts; core visibility policy and configuration are preserved.

Tasks: prove boat-family classification fails on the original implementation; implement the smallest adapter change; verify movement, dismount and destroy cleanup; run clean build, tests and static analysis; inspect exact-head hosted checks; deliver an unmerged test artifact and reviewable PR. No production upload or activation.

No project-local SPEAR EARS validator or state helper is present. This requirement/task/evidence record supplies scaled traceability; no validator pass is claimed.

Client acceptance remains required on TEST: transport a villager from beyond 48 blocks into the 24-block always-visible radius in ordinary/chest boats and rafts, then repeat after relog, occlusion, dismount, boat destruction and world change. Check Java and Geyser/Floodgate clients. Unit tests are not live-client acceptance.

## Verification evidence

CodeRabbit review follow-up: exercise the real async visibility section with PacketEvents' actual entity view. A mounted passenger and its support vehicle must both become visible and publish SHOW transitions after entering the 24-block radius; moving beyond 48 blocks must publish HIDE transitions for both. This complements the controller relationship tests and does not replace pending client acceptance.

The follow-up integration regression passes using production engine, tracked entities, view iteration, visibility mutation and transition queue. Only relationship establishment is arranged by the test; the controller path is covered separately. No product changes were needed. CodeRabbit's generic 80-percent docstring warning includes self-describing test methods and is not a functional defect; existing documentation conventions are preserved.

- The first isolated registry test exposed missing PacketEvents test bootstrap; that infrastructure failure is not regression evidence. After installing the test API, the original minecart-only classifier failed the boat assertion (`minecraft:boat` expected true, actual false). The corrected classifier passes all 22 boat/raft types.
- Focused controller tests passed, including relative movement from beyond 48 blocks into the 24-block radius, teleport back out, dismount, destroy and preserving hidden passenger state.
- Canonical Java 21 clean build, platform baseline verification, all tests and shaded snapshot build passed: 278 tests, zero failures.
- Adapter tests also passed with the installed PacketEvents 2.14.0 JAR substituted for compile-baseline API 2.12.0 at runtime. The normal dependency declarations remain unchanged.
- PMD reports no violations in changed production/test files. Existing report-only findings remain in unrelated files; no scanner suppression was added.
- Hostile review: existing support-vehicle logic handles visibility/replay, world cleanup and destruction. The patch adds no queue, cache, permission, configuration or reflection in product code. Boats follow the same path as minecarts, including existing positional approximation at vehicle coordinates.
- GitHub exact-head checks and TEST client acceptance are recorded separately after PR creation. No server artifacts were uploaded, and production remains unchanged.
