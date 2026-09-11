(ns etzhayyim.cargo.descriptor-test
  "適合規則そのものを、**壊した fixture** で検査する。

  実ファイルに当てるだけの suite は、規則が何も見ていなくても緑になる ——
  違反が 0 件なのか、規則が 0 個なのか、外から区別が付かない。だからここでは
  well-formed な fixture を 1 つ置き、規則ごとに 1 箇所だけ壊して
  「その規則が実際に上がること」を見る。

  各 deftest の docstring は「どの退行を塞いでいるか」を書く。塞いでいる先が
  書けない test は水増しである。"
  (:require [clojure.test :refer [deftest is testing]]
            [etzhayyim.cargo.descriptor :as d]))

;; ── fixture ─────────────────────────────────────────────────────────────────
;; 実物 (actor-manifest.jsonld) ではなく最小形。実物は repo-test が当てる。
;; ここで実物を使うと、実物が変わるたびに規則のテストが落ちる。

(def ok-manifest
  {"name" "cargo"
   "capabilities" ["graph.query" "agent.chat"]
   "actors" [{"path" "bl:master"}]
   "triggers" {"subscribeRepos" {"collections" ["com.etzhayyim.apps.vessel.voyage"
                                                "com.etzhayyim.apps.cargo.container"]}}
   "pipelines"
   [{"trigger" {"type" "cron" "cron" "0 */8 * * *"}
     "steps" [{"id" "q" "fn" "graph.query" "args" {}}]}
    {"trigger" {"type" "xrpc" "nsid" "com.etzhayyim.apps.cargo.health"}
     "steps" [{"id" "h" "fn" "agent.chat" "args" {}}]}
    {"trigger" {"type" "subscribeRepos" "collections" ["com.etzhayyim.apps.vessel.voyage"]}
     "steps" [{"id" "s" "fn" "graph.query" "args" {}}]}]})

(def ok-did-doc
  {"id" "did:web:example.test:actor:cargo"
   "alsoKnownAs" ["at://cargo.example.test"]
   "service" [{"id" "did:web:example.test:actor:cargo#atproto_pds"
               "type" "AtprotoPersonalDataServer"
               "serviceEndpoint" "https://pds.example.test"}]})

(defn- rules
  "違反の :rule 集合。件数ではなく種類で見る —— 1 つ壊すと連鎖して複数上がる
  規則があり、件数で書くと fixture を触るたびに数合わせが要る。"
  [violations]
  (into #{} (map :rule violations)))

(defn- pipeline-step
  "pipeline `i` の最初の step の `k` を `v` に差し替えた manifest。"
  [manifest i k v]
  (assoc-in manifest ["pipelines" i "steps" 0 k] v))

;; ── 基準 ────────────────────────────────────────────────────────────────────

(deftest a-well-formed-descriptor-has-no-violations
  "以下の各テストは『壊すと上がる』ことしか見ない。壊していないときに何も
  上がらないことを先に固定しておかないと、規則が常時発火していても気づけない。"
  (is (= [] (d/check ok-manifest ok-did-doc))))

;; ── capability ──────────────────────────────────────────────────────────────

(deftest a-step-that-calls-an-undeclared-capability-is-a-violation
  "capability 宣言を通らない fn を step が呼べてしまうと、deny-by-default が
  descriptor の面では成立しない。実装側が宣言を読んで権限を絞る前提なので、
  ここが素通りすると『宣言にない権限で動く actor』が作れる。"
  (is (contains? (rules (d/check-manifest (pipeline-step ok-manifest 0 "fn" "graph.write")))
                 :capability/undeclared)))

(deftest the-custom-escape-hatch-is-a-violation
  "fn \"custom\" は任意コードへの逃げ道。1 つ通ると『この descriptor は宣言だけで
  閉じている』という主張がそこで終わる。撤去した vitest も見ていた唯一の
  capability 規則で、これだけは引き継ぐ価値がある。"
  (is (contains? (rules (d/check-manifest (-> ok-manifest
                                              (update "capabilities" conj "custom")
                                              (pipeline-step 0 "fn" "custom"))))
                 :capability/custom-escape-hatch)))

;; ── step の形 ───────────────────────────────────────────────────────────────

(deftest a-step-missing-id-fn-or-args-is-a-violation
  "args が無い step は host が実行時に初めて落ちる。descriptor は実行前に
  読まれる文書なので、そこで落とせるものを実行時まで持ち越さない。"
  (testing "args"
    (is (contains? (rules (d/check-manifest (update-in ok-manifest ["pipelines" 0 "steps" 0] dissoc "args")))
                   :step/missing-key)))
  (testing "id"
    (is (contains? (rules (d/check-manifest (update-in ok-manifest ["pipelines" 0 "steps" 0] dissoc "id")))
                   :step/missing-key))))

;; ── trigger ─────────────────────────────────────────────────────────────────

(deftest an-unknown-trigger-type-is-a-violation
  "型の打ち間違い（\"xrcp\"）と、新種の trigger の追加は、descriptor の上では
  同じに見える。どちらも報告させて、人が区別する。"
  (is (contains? (rules (d/check-manifest (assoc-in ok-manifest ["pipelines" 1 "trigger" "type"] "xrcp")))
                 :trigger/unknown-type)))

(deftest a-cron-that-is-not-five-fields-is-a-violation
  "6 フィールド（秒付き）の cron を 5 フィールドとして読む host は、
  同じ文字列を**別の時刻**として解釈する。落ちずにズレるので気づけない。"
  (is (contains? (rules (d/check-manifest (assoc-in ok-manifest ["pipelines" 0 "trigger" "cron"] "0 0 */8 * * *")))
                 :trigger/cron-not-five-fields)))

;; ── xrpc ────────────────────────────────────────────────────────────────────

(deftest a-duplicate-xrpc-nsid-is-a-violation
  "同じ nsid が 2 本あると後勝ちになり、片方が黙って到達不能になる。
  pipeline は消えていないので、一覧を見ても気づけない。"
  (let [dup (update ok-manifest "pipelines" conj
                    {"trigger" {"type" "xrpc" "nsid" "com.etzhayyim.apps.cargo.health"}
                     "steps" [{"id" "h2" "fn" "graph.query" "args" {}}]})]
    (is (contains? (rules (d/check-manifest dup)) :xrpc/duplicate-nsid))))

(deftest an-nsid-outside-the-actor-namespace-is-a-violation
  "改名を半分だけやった痕跡を捕まえる。この repo は 2026-07-02 に did:web の
  改名を入れて翌日 revert しており（90b15e4 → ec7015f）、名前が複数の面に
  散らばると片側だけ動く、という事故を既に一度起こしている。"
  (is (contains? (rules (d/check-manifest (assoc-in ok-manifest ["pipelines" 1 "trigger" "nsid"]
                                                    "com.etzhayyim.apps.vessel.health")))
                 :xrpc/nsid-outside-namespace)))

;; ── subscribeRepos ──────────────────────────────────────────────────────────

(deftest subscribing-to-an-undeclared-collection-is-a-violation
  "pipeline が購読する collection が冒頭の宣言に無いと、宣言を読んで
  firehose を絞る側は、その pipeline に必要なイベントを渡さない。
  pipeline は起動するが一度も発火しない —— 最も気づきにくい壊れ方。"
  (is (contains? (rules (d/check-manifest (assoc-in ok-manifest ["pipelines" 2 "trigger" "collections"]
                                                    ["com.etzhayyim.apps.vessel.portCall"])))
                 :subscribe/undeclared-collection)))

;; ── DID document ────────────────────────────────────────────────────────────

(deftest a-did-document-service-id-must-be-a-fragment-of-its-own-did
  "service id が別の DID を指したまま残るのは、改名を半分だけやった形そのもの。
  document 全体は妥当に見えるので、id を突き合わせない限り通ってしまう。"
  (is (contains? (rules (d/check-did-document
                         (assoc-in ok-did-doc ["service" 0 "id"]
                                   "did:web:cargo.example.test#atproto_pds")))
                 :did/service-id-foreign)))

(deftest an-unresolvable-did-is-a-violation
  "did:web 以外・空の id は、そもそも document の置き場所を導けない。"
  (testing "別 method"
    (is (contains? (rules (d/check-did-document (assoc ok-did-doc "id" "did:plc:abc123")))
                   :did/unresolvable-id)))
  (testing "method-specific id が空"
    (is (contains? (rules (d/check-did-document (assoc ok-did-doc "id" "did:web:")))
                   :did/unresolvable-id))))

(deftest a-relative-also-known-as-is-a-violation
  "alsoKnownAs は絶対 URI。相対だと、誰から見た相対かが文書に書いていない。"
  (is (contains? (rules (d/check-did-document (assoc ok-did-doc "alsoKnownAs" ["/cargo"])))
                 :did/aka-not-absolute)))

;; ── did:web の解決規則 ──────────────────────────────────────────────────────

(deftest did-web-resolution-follows-the-well-known-rule
  "この派生を間違えると、identity-claims.edn の :resolves-to が全部ズレる ——
  そして network 検査は『測っている URL が違う』まま緑になる。
  規則は 1 つだけ非対称: **path 成分があるときは /.well-known/ を挟まない。**"
  (testing "path 成分なし → /.well-known/did.json"
    (is (= "https://cargo.etzhayyim.com/.well-known/did.json"
           (d/did->document-url "did:web:cargo.etzhayyim.com"))))
  (testing "path 成分あり → そのまま path/did.json"
    (is (= "https://etzhayyim.com/actor/cargo/did.json"
           (d/did->document-url "did:web:etzhayyim.com:actor:cargo")))
    (is (= "https://etzhayyim.github.io/com-etzhayyim-cargo/did.json"
           (d/did->document-url "did:web:etzhayyim.github.io:com-etzhayyim-cargo"))))
  (testing "ポートは %3A で percent-encode されている"
    (is (= "https://localhost:8080/.well-known/did.json"
           (d/did->document-url "did:web:localhost%3A8080"))))
  (testing "did:web でないものは nil（例外にしない —— 判定に使う）"
    (is (nil? (d/did->document-url "did:plc:abc123")))
    (is (nil? (d/did->document-url "did:web:")))
    (is (nil? (d/did->document-url nil)))))

(deftest percent-decoding-leaves-broken-escapes-alone
  "壊れた `%` を捨てる実装にすると、壊れた入力が**正しく見える host** に化ける。
  落ちない壊れ方なので、捨てる側は永久に気づけない。"
  (is (= "a:b" (d/percent-decode "a%3Ab")))
  (is (= "a%zzb" (d/percent-decode "a%zzb")))
  (is (= "a%" (d/percent-decode "a%")))
  (is (= "A" (d/percent-decode "%41"))))

;; ── census ──────────────────────────────────────────────────────────────────

(deftest the-census-counts-what-the-manifest-actually-has
  "census は不変条件ではなく測定値。撤去した vitest は
  `expect(m.pipelines).toHaveLength(8)` を**不変条件として**書いており、
  実際は 10 本ある。count は pin して両方向に落とすものであって、
  正しさの主張ではない。"
  (let [c (d/census ok-manifest)]
    (is (= 3 (:pipeline-count c)))
    (is (= {"cron" 1 "xrpc" 1 "subscribeRepos" 1} (:pipelines-by-trigger c)))
    (is (= 3 (:step-count c)))
    (is (= 1 (:xrpc-nsid-count c))))
  (testing "使われていない capability を数える（過剰付与の検出）"
    (is (= ["agent.chat"]
           (:unexercised-capabilities (d/census (pipeline-step ok-manifest 1 "fn" "graph.query"))))))
  (testing "宣言だけして購読しない collection を数える"
    (is (= ["com.etzhayyim.apps.cargo.container"]
           (:declared-but-unsubscribed-collections (d/census ok-manifest))))))
