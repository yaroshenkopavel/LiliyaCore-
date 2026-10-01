#!/usr/bin/env bash
set -euo pipefail

mkdir -p "$HOME/.gradle"

cat > "$HOME/.gradle/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8
org.gradle.workers.max=1
org.gradle.parallel=false
org.gradle.daemon=false
kotlin.compiler.execution.strategy=in-process
EOF

echo "=== LiliyaCore hosted CI Gradle/Kotlin memory policy ==="
cat "$HOME/.gradle/gradle.properties"
echo
echo "=== Host memory ==="
free -h || true
grep -E 'MemTotal|MemAvailable|SwapTotal|SwapFree' /proc/meminfo || true
echo
echo "=== Java VM ==="
java -XshowSettings:vm -version 2>&1 | sed -n '1,40p'
echo
echo "=== Gradle ==="
gradle --version
