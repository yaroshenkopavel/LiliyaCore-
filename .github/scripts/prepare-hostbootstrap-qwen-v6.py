from pathlib import Path
import subprocess
import tempfile

ROOT = Path.cwd()
TEST = ROOT / "android-app/src/androidTest/kotlin/pro/liliya/app/DevelopmentFirstWorkingLiliyaColdStartInstrumentedTest.kt"
HOST_WORKFLOW = ROOT / ".github/workflows/physical-live-hostbootstrap-qwen-overlay-build.yml"

# Reuse the already verified physical HostBootstrap patch body exactly as v3-v5 do.
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

s = TEST.read_text()

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
        val cleanupIds = listOf(
            \"first-working-liliya-cognitive-protector\",
            \"first-working-liliya-cognitive-protector-v5\",
            ACCEPTANCE_COGNITIVE_PROTECTOR_ID
        )
        val cleanupResults = cleanupIds.associateWith { id ->
            AcceptanceCognitiveProtectorCleanup.retireExactIfPresent(application, id, 1L)
        }
        cleanupResults.values.forEach { result ->
            require(result == \"ABSENT\" || result == \"RETIRED\") {
                \"acceptance cognitive protector pre-clean rejected: $result\"
            }
        }
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            putString(\"hostQwen.v6BaseProtectorCleanup\", cleanupResults.getValue(\"first-working-liliya-cognitive-protector\"))
            putString(\"hostQwen.v6V5ProtectorCleanup\", cleanupResults.getValue(\"first-working-liliya-cognitive-protector-v5\"))
            putString(\"hostQwen.v6ProtectorPreCleanup\", cleanupResults.getValue(ACCEPTANCE_COGNITIVE_PROTECTOR_ID))
        })
        val fixtureRoot = File(targetContext.filesDir, \"cold-start\")
""",
)
once(
    "            application.runtimeOwner.close()\n            fixtureRoot.deleteRecursively()\n",
    """            application.runtimeOwner.close()
            val v6ProtectorFinalCleanup = runCatching {
                AcceptanceCognitiveProtectorCleanup.retireExactIfPresent(
                    application,
                    ACCEPTANCE_COGNITIVE_PROTECTOR_ID,
                    1L
                )
            }.getOrElse { \"CLEANUP_EXCEPTION_\" + it.javaClass.simpleName }
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString(\"hostQwen.v6ProtectorFinalCleanup\", v6ProtectorFinalCleanup)
            })
            println(\"HOST_QWEN_V6_COGNITIVE_PROTECTOR_FINAL_CLEANUP=$v6ProtectorFinalCleanup\")
            fixtureRoot.deleteRecursively()
""",
)
once(
    "        const val PRODUCT_ID = \"liliya-core\"\n",
    "        const val ACCEPTANCE_COGNITIVE_PROTECTOR_ID = \"first-working-liliya-cognitive-protector-v6\"\n        const val PRODUCT_ID = \"liliya-core\"\n",
)

# V5 proved the inference output was exactly the old 768-char ceiling and the grammar-constrained
# prefix then failed STRUCTURE_REJECTED. Raise only acceptance/runtime output capacity; frozen
# production source remains untouched. 2048 also covers the protocol's worst case for this test's
# one-item lists when every decoded field character requires a two-character escape representation.
once(
    "                maxGeneratedTokens = 512,\n",
    "                maxGeneratedTokens = 768,\n",
)
once(
    "        const val MAX_OUTPUT_CHARS = 768\n",
    "        const val MAX_OUTPUT_CHARS = 2_048\n",
)

TEST.write_text(s)

required = [
    'ACCEPTANCE_COGNITIVE_PROTECTOR_ID = "first-working-liliya-cognitive-protector-v6"',
    'hostQwen.v6BaseProtectorCleanup',
    'hostQwen.v6ProtectorFinalCleanup',
    'TypedDiagnosticCognitiveMaterializationPort(structural.cognitiveMaterialization, limits, instrumentation)',
    'maxGeneratedTokens = 768',
    'const val MAX_OUTPUT_CHARS = 2_048',
    'application.startApplicationRuntime()',
    'application.runtimeOwner.send("Hello Liliya /no_think")',
]
for marker in required:
    if marker not in s:
        raise SystemExit(f"missing v6 marker: {marker}")

print("HOSTBOOTSTRAP_QWEN_V6_PREPARE=PASS")
