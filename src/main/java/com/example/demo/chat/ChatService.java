package com.example.demo.chat;

import com.example.demo.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class ChatService {
    private final ChatKnowledge knowledge;
    private final ChatAiClient ai;
    private final Map<String, Window> limits = new HashMap<>();
    private static class Window {
        long start = Instant.now().getEpochSecond();
        int count;
        boolean busy;
    }

    public ChatService(ChatKnowledge knowledge, ChatAiClient ai) {
        this.knowledge = knowledge;
        this.ai = ai;
    }

    String role(Authentication actor) {
        if (actor == null || !actor.isAuthenticated() || "anonymousUser".equals(actor.getPrincipal())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng trợ lý.");
        }
        return actor.getAuthorities().stream().map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .filter(ChatKnowledge.ROLES::contains).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Vai trò chưa được hỗ trợ."));
    }

    public ChatDtos.Status status(Authentication actor) {
        String role = role(actor);
        return new ChatDtos.Status(ai.enabled(), knowledge.available(role).stream().limit(5)
                .map(g -> new ChatDtos.Suggestion(g.question())).toList());
    }

    public ChatDtos.Response reply(Authentication actor, ChatDtos.Request request) {
        String role = role(actor);
        String user = actor.getName();
        enter(user);
        try {
            String query = request.message();
            // Short follow-up questions can use the preceding user question for retrieval only.
            if (ChatKnowledge.normalize(query).matches(".*(buoc tiep|sau do|noi ro|chi tiet|giai thich them|tai sao).*")) {
                var history = request.history() == null ? List.<ChatDtos.Message>of() : request.history();
                for (int i = history.size() - 1; i >= 0; i--) {
                    if (history.get(i).role().equals("user")) {
                        query += " " + history.get(i).content();
                        break;
                    }
                }
            }
            List<ChatKnowledge.Guide> guides = knowledge.search(role, query);
            if (guides.isEmpty()) {
                return new ChatDtos.Response("Mình chưa tìm thấy hướng dẫn phù hợp với câu hỏi trong phạm vi vai trò của bạn. "
                        + "Bạn hãy nêu rõ công việc hoặc chọn một câu hỏi gợi ý. Trợ lý hiện chỉ hướng dẫn thao tác, "
                        + "chưa tra cứu dữ liệu cá nhân hay thực hiện thay đổi trên hệ thống.", "guide", "", List.of());
            }
            Optional<String> generated = ai.answer(role, request, guides);
            String answer = generated.orElseGet(() -> String.join("\n\n", guides.stream()
                    .map(g -> g.title() + "\n" + g.content()).toList()));
            String notice = generated.isPresent() ? ""
                    : ai.enabled() ? "AI tạm thời không phản hồi. Đang hiển thị hướng dẫn có sẵn."
                    : "Đang dùng hướng dẫn có sẵn; AI chưa được bật hoặc chưa có API key.";
            return new ChatDtos.Response(answer, generated.isPresent() ? "ai" : "guide", notice,
                    guides.stream().map(g -> g.source(role)).toList());
        } finally {
            synchronized (limits) { limits.get(user).busy = false; }
        }
    }

    private void enter(String user) {
        synchronized (limits) {
            long now = Instant.now().getEpochSecond();
            limits.entrySet().removeIf(e -> !e.getValue().busy && now - e.getValue().start >= 60);
            if (!limits.containsKey(user) && limits.size() >= 10000) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Trợ lý đang bận, vui lòng thử lại sau.");
            }
            Window window = limits.computeIfAbsent(user, key -> new Window());
            if (window.busy || window.count >= 12) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Vui lòng chờ trước khi gửi tiếp (tối đa 12 câu/phút).");
            }
            window.count++;
            window.busy = true;
        }
    }
}
