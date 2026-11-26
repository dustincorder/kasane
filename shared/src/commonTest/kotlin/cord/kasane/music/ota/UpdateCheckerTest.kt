package cord.kasane.music.ota

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

    // ---- SemVersion ----

    private fun v(s: String) = SemVersion.parse(s) ?: error("unparseable: $s")

    @Test fun semverOrderingHandlesPrereleases() {
        assertTrue(v("0.2.1-alpha") > v("0.2.0-alpha"))
        assertTrue(v("0.2.0") > v("0.2.0-alpha"), "release outranks its prerelease")
        assertTrue(v("0.2.0-beta") > v("0.2.0-alpha"))
        assertTrue(v("1.0") > v("0.9.9"))
        assertTrue(v("v0.3.0") > v("0.2.9"))
        assertEquals(0, v("0.2.0-alpha").compareTo(v("0.2.0-alpha")))
        assertTrue(v("0.2.0-alpha.10") > v("0.2.0-alpha.2"), "numeric tokens compare numerically")
        assertEquals(v("0.2"), v("0.2.0"), "missing patch reads as zero")
    }

    @Test fun semverRejectsJunk() {
        assertNull(SemVersion.parse(""))
        assertNull(SemVersion.parse("abc"))
        assertNull(SemVersion.parse("1..2"))
        assertNull(SemVersion.parse("nightly-build"))
    }

    // ---- checker ----

    private fun releasesJson(vararg entries: String) = "[" + entries.joinToString(",") + "]"
    private fun release(tag: String, prerelease: Boolean, body: String = "notes", assets: String = "") = """
        {"tag_name":"$tag","name":"Release $tag","body":"$body","draft":false,
         "prerelease":$prerelease,"html_url":"https://example.test/r/$tag","assets":[$assets]}
    """.trimIndent()
    private fun asset(name: String, digest: String? = null) =
        """{"name":"$name","browser_download_url":"https://example.test/dl/$name","size":123${digest?.let { ""","digest":"sha256:$it"""" } ?: ""}}"""

    private class Rig(
        channel: UpdateChannel,
        current: String = "0.2.0-alpha",
        val body: String?,
        interval: Long = 24 * 3_600_000L,
    ) {
        var now = 1_000_000L
        var lastCheck = 0L
        var calls = 0
        val extra = mutableMapOf<String, String?>()
        val checker = UpdateChecker(
            http = { url, _ -> calls++; if (extra.containsKey(url)) extra[url] else body },
            channel = channel,
            current = SemVersion.parse(current)!!,
            nowMs = { now },
            autoIntervalMs = interval,
            readLastCheckMs = { lastCheck },
            writeLastCheckMs = { lastCheck = it },
        )
    }

    @Test fun stableChannelSeesNewerRegularRelease() = runBlocking<Unit> {
        val r = Rig(UpdateChannel.STABLE, body = releasesJson(
            release("v0.2.0-alpha", prerelease = true),   // nightly is invisible to stable
            release("v0.2.1", prerelease = false, body = "fixes", assets =
                asset("kasane-desktop-linux-x86_64-0.2.1.tar.gz", "ab".repeat(32))),
        ))
        val out = r.checker.checkNow(listOf("linux", "x86_64", ".tar.gz"))
        assertIs<UpdateOutcome.Available>(out)
        assertEquals("v0.2.1", out.release.tag)
        assertEquals("fixes", out.release.changelog)
        assertEquals("ab".repeat(32), out.asset?.sha256)
    }

    @Test fun nightlyChannelSeesOnlyPrereleases() = runBlocking<Unit> {
        val r = Rig(UpdateChannel.NIGHTLY, current = "0.2.0-alpha", body = releasesJson(
            release("v0.3.0", prerelease = false),                       // stable: skip
            release("v0.2.1-nightly", prerelease = true),
            release("v0.2.0-beta", prerelease = true),
        ))
        val out = r.checker.checkNow()
        assertIs<UpdateOutcome.Available>(out)
        assertEquals("v0.2.1-nightly", out.release.tag, "newest prerelease wins, not first-in-list")
    }

    @Test fun sameOrOlderVersionIsUpToDate() = runBlocking<Unit> {
        val r = Rig(UpdateChannel.STABLE, current = "0.2.1", body = releasesJson(
            release("v0.2.1", prerelease = false),
            release("v0.2.0", prerelease = false),
        ))
        val out = r.checker.checkNow()
        assertIs<UpdateOutcome.UpToDate>(out)
        assertEquals("v0.2.1", out.latest?.tag)
    }

    @Test fun channelWithNoReleasesIsUpToDate() = runBlocking<Unit> {
        val r = Rig(UpdateChannel.NIGHTLY, body = releasesJson(release("v0.2.1", prerelease = false)))
        assertIs<UpdateOutcome.UpToDate>(r.checker.checkNow())
    }

    @Test fun autoCheckIsRateLimitedUntilTheWindowPasses() = runBlocking<Unit> {
        val body = releasesJson(release("v9.9.9", prerelease = false))
        val r = Rig(UpdateChannel.STABLE, body = body)
        assertIs<UpdateOutcome.Available>(r.checker.checkIfDue())
        assertEquals(1, r.calls)
        assertIs<UpdateOutcome.Skipped>(r.checker.checkIfDue(), "second auto check inside 24 h")
        assertEquals(1, r.calls)
        r.now += 24 * 3_600_000L + 1
        assertIs<UpdateOutcome.Available>(r.checker.checkIfDue())
        assertEquals(2, r.calls)
        // Manual check bypasses the limit but re-arms it.
        r.lastCheck = r.now - 1
        r.checker.checkNow()
        assertEquals(3, r.calls)
        assertEquals(r.now, r.lastCheck)
    }

    @Test fun draftsAndJunkAreIgnored() = runBlocking<Unit> {
        val body = """[{"tag_name":"v9.9.9","draft":true,"prerelease":false,"assets":[]},
            {"tag_name":"not-a-version","prerelease":false,"assets":[]},
            {"tag_name":"v0.2.0","prerelease":false,"assets":[],"html_url":"u","body":"b"}]"""
        val r = Rig(UpdateChannel.STABLE, current = "0.1.0", body = body)
        val out = r.checker.checkNow()
        assertIs<UpdateOutcome.Available>(out)
        assertEquals("v0.2.0", out.release.tag)
    }

    @Test fun networkAndParseFailuresAreNotFatal() = runBlocking<Unit> {
        assertIs<UpdateOutcome.Failed>(Rig(UpdateChannel.STABLE, body = null).checker.checkNow())
        assertIs<UpdateOutcome.Failed>(Rig(UpdateChannel.STABLE, body = "{oops").checker.checkNow())
    }

    @Test fun checksumFallsBackToSiblingAsset() = runBlocking<Unit> {
        val hex = "cd".repeat(32)
        val body = releasesJson(release("v0.3.0", prerelease = false,
            assets = asset("a.tar.gz") + "," + asset("a.tar.gz.sha256")))
        val r = Rig(UpdateChannel.STABLE, body = body)
        r.extra["https://example.test/dl/a.tar.gz.sha256"] = "$hex  a.tar.gz\n"
        val rel = r.checker.parseReleases(body)!!.single()
        val a = rel.assets.first { it.name == "a.tar.gz" }
        assertNull(a.sha256)
        assertEquals(hex, r.checker.resolveChecksum(a, rel))
    }

    @Test fun checksumReadsSharedSha256sumsFile() = runBlocking<Unit> {
        val hex = "ef".repeat(32)
        val body = releasesJson(release("v0.3.0", prerelease = false,
            assets = asset("b.apk") + "," + asset("a.tar.gz") + "," + asset("SHA256SUMS")))
        val r = Rig(UpdateChannel.STABLE, body = body)
        r.extra["https://example.test/dl/SHA256SUMS"] = "11".repeat(32) + "  b.apk\n$hex  a.tar.gz\n"
        val rel = r.checker.parseReleases(body)!!.single()
        val a = rel.assets.first { it.name == "a.tar.gz" }
        assertEquals(hex, r.checker.resolveChecksum(a, rel), "the matching line in SHA256SUMS wins")
    }

    @Test fun assetMatcherSupportsExclusionPrefix() = runBlocking<Unit> {
        val body = releasesJson(release("v0.3.0", prerelease = false,
            assets = asset("kasane-nightly-linux-x86_64-0.3.0.tar.gz") + "," +
                asset("kasane-desktop-linux-x86_64-0.3.0.tar.gz") + "," + asset("SHA256SUMS")))
        val r = Rig(UpdateChannel.STABLE, body = body)
        val rel = r.checker.parseReleases(body)!!.single()
        val picked = r.checker.selectAsset(rel, listOf("linux", "x86_64", ".tar.gz", "-nightly"))
        assertEquals("kasane-desktop-linux-x86_64-0.3.0.tar.gz", picked?.name)
    }
}
