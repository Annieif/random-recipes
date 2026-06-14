# Random Recipes v1.0.2

修复单材料配方不改变材料的问题 + 自动修正重复配方。

## 修复

- **单材料配方不变**：剪刀、重质压力板、铁粒等仅用一种原料的配方，替换永远是自身（无意义）。现在从**全局物品池**（所有配方中出现过的物品）中随机补充最多 5 种候选物，确保配方内容的实质变化。
- **重复配方**：随机化后检测到同产物+同原料集合的重复配方时，自动对重复项做二次随机化（替换一个原料为全局池中的随机物品），破坏相同性。
- **洗牌优化**：Fisher-Yates 洗牌只对非空原料槽位操作，不再打乱空槽位位置。

## 功能

- **基于世界种子确定性打乱**：同存档永远相同，不同存档完全不同
- **遍历所有工作台配方**：原版 + 第三方 Mod 自动纳入
- **三重随机化**：打乱顺序 → 增减替换 → 重复修正
- **保护机制**：默认三镐，配置文件可扩展
- **`/reload` 支持**

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)
2. 安装 [Fabric API](https://modrinth.com/mod/fabric-api)
3. 将本 JAR 放入 `.minecraft/mods/`
4. 启动游戏（Minecraft 1.20.1）

## 项目

- 源码仓库：<https://github.com/Annieif/random-recipes>
- Issue / 反馈：<https://github.com/Annieif/random-recipes/issues>

## 许可证

MIT