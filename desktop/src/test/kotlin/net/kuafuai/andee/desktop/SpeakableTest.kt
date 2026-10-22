package net.kuafuai.andee.desktop

import net.kuafuai.andee.desktop.voice.MacVoice
import org.junit.Test
import org.junit.Assert.assertEquals

class SpeakableTest {
    @Test fun endSentinelIsNotRead() {
        assertEquals("好了。", MacVoice.speakable("好了。[END]"))
        assertEquals("好了。", MacVoice.speakable("好了。 [end]"))
    }

    @Test fun markdownIsNotRead() {
        assertEquals("已经打开微信了", MacVoice.speakable("已经**打开微信**了"))
        assertEquals("结果\n第一步", MacVoice.speakable("## 结果\n- 第一步"))
    }

    @Test fun moodMarkersAreNotRead() {
        assertEquals("找到了，在第三页。", MacVoice.speakable("[happy] 找到了，在第三页。[END]"))
    }

    @Test fun snakeCaseSurvives() {
        assertEquals("调用 tap_screen 完成", MacVoice.speakable("调用 tap_screen 完成"))
    }
}
