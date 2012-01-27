package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.HeadersFrame;

public class HeadersBodyParser extends ControlFrameBodyParser
{
    private final Headers headers = new Headers();
    private final ControlFrameParser controlFrameParser;
    private final HeadersBlockParser headersBlockParser;
    private State state = State.STREAM_ID;
    private int cursor;
    private int streamId;

    public HeadersBodyParser(CompressionFactory.Decompressor decompressor, ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
        this.headersBlockParser = new HeadersHeadersBlockParser(decompressor);
    }

    @Override
    public boolean parse(ByteBuffer buffer) throws StreamException
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case STREAM_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        streamId = buffer.getInt() & 0x7F_FF_FF_FF;
                        state = State.HEADERS;
                    }
                    else
                    {
                        state = State.STREAM_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case STREAM_ID_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    streamId += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        streamId &= 0x7F_FF_FF_FF;
                        state = State.HEADERS;
                    }
                    break;
                }
                case HEADERS:
                {
                    short version = controlFrameParser.getVersion();
                    int length = controlFrameParser.getLength() - 4;
                    if (headersBlockParser.parse(version, length, buffer))
                    {
                        byte flags = controlFrameParser.getFlags();
                        if (flags != 0 && flags != HeadersInfo.FLAG_FIN && flags != HeadersInfo.FLAG_RESET_COMPRESSION)
                            throw new IllegalArgumentException("Invalid flag " + flags + " for frame " + ControlFrameType.HEADERS);

                        HeadersFrame frame = new HeadersFrame(version, flags, streamId, new Headers(headers, true));
                        controlFrameParser.onControlFrame(frame);

                        reset();
                        return true;
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private void reset()
    {
        headers.clear();
        state = State.STREAM_ID;
        cursor = 0;
        streamId = 0;
    }

    private enum State
    {
        STREAM_ID, STREAM_ID_BYTES, HEADERS
    }

    private class HeadersHeadersBlockParser extends HeadersBlockParser
    {
        public HeadersHeadersBlockParser(CompressionFactory.Decompressor decompressor)
        {
            super(decompressor);
        }

        @Override
        protected void onHeader(String name, String[] values)
        {
            for (String value : values)
                headers.add(name, value);
        }
    }
}
