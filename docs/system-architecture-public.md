# SmartBrief System Architecture (Public Overview)

**Version:** 2.1
**Last Updated:** 2026-11
**Status:** Production Ready

> **Note**
> 本ドキュメントは、**SmartBrief（AI要約・翻訳SaaSプラットフォーム）** の技術アーキテクチャ概要です。
> 商用運用を前提とした**セキュリティ設計（Zero Trust / HSTS）**、**決済基盤（Stripe）**、および **複合AIパイプライン** の実装詳細について記述します。

---

## 1. システム概要 (System Overview)

SmartBrief は、青空文庫および海外のパブリックドメイン作品を、現代の忙しいユーザー向けに再構築する **時短読書プラットフォーム** です。
完全非同期のバックエンド、マイクロサービス化されたAIバッチ、そして堅牢な決済・認証基盤を組み合わせ、**「読み放題SaaS」** として構築されています。

### 主な技術特性
- **High Performance:** Spring WebFlux (Non-blocking I/O) による高並列処理
- **Secure by Design:** Cloudflare Zero Trust によるオリジン遮蔽と、多層防御アーキテクチャ
- **Automated Operations:** n8n を中核とした、コンテンツ生成からメール配信までの完全自動化
- **Reliable Payment:** Stripe Webhook の冪等性を担保したサブスクリプション管理

---

## 2. アーキテクチャ (High-Level Architecture)

システム全体は Docker Compose によってコンテナ化され、責任範囲ごとに明確に分離されたマイクロサービスアーキテクチャを採用しています。

### 2.1 システム構成図

```mermaid
graph TD
    %% アクセス層
    User((User)) -->|HTTPS / TLS 1.3| CF[Cloudflare Edge]
    CF -->|Zero Trust Tunnel| FE[React Frontend]
    User -->|LINE Messaging| LINE[LINE Bot]
    
    FE -->|REST API / JWT| BE[Spring Boot API]
    
    %% 外部連携 & マイクロサービス
    LINE -->|Webhook| n8n_bot["n8n (LINE Handler)"]
    n8n_bot -->|Internal API| BE
    
    %% アプリケーションコア
    subgraph "Application Core (Docker)"
        BE -->|Async R2DBC| MySQL[("MySQL 8.0")]
        BE -->|Subscription Logic| Stripe[Stripe API]

        BE -->|Vector Search| SearchAPI[Go Search API]

        SearchAPI -->|Embedding| Ollama[Ollama / mxbai-embed-large]

        SearchAPI -->|Vector Store| Qdrant[(Qdrant Vector DB)]

        SearchAPI -->|Reasoning| Groq[Groq / Llama 3.1]
        BE -->|Async Mail Request| n8n_mail["n8n (Email Service)"]
        n8n_mail -->|OAuth2| Gmail[Gmail API]
    end
    
    %% コンテンツ生成ファクトリー
    subgraph "Content Factory (n8n Pipeline)"
        n8n_batch[n8n Batch Workflow] -->|1. Fetch| Gutenberg[Project Gutenberg]
        n8n_batch -->|2. Translate| Vertex["Google Vertex AI<br/>(Gemini 4)"]
        n8n_batch -->|3. Summarize| OpenAI["OpenAI API<br/>(GPT-5.6 Luna)"]
        n8n_batch -->|4. Upsert| MySQL
    end

    classDef container fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
    classDef external fill:#fff3e0,stroke:#ff6f00,stroke-width:2px;
    class FE,BE,n8n_bot,n8n_mail,n8n_batch,MySQL,SearchAPI,Qdrant,Ollama container;
    class User,Stripe,Gutenberg,Vertex,OpenAI,LINE,Gmail,CF,Groq external;
```

---

## 3. バックエンド設計 (Backend Design)

**Tech Stack:** Java 21, Spring Boot 3.5 (WebFlux), Spring Security, R2DBC

### 3.1 リアクティブ・アーキテクチャ (High Performance)
I/O待ち（DBアクセス、外部APIコール）が頻発するSaaSの特性を考慮し、スレッドをブロックしない **Non-blocking I/O** アーキテクチャを採用しています。これにより、最小限のリソースで大量の同時リクエストを処理可能です。

### 3.2 認証・認可 (Identity & Access Management)
- **Stateless Authentication:**
  - JWT (JSON Web Token) を採用し、セッションサーバーに依存しないスケーラブルな認証基盤を構築。
- **Robust Verification Flow:**
  - 仮登録 → 認証トークン発行 → メール到達確認（Verify） → 本登録 の厳格なフローを実装。
  - `is_verified` フラグによるログイン制御と、トークン有効期限管理により不正アカウントを排除。
- **Role-Based Access Control (RBAC):**
  - エンドポイントレベルで `FREE` / `PREMIUM` の権限を厳密に分離。
  - API Gateway 層（Spring Security）で認証不要パス（Webhook等）と認証必須パスをホワイトリスト形式で管理。

### 3.3 データモデル設計 (Data Modeling)
**Spring Data R2DBC** を用いた非同期データアクセス層を構築。

- **User:** 認証情報、サブスクリプション状態、Stripe Customer ID に加え、無料会員の「本日分の閲覧回数」（`daily_view_count` / `last_view_date`）を条件付きアトミック UPDATE で一元管理。
- **Work:** 翻訳本文、要約（Short/Long）、メタデータを格納。
- **UserBookHistory:**
  - ユーザーごとの閲覧履歴を管理（日次カウンター更新と同一トランザクションで記録）。
  - **重複排除ロジック:** 直近60秒以内の同一書籍への連続アクセス時は閲覧回数の消費・履歴記録をスキップし、閲覧権限の不正消費を防止。

### 3.4 決済・サブスクリプション連携 (Payment Integration)
- **Stripe Integration:**
  - Checkout Session によるセキュアなカード情報入力。
  - Billing Portal によるユーザー主導の契約変更・解約フロー。
- **Idempotency (冪等性):**
  - Stripe Webhook（`checkout.session.completed` のみ対象）処理において、イベントIDのアトミックな重複排除（`processed_stripe_events` への `INSERT IGNORE`）を実装。
  - ネットワーク遅延等によるWebhook多重送信時も、重複適用されない堅牢なロジックを確立。

---

## 4. フロントエンド設計 (Frontend Design)

**Tech Stack:** React 19, TypeScript 5.9, React Router v7, Zustand 5, Vite (rolldown-vite), CSS（global）+ theme.ts

### 4.1 UX Optimization
- **Optimistic UI:** データのロード完了を待たずにスケルトンスクリーンを表示し、体感速度を向上。
- **Parallel Data Fetching:** ダッシュボード表示時に「ランキング」「新着」「履歴」のAPIを並列リクエスト（`Promise.all`）し、初期描画時間を短縮。
- **Premium Upsell UI:**
  - 無料会員が制限（10回）に達した際、専用のモーダルへ誘導。
  - 詳細画面では、テキストの一部をグラデーションで隠す「ティーザーUI」により、課金転換率（CVR）向上を狙った設計。

### 4.2 API / 通知の統一 (Consistency)

- **API Client:** `apiClient` によりAPIアクセスを統一（`/api/v1`、`{ ok, status, data, message }` の共通フォーマットで返却）。
- **Toast通知:** 成功/失敗/認証切れ（401）などのメッセージ表示をトーストに集約し、画面ごとの実装差を抑制。
- **JWTの取り扱い:** `authToken` はサーバーから **HttpOnly Cookie**（`Secure` / `SameSite=Lax`）として発行し、フロントエンドの `authStore` はトークンを保持せずブラウザの Cookie 自動送信により認証。
- **状態同期:** 認証状態は `/api/v1/auth/me` の応答に基づいて `authStore` 上のユーザー情報のみを更新し、画面遷移時の不整合を抑止。

### 4.3 Security Implementation
- **XSS対策:** React の自動エスケープ機構に加え、フロントエンド配信層（Nginx）の Content Security Policy (CSP: `default-src 'self'; script-src 'self'` を軸に、Google Fonts および `https://assets.smartbrief.jp` の画像のみ許可) でスクリプト実行元を厳密に制限。
- **機密情報の保護:** LocalStorage にはパスワードや JWT 等の機密情報を保存せず、認証トークンは HttpOnly Cookie で管理。

---

## 5. オートメーション & AIパイプライン (Automation)

**Tech Stack:** n8n, Google Vertex AI, OpenAI API

### 5.1 コンテンツ・ファクトリー (Content Factory)
手動運用ゼロを目指し、n8n をオーケストレーターとして以下のバッチ処理を定期実行しています。

1. **Source Acquisition:** Project Gutenberg / 青空文庫からパブリックドメイン作品を自動収集。
2. **Context-Aware Translation:** 長文脈に強い **Gemini 4 (Vertex AI)** を使用し、作品全体のトーンを維持した自然な翻訳を実行。
3. **Summarization:** **GPT-5.6 Luna** により、3段階（キャッチコピー・要約・解説）のメタデータを生成。
4. **Data Upsert:** 生成結果を MySQL に格納し、即座にフロントエンドへ反映。

### 5.2 疎結合なメッセージング (Decoupled Messaging)
メール配信やLINE通知などの「副作用」を伴う処理は、メインのAPIサーバーから切り離し、n8n の Webhook へオフロード。
- **Gmail API (OAuth2)**: バックエンドからのHTTPリクエストをトリガーに、認証メールやパスワードリセットメールを即時配信。
- **LINE Messaging API**: ユーザーの操作（検索、ID連携）に対し、非同期で応答メッセージを生成・送信。

---

## 6. セキュリティとインフラ (Security & Infrastructure)

本番運用（Production）を前提とした**多層防御 (Defense in Depth)** を実装しています。

### 6.1 ネットワークセキュリティ
- **Cloudflare Zero Trust (Tunnel):**
  - オリジンサーバーのインバウンドポート（80/443含む全ポート）を物理的に閉鎖。
  - Cloudflare エッジ経由のトラフィックのみを許可し、DDoS攻撃やポートスキャンを無効化。

### 6.2 通信セキュリティ
- **Qualys SSL Labs A+ Rating:**
  - **TLS 1.3** の強制および、脆弱な暗号スイートの排除。
  - **HSTS (HTTP Strict Transport Security)** ヘッダ（max-age=6ヶ月）の付与により、中間者攻撃を防止。

### 6.3 アプリケーションセキュリティ
- **Environment Isolation:** APIキーやDBパスワードはすべて環境変数（`.env`）で注入し、コードベースから完全に分離。
- **Security Headers:**
  - `Content-Security-Policy`: XSS対策（`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: https://assets.smartbrief.jp; connect-src 'self'; frame-ancestors 'self'`）
  - `X-Content-Type-Options: nosniff`: MIMEスニフィング防止
  - `X-Frame-Options: SAMEORIGIN`: クリックジャッキング防止

- **Rate Limiting:** 認証系エンドポイント（ログイン／登録／パスワードリセット／認証メール再送）に対し、実IP（CF-Connecting-IP）基準で10回/分の制限をアプリ層（WebFilter）で実施。超過時は 429 と `Retry-After` ヘッダを返却。

- **Account Enumeration Protection:** パスワードリセット／認証メール再送は、登録の有無やアカウント状態にかかわらず同一のステータス・本文を返し、メールアドレスの存在を秘匿。

---

### 付録: 開発・運用環境

- **Repository:** GitHub (Feature Branch Flow)
- **Deployment:** Docker Compose による手動デプロイ（GitHub Actions による CI/CD 自動化は今後の課題）
- **Monitoring:** Cloudflare Analytics, Docker Container Logs

---

*このドキュメントは外部公開用に構成された技術概要です。内部的なIPアドレス、認証キー、詳細なAPI仕様については、非公開の内部ドキュメントを参照してください。*