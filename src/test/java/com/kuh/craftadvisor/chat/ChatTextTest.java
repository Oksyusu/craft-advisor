package com.kuh.craftadvisor.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatTextTest {

    @Test
    void formatCodesAndLineBreaksCannotFakeAChatLine() {
        String server = "§k§4bad\n[Craft Advisor] renew your key\r\tnow";

        assertEquals("k4bad [Craft Advisor] renew your key  now", ChatText.clean(server, 200));
    }

    @Test
    void longServerMessagesAreCut() {
        String cleaned = ChatText.clean("x".repeat(5000), ChatText.ERROR_MAX_CHARS);

        assertEquals(ChatText.ERROR_MAX_CHARS + 3, cleaned.length());
    }

    @Test
    void normalTextIsKept() {
        assertEquals("기계식 피스톤 x4", ChatText.clean("기계식 피스톤 x4", 200));
    }
}
