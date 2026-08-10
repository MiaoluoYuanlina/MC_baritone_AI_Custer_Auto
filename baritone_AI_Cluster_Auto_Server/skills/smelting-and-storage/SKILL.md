---
name: smelting-and-storage
description: Execute mining, fuel collection, furnace loading, smelting wait, output retrieval, chest storage, and final verification.
---

# Smelting and storage

For requests that include smelting, always prefer `progress_smelting_task`. Do not stop after mining raw ore.

Example for “each player mines 20 iron and coal, smelts it, then stores it at home”:

- `inputItemId`: `minecraft:raw_iron`
- `inputCount`: `20`
- `fuelItemId`: `minecraft:coal`
- `fuelCount`: the amount requested by the user, or enough for the recipe when the user did not specify it
- `resultItemId`: `minecraft:iron_ingot`
- home coordinates: exactly those supplied by the user

The state machine advances through collecting input, collecting fuel, returning home, finding/opening a furnace, loading it, waiting, retrieving output, finding/opening a chest, and depositing the output.

If no furnace is reported near the chosen home but the player has a furnace item, first call `place_block` with `minecraft:furnace`, verify `placedBlock`, and open it with `open_nearest_functional_block`. `use_item` does not place blocks.

Reinvoke it after Baritone completion or the periodic wake-up with unchanged arguments. Completion requires fresh evidence that the output was retrieved and stored; furnace loading alone is not completion.
