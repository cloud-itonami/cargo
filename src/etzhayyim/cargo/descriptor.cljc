(ns etzhayyim.cargo.descriptor
  "cargo actor descriptor の適合規則。**純粋** —— ファイルも network も読まない。

   入力は既に parse 済みのデータ（JSON 由来なので **キーは文字列のまま**扱う。
   keywordize すると `\"@id\"` や `\"@context\"` が壊れるので、境界で変換しない）。
   出力は違反の vector。空なら適合。

   ## なぜ「純粋な規則」と「実ファイルの検査」を分けるか

   この repo には 2026-05 から `actor-manifest.test.ts`（vitest）が置かれていた。
   しかし `package.json` も vitest も無く、**一度も実行されたことがない**。その間に

     - manifest の pipeline は 8 本から **10 本**に増え（test は 8 を assert していた）
     - `@id` と `.well-known/did.json` の `id` が**別の DID に割れた**

   のに、test file はどちらも報告しなかった。走らないテストは、自分が古くなった
   ことも報告できない。だからここでは、規則を純粋な関数として書いて
   **fixture で「その規則が実際に落ちる」ことを先に見せてから**、実ファイルに当てる。"
  (:require [clojure.string :as str]))

;; ── did:web の解決 ──────────────────────────────────────────────────────────
;;
;; W3C did:web: method-specific id の ':' を '/' に置き換えて https:// を付ける。
;; **path 成分が無いときだけ** `/.well-known/` を挟む。host のポートは `%3A` で
;; percent-encode されている（`did:web:localhost%3A8080`）。
;;
;; percent-decode は host 側にしか要らないが、`decodeURIComponent` /
;; `URLDecoder` を使うと reader conditional が要る。3 文字の規則なので
;; 自分で書く —— そのぶん fixture で直接テストできる。

(def ^:private hex-alphabet "0123456789abcdef")

(defn- hex-digit
  "16 進 1 桁の値。16 進でなければ nil。

   **char code で書かない。** `(int c)` は JVM では char の code point を返すが、
   ClojureScript では文字が 1 文字の文字列なので数値への強制になる —— 同じ式が
   両 runtime で違う意味になり、しかも例外を投げずに違う答えを出す。
   ここは文字列の索引で書く（両方で同じ 1 つの意味しか持たない）。"
  [c]
  (let [s (str c)]
    (when (= 1 (count s))
      (let [i (.indexOf hex-alphabet (str/lower-case s))]
        (when (nat-int? i) i)))))

(defn percent-decode
  "`%XX` を 1 バイト文字に戻す。壊れた `%` はそのまま残す（捨てない —— 捨てると
   壊れた入力が正しい host に化ける）。"
  [s]
  (loop [cs (seq s) out []]
    (if-let [c (first cs)]
      (if (and (= \% c) (>= (count cs) 3))
        (let [h (hex-digit (nth cs 1)) l (hex-digit (nth cs 2))]
          (if (and h l)
            (recur (drop 3 cs) (conj out (char (+ (* 16 h) l))))
            (recur (rest cs) (conj out c))))
        (recur (rest cs) (conj out c)))
      (str/join out))))

(defn did->document-url
  "did:web の DID から、DID document が置かれているべき URL を導く。
   did:web でないもの・空のものは nil。"
  [did]
  (when (and (string? did) (str/starts-with? did "did:web:"))
    (let [msi (subs did (count "did:web:"))
          segs (remove str/blank? (str/split msi #":"))]
      (when (seq segs)
        (let [host (percent-decode (first segs))
              path (rest segs)]
          (if (seq path)
            (str "https://" host "/" (str/join "/" path) "/did.json")
            (str "https://" host "/.well-known/did.json")))))))

(defn at-handle->did-url
  "AT Protocol の handle（`at://<host>`）から、その handle が DID を宣言している
   べき URL を導く。did:web とは別の規則なので、did->document-url に混ぜない。"
  [handle]
  (when (and (string? handle) (str/starts-with? handle "at://"))
    (let [host (subs handle (count "at://"))]
      (when-not (str/blank? host)
        (str "https://" host "/.well-known/atproto-did")))))

;; ── manifest の読み取り（純粋な射影）────────────────────────────────────────

(def known-trigger-types
  "actor-manifest v1 が知っている trigger の型。ここに無い型は「新しく増えた」
   のか「打ち間違えた」のか区別が付かないので、必ず違反として上げる。"
  #{"cron" "subscribeRepos" "xrpc"})

(defn pipelines [manifest] (vec (get manifest "pipelines")))

(defn all-steps
  "`[pipeline-index step]` の列。pipeline を跨いだ検査に使う。"
  [manifest]
  (vec (mapcat (fn [i p] (map (fn [s] [i s]) (get p "steps")))
               (range) (pipelines manifest))))

(defn step-fns [manifest] (into #{} (map (fn [[_ s]] (get s "fn")) (all-steps manifest))))

(defn xrpc-nsids [manifest]
  (vec (keep #(when (= "xrpc" (get-in % ["trigger" "type"])) (get-in % ["trigger" "nsid"]))
             (pipelines manifest))))

(defn subscribed-collections
  "pipeline が実際に購読している collection（manifest 冒頭の `triggers` 宣言ではなく）。"
  [manifest]
  (into #{} (mapcat #(when (= "subscribeRepos" (get-in % ["trigger" "type"]))
                       (get-in % ["trigger" "collections"]))
                    (pipelines manifest))))

(defn declared-collections [manifest]
  (vec (get-in manifest ["triggers" "subscribeRepos" "collections"])))

(defn unexercised-capabilities
  "宣言されているのに、どの step も使っていない capability。
   deny-by-default の workspace で「使わない権限を持っている」のは過剰付与である。"
  [manifest]
  (let [used (step-fns manifest)]
    (vec (remove used (get manifest "capabilities")))))

(defn census
  "descriptor の**国勢調査**。不変条件ではなく「今こうなっている」という測定値。
   `docs/identity-claims.edn` に固定して、増えても減っても赤くする。

   count を不変条件として書かない理由: `expect(m.pipelines).toHaveLength(8)` は
   まさに 10 に増えて嘘になった assertion である。count は pin して**両方向に**
   落とすもので、正しさの主張ではない。"
  [manifest]
  (let [ps (pipelines manifest)]
    {:pipeline-count (count ps)
     :pipelines-by-trigger (frequencies (map #(get-in % ["trigger" "type"]) ps))
     :step-count (count (all-steps manifest))
     :actor-count (count (get manifest "actors"))
     :capability-count (count (get manifest "capabilities"))
     :xrpc-nsid-count (count (xrpc-nsids manifest))
     :unexercised-capabilities (unexercised-capabilities manifest)
     :declared-but-unsubscribed-collections
     (let [subbed (subscribed-collections manifest)]
       (vec (remove subbed (declared-collections manifest))))}))

;; ── 規則 ────────────────────────────────────────────────────────────────────

(defn- v [rule detail] {:rule rule :detail detail})

(defn check-manifest
  "actor-manifest.jsonld 単体の不変条件。"
  [manifest]
  (let [caps (set (get manifest "capabilities"))
        ps (pipelines manifest)
        nsids (xrpc-nsids manifest)
        lexicon-prefix (str "com.etzhayyim.apps." (get manifest "name") ".")]
    (vec
     (concat
      ;; step の形。id/fn/args のどれかが欠けた step は、host が実行時に初めて落ちる。
      (for [[i s] (all-steps manifest)
            k ["id" "fn" "args"]
            :when (nil? (get s k))]
        (v :step/missing-key (str "pipeline " i " の step に \"" k "\" が無い: " (pr-str s))))

      ;; capability。step が呼ぶ fn は宣言されていなければならない（過小宣言）。
      (for [[i s] (all-steps manifest)
            :let [f (get s "fn")]
            :when (and f (not (caps f)))]
        (v :capability/undeclared (str "pipeline " i " の step が未宣言の fn \"" f "\" を呼ぶ")))

      ;; "custom" は任意コードへの逃げ道。descriptor が宣言だけで閉じている
      ;; という主張が、ここ 1 つで崩れる。
      (for [[i s] (all-steps manifest)
            :when (= "custom" (get s "fn"))]
        (v :capability/custom-escape-hatch (str "pipeline " i " の step が fn \"custom\" を使う")))

      ;; trigger の型
      (for [[i p] (map vector (range) ps)
            :let [t (get-in p ["trigger" "type"])]
            :when (not (known-trigger-types t))]
        (v :trigger/unknown-type (str "pipeline " i " の trigger type \"" t "\" は未知")))

      ;; cron は 5 フィールド。3 フィールドの cron は host によって黙って別の
      ;; 時刻に走る（秒付き 6 フィールドと取り違える）。
      (for [[i p] (map vector (range) ps)
            :let [c (get-in p ["trigger" "cron"])]
            :when (and (= "cron" (get-in p ["trigger" "type"]))
                       (not= 5 (count (str/split (str/trim (str c)) #"\s+"))))]
        (v :trigger/cron-not-five-fields (str "pipeline " i " の cron \"" c "\" が 5 フィールドでない")))

      ;; nsid は一意。重複すると後勝ちで、片方が黙って到達不能になる。
      (for [[nsid n] (frequencies nsids) :when (> n 1)]
        (v :xrpc/duplicate-nsid (str "nsid \"" nsid "\" が " n " 本の pipeline にある")))

      ;; nsid の名前空間。actor の name と食い違う nsid は、改名を半分だけ
      ;; やった痕跡である（この repo が既に 1 度やったこと）。
      (for [nsid nsids :when (not (str/starts-with? nsid lexicon-prefix))]
        (v :xrpc/nsid-outside-namespace (str "nsid \"" nsid "\" が \"" lexicon-prefix "\" で始まらない")))

      ;; 実際に購読する collection は、冒頭の宣言に含まれていなければならない。
      ;; （逆向き＝宣言だけして購読しないものは census で pin する。）
      (let [declared (set (declared-collections manifest))]
        (for [c (subscribed-collections manifest) :when (not (declared c))]
          (v :subscribe/undeclared-collection
             (str "pipeline が購読する \"" c "\" が triggers.subscribeRepos.collections に無い"))))))))

(defn check-did-document
  "`.well-known/did.json` 単体の不変条件。"
  [doc]
  (let [id (get doc "id")]
    (vec
     (concat
      (when-not (did->document-url id)
        [(v :did/unresolvable-id (str "id \"" id "\" から document URL を導けない"))])

      ;; service の id は DID 自身のフラグメントでなければならない。
      ;; 改名を半分だけやると、ここが古い DID のまま残る。
      (for [s (get doc "service")
            :let [sid (get s "id")]
            :when (not (str/starts-with? (str sid) (str id "#")))]
        (v :did/service-id-foreign (str "service id \"" sid "\" が \"" id "#\" で始まらない")))

      ;; alsoKnownAs は絶対 URI。相対だと解決できない。
      (for [aka (get doc "alsoKnownAs")
            :when (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" (str aka)))]
        (v :did/aka-not-absolute (str "alsoKnownAs \"" aka "\" が絶対 URI でない")))))))

(defn check
  "manifest と DID document をまとめて検査する。

   **`@id` と `id` の一致は、ここでは検査しない。** cargo では両者が実際に
   割れており（`docs/identity-claims.edn` に測定値を固定してある）、
   その既知の割れは claims 側の突き合わせが受け持つ。ここで :error にすると
   suite が最初から赤くなり、他の規則が全部見えなくなる。"
  [manifest did-doc]
  (vec (concat (check-manifest manifest) (check-did-document did-doc))))
