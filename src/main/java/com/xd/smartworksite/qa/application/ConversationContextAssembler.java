package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.qa.dto.QaHistoryMessageRequest;
import com.xd.smartworksite.qa.domain.QaMessageRecord;

import java.util.ArrayList;
import java.util.Collections;
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

    public String assemblePersisted(List<QaMessageRecord> newestFirstMessages, List<QaHistoryMessageRequest> requestHistory,
                                    int maxContextMessages) {
        if (maxContextMessages <= 0) {
            return "";
        }
        List<QaHistoryMessageRequest> merged = new ArrayList<>();
        if (newestFirstMessages != null && !newestFirstMessages.isEmpty()) {
            List<QaMessageRecord> chronological = new ArrayList<>(newestFirstMessages);
            Collections.reverse(chronological);
            for (QaMessageRecord record : chronological) {
                if (record == null || record.getRole() == null || record.getContent() == null
                        || record.getContent().isBlank()) {
                    continue;
                }
                QaHistoryMessageRequest message = new QaHistoryMessageRequest();
                message.setRole(record.getRole());
                message.setContent(record.getContent());
                merged.add(message);
            }
        }
        if (requestHistory != null && !requestHistory.isEmpty()) {
            merged.addAll(requestHistory);
        }
        return assemble(merged, maxContextMessages);
    }
}
