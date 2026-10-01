# Porting progress

## Done
- Build setup: Google's Maven Central mirror
  (https://maven-central.storage-download.googleapis.com/maven2/) is the first repository in
  `settings.gradle` (plugins) and `build.gradle` (project, added with `addFirst` so it sits
  ahead of the repositories Loom registers). Maven Central itself was rate-limiting (HTTP 429).
- Build setup: removed Voxy's own `org.lz4:lz4-java:1.8.0` dependency from `build.gradle`.
  Minecraft 26.3 ships `at.yawk.lz4:lz4-java:1.10.1`, a maintained fork that declares the
  `org.lz4:lz4-java` capability, so Gradle refused to put both on the classpath. Voxy now
  compiles against and uses Minecraft's copy (same `net.jpountz.lz4` package). Used by
  `src/main/java/me/cortex/voxy/common/config/compressors/LZ4Compressor.java`.
  Needs a runtime check that LZ4-compressed storage still reads and writes correctly.

## In progress
- None.

## Remaining GL calls
- Not yet catalogued (see ARCHITECTURE_NOTES.md once written).

## Blockers
- None.

## Open questions
- None.
