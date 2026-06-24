import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
const __dirname = dirname(fileURLToPath(import.meta.url));
const m = JSON.parse(readFileSync(resolve(__dirname, "actor-manifest.jsonld"), "utf-8"));
const VP = new Set(["graph.query","graph.write","graph.vectorSearch","agent.chat","agent.invoke","identity.resolve","browser.fetch","signal.encrypt","consent.check","derive:social","dmn.evaluate","form.collect"]);

describe("Cargo Actor Manifest", () => {
  it("@context valid", () => { expect(m["@context"]).toBe("https://etzhayyim.com/ns/actor/v1"); });
  it("DID valid", () => { expect(m["@id"]).toBe("did:web:cargo.etzhayyim.com"); });
  it("runtime", () => { expect(m.runtime).toBe("k8s-langserver"); });
  it("nanoid", () => { expect(m.nanoid).toBe("c4rg0m01"); });
  it("capabilities valid", () => { for (const c of m.capabilities) expect(VP.has(c)).toBe(true); });
  it("no fn:custom", () => { for (const p of m.pipelines) for (const s of p.steps) expect(s.fn).not.toBe("custom"); });
  it("8 pipelines", () => { expect(m.pipelines).toHaveLength(8); });
  it("every step has id/fn/args", () => { for (const p of m.pipelines) for (const s of p.steps) { expect(s.id).toBeDefined(); expect(s.fn).toBeDefined(); expect(s.args).toBeDefined(); } });
  it("cron pipeline: query→query→query→chat→social", () => {
    const cron = m.pipelines.find((p: any) => p.trigger.type === "cron");
    expect(cron.steps).toHaveLength(5);
    expect(cron.steps[3].fn).toBe("agent.chat");
    expect(cron.steps[4].fn).toBe("derive:social");
  });
  it("xrpc covers B/L, container, manifest", () => {
    const nsids = m.pipelines.filter((p: any) => p.trigger.type === "xrpc").map((p: any) => p.trigger.nsid);
    expect(nsids).toContain("com.etzhayyim.apps.cargo.manifest.getBl");
    expect(nsids).toContain("com.etzhayyim.apps.cargo.manifest.trackContainer");
    expect(nsids).toContain("com.etzhayyim.apps.cargo.manifest.getVoyageManifest");
  });
  it("voyageManifest has 3 queries (B/L + containers + DG)", () => {
    const vm = m.pipelines.find((p: any) => p.trigger?.nsid?.includes("getVoyageManifest"));
    expect(vm.steps).toHaveLength(3);
  });
  it("subscribes to vessel.voyage", () => {
    expect(m.triggers.subscribeRepos.collections).toContain("com.etzhayyim.apps.vessel.voyage");
  });
  it("4 actors (master BL, house BL, container, DG)", () => { expect(m.actors).toHaveLength(4); });
  it("IMDG Code compliance", () => { expect(m.governance.complianceFrameworks).toContain("IMDG Code"); });
  it("isBot true", () => { expect(m.profile.isBot).toBe(true); });
});
