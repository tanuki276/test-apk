# CookAi: Play Console入力用メモ

これはコードから整理した提出用の下書きです。実際の本番設定、AIサービスの契約・ログ保持、Play Consoleの質問に合わせて運営者が確定してください。

| 情報 | 用途 | 保存・送信 |
| --- | --- | --- |
| ユーザーID（Firebase UID） | 認証、履歴保護、不正利用防止 | Firebase / Cloudflare。AIプロンプトには含めない |
| メールアドレス | ログイン、履歴復元、パスワード再設定 | 任意のアカウント登録時にFirebase Authenticationで保存 |
| その他のユーザー生成コンテンツ（食材、指示、生成レシピ） | アプリの機能 | Cloudflare経由でAI処理。Firestoreに履歴保存 |
| 健康に関する情報（アレルギー等を入力した場合） | 指定した料理条件への対応 | 任意入力。料理条件と同じ送信・保存経路 |
| 接続情報（IP等） | 通信、安全管理 | Firebase / Cloudflareなどの接続先で処理。保持条件は運営設定を確認 |

- データ収集あり。端末外に送るため「収集なし」にはしない。
- 利用者の入力は任意だが、レシピ生成には食材の入力と送信が必要。
- 転送時の暗号化: HTTPS。
- 削除: 履歴画面で個別削除、設定画面で登録アカウントと全履歴の削除ができる。提供元ログの削除とは別。
- 外部提供: Google Firebase、Cloudflare、Groq、Google Gemini。サービスプロバイダ扱いで共有の例外になるかは実際の契約を確認し、断定しない。
- 広告、解析、Crashlyticsは導入していない。カメラ等の端末権限なし。
- ポリシー本文はアプリ設定内と `docs/PRIVACY_POLICY.md` に収録。ストア掲載には、同じ本文をログイン不要の公開Webページに掲載し、そのURLを登録する。
- アカウント登録機能あり。アプリ内の削除導線を実装済み。ストア提出前に、アプリ外から削除を依頼できる公開窓口・URLも用意して申告する。

公式確認先:
https://support.google.com/googleplay/android-developer/answer/10787469
https://support.google.com/googleplay/android-developer/answer/10144311
