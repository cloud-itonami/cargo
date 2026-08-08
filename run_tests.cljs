#!/usr/bin/env nbb
;; run_tests.cljs — cargo descriptor の検査。
;;
;;   nbb --classpath src:test run_tests.cljs             構造 + 固定値（network 不要）
;;   nbb --classpath src:test run_tests.cljs --network   claims の測定値を実際に取りに行く
;;
;; この repo には 2026-05 から `actor-manifest.test.ts`（vitest）が置かれていたが、
;; `package.json` も vitest も無く **一度も実行できなかった**。その間に manifest の
;; pipeline は 8 → 10 に増え、`@id` と `.well-known/did.json` の `id` は別の DID に
;; 割れたが、test file はどちらも報告していない。走らないテストは、自分が古く
;; なったことも報告しない。
;;
;; workspace の規則（superproject CLAUDE.md）で script host は nbb に一本化されて
;; おり、新規の .ts / .mjs / .sh は禁止。よって置き換え先は nbb + cljs.test である。
(ns run-tests
  (:require [clojure.test :as t]
            [etzhayyim.cargo.descriptor-test]
            [etzhayyim.cargo.repo-test]
            [etzhayyim.cargo.network-test :as network]))

(def green-marker
  "scripts/maturity-loop/mutations.edn の `:green-marker`。
  全部緑のときだけ出る —— 出力に現れるかどうかで mutation が噛んだかを判定する
  ので、緑でないときに印字してはならない。"
  "cargo descriptor: all green")

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (if (t/successful? m)
    (println (str "\nmode: " (if network/enabled? "offline + network" "offline") "\n" green-marker))
    (do (println "\ncargo descriptor: FAILED")
        (js/process.exit 1))))

(t/run-tests 'etzhayyim.cargo.descriptor-test
             'etzhayyim.cargo.repo-test
             'etzhayyim.cargo.network-test)
