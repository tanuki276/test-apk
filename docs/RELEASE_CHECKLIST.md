# リリース確認

## 実装
- compileSdk / targetSdk 36、AGP 8.10.1、Gradle 8.11.1、JDK 17。
- 既存1.1を巻き戻さずVersion 1.2 / versionCode 3を表示。
- カメラUI・コード・文字列、履歴全削除を撤去。
- 停止で端末の通信をキャンセルし、古いコールバックによる表示更新・履歴保存を防ぐ。送信済みAI処理と課金が停止する保証はない。
- Worker: 15秒間隔、UIDあたりUTC日20回、全体UTC日500回（wrangler変数で調整）。失敗・停止も試行数に含む。最大3プロバイダへの試行と出力トークンを制限。
- Durable Objectが未設定・障害の場合は生成を拒否。履歴・食材・IPは予算ストアに保存しない。
- Firestore Rulesは固定名前空間で認証UIDとパスUIDが一致する履歴のみ許可。他のパスは拒否。

## デプロイ時
1. WorkerのSecrets・FirebaseプロジェクトIDと利用するモデルの実在・利用権限を確認し `cd cloudflare-worker && npm install && npm test && npx wrangler deploy`。既存モデル名はこの変更では変更していない。
2. `firebase deploy --only firestore:rules --project liefantidia2` を実行する。既存の本番Rulesは未取得・未確認であり、リポジトリ内のRules追加だけでは本番には適用されない。
3. リリースビルド用にRepository Variablesの`AI_PROXY_URL`を設定する。未指定・サンプルURLではリリースビルドを拒否する。実際のWorker URLを `-PAI_PROXY_URL=https://.../generate-recipe` で指定しビルド。秘密鍵はAPKに入れない。
4. `docs/PRIVACY_POLICY.md` の公開Web URLを用意。運営者名・問い合わせ窓口・保持運用が実態と一致することを確認しData Safetyを申告。
5. ストア用AABを署名して作成。既存の署名設定・ストア登録は本変更の対象外。

## 検証結果（この作業環境）
- NodeでWorkerの7テスト成功（同時アクセス、全体上限、個人上限、日付変更、不正設定、ヘルス応答、認証拒否）。
- Android XML構文チェック、JavaScript構文チェック、git diff --check成功。
- Androidビルド: Gradleは取得できたが、AGP等のMaven依存取得がNetwork is unreachableで失敗。ビルド成功は未確認。
- Firestoreエミュレータで本人のCRUD・一覧読み込み、他UID/未認証の拒否、範囲外パスと不正データの拒否を検証し成功。Worker設定検証のCIも追加。
- Worker dry-runは、ソースのCloudflare送信可能性を理由に自動承認レビューで拒否され未確認。
- 実機・エミュレーター未接続。UIとクラッシュ確認は未実行。

## 必須の実機確認（未確認）
API 26とAPI 36の端末で以下を確認し、機種/OS/結果を記録する。
- 起動、設定、プライバシー本文のスクロール、戻る、履歴の空表示。
- 画面幅320dp/文字サイズ大、各Spinnerの全選択肢、キーボード表示時の余白とボタン。
- 生成成功、長いレシピの末尾までスクロール、コピー・共有・ファイル保存。
- オフライン、混雑429/503、認証失敗からの再試行。
- 生成中の停止→即再生成、画面回転、ホーム移動/復帰。古い結果が表示・保存されないこと。
- 自分の履歴の個別削除、再表示。別UID/未認証では読み書き不可（CIのRulesテストも確認）。
- `adb logcat -b crash` でFATAL EXCEPTIONの有無を確認。
