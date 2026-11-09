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

## ライセンス

このプロジェクトは[MIT License](./LICENSE)で公開されています。
