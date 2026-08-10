---
name: resource-collection
description: Reliably mine a requested item count, return to a home coordinate, locate a nearby chest, deposit the item, and verify each player.
---

# Resource collection and storage

Use `progress_collection_task` for an end-to-end request such as “mine 20 diamonds and put them in the chest near home”. It is safer than manually composing `mine_item`, `move_to`, `open_block`, and `deposit_item`.

Required inputs:

- `target`: an exact instance ID or `all`.
- `itemId`: a Minecraft item registry ID, for example `minecraft:diamond`.
- `requiredCount`: the amount each target player must collect and deposit.
- `homeX`, `homeY`, `homeZ`: the user's stated home coordinate.

Call the state-machine tool once, inspect its per-instance `stage`, then allow Baritone to work. On a later completion event or timer cycle, call it again with exactly the same inputs. Do not substitute total inventory size for the requested item count.

The task is complete only after the tool/status confirms the requested item left the player's inventory and was deposited in a chest. Merely reaching home or opening a chest is not completion.
