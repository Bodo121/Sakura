package dev.sakura.home

import android.util.Log

object NativeBridge {
    val available: Boolean = try {
        System.loadLibrary("sakura_home")
        true
    } catch (e: LinkageError) {
        Log.w("SakuraHome", "Native search unavailable; using Kotlin search", e)
        false
    } catch (e: SecurityException) {
        Log.w("SakuraHome", "Native search denied; using Kotlin search", e)
        false
    }

    @JvmStatic private external fun rank(labels: Array<String>, query: String): IntArray

    fun search(labels: List<String>, query: String): List<Int> {
        if (available) {
            try { return rank(labels.toTypedArray(), query).filter { it in labels.indices } }
            catch (e: Exception) { Log.w("SakuraHome", "Native search failed; using Kotlin search", e) }
            catch (e: LinkageError) { Log.w("SakuraHome", "Native search linkage failed", e) }
        }
        val q=query.trim().lowercase(java.util.Locale.ROOT)
        if (q.isEmpty()) return labels.indices.toList()
        val words=q.split(Regex("\\s+"))
        return labels.indices.filter { i -> words.all { labels[i].lowercase(java.util.Locale.ROOT).contains(it) } }
            .sortedBy { i ->
                val value=labels[i].lowercase(java.util.Locale.ROOT)
                when { value==q -> 0; value.startsWith(q) -> 1
                    value.split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(q) } -> 2; else -> 3 }
            }
    }
}
