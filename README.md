# Create: Media Systems

様々なメディアをゲーム内で再生可能にするNeoforge向けMod

## 対応環境

- Minecraft 1.21.1 / NeoForge 21.1
- Create 6.0.10^
- Java 21

Modの完全な動作には、[CMS-API](https://github.com/haruyq/CMS-API)が別途必要です。  

## コンサートグランドピアノ

作業台でクラフトし、2×3ブロックの平らな床に設置します。右クリックで演奏画面を開き、Shift＋右クリックまたは演奏画面の「大屋根」で蓋を開閉できます。

USB MIDIキーボードをPCにつなぐと、演奏画面に入力機器が表示されます。機器名をクリックすると入力を切り替えられます。88鍵の打鍵・離鍵ベロシティ、ダンパーとハーフペダル（CC64）、ソステヌート（CC66）、ソフト（CC67）に対応します。画面の鍵盤やPCキーボードでも演奏できます。Z/Xでオクターブを変え、Ctrl / Shift / Spaceで左から順に3本のペダルを踏めます。

マルチプレイでは同じバージョンのModを入れた周囲のプレイヤーにも演奏が届きます。ピアノはPCの既定の音声出力を使い、Minecraftの全体音量と「レコード」の音量設定に従います。

音源はAlexander Holmの [Salamander Grand Piano V3](https://github.com/sfzinstruments/SalamanderGrandPiano/tree/3382bf9496bba2486f5ab0de55a264d1dfc38404)（[CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)）を加工して同梱しています。

## ビルド

```bash
./gradlew build
```

## ライセンス

このプロジェクトは[MIT License](./LICENSE)で公開されています。
