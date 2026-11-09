# CMS P2P 映像テスト

ModのP2P通信（`p2p/Transport` のWebRTC DataChannel、そのままのコード）で、ホストから視聴者へ映像を送って映るかを確かめる小さなデスクトップアプリです。映像のみ（音声なし）で、640×360・約10fpsのJPEGを1枚ずつ送ります。接続と送受信のテストであり、Blu-rayのコーデックや性能の検証ではありません。

必要なもの: 64bit Windows、Java 21。ローカルデモには [uv](https://docs.astral.sh/uv/) と、このリポジトリの隣に置いた [CMS-API](https://github.com/haruyq/CMS-API) も要ります。

## ローカルデモ（1台）

ソース一式から試す場合は `start-local-demo.cmd` をダブルクリックします。アプリをビルドし、このPCのループバックにデモ専用のCMS-API（P2Pのシグナリングだけ、ランダムなテスト用トークン、STUNなし、`.env` は読まない）を起動して、ホストと視聴者のウィンドウを開きます。

ZIP版では、展開先から `bin\cms-p2p-test.bat --local-demo "CMS-APIのフォルダー"` を実行すると同じデモを起動できます。

1. ホスト側で「ホスト開始」。ルームIDが視聴者側にも入ります
2. 視聴者側で「参加」

ウィンドウをすべて閉じると、デモのCMS-APIも止まります。

## 2台のPCで

1. テスト用のCMS-APIを用意します（リモートなら `https://`）。`P2P_SERVER_TOKEN` にはテスト専用の値を使ってください。このトークンを持つ人は誰でもそのAPIにルームを作れるので、本番のMinecraftサーバーの値を視聴者に渡してはいけません。別々のネットワークのPC同士なら、APIの `P2P_STUN_URLS` に使うSTUNサーバー（`stun:` のURL）を設定してください（空のままだと同じLAN内の直接接続しか見つかりません。設定は [CMS-APIのREADME](https://github.com/haruyq/CMS-API) を参照）
2. 各PCで `start.cmd` を実行します。ビルドしたものを持っていく場合は `gradlew -p tools/p2p-test distZip` の `build/distributions/cms-p2p-test.zip` を展開し、`bin\cms-p2p-test.bat` を実行します
3. API URLとテスト用サーバートークンを入れます（環境変数 `CMS_P2P_API_URL` と `CMS_P2P_TEST_TOKEN` があれば最初から入ります。トークンはどこにも保存されません）
4. ホスト側で「ホスト開始」し、「コピー」したルームIDを視聴者に伝えます（トークンは含まれません）。視聴者はルームIDを入れて「参加」します

上り・下りのMbpsは手入力のテスト値で、計測値ではありません。APIはこの値から視聴者1人あたりの予算を決め、ホストは予算を超えないように、また回線が送り切れないぶんが溜まらないように、フレームを間引いて送ります。

「送信映像」で画面を選ぶと、その画面を取り込んで送ります（選ぶまでは取り込みません）。直接つながらない環境（厳しいNATやファイアウォール）では、TURNを使わないので失敗し、その旨を表示します。

## テスト

```
gradlew -p tools/p2p-test test
build\install\cms-p2p-test\bin\cms-p2p-test.bat --smoke <CMS-APIのフォルダー>
```

スモークテスト（`--smoke` と `SmokeTest`）は、デモ専用のCMS-APIと2つのTransportで、視聴者が変化するフレームを復号できること、セッションとルームとAPIが閉じることを確かめます。画面の取り込みはしません。
