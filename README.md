# Nether Search

## コマンド一覧
| コマンド | 説明 |
| --- | --- |
| `/ns search fortress [count]` | ネザー要塞の候補を最大20件まで距離順で表示します（count省略時は1件）。 |
| `/ns search bastion_remnant [count]` | 砦の遺跡を検索して表示します。 |
| `/ns search new <structure> [count]` | まだ訪れていない構造物のみを抽出して表示します。 |
| `/ns chest [range]` | 指定半径（16〜192、未指定なら96）に存在するチェストの数を返します。 |
| `/ns glowing_chest [range] [duration_seconds]` | 周囲のチェストに発光マーカーを付与します（範囲16〜192、時間1〜600秒）。 |
| `/ns exp` | 利用可能なコマンド一覧とヒントを表示します。 |

## 対応環境
- Minecraft 1.21 〜 1.21.10（ネザーワールド向け）
- Fabric Loader 0.18.1 以上
- Fabric API 0.138.3+1.21.10
- Java 21
