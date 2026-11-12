# Create: Media Systems

様々なメディアをゲーム内で再生可能にするNeoforge向けMod

## 対応環境

- Minecraft 1.21.1 / NeoForge 21.1
- Create 6.0.10^
- Java 21

Modの完全な動作には、[CMS-API](https://github.com/haruyq/CMS-API)が別途必要です。  

## Hayacio PX-S1100WE MIDIキーボード

作業台でクラフトします。上段に滑らかなクォーツブロック3個、中段に真鍮の板・音符ブロック・真鍮の板、下段に安山岩合金・精密機構・安山岩合金を並べます。横2ブロック分の床に置き、低音側の背面からFEを供給します。充電済みのCMSバッテリーで右クリックしても充電でき、電源オン時に毎tick 10 FEを使います。

右クリックで演奏画面を開きます。マウスでは鍵盤の手前ほど強く鳴り、PCではZ〜/とQ〜Pで演奏し、← →でオクターブを切り替えます。Spaceでダンパー、左Shiftでソステヌート、左Ctrlでソフトペダルを操作できます。USB MIDIキーボードを接続したら「設定」で入力機器を選択します。MIDIの打鍵、ベロシティ、CC64・66・67、音量、音色変更、ピッチベンドを受け付けます。

盤面のタッチボタンから音色、メトロノーム、録音と再生を操作できます。音色ブラウザでは音色名とバンク・プログラム番号で検索できます。レイヤーとスプリットは「設定」で有効にしてから「音色」で各パートを選びます。録音は楽器のブロックデータに保存されます。

同梱の [GeneralUser GS](https://github.com/mrbumpy409/GeneralUser-GS/tree/684543d5e5efaef08d02be50dcda8d552478fa60) でGM 128音色、ドラムキット、追加音色を演奏できます。別のSoundFontを使う場合は、各クライアントの `config/create_media_systems/soundfonts/` に同じSF2を入れ、設定の「SF2」で選択します。同梱音源とシンセのライセンスはJAR内の `assets/create_media_systems/px_s1100/` にあります。

周囲のプレイヤーにも演奏と鍵盤の動きが同期され、音は左右の内蔵スピーカーの位置から聞こえます。CreateのからくりとSableの船上でも、蓄えた電力で演奏できます。Sableの船上では背面端子へのFE供給も使えます。

## ビルド

```bash
./gradlew build
```

## ライセンス

このプロジェクトは[MIT License](./LICENSE)で公開されています。
