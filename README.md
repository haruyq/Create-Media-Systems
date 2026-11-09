# Create: Media Systems

様々なメディアをゲーム内で再生可能にするNeoforge向けMod

## 対応環境

- Minecraft 1.21.1 / NeoForge 21.1
- Create 6.0.10^
- Java 21

Modの完全な動作には、[CMS-API](https://github.com/haruyq/CMS-API)が別途必要です。  

## ビルド

```bash
./gradlew build
```

## P2P通信

プレイヤー同士がWebRTCのDataChannelで直接データを送り合います（ホストから視聴者へ）。CMS-APIはシグナリングだけを中継し、TURNやメディアの中継はしません。直接つながらない環境（厳しいNATやファイアウォール）では失敗し、その旨を表示します。ネイティブWebRTC（[webrtc-java](https://github.com/devopvoid/webrtc-java) 1.0.0、ライセンスはjar内の `create_media_systems/webrtc/`）は今のところ64bit Windowsのクライアントだけに同梱され、専用サーバーでは読み込まれません。Windows の N / KN エディションでは、先に「メディア機能パック」（Media Feature Pack）を入れてください（ないと読み込めません）。

- サーバー: CMS-APIの `P2P_SERVER_TOKEN` と同じ値を、環境変数 `CMS_P2P_SERVER_TOKEN` か `config/cms-p2p-server-token.txt` に置きます。クライアントに同期される `cms-server.toml` には書きません。`apiUrl` は `https://`（ローカルなら `http://127.0.0.1` など）にします。パス付きのURLもそのまま使えます
- クライアント: [Ookla Speedtest CLI](https://www.speedtest.net/apps/cli) を各自で入れ、一度ターミナルで実行して利用規約を自分で確認・同意してください（CMSは同意しません）。PATHにない場合は `cms-client.toml` の `speedtestPath` に場所を書きます

| コマンド | 内容 |
|---|---|
| `/cms_p2p speedtest [cancel]` | このPCの帯域を測定（結果は1時間有効、上り・下りの値と時刻だけを保持） |
| `/cms_p2p host` | セッションをホスト |
| `/cms_p2p join <プレイヤー>` | そのプレイヤーのセッションに参加 |
| `/cms_p2p status` / `ping` / `leave` | 状態、直接接続の往復時間、退出 |

送信は部屋の予算（APIが計算する視聴者1人あたりのbps）を超えないよう、待ち行列に入れて速度を抑えて送られます（データは捨てません）。待ち行列がいっぱいなら `Transport.send` が `false` を返すので、後で送り直します。

ローカルのCMS-APIに対する結合テスト: `CMS_P2P_API_URL=http://127.0.0.1:8000 CMS_P2P_SERVER_TOKEN=<秘密> ./gradlew test --tests '*.p2p.*' --rerun`

このP2P通信で映像を送ってみる単体のテストアプリ: [tools/p2p-test](tools/p2p-test/README.md)

## 非暗号化DVD / Blu-rayの配信

Windows x64の配信元PCで、DVDドライブまたは `VIDEO_TS` / `BDMV` フォルダーを読み込み、ゲーム内のBlu-rayプレイヤー・ディスプレイ・スピーカーで再生できます。配信元がログインしている間だけ利用できます。

1. 上記のP2P設定を済ませ、配信元と視聴者がそれぞれ `/cms_p2p speedtest` を実行します。
2. 配信元では、初回の `/cms_disc load` 時にFFmpeg／FFprobeを自動取得します（Windows x64）。取得中もゲームの操作を続けられ、`/cms_disc unload` で中止できます。次回からはゲームフォルダー内の `create_media_systems/ffmpeg/` に保存したものを使うため、再ダウンロードは不要です。失敗した場合は通信状態を確認し、同じ `load` コマンドで再試行してください。視聴者にはFFmpegは不要です。

   自分で用意したものを使う場合は、`cms-client.toml` の `discFfmpegPath` と `discFfprobePath` に同じ配布物の実行ファイルへのパスを設定します。DVDには `dvdvideo`（libdvdnav/libdvdread）、配信にはH.264とAC-3のエンコーダーが必要です。

3. ゲーム内のDVDまたはBlu-rayアイテムをメインハンドに持ち、次のいずれかを実行します。パスには `/` を使うと入力しやすくなります。

   ```text
   /cms_disc load "E:/"
   /cms_disc load "C:/Media/MyDisc"
   /cms_disc load "E:/" 1
   ```

   最後の数字はDVDのタイトル番号、Blu-rayではプレイリスト番号です。省略時はDVDの既定タイトル、Blu-rayの最長の動画を選びます。Blu-rayの `00000.mpls` は `0` を指定します。読み込み完了まで同じアイテムを持ってください。

4. そのアイテムをゲーム内のBlu-rayプレイヤーに入れます。視聴者は再生範囲に入ると配信元へ自動接続します。再生・一時停止・停止は既存のプレイヤー操作を使います。

配信を終えるには `/cms_disc unload` を実行します。再ログイン後は再度 `load` してください。1人のクライアントで同時に共有・視聴する物理ディスクは1枚です。別の配信元へ接続すると以前のP2Pセッションから退出します。

映像は1080p・60fpsを基準に、測定した帯域、実際の転送時間、エンコード速度から解像度・フレームレート・ビットレートを調整します。GPUエンコーダー（NVENC、QSV、AMF）を試し、利用できなければCPUを使います。音声は48 kHzのステレオです。回線が悪化すると画質を下げ、安定が続いてから段階的に戻します。

先頭と再生位置付近を先読みし、配信元を含む視聴者の準備ができてから再生を開始します。接続できない視聴者を待ち続けないよう、開始待ちには上限があります。映像は容量制限のあるメモリバッファに置き、ディスクへの保存やCMS-APIによる映像の中継は行いません。長時間の回線断では再バッファや再接続が必要です。

ディスクの動画本編を対象とし、暗号化解除、DVD/BDのメニュー操作、静止画タイトル、字幕・音声トラックの選択には対応していません。DVDはタイトル、Blu-rayはプレイリストの最初のアングル・映像・音声トラックを再生します。

自動取得には[GyanのFFmpeg full sharedビルド](https://www.gyan.dev/ffmpeg/builds/)を使い、固定した版とSHA-256を確認してから展開します。配布物のライセンスとREADMEも同じ保存先に置きます。

開発時の検証用メディアは、FFmpeg・tsMuxeR・dvdauthorを用意し、`scripts/disc_fixture.ps1` で作成できます。出力先の `bluray-long`、`bluray-short`、`dvd` をそれぞれ環境変数 `CMS_DISC_FIXTURE`、`CMS_DISC_SHORT_FIXTURE`、`CMS_DISC_DVD_FIXTURE` に指定し、`./gradlew test --tests '*.disc.*' --tests '*.p2p.DiscIntegrationTest'` を実行します。`CMS_DISC_VLC_DIR` に同梱版VLCの展開先を指定すると、配信元・受信側の実デコードと同期も検証します。これらの試験では帯域値を固定しており、Speedtestの実測は行いません。 自動取得そのものを検証する場合は、`CMS_FFMPEG_DOWNLOAD_TEST=1` を設定し、`./gradlew test --tests '*.disc.DiscToolsIntegrationTest'` を実行します。初回は配布元へ接続します。

## ライセンス

このプロジェクトは[MIT License](./LICENSE)で公開されています。
