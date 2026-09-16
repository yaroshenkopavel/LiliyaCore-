from pathlib import Path
import subprocess
import tempfile

ROOT = Path.cwd()
FROZEN_TEST = ROOT / "android-app/src/androidTest/kotlin/pro/liliya/app/DevelopmentFirstWorkingLiliyaColdStartInstrumentedTest.kt"
HOST_WORKFLOW = ROOT / ".github/workflows/physical-live-hostbootstrap-qwen-overlay-build.yml"

# Reuse the already verified physical HostBootstrap patch body exactly as v3/v4 do.
lines = HOST_WORKFLOW.read_text().splitlines()
start = next(i for i, line in enumerate(lines) if line.strip() == "- name: Patch canonical cold-start for isolated live Qwen physical acceptance")
end = next(i for i in range(start + 1, len(lines)) if lines[i].strip() == "- name: Set up Java 17")
block = lines[start + 1:end]
run_index = next(i for i, line in enumerate(block) if line.strip() == "run: |")
body = [line[10:] if line.startswith("          ") else line for line in block[run_index + 1:]]
with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False) as tmp:
    tmp.write("\n".join(body) + "\n")
    patch_script = tmp.name
subprocess.run(["bash", patch_script], check=True)

s = FROZEN_TEST.read_text()

def once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"expected one replacement, got {count}: {old!r}")
    s = s.replace(old, new, 1)

once(
    "            expiresAt = entitlement.expiresAt,\n",
    "            expiresAt = requireNotNull(entitlement.expiresAt) { \"live entitlement expiry required by startup authority grant\" },\n",
)
once(
    "        const val STORIES_15M_ASSET = QWEN_FILE_NAME\n",
    "        const val STORIES_15M_ASSET = \"Qwen3-1.7B-Q4_K_M.gguf\"\n",
)
once(
    "        const val STORIES_15M_BYTES = QWEN_BYTES\n",
    "        const val STORIES_15M_BYTES = 1_282_439_264L\n",
)
once(
    "                    cognitiveMaterialization = structural.cognitiveMaterialization,\n",
    "                    cognitiveMaterialization = TypedDiagnosticCognitiveMaterializationPort(structural.cognitiveMaterialization, limits, instrumentation),\n",
)
once(
    "                    protectorId = \"first-working-liliya-cognitive-protector\",\n",
    "                    protectorId = ACCEPTANCE_COGNITIVE_PROTECTOR_ID,\n",
)
once(
    "        val testContext = instrumentation.context\n        val fixtureRoot = File(targetContext.filesDir, \"cold-start\")\n",
    """        val testContext = instrumentation.context
        val previousProtectorCleanup = AcceptanceCognitiveProtectorCleanup.retireExactIfPresent(
            application,
            \"first-working-liliya-cognitive-protector\",
            1L
        )
        require(previousProtectorCleanup == \"ABSENT\" || previousProtectorCleanup == \"RETIRED\") {
            \"previous acceptance cognitive protector cleanup rejected: $previousProtectorCleanup\"
        }
        val v5ProtectorCleanup = AcceptanceCognitiveProtectorCleanup.retireExactIfPresent(
            application,
            ACCEPTANCE_COGNITIVE_PROTECTOR_ID,
            1L
        )
        require(v5ProtectorCleanup == \"ABSENT\" || v5ProtectorCleanup == \"RETIRED\") {
            \"v5 acceptance cognitive protector pre-clean rejected: $v5ProtectorCleanup\"
        }
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            putString(\"hostQwen.v5PreviousProtectorCleanup\", previousProtectorCleanup)
            putString(\"hostQwen.v5ProtectorPreCleanup\", v5ProtectorCleanup)
        })
        val fixtureRoot = File(targetContext.filesDir, \"cold-start\")
""",
)
once(
    "            application.runtimeOwner.close()\n            fixtureRoot.deleteRecursively()\n",
    """            application.runtimeOwner.close()
            val v5ProtectorFinalCleanup = runCatching {
                AcceptanceCognitiveProtectorCleanup.retireExactIfPresent(
                    application,
                    ACCEPTANCE_COGNITIVE_PROTECTOR_ID,
                    1L
                )
            }.getOrElse { \"CLEANUP_EXCEPTION_\" + it.javaClass.simpleName }
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString(\"hostQwen.v5ProtectorFinalCleanup\", v5ProtectorFinalCleanup)
            })
            println(\"HOST_QWEN_V5_COGNITIVE_PROTECTOR_FINAL_CLEANUP=$v5ProtectorFinalCleanup\")
            fixtureRoot.deleteRecursively()
""",
)
once(
    "        const val PRODUCT_ID = \"liliya-core\"\n",
    "        const val ACCEPTANCE_COGNITIVE_PROTECTOR_ID = \"first-working-liliya-cognitive-protector-v5\"\n        const val PRODUCT_ID = \"liliya-core\"\n",
)

FROZEN_TEST.write_text(s)

required = [
    'ACCEPTANCE_COGNITIVE_PROTECTOR_ID = "first-working-liliya-cognitive-protector-v5"',
    'hostQwen.v5PreviousProtectorCleanup',
    'HOST_QWEN_V5_COGNITIVE_PROTECTOR_FINAL_CLEANUP=',
    'TypedDiagnosticCognitiveMaterializationPort(structural.cognitiveMaterialization, limits, instrumentation)',
    'application.startApplicationRuntime()',
    'application.runtimeOwner.send("Hello Liliya /no_think")',
]
for marker in required:
    if marker not in s:
        raise SystemExit(f"missing v5 marker: {marker}")

print("HOSTBOOTSTRAP_QWEN_V5_PREPARE=PASS")
