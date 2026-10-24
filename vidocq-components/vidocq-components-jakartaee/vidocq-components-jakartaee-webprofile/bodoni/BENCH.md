# BENCH.md — Bodoni

JMH performance numbers. Entry format: `date · hardware/JVM · exact command ·
raw results · delta vs previous run`. No performance numbers elsewhere (README, commit)
without a corresponding entry here.

---

## BENCH-20261024-01 — B6 spike: standard handlers vs hand-inlined core tags on Ibarra

- **Date:** 2026-10-24
- **Code under test:** the snapshots of bodoni (the code `517652b` measures), ibarra `589b5c0` and peano
  `df3b997`, all `0.4.0-SNAPSHOT`. The bodoni snapshot predates commits that touch only
  `CoreTagLibraryValidator`, `c.tld` and `ForTokensTag`, none of which is on the measured path.
- **JVM:** Temurin 25+36-LTS, default flags
- **Hardware:** Apple M5 Max, 18 cores, 128 GB RAM
- **OS:** Darwin 27.0.0 arm64
- **What is measured:**
  - **The page:** `<c:forEach items="${items}" var="it"><c:if test="${it.even}"><c:out
    value="${it.name}"/></c:if></c:forEach>`, over a `List` of a public bean.
  - **Variant A** is the exact Java source that Ibarra's `PageGenerator` emits, with
    Bodoni's `c.tld` and the EL compiled by Peano.
  - **Variant B** is A with the three handler sequences replaced by hand-written inlined code,
    as an inliner would emit it. It keeps the same compiled expression objects, the same
    `coerceToType` calls, the `var` page attribute set per iteration and removed afterwards, and
    `c:out` escaping through Bodoni's `Xml.escape`. A and B give byte-identical output: 25 bytes
    for N=10 and 3,445 bytes for N=1000.
  - **What one operation covers:** one `service()` call through Ibarra's `IbarraJspFactory`,
    with servlet doubles, on the class path, and a response writer reset before each call.
    PageContext, the JspWriter and the flush are therefore included.
  - **The harness is a throwaway spike and is not committed.**
- **Command** (JMH 1.37):
  ```bash
  java -cp <spike class path> org.openjdk.jmh.Main bench.PageBench -f 3 -wi 5 -w 1s -i 5 -r 1s -prof gc
  ```
- **Results** (avgt, single thread, ± 99.9 % error):
  ```
  N     variant  ns/op                 gc.alloc.rate.norm
  10    A          2772.109 ±   12.162   28048 B/op
  10    B          2819.101 ±   27.688   27392 B/op
  1000  A        177248.302 ± 2265.220   99569 B/op
  1000  B        175978.940 ± 1851.243   27425 B/op
  ```
  The ratios of A to B:
  - **Time:** 0.983 for N=10 and 1.007 for N=1000, inside the noise. The 1.7 % gap at N=10 reversed
    in a control run.
  - **Allocation:** 1.024 for N=10 and 3.631 for N=1000. The handlers cost about 72 B per item,
    and B's allocation does not grow with N.
- **Delta vs previous run:** first run.
- **Notes:**
  - **Escape analysis does not remove the handlers.** A control run with
    `-XX:-DoEscapeAnalysis` showed the same 72,144 B/op gap between A and B at N=1000.
  - **The time is in EL resolution.** It is about 176 ns per item. Two thirds of the benchmark
    thread's stack samples are under `jakarta.el.CompositeELResolver.getValue` (24–26 %),
    `convertToType` (11 %), `HashMap.getNode` (6 %) and Peano's `GeneratedAccessorELResolver`
    (2 %). The page's `_jspService` takes 3–5 % in A and about 1 % in B.
    `coerceToType(…, Object.class)` also walks the resolver chain. Tracked as
    [Vidocq/peano#63](https://codefloe.com/Vidocq/peano/issues/63).
  - **Allocation rate at N=1000:** 536 MB/s for A and 149 MB/s for B. That could matter under
    concurrent load, which a single-threaded benchmark does not measure.
  - **Not measured:** tag-heavy pages with little EL, `varStatus` and `begin`/`end`, buffered
    bodies, concurrency, the module path.
  - **Module path:** there, an inliner could not call `bodoni.core.internal`, which is not exported.
  - **Decision:** B6 is on standby (PLAN.md).
