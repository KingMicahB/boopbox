package com.cardplayer.link

import com.cardplayer.data.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkInterpretTest {
    private fun kind(link: String) = LinkResolver.interpret(link)

    @Test
    fun knownAppsStayWhatDetectSays() {
        assertEquals(SourceType.SPOTIFY, kind("https://open.spotify.com/album/abc?si=1"))
        assertEquals(SourceType.YOUTUBE, kind("https://youtu.be/xyz"))
        assertEquals(SourceType.YOUTUBE_MUSIC, kind("https://music.youtube.com/watch?v=1"))
        assertEquals(SourceType.AUDIBLE, kind("https://www.audible.com/pd/B0123"))
        assertEquals(SourceType.VIDEO, kind("https://www.netflix.com/title/80100172"))
    }

    @Test
    fun audioFilesAndRadioAreStreams() {
        assertEquals(SourceType.STREAM_URL, kind("https://example.com/story.mp3?token=1"))
        assertEquals(SourceType.STREAM_URL, kind("https://example.com/live/playlist.m3u8"))
        assertEquals(SourceType.STREAM_URL, kind("http://radio.example.org:8000/live"))
        assertEquals(SourceType.STREAM_URL, kind("https://stream.example.fm/kids"))
        assertEquals(SourceType.STREAM_URL, kind("https://playerservices.streamtheworld.com/api/livestream-redirect/KIDS.mp3"))
    }

    @Test
    fun feedsArePodcasts() {
        assertEquals(SourceType.PODCAST, kind("https://feeds.megaphone.fm/storypirates"))
        assertEquals(SourceType.PODCAST, kind("https://anchor.fm/s/12345/podcast/rss"))
        assertEquals(SourceType.PODCAST, kind("https://example.com/show/feed.xml"))
        assertEquals(SourceType.PODCAST, kind("https://www.example.com/podcast/rss/"))
        assertEquals(SourceType.PODCAST, kind("https://rss.art19.com/wow-in-the-world"))
    }

    @Test
    fun otherPagesOpenInAnotherApp() {
        assertEquals(SourceType.APP_LINK, kind("https://www.example.com/kids/stories"))
        assertEquals(SourceType.APP_LINK, kind("someapp://play/123"))
    }
}
