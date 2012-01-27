package org.eclipse.jetty.spdy.api;

import java.util.HashMap;
import java.util.Map;

public enum StreamStatus
{
    PROTOCOL_ERROR(1, 1),
    INVALID_STREAM(2, 2),
    REFUSED_STREAM(3, 3),
    UNSUPPORTED_VERSION(4, 4),
    CANCEL_STREAM(5, 5),
    INTERNAL_ERROR(6, -1),
    FLOW_CONTROL_ERROR(7, 6),
    STREAM_IN_USE(-1, 7),
    STREAM_ALREADY_CLOSED(-1, 8);

    public static StreamStatus from(short version, int code)
    {
        switch (version)
        {
            case 2:
                return Mapper.v2Codes.get(code);
            case 3:
                return Mapper.v3Codes.get(code);
            default:
                throw new IllegalStateException();
        }
    }

    private final int v2Code;
    private final int v3Code;

    private StreamStatus(int v2Code, int v3Code)
    {
        this.v2Code = v2Code;
        if (v2Code >= 0)
            Mapper.v2Codes.put(v2Code, this);
        this.v3Code = v3Code;
        if (v3Code >= 0)
            Mapper.v3Codes.put(v3Code, this);
    }

    public int getCode(short version)
    {
        switch (version)
        {
            case 2:
                return v2Code;
            case 3:
                return v3Code;
            default:
                throw new IllegalStateException();
        }
    }

    private static class Mapper
    {
        private static final Map<Integer, StreamStatus> v2Codes = new HashMap<>();
        private static final Map<Integer, StreamStatus> v3Codes = new HashMap<>();
    }
}
