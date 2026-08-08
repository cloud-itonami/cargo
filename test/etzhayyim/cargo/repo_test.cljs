(ns etzhayyim.cargo.repo-test
  "**この repo に実際に commit されているファイル**を検査する。

  descriptor-test が『規則が落ちること』を fixture で見せるのに対し、こちらは
  『実物がその規則を通ること』と『docs/identity-claims.edn の測定値が実物と
  一致すること』を見る。network は要らない —— network を要る検査は
  network-test にある。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cljs.reader :as reader]
            [etzhayyim.cargo.descriptor :as d]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def repo-root (.cwd js/process))

(defn- slurp* [rel] (.readFileSync fs (path/join repo-root rel) "utf8"))
(defn- json* [rel] (js->clj (js/JSON.parse (slurp* rel))))
(defn- exists? [rel] (.existsSync fs (path/join repo-root rel)))

(def manifest (json* "actor-manifest.jsonld"))
(def did-doc (json* ".well-known/did.json"))
(def claims (reader/read-string (slurp* "docs/identity-claims.edn")))

(defn- claim [id] (first (filter #(= id (:id %)) (:claims claims))))
(defn- surface [id] (first (filter #(= id (:id %)) (:surfaces claims))))

;; ── 構造 ────────────────────────────────────────────────────────────────────

(deftest the-committed-descriptor-has-no-structural-violations
  "実物が規則を通ること。落ちたら、違反の中身をそのまま出す ——
  「N 件あります」だけ言うテストは、直す人にとって役に立たない。"
  (let [violations (d/check manifest did-doc)]
    (is (= [] violations)
        (str "違反 " (count violations) " 件:\n"
             (str/join "\n" (map #(str "  " (:rule %) " — " (:detail %)) violations))))))

;; ── census（drift 検出）─────────────────────────────────────────────────────

(deftest the-pinned-census-matches-the-manifest
  "**この repo が 2 年半近く見逃していた種類の drift をここで止める。**

  撤去した actor-manifest.test.ts は `toHaveLength(8)` と書いていたが、
  manifest は 10 本に増えていた。走らないテストは、自分が古くなったことも
  報告しない。ここは increase でも decrease でも赤くなり、赤くなったら
  『manifest を戻せ』ではなく『identity-claims.edn を測り直して更新しろ』
  という意味になる。"
  (is (= (:census claims) (d/census manifest))))

;; ── identity ────────────────────────────────────────────────────────────────

(deftest every-identity-claim-names-the-did-its-source-file-actually-says
  "claims の :did が、その :source が指すファイルの実際の値であること。
  claims 側だけを直して descriptor を直し忘れる（あるいは逆）を止める。"
  (testing ":did/manifest は actor-manifest.jsonld の @id"
    (is (= (:did (claim :did/manifest)) (get manifest "@id"))))
  (testing ":did/committed は .well-known/did.json の id"
    (is (= (:did (claim :did/committed)) (get did-doc "id"))))
  (testing ":aka/at-handle は did.json の alsoKnownAs の 1 件目"
    (is (= (:did (claim :aka/at-handle)) (first (get did-doc "alsoKnownAs"))))))

(deftest the-only-did-that-resolves-is-named-by-no-descriptor-file
  "この actor の identity の要点。**唯一解決する DID
  (did:web:etzhayyim.com:actor:cargo) を、descriptor 側のどのファイルも
  名乗っていない。** 直ったら（= descriptor がその DID を名乗るようになったら）
  ここが赤くなり、claims と README を測り直せと言う。"
  (let [live (:did (claim :did/live))]
    (is (false? (:in-repo? (:measured (claim :did/live)))))
    (is (not (str/includes? (slurp* "actor-manifest.jsonld") live))
        "actor-manifest.jsonld が live DID を名乗るようになった")
    (is (not (str/includes? (slurp* ".well-known/did.json") live))
        ".well-known/did.json が live DID を名乗るようになった")))

(deftest the-claims-file-derives-each-resolution-url-from-its-own-did
  "`:resolves-to` を手で書かせない。手書きの URL は、did:web の解決規則を
  1 箇所間違えただけで『存在しない URL を測って 404 だと報告する』——
  測定は動いているように見えるので、間違いに気づく手がかりが無い。"
  (doseq [c (:claims claims)]
    (testing (str (:id c))
      (is (= (:resolves-to c)
             (or (d/did->document-url (:did c))
                 (d/at-handle->did-url (:did c))))))))

;; ── repo 内の参照 ───────────────────────────────────────────────────────────

(deftest the-dangling-references-recorded-are-still-dangling
  "`:exists? false` と記録した参照が、今も本当に repo に無いこと。
  誰かが CHARTER-RIDER.md を足したら赤くなり、claims と README を直せと言う。"
  (doseq [r (:dangling-references claims)]
    (testing (:refers-to r)
      (is (= (:exists? r) (exists? (:refers-to r)))))))

(deftest the-compliance-docs-the-manifest-points-at-are-the-ones-recorded
  "manifest の complianceDocs が増減したら、dangling の記録も測り直す。
  記録側だけが古くなるのを止める。"
  (is (= (set (filter #(str/starts-with? % "90-docs/") (get manifest "complianceDocs")))
         (set (keep #(when (str/starts-with? (:refers-to %) "90-docs/") (:refers-to %))
                    (:dangling-references claims))))))

;; ── 走らないテストを二度と置かない ──────────────────────────────────────────

(deftest no-test-file-sits-here-without-a-runner
  "撤去した actor-manifest.test.ts は vitest を import していたが、
  package.json も node_modules も無く、**一度も実行できなかった**。
  走らないテスト file は、緑にも赤にもならないぶん、無いより悪い ——
  『テストがある』という外見だけが残る。

  workspace の規則（superproject CLAUDE.md）でも新規 .ts / .mjs / .sh は禁止で、
  script host は nbb に一本化されている。"
  (let [ts (filter #(str/ends-with? % ".ts") (.readdirSync fs repo-root))]
    (is (= [] (vec ts))
        (str "runner の無い test file が戻っている: " (str/join ", " ts)))))
