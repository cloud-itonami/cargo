# cargo — Cargo & Container Intelligence Actor

**DID**: `did:web:cargo.etzhayyim.com`
**Namespace**: `com.etzhayyim.cargo.*`
**Status**: migration boundary for aviation and maritime cargo domain cells

## Migration Boundary

`src/cargo/murakumo.cljc` is the Murakumo-facing cljc actor boundary for the
legacy cargo-related kotoba-kotodama cells:

- `air_flight_ops` -> `flightOpsAttestation`
- `air_ground_handling` -> `groundHandlingAttestation`
- `air_mro_robotics` -> `mroRoboticsAttestation`
- `maritime_cargo_handling` -> `terminalHandlingAttestation`

The AT/XRPC and graph-facing manifest remains in `actor-manifest.jsonld`.
Physical aviation and terminal movement remains blocked unless Charter Rider,
operator certification, safety envelope, geofence, human override, port/airport
authority, dangerous-goods, Murakumo-only, and kotoba-only attestations are
present.
