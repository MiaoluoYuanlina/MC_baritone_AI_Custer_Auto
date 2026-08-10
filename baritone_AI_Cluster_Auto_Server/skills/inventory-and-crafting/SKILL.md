---
name: inventory-and-crafting
description: Inspect inventories and containers, craft recipes, use or discard items, and safely transfer exact items between player and container.
---

# Inventory, containers, and crafting

Before inventory actions, use `get_instance_status` and count the exact registry ID across inventory, equipment, offhand, and the currently open container as appropriate.

- To store or retrieve items, first confirm the functional block coordinates, call `open_block`, wait for a fresh state showing that container, then call `deposit_item` or `withdraw_item`.
- Use `click_container_slot` only when an exact slot operation is required. Prefer semantic deposit/withdraw tools otherwise.
- For 2x2 recipes, `craft_item` can work from the player inventory. For 3x3 recipes, navigate to and open a crafting table before calling it.
- If the required crafting table is in inventory rather than in the world, call `place_block` with `minecraft:crafting_table`, require a successful result with `placedBlock`, then call `open_nearest_functional_block`. Never use `use_item`, `#place`, or `#setblock` to place it.
- The same placement rule applies to furnaces, blast furnaces, chests, barrels, anvils and other placeable functional blocks.
- To hand an item to another player, use `give_item_to_player`. It approaches the recipient, drops the exact item nearby, and verifies pickup when the recipient is a connected instance.
- After crafting, transferring, using, or discarding, read a fresh state and compare the exact item count before and after.
- `discard_item` changes the world irreversibly. Use it only when explicitly requested or necessary for a verified goal.

Do not assume a right-click or command acknowledgement means the GUI operation succeeded.
