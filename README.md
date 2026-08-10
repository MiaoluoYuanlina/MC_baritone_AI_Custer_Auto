# MC_baritone_AI_Custer_Auto
我的世界基于baritone的AI集群自动化mod

此mod使用codex辅助编写。

Baritone AI Cluster Auto
Baritone AI Cluster Auto 是一个面向 Minecraft Fabric 客户端的多实例 AI 控制辅助 Mod。它通过 C# 集群服务端连接多个加载了 Baritone 的 Minecraft 客户端，让用户可以使用自然语言统一规划、控制和监控多个玩家实例。
本 Mod 不直接提供 AI 模型服务，而是负责在 Minecraft、Baritone 与外部 C# 程序之间传递实时状态和控制指令。C# 服务端可接入 DeepSeek 等大模型，将复杂目标拆分为多个步骤，并持续执行、检查进度和处理失败情况。

主要功能：
多个 Minecraft 客户端同时连接到 C# 服务端，默认使用 25570 端口。
实时上报生命值、饱食度、位置、维度、背包、装备栏、副手和容器内容。
上报附近方块、功能方块、相邻方块、方块变化、玩家位置和 Baritone 路线。
支持 AI 控制移动、挖掘、合成、放置方块、使用物品、操作工作台、熔炉和容器。
支持采集、烧炼、存入箱子、向指定玩家交付物品等长流程任务。
AI 会先生成详细执行计划，并在主要玩家右上角显示任务步骤和当前进度。
C# 根据真实背包数量控制挖掘配额，达到数量后自动停止 Baritone。
支持数小时持续任务、超时保护、无进展检测和上下文自动压缩。
可设置主要玩家；主要玩家默认受 AI 保护，必须明确授权后才能由 AI 控制。
主要玩家可以通过 F8 界面管理其他实例、查看背包、操作附近方块或远程附身。
提供可交互的 3D 附近方块界面和 C# 集群地图。
支持显示其他实例的位置、皮肤头像和 Baritone 行进路线。
可配置允许破坏、禁止挖掘和尽量避免挖掘的方块。
支持手动中断任务、停止指定玩家的 Baritone，以及清空 AI 对话上下文。

使用要求：
Fabric Loader
Baritone Mod
Baritone AI Cluster Auto 客户端 Mod
配套的 Baritone AI Cluster Auto C# 服务端
可选的 DeepSeek API 或其他兼容大模型服务
这是一个客户端辅助 Mod，所有跨实例通信、AI 调度和远程控制均由 C# 程序作为中间服务完成。它适合多开自动化、资源采集、协作、远程管理和 Minecraft AI Agent 实验。
