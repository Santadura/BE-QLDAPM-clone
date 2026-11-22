package com.example.demo.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatAiClientTest {
    @Test
    @SuppressWarnings("unchecked")
    void geminiMapsConversationRolesAndKeepsApiKeyOutOfUrlAndBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        ObjectMapper mapper = spy(new ObjectMapper());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[
                  {"thought":true,"text":"internal reasoning"},{"text":"Mở mục đăng ký lịch rảnh."}]}}]}
                """);
        var client = new ChatAiClient(mapper, true, "gemini", "test-gemini-key", "", http);
        var question = new ChatDtos.Request("Bước tiếp theo?", List.of(
                new ChatDtos.Message("user", "Đăng ký lịch rảnh"),
                new ChatDtos.Message("assistant", "Chọn tuần.")));
        assertEquals("Mở mục đăng ký lịch rảnh.", client.answer("TEACHER", question, List.of()).orElseThrow());
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
                request.getValue().uri().toString());
        assertEquals("test-gemini-key", request.getValue().headers().firstValue("x-goog-api-key").orElseThrow());
        assertTrue(request.getValue().headers().firstValue("Authorization").isEmpty());
        var body = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(mapper).writeValueAsString(body.capture());
        var json = mapper.valueToTree(body.getValue());
        assertTrue(json.path("systemInstruction").path("parts").path(0).path("text").asText().contains("TEACHER"));
        assertEquals("user", json.path("contents").path(0).path("role").asText());
        assertEquals("model", json.path("contents").path(1).path("role").asText());
        assertEquals("user", json.path("contents").path(2).path("role").asText());
        assertEquals("Bước tiếp theo?", json.path("contents").path(2).path("parts").path(0).path("text").asText());
        assertFalse(json.toString().contains("test-gemini-key"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void geminiRejectsBlockedIncompleteEmptyAndFailedResponses() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        var client = new ChatAiClient(new ObjectMapper(), true, "gemini", "test-key", "gemini-3.5-flash-lite", http);
        var question = new ChatDtos.Request("Lịch rảnh", null);
        for (String body : List.of(
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"},\"candidates\":[]}",
                "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"unfinished\"}]}}]}",
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}",
                "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[]}}]}",
                "not json")) {
            when(response.body()).thenReturn(body);
            assertTrue(client.answer("CS", question, List.of()).isEmpty());
        }
        when(response.statusCode()).thenReturn(429);
        assertTrue(client.answer("CS", question, List.of()).isEmpty());
    }

    @Test
    void invalidProviderOrGeminiModelCannotMakeANetworkCall() {
        HttpClient http = mock(HttpClient.class);
        for (var client : List.of(
                new ChatAiClient(new ObjectMapper(), true, "unknown", "test-key", "model", http),
                new ChatAiClient(new ObjectMapper(), true, "gemini", "test-key", "../other?key=secret", http),
                new ChatAiClient(new ObjectMapper(), true, "gemini", "", "model", http))) {
            assertFalse(client.enabled());
            assertTrue(client.answer("CS", new ChatDtos.Request("test", null), List.of()).isEmpty());
        }
        verifyNoInteractions(http);
    }

    @Test
    void springCanConstructTheChatModuleWithoutDatabaseOrAiCredentials() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "chat-test", java.util.Map.of("chat.ai-enabled", "false", "chat.api-key", "")));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(ChatAiClient.class, ChatKnowledge.class, ChatService.class, ChatController.class);
            context.refresh();
            assertFalse(context.getBean(ChatAiClient.class).enabled());
            assertFalse(context.getBean(ChatService.class).status(ChatServiceTest.actor("ADMIN")).suggestions().isEmpty());
        }
    }

    @Test
    void disabledOrMissingKeyNeverCallsProvider() {
        HttpClient http = mock(HttpClient.class);
        for (var client : List.of(new ChatAiClient(new ObjectMapper(), false, "test-key", "model", http),
                new ChatAiClient(new ObjectMapper(), true, "", "model", http))) {
            assertFalse(client.enabled());
            assertTrue(client.answer("CS", new ChatDtos.Request("test", null), List.of()).isEmpty());
        }
        verifyNoInteractions(http);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesResponsesOutputAndDiscardsFailuresAndPartialAnswers() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new ChatAiClient(new ObjectMapper(), true, "test-key", "model", http);
        var request = new ChatDtos.Request("Đăng ký lịch rảnh", null);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"status":"completed","output":[{"type":"reasoning"},
                  {"type":"message","content":[{"type":"output_text","text":"Chọn tuần cần đăng ký."}]}]}
                """);
        assertEquals("Chọn tuần cần đăng ký.", client.answer("CS", request, List.of()).orElseThrow());
        when(response.body()).thenReturn("{\"status\":\"incomplete\",\"output\":[]}");
        assertTrue(client.answer("CS", request, List.of()).isEmpty());
        when(response.statusCode()).thenReturn(429);
        assertTrue(client.answer("CS", request, List.of()).isEmpty());
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("malformed json");
        assertTrue(client.answer("CS", request, List.of()).isEmpty());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("upstream unavailable"));
        assertTrue(client.answer("CS", request, List.of()).isEmpty());
    }
}
