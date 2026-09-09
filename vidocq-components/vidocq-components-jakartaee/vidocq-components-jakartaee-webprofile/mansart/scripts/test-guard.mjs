// Contract for .opencode/plugin/mansart-guard.js
// Run: node scripts/test-guard.mjs
import { mkdtempSync, writeFileSync, statSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import {
  normalise,
  isRawMaven,
  isTckWrite,
  isArchiveDump,
  longCatTarget,
  makeSessionState,
  searchVerdict,
  rereadVerdict,
} from "../.opencode/guard-rules.mjs"

let pass = 0,
  fail = 0
const t = (name, got, want) => {
  const ok = JSON.stringify(got) === JSON.stringify(want)
  if (ok) { pass++; console.log("  PASS", name) }
  else { fail++; console.log("  FAIL", name, "got", JSON.stringify(got), "want", JSON.stringify(want)) }
}

// --- 1. raw maven (the exit-code bug) ----------------------------------------
t("plain mvn", isRawMaven("mvn test"), true)
t("mvnw", isRawMaven("./mvnw -B test"), true)
t("piped mvn (THE bug)", isRawMaven("mvn -B test | tail -5"), true)
t("mvn behind cd", isRawMaven("cd mansart && mvn test"), true)
t("mvn behind rtk", isRawMaven("rtk mvn test"), true)
t("mvn + stderr redirect", isRawMaven("mvn test 2>/dev/null"), true)
t("mvn after semicolon", isRawMaven("echo hi; mvn test"), true)
t("build.sh allowed", isRawMaven("./scripts/build.sh test"), false)
t("build.sh via rtk", isRawMaven("rtk ./scripts/build.sh -pl x test"), false)
t("sonar.sh allowed", isRawMaven("./scripts/sonar.sh core"), false)
t("unrelated grep", isRawMaven("grep -rn mvn README.md"), false)
t("word containing mvn", isRawMaven("echo mvnfoo"), false)

// --- 2. TCK is read-only, including through a shell ---------------------------
t("redirect into tck", isTckWrite("echo x > mansart-persistence-tck/src/A.java"), true)
t("append into tck", isTckWrite("cat a >> ./mansart-data-tck/pom.xml"), true)
t("tee into tck", isTckWrite("echo x | tee mansart-persistence-tck/f"), true)
t("cp into tck", isTckWrite("cp a.java mansart-data-tck/src/"), true)
t("mv into tck", isTckWrite("mv a.java ./mansart-persistence-tck/"), true)
t("sed -i on tck", isTckWrite("sed -i '' s/a/b/ mansart-data-tck/pom.xml"), true)
t("reading tck is fine", isTckWrite("grep -rn Foo mansart-data-tck/"), false)
t("writing elsewhere is fine", isTckWrite("echo x > src/main/java/A.java"), false)

// --- 3. archive dumps: do it once, put it in a note ---------------------------
t("jar tf", isArchiveDump("jar tf foo.jar"), true)
t("unzip -l", isArchiveDump("unzip -l foo.jar"), true)
t("javap", isArchiveDump("javap -p java.lang.String"), true)
t("jar via rtk", isArchiveDump("rtk jar tf foo.jar"), true)
t("normal jar build", isArchiveDump("jar cf out.jar ."), false)

// --- 4. cat of a long file -> use sed -n / grep -n ----------------------------
const dir = mkdtempSync(join(tmpdir(), "guard-"))
const longFile = join(dir, "long.txt")
const shortFile = join(dir, "short.txt")
writeFileSync(longFile, Array.from({ length: 500 }, (_, i) => `line ${i}`).join("\n"))
writeFileSync(shortFile, "one\ntwo\n")
t("cat long file", longCatTarget(`cat ${longFile}`) !== null, true)
t("cat short file", longCatTarget(`cat ${shortFile}`), null)
t("cat missing file", longCatTarget(`cat ${dir}/nope.txt`), null)
t("sed -n is fine", longCatTarget(`sed -n '1,50p' ${longFile}`), null)
t("cat long via rtk", longCatTarget(`rtk cat ${longFile}`) !== null, true)
// rtk rewrites `cat X` into `rtk read X`, which does NOT truncate (346 lines in,
// 346 out). Without this the rule silently stops firing for every cat.
t("rtk read long file", longCatTarget(`rtk read ${longFile}`) !== null, true)
t("rtk read short file", longCatTarget(`rtk read ${shortFile}`), null)
t("read with flags", longCatTarget(`rtk read ${longFile} --max-lines 50`) !== null, true)

// --- 5. third identical search is denied (2 are allowed) ----------------------
{
  const st = makeSessionState()
  const q = "find . -name EntityModel.java"
  t("search 1st", searchVerdict(q, st), "allow")
  t("search 2nd", searchVerdict(q, st), "allow")
  t("search 3rd", searchVerdict(q, st), "deny")
  // the 75 duplicates of V3 differed only by spelling — normalisation must catch them
  t("3rd, other spelling", searchVerdict("rtk find . -name EntityModel.java 2>/dev/null", st), "deny")
  t("a different search still allowed", searchVerdict("find . -name Other.java", st), "allow")
  t("non-search command untouched", searchVerdict("echo hello", st), "allow")
}

// --- 6. re-reading an unchanged file ------------------------------------------
{
  const st = makeSessionState()
  t("read 1st", rereadVerdict(shortFile, st), "allow")
  t("read 2nd unchanged", rereadVerdict(shortFile, st), "deny")
  writeFileSync(shortFile, "one\ntwo\nthree\n") // content changed
  t("read after change", rereadVerdict(shortFile, st), "allow")
  t("missing file untouched", rereadVerdict(join(dir, "nope.txt"), st), "allow")
}

// --- 7. normalisation is what makes all of the above work ---------------------
t("normalise strips rtk+cd+redirect",
  normalise("cd /x && rtk find . -name A.java 2>/dev/null"), "find . -name A.java")

console.log(`RESULT pass=${pass} fail=${fail}`)
process.exit(fail ? 1 : 0)
