# Recon プラグイン セキュリティ・安定性レビュー

対象バージョン: `1.6-SNAPSHOT`
レビュー日: 2026-06-13
対象範囲: プラグイン本体および付属ライブラリ（共有コア / Bukkit-Spigot-Paper-Folia / BungeeCord-Waterfall / Velocity）

---

## 1. アーキテクチャ概要

Recon は RCON の代替となる REST API 型のリモートコマンド実行プラグインである。
設計は「プラットフォーム非依存の共有コア」+「各プラットフォーム実装」という構成になっており、
このレイヤリング自体は良好で、エンタープライズ化の素地がある。

```
共有コア (platform-agnostic)
  ├ http/ReconHttpServer        … JDK内蔵HttpServerでREST APIを提供
  ├ http/RateLimiter            … IP単位レート制限
  ├ crypto/AESCrypto            … AES-256-CBC 暗号化
  ├ util/NonceTracker           … リプレイ防止
  ├ config/*                    … config.yml / users.yml(YAML or SQL) / queues.yml / lang
  ├ command/ReconCommandLogic   … /recon コマンドの共通ロジック
  └ platform/ReconPlatform      … 各実装が満たすインターフェース

プラットフォーム実装
  ├ Recon (Bukkit/Spigot/Paper/Folia) + util/SchedulerUtil + execution/*
  ├ proxy/velocity/ReconVelocity
  └ proxy/bungeecord/ReconBungee  (Waterfall互換)
```

評価サマリ:
- **設計の方向性は良い**（共有コア + プラットフォーム抽象、Folia対応のスケジューラ抽象、複数ストレージバックエンド）。
- 一方で、**並行処理の安全性・HTTPサーバの安定性・暗号プロトコルの強度**にエンタープライズ水準には届かない課題が複数存在する。

重大度の凡例: 🔴 Critical / 🟠 High / 🟡 Medium / 🔵 Low

---

## 2. セキュリティ評価

### 🔴 S-1. 暗号プロトコルの強度（鍵導出・認証付き暗号）→ ✅ プロトコル v2 で解決
旧来の `AESCrypto.deriveKey()` は `SHA-256(password + "_" + nonce + "_" + timestamp)` の **単一ハッシュ**
で鍵を導出し、**AES-CBC（認証なし）** を使用していた。問題点:

- nonce と timestamp は平文で送られるため、暗号文を 1 件入手すればパスワードのみが未知。
  単一 SHA-256 は GPU で高速総当たり可能で **オフライン辞書攻撃に脆弱**。
- AES-CBC は認証がなく、**パディングオラクル攻撃**の理論的余地。
- 平文 HTTP のためメタデータ（user/nonce/timestamp）が平文で流れ、改ざん検知もない。

**実装した解決策（プロトコル v2、後方互換維持）**:
- **AES-256-GCM**（AEAD）でメッセージ改ざんを検知。CBC のパディングオラクルを排除。
- **PBKDF2-HMAC-SHA256**（反復回数を設定可能、自己記述的にリクエストへ含める）で
  オフライン総当たりを困難化。**TLS 証明書なしでも実用的な安全性を確保**。
- **AAD = `user|nonce|timestamp`** を暗号文に束縛し、MITM によるメタデータ改ざんを検知。
- レスポンスも GCM + AAD で暗号化し、サーバがパスワードを知る証明＝**相互認証**を実現。
- v1 は後方互換のため残置。`security.allow-legacy-protocol: false` で **v2 強制**が可能。
- 鍵導出は全チェック（user/IP/timestamp/nonce）通過後に実行され、`iterations` は
  `[設定下限, 1,000,000]` に制限して CPU-DoS を防止。
- 7 言語クライアント（Java/JS/TS/Go/PHP/Python/Dart）も v2 対応。Java↔{Node,PHP,Go} および
  サーバ AESCrypto との相互運用をクロス言語テストで検証済み。

残課題: 通信路自体の TLS は任意（リバースプロキシ前提）。メタデータ（user 名等）の平文露出は仕様上残るが、
改ざんは AAD で検知される。

### 🟠 S-2. パスワードの平文保存
`users.yml` / DB にパスワードが平文保存される。これは「共有秘密から鍵を導出する」対称設計上、サーバ側が
平文を必要とするため**設計上不可避**。ただし最低限、
- ファイルパーミッションの制限・保管場所の注意喚起、
- 将来的には「サーバ側マスタキーで at-rest 暗号化」する余地、
をドキュメント化すべき。

### 🟠 S-3. リクエスト転送（request-forwarding）が認証前・無検証で発火
`ReconHttpServer.handle()` は、**JSONパース・認証・レート判定の前**に `forwardRequestAsync(body)` を呼び、
受信ボディを全転送先へ送っていた。

- 不正・未認証リクエストまで転送され、**増幅・踏み台**に悪用され得る。
- 転送先同士が相互参照すると**無限ループ**になり得る（ループ防止ヘッダなし）。
- 転送 `HttpClient` に**タイムアウト未設定**、停止時に**クローズされない**（リソースリーク）。

→ **修正済み**（§4 F-3）。

### 🟡 S-4. クライアントへのエラーメッセージに内部情報が混入
一部の応答で例外メッセージ（`e.getMessage()`）をそのまま返している箇所があり、内部実装の手掛かりを与え得る。
認証前パスは概ね言語キーで一般化されているが、`error.internal` 等にスタック由来文字列が乗る。
→ 認証前応答の一般化は維持しつつ、サーバ側ログに詳細を残す方針へ整理。

### 🟡 S-5. バインドアドレスが常に全インターフェース（0.0.0.0）
`new InetSocketAddress(port)` のため、API が常に全 NIC で公開される。内部ネットワーク限定運用ができない。
→ **修正済み**: `bind-address` 設定を追加（既定は従来同様＝全インターフェース）。

### 🟡 S-6. リクエストボディのサイズ無制限
`readRequestBody()` がボディを上限なく読み込むため、巨大 POST による**メモリ枯渇 DoS**が可能。
→ **修正済み**: 上限（既定 256KB）を超えると 413 を返す。

### 🟡 S-7. 自動登録パスワードが弱い
`UUID.randomUUID().toString().substring(0, 8)`（16進8文字＝約32bit）。API 認証情報としては短い。
→ **修正済み**: `SecureRandom` ベースの十分長いトークンに変更（Bukkit/Velocity 両リスナー）。

### 🔵 S-8. プロキシ背後での IP 判定
`getRemoteAddress()` をそのまま使用。リバースプロキシ背後では全クライアントが同一 IP に見え、
IP ホワイトリスト/レート制限が機能しない。XFF を**信用しない**現方針はセキュリティ的には正しいので、
「Recon は信頼ネットワークに直接公開する前提」をドキュメント化する。

---

## 3. 安定性・並行処理評価

### 🔴 P-1. HTTP サーバが実質シングルスレッドで、リクエストごとに最大10秒ブロック
`server.setExecutor(null)` は既定エグゼキュータ（ディスパッチスレッド上で**直列実行**）を使う。
一方、各コマンド実行は `future.get(10, TimeUnit.SECONDS)` で**最大10秒ブロック**する。

→ オフラインプレイヤー宛コマンドや重いコマンドが 1 件来ると、**他の全 API リクエストが詰まる**。
実質的に DoS。エンタープライズ運用では致命的。
→ **修正済み**: 名前付き・デーモンの**境界付きスレッドプール**を設定し、停止時にシャットダウン。

### 🔴 P-2. QueueManager がスレッドセーフでない
`queuesConfig`（内部 `LinkedHashMap`）が
- HTTP スレッド（`addToQueue`）、
- スケジューラスレッド（`cleanExpiredEntries`）、
- 参加リスナースレッド/リージョンスレッド（`getAndClearQueue`）、

から**無同期で同時変更**される。`ConcurrentModificationException` やデータ破損を招く。
→ **修正済み**: QueueManager の公開メソッドを `synchronized` 化。

### 🟠 P-3. NonceTracker が毎リクエストで全件スキャン
`useNonce()` が呼び出しのたびに `cleanup()`（マップ全走査 O(n)）を実行。高負荷時に O(n²) 的劣化。
→ **修正済み**: 毎回の全走査を廃止し、エントリ単位の期限判定に変更。全件クリーンは定期タスク（5分毎）に委譲。

### 🟠 P-4. UserManager リロード時の空ウィンドウ
`loadUsers()` が `users.clear()` → `putAll()` の順で更新するため、その**隙間**で `getUser()` が空マップを見て
**正規リクエストが一時的に 401** になり得る。
→ **修正済み**: 新しいマップを構築してから**参照を原子的に差し替える**方式（`volatile`）へ変更。

### 🟠 P-5. DB 書き込みがサーバのメインスレッド/リージョンスレッドをブロック
`/recon create|edit|remove` および参加時の自動登録は `UserManager.addUser()` を**メインスレッドから**呼び、
`SqlUserStorage` は毎回 `DriverManager.getConnection()` で**新規接続**して I/O する。
DB が遅延すると**サーバ本体が固まる**。コネクションプール（HikariCP）も pom で relocate 指定だけされ未使用。

→ 本修正では破壊的変更を避けるため**コード自体は据え置き**、§5 のロードマップ（接続プール＋非同期化）に明記。
   毎接続生成のコストとメインスレッドブロックは、本番規模では要対応。

### 🟡 P-6. 転送 HttpClient のライフサイクル
§S-3 と同件。タイムアウト・クローズ・ループ防止を整備（**修正済み**）。

### 🟡 P-7. SchedulerUtil の遅延クランプ漏れ
`runForEntityLater` の Folia 分岐が `delayTicks` を最小1ティックにクランプしていない（`runGlobalLater` は実施済み）。
→ **修正済み**: `Math.max(1, delayTicks)` を追加。

### 🔵 P-8. config 自動更新ロジックの脆さ
`ConfigManager.updateConfigWithComments()` は行を `:` で分割し値を素朴に再ダンプするため、
値にコロンや YAML 特殊文字を含むと壊れ得る。現状 `config-version` が固定（1）で発火しないが、将来の版上げ時に注意。
→ 値の引用化など堅牢化の余地。ロードマップに記載。

---

## 4. 既存実装の良い点

- プラットフォーム抽象（`ReconPlatform` / `CommandExecutionService` / `PlatformCommandSender`）が明快。
- Folia 検出とリージョン対応スケジューラ（`SchedulerUtil`）をリフレクションで実装し、
  単一 JAR で Bukkit/Paper/Folia を跨いで動作させる方針は妥当。
- ストレージ抽象（YAML / MySQL / MariaDB）とフォールバック、YAML→DB 移行の配慮。
- nonce + timestamp によるリプレイ防止、IP ホワイトリスト（グローバル/ユーザー別）、レート制限といった
  多層防御の枠組みが既に存在。
- 多言語対応・豊富なクライアントライブラリ。

---

## 5. 改善ロードマップ（破壊的変更を要するもの＝今回コード変更しない）

1. ~~**プロトコル v2（S-1）**~~ → ✅ **実装済み**（AES-256-GCM + PBKDF2、v1/v2 併存、v2 強制オプション、
   7 言語クライアント対応、クロス言語相互運用テスト済み）。TLS 自体は任意（リバースプロキシ前提）。

以下は互換性・規模の都合で段階移行が必要なため、引き続きロードマップとして記録する。

2. **DB 接続プール＋非同期化（P-5）**: HikariCP を実依存に追加し、書き込みを専用エグゼキュータでオフロード。
3. **at-rest 暗号化（S-2）**: サーバマスタキーで `users.yml` を暗号化する任意機能。
4. **config 自動更新の堅牢化（P-8）**: 値の YAML 引用・スキーマ駆動マイグレーション。
5. **Argon2 オプション（S-1 強化）**: PBKDF2 に加え Argon2id を選択可能にする（要クライアント対応）。

---

## 6. 本コミットで実施した修正（非破壊・互換維持）

| ID | 内容 | 重大度 |
|----|------|--------|
| F-1 | HTTP サーバに境界付きスレッドプールを設定し、直列ブロッキングを解消（P-1） | 🔴 |
| F-2 | リクエストボディにサイズ上限（既定256KB / 413応答）を追加（S-6） | 🟡 |
| F-3 | request-forwarding を認証前無検証発火から「検証後・ループ防止ヘッダ・タイムアウト・停止時クローズ」へ（S-3/P-6） | 🟠 |
| F-4 | NonceTracker の毎回全走査を廃止しエントリ単位期限判定へ（P-3） | 🟠 |
| F-5 | QueueManager を同期化しスレッドセーフに（P-2） | 🔴 |
| F-6 | UserManager のリロードを原子的参照差し替えに（P-4） | 🟠 |
| F-7 | `bind-address` 設定を追加（S-5、既定は従来動作） | 🟡 |
| F-8 | 自動登録パスワードを SecureRandom ベースの強力なトークンへ（S-7） | 🟡 |
| F-9 | SchedulerUtil の遅延を最小1ティックへクランプ（P-7） | 🟡 |
| F-10 | bStats のプレースホルダチャートを実データ（ストレージ種別）に置換 | 🔵 |
| F-11 | デッドコード（未使用 `cleanColorCodes`）削除・リポジトリ不要物の整理 | 🔵 |

### 追加実装: プロトコル v2（暗号ハードニング、TLS不要で安全性確保）

| 項目 | 内容 |
|------|------|
| サーバ | `AESCrypto` に PBKDF2 + AES-256-GCM を追加（v1 CBC は残置）。`ReconHttpServer` を v1/v2 デュアル対応にし、`protocol`/`iterations` を処理。AAD で user/nonce/timestamp を束縛。 |
| 設定 | `security.allow-legacy-protocol`（v2 強制）と `security.pbkdf2-iterations`（受理する反復回数の下限、既定 100000）を追加。 |
| DoS対策 | 反復回数は `[下限, 1,000,000]` に制限。高コストな鍵導出は user/IP/timestamp/nonce の各チェック通過後にのみ実行。 |
| クライアント | Java / JavaScript / TypeScript / Go / PHP / Python / Dart の全 7 言語を v2 既定に更新（v1 も選択可）。 |
| 検証 | クロス言語相互運用テスト: Java↔Node, Java↔PHP, Java↔Go を双方向で確認。Java クライアント↔サーバ `AESCrypto` を双方向確認。Python は PBKDF2 鍵の一致とワイヤ形式を確認（GCM はライブラリ依存、本サンドボックスのみ実行不可）。Dart は実行環境がないため検証スペックに準拠した移植。 |

**仕様（v2）**:
- 鍵: `PBKDF2-HMAC-SHA256(password, salt="<nonce>_<timestamp>", iterations, 32B)`
- 暗号: `AES-256-GCM`（IV 12B ランダム、タグ 128bit）
- AAD: `"<user>|<nonce>|<timestamp>"`
- ワイヤ形式: `Base64( IV(12) ‖ ciphertext ‖ tag(16) )`

---

## 7. クロスプラットフォーム動作の再確認

| 環境 | 判定 | 要点 |
|------|------|------|
| Bukkit / Spigot | ✅ | メインスレッド経由でコマンド実行。共有コアは Bukkit 非依存。 |
| Paper | ✅ | Bukkit 互換。 |
| **Folia（非同期）** | ✅ | `SchedulerUtil` がリフレクションで GlobalRegionScheduler / EntityScheduler を使用。HTTP スレッド→`runForEntity`/`runGlobal` でリージョンに委譲。F-1 によりスレッドプール化しても、実コマンド実行は必ず正しいリージョンスレッド上で行われる。 |
| **Waterfall（BungeeCord）** | ✅ | `ReconBungee` が独自ランナーを使用。Bukkit/Netty 干渉系コードはロードされない。 |
| **Velocity** | ✅ | `ReconVelocity` が `executeAsync` で実行。共有 HTTP サーバはプラットフォーム非依存。 |

詳細は本ファイル §8 と最終報告を参照。共有コア（`ReconHttpServer` 等）は `org.bukkit` を一切参照せず、
プロキシ環境でも安全にロードされることを確認済み。

---

## 8. 注意事項（運用ドキュメントへ反映推奨）

- Recon は API を直接公開する設計のため、**信頼ネットワーク内**もしくは **TLS 終端リバースプロキシ背後**での運用を推奨。
- プロキシ背後では IP ホワイトリスト/レート制限が proxy IP 基準になる点に留意。
- 現行プロトコル（v1）の暗号強度の限界（S-1）を理解の上、機密環境ではネットワーク層の保護を併用する。
</content>
</invoke>
