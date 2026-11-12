# Create: Media Systems

様々なメディアをゲーム内で再生可能にするNeoforge向けMod

## 対応環境

- Minecraft 1.21.1 / NeoForge 21.1
- Create 6.0.10^
- Java 21

Modの完全な動作には、[CMS-API](https://github.com/haruyq/CMS-API)が別途必要です。  

## Sable船体とCreateのからくり

- 放送塔はヘッド、支柱、給電台を一緒に載せます。組み立て前にジュークボックス範囲の羽ペンで再生機をリンクします。
- Sableでは、給電台とセル充電器の下面に16 RPM以上の回転をつなぐか、FEを入力できます。
- Createのからくりは組み立て前に蓄えたFEを使います。移動中は充電済みバッテリーで給電台を右クリック、セル充電器をスニーク＋右クリックすると補給できます。
- セルは充電器への右クリックで出し入れできます。PartyBoxは床の上面に右クリックで設置し、従来どおり引いて運べます。

## ビルド

```bash
./gradlew build
```

## ライセンス

このプロジェクトは[MIT License](./LICENSE)で公開されています。
