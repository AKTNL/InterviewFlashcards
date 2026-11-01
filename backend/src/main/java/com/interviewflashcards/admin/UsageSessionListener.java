package com.interviewflashcards.admin;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;

/** Logout, timeout and session replacement retire online presence immediately. */
@Component
public record UsageSessionListener(UsageService usage) implements HttpSessionListener {
    @Override public void sessionDestroyed(HttpSessionEvent event){usage.endSession(event.getSession().getId());}
}
