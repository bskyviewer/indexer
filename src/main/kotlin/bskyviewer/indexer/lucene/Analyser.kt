package bskyviewer.indexer.lucene

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper
import org.apache.lucene.analysis.core.SimpleAnalyzer
import org.apache.lucene.analysis.morfologik.MorfologikAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.meeuw.i18n.languages.LanguageCode
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import java.util.*
import kotlin.jvm.optionals.getOrNull

private val logger = KotlinLogging.logger {}

class Analyser : DelegatingAnalyzerWrapper(PER_FIELD_REUSE_STRATEGY) {
    val unseen = HashSet<String>()

    val standard = StandardAnalyzer()
    val simple = SimpleAnalyzer()
    val byLang = ClassPathScanningCandidateComponentProvider(false).also {
        it.addIncludeFilter(AssignableTypeFilter(Analyzer::class.java))
    }.findCandidateComponents("org.apache.lucene.analysis").associateBy {
        it.beanClassName?.substringAfter("org.apache.lucene.analysis.")?.substringBefore(".")
    }.filter {
        LanguageCode.get(it.key).isPresent
    }.mapValues {
        Class.forName(it.value.beanClassName).getDeclaredConstructor().newInstance() as Analyzer
    }.also {
        "uk" to MorfologikAnalyzer()
    }

    override fun getWrappedAnalyzer(fieldName: String?): Analyzer? {
        if (fieldName == "text") return standard
        val code = toCode(fieldName?.substringAfter("text_", ""))
        if (code?.length == 2 && code !in byLang && unseen.add(code)) {
            logger.info { "missing analyser for $code ($fieldName)" }
        }
        return byLang[code] ?: simple
    }

    fun toCode(lang: String?): String? = lang.let {
        if (lang.isNullOrBlank()) return null
        val code = try {
            LanguageCode.get(lang)
            val locale = Locale.Builder().setLanguageTag(lang).build()
            // Most analyzers classified by 2-letter code
            if (locale.language in byLang) return locale.language
            // SoraniAnalyser has a 3-letter code (ckb uses a different alphabet than ku)
            if (locale.isO3Language in byLang) return locale.isO3Language
            LanguageCode.get(locale.isO3Language).getOrNull()?.code() ?: lang
        } catch (_: Exception) {
            lang
        }
        // If specific language not found, fall back to macrolanguage (e.g nn -> no)
        LanguageCode.get(code).getOrNull()?.macroLanguages()?.map(LanguageCode::code)?.find { it in byLang }
    }

    init {
        LanguageCode.registerFallback("jp", LanguageCode.languageCode("ja"))
    }
}