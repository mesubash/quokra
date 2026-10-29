package io.github.mesubash.quokra.conversation;

public final class Enums {

    private Enums() {}

    public enum Priority { NORMAL, HIGH, URGENT }

    public enum HandoffReason { CUSTOMER_REQUEST, FALLBACK_LIMIT, URGENT, BOT_ERROR }

    public enum SenderType { CUSTOMER, BOT, AGENT, SYSTEM }

    public enum EventType { CREATED, HANDOFF, ACCEPTED, RESOLVED, RETURNED_TO_BOT, REOPENED, IDENTIFIED,
        CONTACT_ADDED, BLOCKED, TRANSFERRED }
}
