package com.network.p2p.network;

public class Protocol {
    public static final byte TYPE_HELLO = 0x01;
    public static final byte TYPE_QUERY_FILES = 0x02;
    public static final byte TYPE_RESPONSE_FILES = 0x03;

    // Separator for text fields in payload
    public static final String SEPARATOR = ":";
}
