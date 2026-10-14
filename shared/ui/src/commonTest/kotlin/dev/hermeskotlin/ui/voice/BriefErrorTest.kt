package dev.hermeskotlin.ui.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BriefErrorTest {

    @Test
    fun aProvidersRequestUrlIsLeftOut() {
        val raw = "TTS chunk 1 failed (edge): TTS generation failed (edge): 403, message='Invalid response status', " +
            "url='wss://speech.example.com/consumer/speech/synthesize/v1?TrustedClientToken=ABC123&ConnectionId=42'"

        assertEquals("TTS chunk 1 failed (edge): TTS generation failed (edge): 403, message='Invalid response status'", briefError(raw))
    }

    @Test
    fun anAddressKeepsItsPlaceButNotItsQuery() {
        assertEquals("Couldn't fetch the voice from https://tts.example.com/v1", briefError("Couldn't fetch the voice from https://tts.example.com/v1?key=secret"))
        assertEquals("Can't reach the gateway: Failed to connect to https://hermes.example.com", briefError("Can't reach the gateway: Failed to connect to https://hermes.example.com"))
    }

    @Test
    fun onlyTheFirstLineShows() {
        assertEquals("Speech-to-text failed.", briefError("\nSpeech-to-text failed.\nTraceback (most recent call last):\n  File \"x.py\""))
    }

    @Test
    fun aVeryLongMessageIsCut() {
        val brief = briefError("A".repeat(500))

        assertEquals(200, brief.length)
        assertTrue(brief.endsWith("…"))
    }

    @Test
    fun aLineThatIsOnlyTheUrlFallsToTheNextOne() {
        assertEquals("403 Forbidden", briefError("url='wss://speech.example.com/v1?token=ABC'\n403 Forbidden"))
        assertEquals("The voice service failed.", briefError("url='wss://speech.example.com/v1?token=ABC'"))
    }

    @Test
    fun anAddressEndsWhereTheSentenceCloses() {
        assertEquals(
            "Timed out (https://tts.example.com/v1) after 30 s",
            briefError("Timed out (https://tts.example.com/v1?key=secret) after 30 s"),
        )
    }

    @Test
    fun fragmentsAndSignInsAreLeftOut() {
        assertEquals(
            "Failed: https://tts.example.com/v1 and wss://host.example.com/s",
            briefError("Failed: https://user:pass@tts.example.com/v1#access_token=XYZ and wss://host.example.com/s"),
        )
    }

    @Test
    fun aQueryWithCommasOrBracketsGoesWhole() {
        assertEquals("Failed: https://tts.example.com/v1", briefError("Failed: https://tts.example.com/v1?ids=1,2&key=SECRET"))
        assertEquals("Failed: https://tts.example.com/v1 now", briefError("Failed: https://tts.example.com/v1?a=(1)&key=SECRET now"))
        assertEquals("Failed: https://tts.example.com/v1.", briefError("Failed: https://tts.example.com/v1?key=SECRET."))
    }

    @Test
    fun anUnquotedUrlArgumentGoesWhole() {
        assertEquals("403", briefError("403, url=wss://h.example.com/s?a=1;TrustedClientToken=SECRET"))
        assertEquals("403 later", briefError("403, url=wss://h.example.com/s?ids=1,2&token=X later"))
        assertEquals("403", briefError("403, base_url='https://h.example.com/v1?key=X'"))
    }

    @Test
    fun yarlsUrlReprGoesWhole() {
        assertEquals("Can't connect", briefError("Can't connect, url=URL('wss://h.example.com/s?t=X')"))
        assertEquals("The voice service failed.", briefError("url=URL('wss://h.example.com/s?t=X')"))
    }

    @Test
    fun aSignInWithAQuestionMarkStillGoes() {
        assertEquals("Failed: https://tts.example.com/v1", briefError("Failed: https://user:p?ss@tts.example.com/v1"))
    }

    @Test
    fun aPathWithCommasOrBracketsStillLosesItsQuery() {
        assertEquals("Look: http://x.example.com/a,b", briefError("Look: http://x.example.com/a,b?token=SECRET"))
        assertEquals("Look: http://x.example.com/a(b)", briefError("Look: http://x.example.com/a(b)?token=SECRET"))
    }

    @Test
    fun aPasswordWithAnAtOrASlashGoesWhole() {
        assertEquals("Failed: https://host.example.com/v1", briefError("Failed: https://user:p@ss@host.example.com/v1"))
        assertEquals("Failed: https://host.example.com/v1", briefError("Failed: https://user:p/ss@host.example.com/v1"))
        assertEquals("Failed: http://host.example.com:8080/v1", briefError("Failed: http://host.example.com:8080/v1?k=S"))
        assertEquals("Failed: http://[::1]:8080/p", briefError("Failed: http://[::1]:8080/p?token=S"))
    }

    @Test
    fun aDigitOrEmptyPasswordGoesToo() {
        assertEquals("connect to https://host.example.com/x failed", briefError("connect to https://user:1234/56@host.example.com/x failed"))
        assertEquals("connect to https://host.example.com/x failed", briefError("connect to https://user:/s3cret@host.example.com/x failed"))
    }

    @Test
    fun aSecondAddressInTheSameWordLosesItsSignInToo() {
        assertEquals(
            """{"url":"https://a.example.com/x","proxy":"http://b.example.com"}""",
            briefError("""{"url":"https://a.example.com/x","proxy":"http://u:SECRET@b.example.com"}"""),
        )
        assertEquals("proxy https://a.example.com/x,https://b.example.com/y failed", briefError("proxy https://a.example.com/x,https://u:SECRET@b.example.com/y failed"))
    }

    @Test
    fun aPasswordWithAnyPunctuationGoes() {
        assertEquals("https://host.example.com/x failed", briefError("https://user:pa#s/s@host.example.com/x failed"))
        assertEquals("https://host.example.com/ failed", briefError("https://user:pa/ss?x@host.example.com/ failed"))
        assertEquals("https://host.example.com/x", briefError("https://user:p@ss/word@host.example.com/x"))
        assertEquals("http://host.example.com:8080/x", briefError("http://host.example.com:8080/x?email=a@b.example.com"))
        assertEquals("https://host.example.com/ failed", briefError("https://user:12/34?x@host.example.com/ failed"))
    }

    @Test
    fun aSchemeGluedOnIsStillAnAddress() {
        assertEquals("x_https://h.example.com/", briefError("x_https://u:S@h.example.com/"))
        assertEquals("1https://h.example.com/", briefError("1https://u:S@h.example.com/"))
    }

    @Test
    fun aQueryHoldingAnAddressGoesWhole() {
        assertEquals("GET /authorize failed", briefError("GET /authorize?client_secret=SECRET&redirect_uri=https://app.example.com/cb failed"))
        assertEquals("Error: https://host.example.com/", briefError("Error: https://host.example.com/?email=a@b.com/x?k=S"))
    }

    @Test
    fun aPathAfterAnEqualsOrABraceLosesItsQuery() {
        assertEquals("failed path=/v1/speech", briefError("failed path=/v1/speech?key=SECRET"))
        assertEquals("failed {/v1/speech}", briefError("failed {/v1/speech?key=SECRET}"))
    }

    @Test
    fun aQuoteInsideAQueryDoesntEndIt() {
        assertEquals("Failed: https://host.example.com/v1 more", briefError("Failed: https://host.example.com/v1?key=SEC'RET more"))
        assertEquals("403", briefError("403, url='wss://h.example.com/s?t=SEC'RET'"))
    }

    @Test
    fun aQuestionAfterAPathIsntAQuery() {
        assertEquals("Is it in /tmp/x? Yes", briefError("Is it in /tmp/x? Yes"))
        assertEquals("Path /a/b#3 failed", briefError("Path /a/b#3 failed"))
    }

    @Test
    fun aBlankErrorStillSaysSomething() {
        assertEquals("The voice service failed.", briefError("  \n "))
    }

    @Test
    fun aLongRunOfPunctuationIsQuick() {
        val brief = briefError("https://h.example.com/?" + ")".repeat(50_000) + "x " + "url=" + "a".repeat(50_000))

        assertTrue(brief.startsWith("https://h.example.com/)") && '?' !in brief, brief)
    }

    @Test
    fun otherSchemesAndBarePathsLoseTheirQueryToo() {
        assertEquals("Failed: rtsp://cam.example.com/live", briefError("Failed: rtsp://cam.example.com/live?token=X"))
        assertEquals("404 on GET /v1/speech", briefError("404 on GET /v1/speech?TrustedClientToken=X"))
    }

    @Test
    fun theCutDoesntSplitAnEmoji() {
        val brief = briefError("A".repeat(198) + "😀".repeat(10))

        assertEquals("A".repeat(198) + "…", brief)
    }

    @Test
    fun urlInProseIsntAnArgument() {
        assertEquals("Set the url = the gateway's address", briefError("Set the url = the gateway's address"))
    }

    @Test
    fun ordinaryMessagesAreUntouched() {
        assertEquals("Couldn't voice the reply.", briefError("Couldn't voice the reply."))
        assertEquals("Didn't catch anything.", briefError("Didn't catch anything."))
    }
}
