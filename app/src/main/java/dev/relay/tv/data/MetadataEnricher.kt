package dev.relay.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Quiet background enrichment for Movies & Series using TMDB (primary) or MDBList (conservative
 * fallback). Fixes Hindi-dubbed Hollywood classifications, learns TMDB/MDBList genres and age
 * certifications for Kids age filtering, and batches UI list refreshes so grids never thrash.
 */
class MetadataEnricher(
    private val catalog: Catalog,
    private val tmdb: Tmdb,
    private val ratings: Ratings,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
) {
    data class Target(
        val key: String,
        val tv: Boolean,
        val id: Int,
        val tmdbId: String?,
        val name: String,
        val year: String?,
        val categoryId: String,
    )

    private val attempted = Collections.synchronizedSet(HashSet<String>())
    private val queue = ConcurrentLinkedQueue<Target>()
    private val networkSpent = AtomicInteger(0)
    private val gate = Semaphore(2)
    private var worker: Job? = null

    fun resetSession() {
        attempted.clear()
        queue.clear()
        networkSpent.set(0)
        worker?.cancel()
        worker = null
    }

    /** Captures language, age certification, and genres whenever MDBList fetches RT scores. */
    fun onRatingsMeta(key: String, found: MdbList.Reply.Found) {
        catalog.learnTitleMeta(
            key = key,
            isoCode = found.language,
            region = found.country,
            cert = found.certification,
            genres = found.genres,
        )
    }

    /** Queues high-value movies/series (dubbed-suspected, Hindi/India/Asia/unclassified, and kids). */
    fun enrichPriority() {
        val s = settings()
        if (s.tmdbKey.isBlank() && s.ratingsKey.isBlank()) return
        val targets = ArrayList<Target>(48)
        for (m in catalog.moviesByAdded) {
            if (targets.size >= 36) break
            val key = "m:${m.id}"
            if (alreadyEnriched(key) || key in attempted) continue
            val cat = catalog.vodCatName(m.categoryId)
            val lang = catalog.movieLang(m.id)
            if (isPriorityCandidate(m.name, cat, lang)) {
                targets += Target(key, false, m.id, m.tmdbId, m.name, m.year, m.categoryId)
            }
        }
        for (sr in catalog.seriesByAdded) {
            if (targets.size >= 48) break
            val key = "s:${sr.id}"
            if (alreadyEnriched(key) || key in attempted) continue
            val cat = catalog.seriesCatName(sr.categoryId)
            val lang = catalog.seriesLang(sr.id)
            if (isPriorityCandidate(sr.name, cat, lang)) {
                targets += Target(key, true, sr.id, sr.tmdbId, sr.name, sr.year, sr.categoryId)
            }
        }
        enqueueTargets(targets)
    }

    fun enqueueVisibleMovies(movies: List<Movie>, limit: Int = 24) {
        val targets = ArrayList<Target>(limit)
        for (m in movies) {
            if (targets.size >= limit) break
            val key = "m:${m.id}"
            if (alreadyEnriched(key) || key in attempted) continue
            targets += Target(key, false, m.id, m.tmdbId, m.name, m.year, m.categoryId)
        }
        enqueueTargets(targets)
    }

    fun enqueueVisibleSeries(series: List<Series>, limit: Int = 24) {
        val targets = ArrayList<Target>(limit)
        for (sr in series) {
            if (targets.size >= limit) break
            val key = "s:${sr.id}"
            if (alreadyEnriched(key) || key in attempted) continue
            targets += Target(key, true, sr.id, sr.tmdbId, sr.name, sr.year, sr.categoryId)
        }
        enqueueTargets(targets)
    }

    private fun enqueueTargets(list: List<Target>) {
        if (list.isEmpty()) return
        val s = settings()
        if (s.tmdbKey.isBlank() && s.ratingsKey.isBlank()) return
        for (t in list) {
            if (attempted.add(t.key)) queue.add(t)
        }
        startWorkerIfNeeded()
    }

    private fun startWorkerIfNeeded() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) {
            var learnedSinceCommit = 0
            try {
                while (true) {
                    val t = queue.poll() ?: break
                    val s = settings()
                    val hasTmdb = s.tmdbKey.isNotBlank()
                    val hasMdb = s.ratingsKey.isNotBlank()
                    if (!hasTmdb && !hasMdb) break

                    val catName = if (t.tv) catalog.seriesCatName(t.categoryId) else catalog.vodCatName(t.categoryId)
                    // 1. Free 7-day TMDB disk/RAM cache hit first.
                    val cached = tmdb.cachedOrDisk(t.tv, t.tmdbId, t.name)
                    if (cached != null) {
                        catalog.learnTitleMeta(
                            key = t.key,
                            isoCode = cached.language,
                            region = cached.country,
                            cert = cached.certification,
                            genres = cached.genres,
                            categoryName = catName,
                            deferNotify = true,
                        )
                        learnedSinceCommit++
                        if (learnedSinceCommit >= 6) {
                            catalog.commitMetaUpdates()
                            learnedSinceCommit = 0
                        }
                        continue
                    }

                    // 2. Network enrichment under session quota and rate throttle.
                    if (hasTmdb) {
                        if (networkSpent.get() >= MAX_TMDB_PER_SESSION) break
                        networkSpent.incrementAndGet()
                        val info = gate.withPermit {
                            tmdb.lookup(s.tmdbKey, t.tv, t.tmdbId, t.name, t.year)
                        }
                        if (info != null) {
                            catalog.learnTitleMeta(
                                key = t.key,
                                isoCode = info.language,
                                region = info.country,
                                cert = info.certification,
                                genres = info.genres,
                                categoryName = catName,
                                deferNotify = true,
                            )
                            learnedSinceCommit++
                        }
                        if (learnedSinceCommit >= 5) {
                            catalog.commitMetaUpdates()
                            learnedSinceCommit = 0
                        }
                        delay(120)
                    } else if (hasMdb && networkSpent.get() < MAX_MDBLIST_PER_SESSION && ratings.cache.usedToday < 200) {
                        networkSpent.incrementAndGet()
                        gate.withPermit {
                            ratings.lookup(s.ratingsKey, t.key, t.tv, t.tmdbId, t.name, t.year)
                        }
                        learnedSinceCommit++
                        if (learnedSinceCommit >= 4) {
                            catalog.commitMetaUpdates()
                            learnedSinceCommit = 0
                        }
                        delay(200)
                    } else {
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("MetadataEnricher", "Enrichment stopped: ${e.message}")
            } finally {
                catalog.commitMetaUpdates()
            }
        }
    }

    private fun alreadyEnriched(key: String): Boolean {
        val m = catalog.learnedMetaOf(key) ?: return false
        return m.originalLang != null || m.cert != null
    }

    private fun isPriorityCandidate(name: String, catName: String, lang: String?): Boolean {
        if (LanguageDetector.isDubbed(name, catName)) return true
        if (lang == Langs.HI || lang == null) return true
        val up = (name + " " + catName).uppercase()
        return "INDIA" in up || "ASIA" in up || "KIDS" in up || "ANIME" in up || "FAMILY" in up
    }

    companion object {
        const val MAX_TMDB_PER_SESSION = 50
        const val MAX_MDBLIST_PER_SESSION = 15
    }
}
