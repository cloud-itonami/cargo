# cargo — 海上貨物 actor の *descriptor*

**実装ではない。** ここにあるのは「この actor が何を名乗り、何を要求し、どの
pipeline を持つと宣言しているか」だけで、動くサービスは無い。出自は etzhayyim
monorepo の `20-actors/`（`NOTICE` 参照）。

`cargo` という名前は主題（船荷証券・コンテナ・IMDG 危険物）を言うが、**この repo が
何であるか**は言わない。だから最初に名乗る。

| ファイル | 役割 |
|---|---|
| `actor-manifest.jsonld` | actor 宣言。10 pipeline（cron×2 / subscribeRepos×1 / xrpc×7）、19 step、4 actor path |
| `.well-known/did.json` | DID document（**解決しない** — 下記） |
| `docs/identity-claims.edn` | 名乗りと配信面の**実測値を固定したもの**。テストの期待値 |
| `src/etzhayyim/cargo/descriptor.cljc` | descriptor の適合規則。純粋（I/O 無し） |
| `test/` | 上の規則を壊した fixture で検査し、実ファイルと固定値を突き合わせる |
| `NOTICE` / `.nojekyll` | 出所・ライセンス表示 / GitHub Pages 用（**現役**） |

## 確かめる

散文ではなく実行で確かめられる。

```bash
nbb --classpath src:test run_tests.cljs             # 27 tests / 48 assertions（network 不要）
nbb --classpath src:test run_tests.cljs --network   # 59 assertions（claims を実際に取りに行く）
```

`--network` は `docs/identity-claims.edn` の `:measured` を curl で照合する。
**直った側にも落ちる** —— 例えば `cargo.etzhayyim.com` が生えたら 0 → 200 で赤くなり、
「測り直して README と claims を更新しろ」という意味になる。

## identity が 4 つあり、解決する 1 つをこの repo は名乗っていない

2026-08-08 実測、`docs/identity-claims.edn` に固定済み:

| 名乗り | 出所 | 解決先 | 実測 |
|---|---|---|---|
| `did:web:cargo.etzhayyim.com` | `actor-manifest.jsonld` の `@id` | `https://cargo.etzhayyim.com/.well-known/did.json` | **0**（ホスト不在） |
| `did:web:etzhayyim.github.io:com-etzhayyim-cargo` | `.well-known/did.json` の `id` | `https://etzhayyim.github.io/com-etzhayyim-cargo/did.json` | **404** |
| `did:web:etzhayyim.com:actor:cargo` | **この repo のどこにも無い** | `https://etzhayyim.com/actor/cargo/did.json` | **200** |
| `at://cargo.etzhayyim.com` | `alsoKnownAs[0]` | `https://cargo.etzhayyim.com/.well-known/atproto-did` | **0** |

commit されている DID document は**自分を無効化している**。GitHub Pages
（`https://cloud-itonami.github.io/cargo/`、source: `main` / root、実測 200）で
実際に配信されているが、その配信 URL は自分の `id` が導く URL ではない ——
`id` を辿った resolver は 404 を受け取り、配信 URL を直接読んだ resolver は
`id` 不一致で捨てる。

さらに endpoint も割れている: commit 側が指す `pds.etzhayyim.com` は
Cloudflare 1033（tunnel 不在、実測 530）、解決する側が指す `pds.aozora.app` は
生きている（200）。

経緯は 2026-07-02 の `90b15e4`「migrate did:web to etzhayyim.com scheme」を翌日
`ec7015f` で revert したこと。兄弟の `cloud-itonami/handotai-actor` は revert されず、
`did:web:etzhayyim.com:actor:handotai` の形を commit している。

**どれを正とするかはこの repo が決められない**（live document を発行しているのは
`etzhayyim.com` 側）。だから直さず、**測って固定した**。descriptor がその DID を
名乗るようになったら `the-only-did-that-resolves-is-named-by-no-descriptor-file`
が赤くなる。

## `actor-manifest.test.ts` を撤去した（2026-08-08）

2026-05 から置かれていた vitest の suite を消し、`test/` の nbb suite に置き換えた。

**あれは一度も実行できなかった。** `package.json` も vitest 依存も無い。走らない
まま、次の 2 つが起きても何も報告しなかった:

- `expect(m.pipelines).toHaveLength(8)` と書いていたが、manifest は **10 本**ある
- `expect(m["@id"]).toBe("did:web:cargo.etzhayyim.com")` と書いていたが、
  `.well-known/did.json` は別の DID を名乗るようになった

走らないテストは、自分が古くなったことも報告しない。加えて workspace の規則
（superproject `CLAUDE.md`）で新規の `.ts` / `.mjs` / `.sh` は禁止、script host は
nbb に一本化されている。

引き継いだ検査（`fn: "custom"` の禁止、step の形、nsid の一意性）は `test/` に
残っている。**pipeline 数のような「数」は不変条件として書かず**、
`docs/identity-claims.edn` の `:census` に測定値として固定して、増減どちらでも
赤くなるようにした。

## 既知の欠落

- `@context`（`https://etzhayyim.com/ns/actor/v1`）は **404**。よってこの文書は
  JSON-LD として展開できず、実質ただの JSON である。
- `capabilities` の `agent.invoke` はどの step も呼んでいない（過剰付与）。
- `triggers.subscribeRepos.collections` は 6 件宣言しているが、実際に購読する
  pipeline があるのは `com.etzhayyim.apps.vessel.voyage` の 1 件だけ。
- `NOTICE` が指す `CHARTER-RIDER.md` と、manifest の `complianceDocs` 2 件は
  この repo に無い。

いずれも `docs/identity-claims.edn` に記録済みで、状態が変わればテストが赤くなる。
