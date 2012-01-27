package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.GoAwayFrame;

public class GoAwayBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;
    private State state = State.LAST_STREAM_ID;
    private int cursor;
    private int lastStreamId;
    private int statusCode;

    public GoAwayBodyParser(ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
    }

    @Override
    public boolean parse(ByteBuffer buffer) throws StreamException
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case LAST_STREAM_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        lastStreamId = buffer.getInt() & 0x7F_FF_FF_FF;
                        state = State.STATUS_CODE;
                    }
                    else
                    {
                        state = State.LAST_STREAM_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case LAST_STREAM_ID_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    lastStreamId += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        lastStreamId &= 0x7F_FF_FF_FF;
                        state = State.STATUS_CODE;
                    }
                    break;
                }
                case STATUS_CODE:
                {
                    if (buffer.remaining() >= 4)
                    {
                        statusCode = buffer.getInt();
                        onGoAway();
                        return true;
                    }
                    else
                    {
                        state = State.STATUS_CODE_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case STATUS_CODE_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    statusCode += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        onGoAway();
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

    private void onGoAway()
    {
        GoAwayFrame frame = new GoAwayFrame(controlFrameParser.getVersion(), lastStreamId, statusCode);
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        state = State.LAST_STREAM_ID;
        cursor = 0;
        lastStreamId = 0;
        statusCode = 0;
    }

    private enum State
    {
        LAST_STREAM_ID, LAST_STREAM_ID_BYTES, STATUS_CODE, STATUS_CODE_BYTES
    }
}
