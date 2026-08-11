# Project instructions — acp-ssh-kmp

## Gradle tuning is machine-specific — gate every build on it

`gradle.properties` is tuned for Nxssie's actual dev machine (16 cores, 31 GiB RAM), not for a
constrained CI/sandbox. It requests:

- `org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -XX:MaxDirectMemorySize=512m`
- `kotlin.daemon.jvmargs=-Xmx2g`
- `org.gradle.parallel=true` (+ `org.gradle.tooling.parallel=true`, no `workers.max` cap — Gradle
  defaults to one worker per core)

Worst case that's ~7.5 GiB of JVM heap/metaspace/direct memory across the Gradle daemon + Kotlin
daemon, plus OS/IDE overhead, and `parallel=true` only helps with several cores free to use.

**Before running any Gradle build in this repo** (`./gradlew compile*`, `assemble*`, `build`,
`test`, `check`, etc.), check the host's actual resources first (e.g. `free -h` / `nproc`, or the
platform equivalent) and confirm it meets the minimum this config assumes:

- **≥ 8 GiB of free RAM** (not total — free/available, headroom for the JVM processes above OS use)
- **≥ 4 CPU cores** free to use

**If the host does NOT meet those minimums:**

1. Do **not** run the build/compile/assemble/test command. Aborting the command is the right
   outcome, not a failure to work around.
2. Do **not** edit `gradle.properties` or `gradle/gradle-daemon-jvm.properties` to "fix" it
   (lowering heap sizes, disabling parallel, etc.) — that's exactly the constrained-sandbox
   tuning this file replaced; downgrading it defeats the point of having machine-specific
   settings and would silently regress performance on the real dev machine if committed back.
3. Tell the user directly: the detected RAM/cores, the minimums required, and that the build was
   skipped for that reason. Let them decide (run elsewhere, free up resources, or explicitly ask
   for a one-off override).

This applies to any agent or tool running Gradle in this repo, not just to editing these files.
