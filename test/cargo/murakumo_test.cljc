(ns cargo.murakumo-test
  (:require [clojure.test :refer [deftest is]]
            [cargo.murakumo :as cargo]))

(def full-attestations
  (into {}
        (map (fn [gate] [gate (str "attested-" (name gate))]))
        (distinct (mapcat :required-gates (vals cargo/cell-specs)))))

(deftest maps-all-legacy-cargo-cells
  (is (= #{"air_flight_ops"
           "air_ground_handling"
           "air_mro_robotics"
           "maritime_cargo_handling"}
         (set (map :legacy-cell (vals cargo/cell-specs))))))

(deftest r0-gates-block-physical-effects
  (let [plan (cargo/cell-plan :flight-ops
                              {:task-id "flight-task-001"
                               :flight-id "flt-001"
                               :computed-at "2026-06-29T00:00:00Z"})]
    (is (= :blocked (:status plan)))
    (is (= [:council-charter-attestation
            :cargo-baseline-review
            :charter-rider-scan-baseline
            :operator-certified-baseline
            :safety-envelope-baseline
            :human-override-baseline
            :geofence-baseline
            :authority-permit-baseline
            :dangerous-goods-screen-baseline
            :no-passenger-harm-baseline
            :no-autonomous-weaponization-baseline
            :murakumo-only-inference-baseline
            :kotoba-only-substrate-baseline
            :append-only-audit-baseline
            :flight-plan-authorized-baseline
            :airspace-clearance-baseline
            :weather-minima-baseline
            :notam-reviewed-baseline
            :crew-duty-limit-baseline
            :no-autonomous-flight-control-baseline]
           (:missing-gates plan)))
    (is (empty? (:effects plan)))))

(deftest flight-ops-is-attestation-only
  (let [plan (cargo/cell-plan :flight-ops
                              {:attestations full-attestations
                               :task-id "flight-task-001"
                               :flight-id "flt-001"
                               :operator-did "did:example:operator"
                               :authority-ref "atc-clearance-001"
                               :geofence-cid "bafkreigeofence"
                               :safety-case-cid "bafkreisafety"
                               :computed-at "2026-06-29T00:00:00Z"})
        effect (first (:effects plan))]
    (is (= :ready (:status plan)))
    (is (= :critical (:risk plan)))
    (is (= "com.etzhayyim.cargo.flightOpsAttestation" (:collection effect)))
    (is (= false (get-in effect [:record :physicalExecutionAuthorized])))
    (is (= true (get-in effect [:record :humanOverrideRequired])))))

(deftest ground-handling-requires-ramp-safety-gates
  (let [attestations (-> full-attestations
                         (dissoc :ramp-zone-clear-baseline)
                         (dissoc :human-marshaller-present-baseline))
        plan (cargo/cell-plan :ground-handling
                              {:attestations attestations
                               :task-id "ramp-001"})]
    (is (= :blocked (:status plan)))
    (is (= [:ramp-zone-clear-baseline :human-marshaller-present-baseline]
           (:missing-gates plan)))))

(deftest mro-robotics-cannot-release-airworthiness
  (let [attestations (dissoc full-attestations :no-airworthiness-release-baseline)
        plan (cargo/cell-plan :mro-robotics
                              {:attestations attestations
                               :work-order-id "wo-001"})]
    (is (= :blocked (:status plan)))
    (is (= [:no-airworthiness-release-baseline] (:missing-gates plan)))))

(deftest maritime-handling-requires-imdg-and-port-handoff
  (let [attestations (-> full-attestations
                         (dissoc :imdg-segregation-baseline)
                         (dissoc :port-authority-handoff-baseline))
        plan (cargo/cell-plan :maritime-cargo-handling
                              {:attestations attestations
                               :container-id "CONT1234567"})]
    (is (= :blocked (:status plan)))
    (is (= [:imdg-segregation-baseline :port-authority-handoff-baseline]
           (:missing-gates plan)))))

(deftest all-cell-plans-ready-when-attested
  (let [plans (cargo/all-cell-plans {:attestations full-attestations
                                     :task-id "cargo-task-001"
                                     :flight-id "flt-001"
                                     :voyage-id "voy-001"
                                     :port-call-id "port-call-001"
                                     :container-id "CONT1234567"
                                     :bill-of-lading-id "bl-001"
                                     :equipment-id "agv-001"
                                     :work-order-id "wo-001"
                                     :operator-did "did:example:operator"
                                     :authority-ref "authority-001"
                                     :geofence-cid "bafkreigeofence"
                                     :safety-case-cid "bafkreisafety"
                                     :dangerous-goods-ref "imdg-none"
                                     :computed-at "2026-06-29T00:00:00Z"})]
    (is (= (set (keys cargo/cell-specs)) (set (keys plans))))
    (is (every? #(= :ready (:status %)) (vals plans)))
    (is (= 4 (count (mapcat :effects (vals plans)))))))
