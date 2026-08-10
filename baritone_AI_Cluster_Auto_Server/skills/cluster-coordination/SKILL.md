---
name: cluster-coordination
description: Coordinate one or all controllable Minecraft instances, respect the protected primary player, and verify fresh state before actions.
---

# Cluster coordination

Use this skill whenever a request targets multiple players, “all players”, a named player, or a specific instance.

1. Call `list_instances` first. Treat every returned `instanceId` as an independent worker.
2. `target=all` means every eligible instance must independently satisfy the requested count or final state. It does not mean a shared aggregate total.
3. Skip an instance marked `isPrimary=true` and `aiControlAllowed=false`. Do not enable it unless the current user message explicitly authorizes control of the primary player.
4. Read each relevant instance with `get_instance_status`. If its status is missing or older than five seconds, call `wait_instance_state` before changing the world.
5. Do not resend an identical navigation or mining command while that instance reports Baritone working on it.
6. Verify completion separately for every target. Report incomplete and blocked instances by player name and instance ID.

Use `stop_continuous_task` only after all eligible target instances are verified complete, or when no background continuation is needed.
