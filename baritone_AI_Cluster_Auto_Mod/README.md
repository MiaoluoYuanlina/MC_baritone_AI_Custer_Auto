# Baritone AI Cluster bridge (Fabric 26.1.2)

This previously empty template has no Gradle Wrapper. You can run it with the workspace's Gradle 9 Wrapper: `..\\baritone-1.19.4\\gradlew.bat -p . build`. Install the resulting JAR beside Fabric API and the matching 26.1.2 Baritone JAR.

Start Minecraft with matching JVM properties for every instance:
`-Dbaritone.cluster.host=127.0.0.1 -Dbaritone.cluster.port=25570 -Dbaritone.cluster.token=replace-with-a-long-random-secret -Dbaritone.cluster.instance=bot-01`

The mod sends a status every five seconds: player name, health, food, position, occupied inventory slots, and up to 64 non-air blocks within 4×2×4. Incoming `command` messages are submitted as player chat. Use Baritone's `#` command prefix. Do not expose port 25570 to an untrusted network.
