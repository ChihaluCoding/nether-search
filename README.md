# Nether Search

## コマンド一覧
| コマンド | 説明 |
| --- | --- |
| `/ns search fortress [count]` | 半径内のネザー要塞を最大 20 件まで表示。（count省略可能） |
| `/ns search bastion_remnant [count]` | ピグリン要塞の探索。 |
| `/ns search new <structure> [count]` | まだ記録されていない新規構造物のみ表示。 |
| `/ns chest [range]` | デフォルト 96 ブロック、指定可能範囲 16〜192。範囲内チェスト数を返す。 |
| `/ns glowing_chest [range] [duration_seconds]` | 範囲内チェストを 1〜600 秒（既定 60 秒）発光させる。 |
| `/ns exp` | コマンドチートシートをチャットに表示。 |

## 環境
- Minecraft 1.21.9 ~ 1.21.10（ネザーワールドでの使用を想定）
- Fabric Loader 0.18.1 以上
- Fabric API
- Java 21