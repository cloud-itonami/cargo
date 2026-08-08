(ns etzhayyim.cargo.network-test
  "docs/identity-claims.edn に固定した測定値を、**実際に取りに行って**照合する。

  既定では走らない（`nbb run_tests.cljs --network` で有効）。network を要る検査を
  既定にすると、回線が落ちているだけで赤くなり、赤の意味が薄まる。
  一方これを持たないと、claims は『誰も確かめていない散文』に戻る ——
  だから消さずに、明示的に呼べる場所に置く。

  curl を使うのは、claims の :measured-how がまさに curl の invocation だから。
  同じ道具で測って同じ道具で照合する。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cljs.reader :as reader]
            [etzhayyim.cargo.descriptor :as d]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def enabled?
  (boolean (some #{"--network"} (vec *command-line-args*))))

(def claims
  (reader/read-string (.readFileSync fs (path/join (.cwd js/process) "docs/identity-claims.edn") "utf8")))

(defn- http-status
  "curl の %{http_code}。**0 は『接続そのものが失敗』**（DNS 不在・TLS 不成立）
  であって、空応答でも 0 件でもない。"
  [url]
  (let [r (cp/spawnSync "curl" #js ["-sL" "--max-time" "15" "-o" "/dev/null" "-w" "%{http_code}" url]
                        #js {:encoding "utf8" :shell false})]
    (js/parseInt (str/trim (str (.-stdout r))) 10)))

(deftest each-claim-resolves-exactly-as-the-claims-file-records
  "測定値と実測が食い違ったら赤。**直った側にも落ちる** —— 例えば
  did:web:cargo.etzhayyim.com のホストが生えたら 0 → 200 で赤くなり、
  claims と README を測り直せという意味になる。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (doseq [c (:claims claims)]
      (testing (str (:id c) " " (:resolves-to c))
        (is (= (:http (:measured c)) (http-status (:resolves-to c))))))))

(deftest each-surface-responds-exactly-as-the-claims-file-records
  "配信面（GitHub Pages・@context・両 PDS）も同じ扱い。
  とくに :surface/github-pages が 200 であることは、
  『commit した did.json が実際に世に出ている』という主張の根拠になっている。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (doseq [s (:surfaces claims)]
      (testing (str (:id s) " " (:url s))
        (is (= (:http (:measured s)) (http-status (:url s))))))))

(deftest the-github-pages-surface-serves-the-bytes-this-repo-committed
  "Pages が配信しているのが**この repo の .well-known/did.json そのもの**か。
  ここが割れると、repo を読んで分かることと世に出ていることがずれる。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (let [s (first (filter #(= :surface/github-pages (:id %)) (:surfaces claims)))
          r (cp/spawnSync "curl" #js ["-sL" "--max-time" "15" (:url s)] #js {:encoding "utf8" :shell false})
          served (js->clj (js/JSON.parse (str (.-stdout r))))
          committed (js->clj (js/JSON.parse (.readFileSync fs (path/join (.cwd js/process) ".well-known/did.json") "utf8")))]
      (is (= (:serves-committed-bytes? (:measured s)) (= served committed))))))

(deftest the-did-that-resolves-is-the-one-the-claims-file-says-resolves
  "200 が返ってくるだけでは足りない —— **返ってきた document の id が、
  その URL を導いた DID と同じ**でなければ、did:web としては無効である。
  :did/committed はまさにここで落ちる形（Pages では取れるが、id が導く URL では
  取れない）なので、規則として書いておく。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (let [live (first (filter #(= :did/live (:id %)) (:claims claims)))
          r (cp/spawnSync "curl" #js ["-sL" "--max-time" "15" (:resolves-to live)] #js {:encoding "utf8" :shell false})
          doc (js->clj (js/JSON.parse (str (.-stdout r))))]
      (is (= (:did live) (get doc "id")))
      (is (= (:resolves-to live) (d/did->document-url (get doc "id")))))))
