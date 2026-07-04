package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.qa.dto.QaHistoryMessageRequest;

import java.util.List;

public class ConversationContextAssembler {

    public String assemble(List<QaHistoryMessageRequest> history, int maxContextMessages) {
        if (history == null || history.isEmpty() || maxContextMessages <= 0) {
            return "";
        }
        int fromIndex = Math.max(0, history.size() - maxContextMessages);
        StringBuilder builder = new StringBuilder();
        for (QaHistoryMessageRequest message : history.subList(fromIndex, history.size())) {
            if (message == null || message.getRole() == null || message.getContent() == null
                    || message.getContent().isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(message.getRole().name()).append(": ").append(message.getContent().trim());
        }
        return builder.toString();
    }
}
