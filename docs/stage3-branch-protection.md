# Stage 3 CI ブランチ保護チェックリスト

設定対象は GitHub の `main` ブランチ。Koichi の別承認を受け、ワークフローの全ゲートが揃ってから適用する。

## 1人運用での必須設定

- [ ] Settings → Branches → Branch protection rules で対象を `main` に指定する。
- [ ] Require a pull request before merging を有効にする。
- [ ] Require status checks to pass before merging を有効にし、`Stage 3 CI` のジョブを必須にする。
  - `Gitleaks`
  - `Semgrep`
  - `Trivy production dependencies`
  - `Path risk and suppressions`
  - `Frontend checks`
  - `Billing API checks`
  - `Search API checks`
  - `Text prep checks`
- [ ] Require branches to be up to date before merging を有効にし、最新のベースに対する必須チェックの通過を求める。
- [ ] ドキュメントのみの PR でも全ジョブが起動し、必須チェックとして表示されることを確認する。

## 複数人・レビュー専用アカウント導入時のオプション

- [ ] PR 作成者と別の承認者を確保できるようになったら、Require approvals を有効にする。
- [ ] 承認を必須にする場合は、Dismiss stale pull request approvals when new commits are pushed を有効にする。

## パス判定

分類結果は `low` と `non-low` の2区分。`non-low` のうち高リスクか中リスクかは v0.4 付録A.2 に従ってレビューで判定する。抑制記述の新規追加は判定ステップを失敗させる。低リスク区分の運用開始条件は Stage 3 CI の稼働後に確認する。

ローカルで PR と同じ差分を分類する場合は、リポジトリ内で `python3 tools/ci/check_paths.py <base-sha> <head-sha>` を実行する。判定は merge base から PR head までのコミット済み差分を使う。

[GitHub のブランチ保護ルール作成手順](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/managing-a-branch-protection-rule)
