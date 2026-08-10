# Baritone Cluster Server

Run `set BARITONE_CLUSTER_TOKEN=replace-with-a-long-random-secret` then `dotnet run`.

The TCP listener is `25570`. Minecraft bridge clients connect outward to it. Console commands: `list`, `status <id>`, and `send <id|all> <command>`. Commands are sent as chat input, so Baritone commands normally start with `#` (for example `#goto 100 64 -50`).

The wire format is one UTF-8 JSON object per line: clients authenticate with `hello`, periodically send `status`, and receive `command`. This makes it straightforward to add an AI provider in C#: translate a model tool call into the same `send` operation, after enforcing your own allow-list and rate limits.
