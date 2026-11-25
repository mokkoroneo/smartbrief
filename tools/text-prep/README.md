# text-prep

SmartBrief の Gutenberg テキスト前処理 CLI。Rust edition 2021 と標準ライブラリだけでビルドできます。

## ビルドと実行

リポジトリのルートからクレートへ移動します。

~~~powershell
cd tools/text-prep
cargo build --offline --release
.\target\release\text-prep.exe strip-gutenberg tests/fixtures/grimm.txt
.\target\release\text-prep.exe count tests/fixtures/count.txt
.\target\release\text-prep.exe title --from-gutendex tests/fixtures/hugo.json
.\target\release\text-prep.exe title --from-text tests/fixtures/austen.txt
~~~

Windows 以外ではバイナリ名に .exe は付きません。すべてのサブコマンドで、ファイルの代わりに標準入力を使えます。ファイル引数を省略するか、引数に - を指定してください。

入力は UTF-8 で、無効なバイト列はエラーになります。標準出力には結果だけを UTF-8 で書きます。警告、処理件数、エラーは標準エラーに出ます。終了コード 0 は成功または警告付き成功、2 は使い方、入出力、検証のエラーです。

## サブコマンド

### strip-gutenberg [FILE|-]

最初の START 行までと、最後の END 行から後ろを、マーカー行を含めて除去します。各行に含まれる Gutenberg の THE と THIS 形式を ASCII の大文字・小文字を区別せずに照合します。片側だけ見つかった場合はその側を除去します。マーカーが見つからない場合や最初の START が最後の END より後ろにある場合は入力をそのまま返し、警告を出します。

残る本文の CRLF や末尾の改行は保持します。削除した行数と、残った Unicode スカラー数を標準エラーの removed_lines と remaining_chars に出します。

### count [FILE|-]

結果は JSON 1 行です。chars は改行を含む Unicode スカラー数、lines は Rust の str::lines() による行数、bytes は UTF-8 バイト数です。行の区切りは LF または CRLF で、末尾の区切りの後ろに空行を加えません。

JavaScript の文字列の .length は UTF-16 コードユニット数です。付属の count.txt にある「日本A😀」と末尾の LF は、chars が 5、.length が 6、bytes が 12 です。この CLI では chars を文字数として扱います。

### title (--from-gutendex|--from-text) [FILE|-]

- --from-gutendex はトップレベルに文字列の title を持つ、単一書籍の JSON オブジェクトを読みます。JSON エスケープと UTF-16 サロゲートペアをデコードします。Gutendex の results 配列形式は未対応です。
- --from-text は最初の START マーカーより前にある、行頭が Title: の行から読みます。START マーカーがない入力では、入力全体から Title: 行を探します。Title: 行の複数行折り返しは未対応で、最初の行だけを使います。Title: 行が見つからない場合はエラーになります。

前後の空白を落とし、両端に同じ引用符があれば、二重引用符または単一引用符を 1 組だけ剥がして再度空白を落とします。空のタイトル、ASCII 数字だけのタイトル、途中に CR または LF を含むタイトルはエラーになります。1984 のような実在作品も、事故再発防止のため数字だけのタイトルは意図的に弾きます。検証後のタイトルに LF を付けて 1 行で返します。

## 対象範囲

Docker Compose への追加、n8n ワークフローの変更、青空文庫のルビ・注記剥がし、チャンク分割、邦題生成は対象外です。1,000 文字未満の除外と 6 万文字以下の分岐は、パイプライン側の処理です。

## HTTP サーバー

クレートのディレクトリでビルドして起動します。

~~~powershell
cargo build --offline --release
.\target\release\text-prep-server.exe --bind 127.0.0.1 --port 18080
~~~

--bind の既定値は 127.0.0.1、--port の既定値は 18080 です。起動時に標準エラーへ listening on に続けて実アドレスを 1 行出力します。--port 0 では OS が割り当てたポートが表示されます。

既定ではループバックだけで待ち受けます。ローカル専用の前提で認証を備えていません。次段で Docker 化する際は、コンテナ間通信のため --bind 0.0.0.0 を指定します。

### エンドポイント

本文は UTF-8 の JSON です。表の N、M、B は整数です。

| メソッドとパス | リクエスト本文 | 成功時の応答（200） |
| --- | --- | --- |
| GET /health | なし | `{"status":"ok"}` |
| POST /strip-gutenberg | `{"text":"<本文>"}` | `{"text":"<除去後本文>","removed_lines":N,"remaining_chars":M,"warning":null}` |
| POST /count | `{"text":"<本文>"}` | `{"chars":N,"lines":M,"bytes":B}` |
| POST /title | `{"source":"text","text":"<Gutenberg 生テキスト>"}` | `{"title":"<原題>"}` |
| POST /title | `{"source":"gutendex","json":"<単一書籍 JSON の生文字列>"}` | `{"title":"<原題>"}` |

除去、統計、タイトル検証には CLI と共通の規則を使います。マーカーがない場合など、除去時に警告があれば warning は警告文字列になります。

Gutendex の json フィールドは、単一書籍 JSON を格納した**文字列**です。書籍オブジェクトから作る場合は、n8n 側でそのオブジェクトに JSON.stringify を適用した結果を json に入れます。

~~~json
{"source":"gutendex","json":"{\"title\":\"Les Mis\\u00e9rables\"}"}
~~~

別の PowerShell での実行例です。

~~~powershell
$base = 'http://127.0.0.1:18080'
Invoke-RestMethod "$base/health"
$text = Get-Content -Raw -Encoding UTF8 tests/fixtures/count.txt
$body = @{ text = $text } | ConvertTo-Json -Compress
Invoke-RestMethod "$base/count" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
~~~

### HTTP とエラー

HTTP/1.1 のリクエストを逐次処理し、応答後に接続を閉じます。POST には Content-Length が必要です。ヘッダー名は大小文字を区別しません。Expect: 100-continue に対応します。読み取りと書き込みのタイムアウトは各 I/O 操作で 15 秒です。

エラーの応答本文は `{"error":"<内容>"}` です。

| ステータス | 条件 |
| --- | --- |
| 400 | JSON、UTF-8、必須フィールド、タイトル検証、または HTTP 形式のエラー。Content-Length の欠落・重複、Transfer-Encoding、16 KiB を超えるリクエストヘッダーも含む |
| 404 | 存在しないパス |
| 405 | パスで許可されていないメソッド |
| 413 | Content-Length が本文上限の 20 MiB（20,971,520 バイト）を超過 |
