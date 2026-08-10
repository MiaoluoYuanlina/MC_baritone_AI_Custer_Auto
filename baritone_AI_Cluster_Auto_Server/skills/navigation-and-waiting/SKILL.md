---
name: navigation-and-waiting
description: Navigate or follow with Baritone without duplicate commands, wait efficiently for long-running work, and stop safely.
---

# Navigation and long-running work

Use `move_to` for fixed coordinates, `follow_player` for continuous following, and `mine_item` only for mining without a later storage/smelting workflow.

After starting Baritone:

1. Confirm the command was accepted and the instance reports Baritone working.
2. Do not repeatedly send the same command.
3. End the current reasoning turn with a concise progress report. The C# runner will wake the same Agent session when Baritone finishes or after its periodic timer.
4. On wake-up, obtain a fresh state and continue the existing plan rather than restarting it.

If the user asks to interrupt, the C# Interrupt button cancels the Agent request and sends `#stop` to all online instances. When the goal is verified complete, call `stop_baritone` and then `stop_continuous_task`.
