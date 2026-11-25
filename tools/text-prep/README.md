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

HTTP サイドカー化、青空文庫のルビ・注記剥がし、チャンク分割、邦題生成は今回の対象外です。1,000 文字未満の除外と 6 万文字以下の分岐は、パイプライン側の処理です。
