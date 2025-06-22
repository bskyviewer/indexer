package bskyviewer.indexer.lucene

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer
import org.apache.lucene.analysis.core.KeywordAnalyzer
import org.apache.lucene.analysis.ja.JapaneseAnalyzer
import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.analysis.morfologik.MorfologikAnalyzer
import org.apache.lucene.analysis.pl.PolishAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.meeuw.i18n.languages.LanguageCode
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.stereotype.Component
import java.util.*
import kotlin.jvm.optionals.getOrNull

private val logger = KotlinLogging.logger {}

@Component
class Analyser : DelegatingAnalyzerWrapper(PER_FIELD_REUSE_STRATEGY) {
    val unseen = HashSet<String>()

    val standard = StandardAnalyzer()
    val keyword = KeywordAnalyzer()
    val byLang = ClassPathScanningCandidateComponentProvider(false).also {
        it.addIncludeFilter(AssignableTypeFilter(Analyzer::class.java))
    }.findCandidateComponents("org.apache.lucene.analysis").associateBy {
        it.beanClassName?.substringAfter("org.apache.lucene.analysis.")?.substringBefore(".")
    }.filter {
        LanguageCode.get(it.key).isPresent
    }.mapValues {
        Class.forName(it.value.beanClassName).getDeclaredConstructor().newInstance() as Analyzer
    }.toMutableMap().also {
        it["uk"] = MorfologikAnalyzer()
        it["pl"] = PolishAnalyzer()
        it["zh"] = SmartChineseAnalyzer()
        it["ja"] = JapaneseAnalyzer()
        it["ko"] = KoreanAnalyzer()
    }

    init {
        logger.info { "loaded analyzers: ${byLang.keys}" }
    }

    override fun getWrappedAnalyzer(fieldName: String?): Analyzer? {
        val code = toCode(fieldName?.substringAfter("text_", ""))
        return byLang[code] ?: if (fieldName?.startsWith("text") == true) standard else keyword
    }

    fun toCode(lang: String?): String? = lang?.trim()?.let {
        if (lang in byLang) return lang
        if (lang == "jp") return "ja"
        val code = try {
            val locale = Locale.Builder().setLanguageTag(lang).build()
            // Most analyzers classified by 2-letter code
            if (locale.language in byLang) return locale.language
            // SoraniAnalyser has a 3-letter code (ckb uses a different alphabet than ku)
            if (locale.isO3Language in byLang) return locale.isO3Language
            val code = LanguageCode.get(locale.isO3Language).getOrNull()
            if (code != null && code.code() in byLang) return code.code()
            code
        } catch (_: Exception) {
            LanguageCode.get(lang).getOrNull()
        }
        // If specific language not found, fall back to macrolanguage (e.g nn -> no)
        val macroLangs = code?.macroLanguages()?.map(LanguageCode::code)
        val found = macroLangs?.find { it in byLang }
        if (found == null && unseen.size < 100 && unseen.add(lang)) {
            logger.info { "missing analyser for '$lang' ($code, $macroLangs)" }
        }
        found ?: code?.code()
    }
}
