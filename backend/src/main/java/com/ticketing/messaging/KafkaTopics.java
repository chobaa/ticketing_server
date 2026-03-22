package com.ticketing.messaging;

public final class KafkaTopics {

    public static final String TICKET_RESERVED = "ticket-reserved";
    public static final String TICKET_CANCELED = "ticket-canceled";
    public static final String QUEUE_ENTER = "queue-enter";

    private KafkaTopics() {}
}
