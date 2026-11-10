# Create: Media Systems

様々なメディアをゲーム内で再生可能にするNeoforge向けMod

## 対応環境

- Minecraft 1.21.1 / NeoForge 21.1
- Create 6.0.10^
- Java 21

Modの完全な動作には、[CMS-API](https://github.com/haruyq/CMS-API)が別途必要です。  

Disc Cellランチャーの操作・充電・6段階のレシピ・任意のCreate Plant連携は
[Disc Cellガイド](DISC_LAUNCHER_README_JA.md)を参照してください。

SBL PartyBox Stage 320の仕様・運搬・再生・電池交換と充電は
[PartyBoxガイド](PARTYBOX_README_JA.md)を参照してください。

白／黒のSBLメガホン30と、専用ホーンDSP・操作・レシピは
[メガホンガイド](MEGAPHONE_README_JA.md)を参照してください。

今回追加したランチャー・セル・充電器・SBLスピーカー・メガホンの12種類のレシピ画像と、導入元の既存レシピを含む34種類の素材・配置・完成個数は
[レシピ一覧（1.6.0時点）](docs/recipes-1.6.0.html)にまとめています。上流で後から追加されたHi-Fi機器などのレシピは、この画像一覧には含みません。

1.6.2では、JEIがLv.2～6のセル強化を通常のクラフトレシピとして扱えるようにしました。
前段階のセル・8個の素材・完成セルを表示し、強化後も原本ディスクと充電量を引き継ぎます。
Lv.6だけはCreate Plant導入時に追加されます。JEIは必須依存ではありません。

## ビルド

```bash
./gradlew build
```

JEIの実装を使った表示判定の回帰試験は `./gradlew -PrecipeViewerTest test` で実行できます。
このオプションはテスト用の依存だけを追加します。

## ライセンス

このプロジェクトは[MIT License](./LICENSE)で公開されています。
