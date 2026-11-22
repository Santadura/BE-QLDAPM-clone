package com.example.demo.chat;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chat;

    public ChatController(ChatService chat) { this.chat = chat; }

    @GetMapping("/status")
    public ChatDtos.Status status(Authentication actor) { return chat.status(actor); }

    @PostMapping("/messages")
    public ChatDtos.Response message(Authentication actor, @Valid @RequestBody ChatDtos.Request request) {
        return chat.reply(actor, request);
    }
}
