# Create: Media Systems

様々なメディアをゲーム内で再生可能にするNeoforge向けMod

## 対応環境

- Minecraft 1.21.1 / NeoForge 21.1
- Create 6.0.10^
- Java 21

Modの完全な動作には、[CMS-API](https://github.com/haruyq/CMS-API)が別途必要です。  

## 防災行政無線

給電台を最下段に置き、支柱を積んで一番上に放送ヘッドを付けます。給電台は底面のCreate回転軸（16 RPM以上）かFEで動きます。範囲設定の羽ペンでディスク読み取り機などの音源、放送ヘッドの順に右クリックし、設定を保存すると音源の再生に追従します。

| 放送ヘッド | 最大半径 | FE消費 |
|---|---:|---:|
| アナログホーン型 | 400ブロック | 8 FE/t |
| デジタルホーン型 | 500ブロック | 10 FE/t |
| デジタル柱状型 | 800ブロック | 12 FE/t |

距離と壁で音は弱くなります。柱状型は真下の音量を抑えつつ、水平に近い方向へ広く届けます。到達距離は[消防庁の手引き](https://www.fdma.go.jp/mission/prepare/transmission/items/tebiki_r8.pdf)と[TOAの設置資料](https://contents.toa.co.jp/products/hornarrayspeaker/about/p01.htm)を基にゲーム内で調整しています。

設置したPartyBoxはShift＋右クリックで再生画面を開きます。リモコンを持って同じ操作をするとペア設定できます。メガホンは手に持って右クリックすると再生画面が開きます。どちらもディスク内の曲送り・曲戻し・シークに対応します。

## ビルド

```bash
./gradlew build
```

## ライセンス

このプロジェクトは[MIT License](./LICENSE)で公開されています。
