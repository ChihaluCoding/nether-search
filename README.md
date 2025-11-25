# Nether Search

日本語と英語に対応した Fabric 1.21.9 向けの Nether 探索支援コマンド MOD です。ネザー要塞やピグリン要塞の位置情報、周囲のチェスト検出/発光を便利に行えます。

## 主な機能 / Features
- `/ns search` 系コマンドでネザー要塞・ピグリン要塞を複数件まとめて探索。
- `/ns chest` で任意半径内のチェスト数を即時計測。
- `/ns glowing_chest` で周囲のチェストに透明グローマーカーを設置し、一定時間ハイライト。
- プレイヤーのログイン/ログアウトやサーバー停止時にマーカーをクリーンアップ。
- `en_us` と `ja_jp` を同梱したローカリゼーション。`assets/nether-search/lang/` にファイルを追加するだけで他言語を拡張可能。

## コマンド一覧 / Commands
| Command | 説明 / Description |
| --- | --- |
| `/ns search fortress [count]` | 半径内のネザー要塞を最大 20 件まで表示。省略時 count=1。 |
| `/ns search bastion_remnant [count]` | ピグリン要塞の探索。 |
| `/ns search new <structure> [count]` | まだ記録されていない新規構造物のみ表示。 |
| `/ns chest [range]` | デフォルト 96 ブロック、指定可能範囲 16〜192。範囲内チェスト数を返す。 |
| `/ns glowing_chest [range] [duration_seconds]` | 範囲内チェストを 1〜600 秒（既定 60 秒）発光させる。 |
| `/ns exp` | コマンドチートシートをチャットに表示。 |

## 必要環境 / Requirements
- Minecraft 1.21.9（ネザーワールドでの使用を想定）
- Fabric Loader 0.18.1 以上
- Fabric API
- Java 21

## 導入方法 / Installation
1. GitHub Release もしくは `./gradlew build` で生成した `build/libs/nether-search-<version>.jar` を取得。
2. サーバーまたはクライアントの `mods/` ディレクトリへ配置。
3. Fabric API も同梱されていることを確認して再起動。

## ビルド / Building from source
```bash
# Windows PowerShell (その他シェルでも同様)
./gradlew build
```
`build/libs/` に remap 済みの JAR と sources JAR が生成されます。

## ローカライズ / Localization
- 翻訳キーは `message.nether_search.*` で統一されています。
- 既存の `en_us.json` / `ja_jp.json` を参考に、新規 `<locale>.json` を `assets/nether-search/lang/` へ追加してください。

## ライセンス / License
`fabric.mod.json` に記載のとおり MIT License を採用しています。
