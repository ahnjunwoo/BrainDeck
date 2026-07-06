package com.braindeck.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.braindeck"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureTest {

    companion object {
        private const val ROOT = "com.braindeck"
        private val CONTEXTS = listOf("knowledge", "ingest", "retrieval", "eval")

        // 다른 컨텍스트에서 import 금지 대상: readport를 제외한 모든 내부 레이어
        private val NON_READPORT_SUFFIXES = listOf(
            ".application.service..", ".domain..", ".infrastructure..", ".presentation..",
        )

        private fun crossContextReadPortOnly(self: String): ArchRule {
            val forbidden = (CONTEXTS - self).flatMap { other ->
                NON_READPORT_SUFFIXES.map { suffix -> "$ROOT.$other$suffix" }
            }.toTypedArray()
            return noClasses().that().resideInAPackage("$ROOT.$self..")
                .should().dependOnClassesThat().resideInAnyPackage(*forbidden)
                .allowEmptyShould(true)
        }
    }

    // common 은 어떤 도메인 컨텍스트도 의존하지 않는다.
    @ArchTest
    val commonIsolated: ArchRule = noClasses().that().resideInAPackage("$ROOT.common..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(*CONTEXTS.map { "$ROOT.$it.." }.toTypedArray())
        .allowEmptyShould(true)

    @ArchTest val knowledgeReadPortOnly: ArchRule = crossContextReadPortOnly("knowledge")
    @ArchTest val ingestReadPortOnly: ArchRule = crossContextReadPortOnly("ingest")
    @ArchTest val retrievalReadPortOnly: ArchRule = crossContextReadPortOnly("retrieval")
    @ArchTest val evalReadPortOnly: ArchRule = crossContextReadPortOnly("eval")
}
