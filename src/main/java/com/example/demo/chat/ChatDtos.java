package com.example.demo.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class ChatDtos {
    private ChatDtos() {}

    public record Message(
            @NotNull @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 4000) String content) {}

    public record Request(
            @NotBlank @Size(max = 2000) String message,
            @Size(max = 8) List<@NotNull @Valid Message> history) {}

    public record Source(String id, String title, String path) {}
    public record Suggestion(String question) {}
    public record Status(boolean aiEnabled, List<Suggestion> suggestions) {}
    public record Response(String answer, String mode, String notice, List<Source> sources) {}
}
