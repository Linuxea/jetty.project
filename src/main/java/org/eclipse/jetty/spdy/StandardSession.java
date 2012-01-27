package org.eclipse.jetty.spdy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.spdy.api.*;
import org.eclipse.jetty.spdy.frames.*;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StandardSession implements ISession, Parser.Listener, ISession.Controller.Handler
{
    private static final Logger logger = LoggerFactory.getLogger(Session.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Integer, IStream> streams = new ConcurrentHashMap<>();
    private final Queue<FrameBytes> queue = new LinkedList<>();
    private final Controller controller;
    private final AtomicInteger streamIds;
    private final AtomicInteger pingIds;
    private final FrameListener frameListener;
    private final Generator generator;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int lastStreamId;
    private volatile boolean rejected;
    private boolean flushing;

    public StandardSession(Controller controller, int initialStreamId, FrameListener frameListener, Generator generator)
    {
        this.controller = controller;
        this.streamIds = new AtomicInteger(initialStreamId);
        this.pingIds = new AtomicInteger(initialStreamId);
        this.frameListener = frameListener;
        this.generator = generator;
    }

    @Override
    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    @Override
    public Stream syn(short version, SynInfo synInfo, Stream.FrameListener frameListener)
    {
        // Synchronization is necessary.
        // SPEC v3, 2.3.1 requires that the stream creation be monotonically crescent
        // so we cannot allow thread1 to create stream1 and thread2 create stream3 and
        // have stream3 hit the network before stream1, not only to comply with the spec
        // but also because the compression context for the headers would be wrong, as the
        // frame with a compression history will come before the first compressed frame.
        synchronized (this)
        {
            if (synInfo.isUnidirectional())
            {
                // TODO: unidirectional functionality
                throw new UnsupportedOperationException();
            }
            else
            {
                int streamId = streamIds.getAndAdd(2);
                SynStreamFrame synStream = new SynStreamFrame(version, synInfo.getFlags(), streamId, 0, synInfo.getPriority(), synInfo.getHeaders());
                IStream stream = createStream(synStream, frameListener);
                try
                {
                    // May throw if wrong version or headers too big
                    control(stream, synStream);
                    flush();
                }
                catch (StreamException x)
                {
                    removeStream(stream);
                    throw new SPDYException(x);
                }

                return stream;
            }
        }
    }

    @Override
    public void rst(short version, RstInfo rstInfo)
    {
        try
        {
            // SPEC v3, 2.2.2
            if (!closed.get())
            {
                RstStreamFrame frame = new RstStreamFrame(version, rstInfo.getStreamId(), rstInfo.getStreamStatus().getCode(version));
                control(null, frame);
            }
        }
        catch (StreamException x)
        {
            logger.info("Could not send reset on stream " + rstInfo.getStreamId(), x);
        }
    }

    @Override
    public void settings(short version, SettingsInfo settingsInfo)
    {
        SettingsFrame frame = new SettingsFrame(version, settingsInfo.getFlags(), settingsInfo.getSettings());
        settings(frame);
        flush();
    }

    private void settings(SettingsFrame frame)
    {
        try
        {
            control(null, frame);
        }
        catch (StreamException x)
        {
            // Should never happen, but just in case we rethrow
            throw new SPDYException(x);
        }
    }

    @Override
    public PingInfo ping(short version)
    {
        int pingId = pingIds.getAndAdd(2);
        PingFrame frame = new PingFrame(version, pingId);
        ping(frame);
        flush();
        return new PingInfo(pingId);
    }

    private void ping(PingFrame frame)
    {
        try
        {
            control(null, frame);
        }
        catch (StreamException x)
        {
            // Should never happen, but just in case we rethrow
            throw new SPDYException(x);
        }
    }

    @Override
    public List<Stream> getStreams()
    {
        List<Stream> result = new ArrayList<>();
        result.addAll(streams.values());
        return result;
    }

    @Override
    public void goAway(short version)
    {
        if (closed.compareAndSet(false, true))
        {
            if (!rejected)
            {
                GoAwayFrame frame = new GoAwayFrame(version, lastStreamId, SessionStatus.OK.getCode());
                goAway(frame);
                flush();
            }
        }
    }

    private void goAway(GoAwayFrame frame)
    {
        try
        {
            control(null, frame);
        }
        catch (StreamException x)
        {
            // Should never happen, but just in case we rethrow
            throw new SPDYException(x);
        }
    }

    @Override
    public void onControlFrame(ControlFrame frame)
    {
        logger.debug("Processing {}", frame);

        switch (frame.getType())
        {
            case SYN_STREAM:
            {
                if (!closed.get())
                    onSyn((SynStreamFrame)frame);
                break;
            }
            case SYN_REPLY:
            {
                onReply((SynReplyFrame)frame);
                break;
            }
            case RST_STREAM:
            {
                onRst((RstStreamFrame)frame);
                break;
            }
            case SETTINGS:
            {
                onSettings((SettingsFrame)frame);
                break;
            }
            case NOOP:
            {
                // Just ignore it
                break;
            }
            case PING:
            {
                onPing((PingFrame)frame);
                break;
            }
            case GO_AWAY:
            {
                onGoAway((GoAwayFrame)frame);
                break;
            }
            case HEADERS:
            {
                onHeaders((HeadersFrame)frame);
                break;
            }
            case WINDOW_UPDATE:
            {
                onWindowUpdate((WindowUpdateFrame)frame);
                break;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
    }

    @Override
    public void onDataFrame(DataFrame frame, ByteBuffer data)
    {
        logger.info("Processing {}, {} data bytes", frame, data.remaining());

        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        if (stream == null)
        {
            // There is no stream, therefore no version, so we hardcode version 2.
            short version = (short)2;
            rst(version, new RstInfo(streamId, StreamStatus.INVALID_STREAM));
        }
        else
        {
            stream.handle(frame, data);
            flush();

            if (stream.isClosed())
            {
                updateLastStreamId(stream);
                removeStream(stream);
            }
        }
    }

    @Override
    public void onStreamException(StreamException x)
    {
        // TODO: must send a RST_STREAM on the proper stream... too little information in StreamException
    }

    @Override
    public void onSessionException(SessionException x)
    {
        // TODO: must send a GOAWAY with the x.sessionStatus, then close

        // Check for null to support tests
        if (controller != null)
            controller.close(true);
    }

    private void onSyn(SynStreamFrame synStream)
    {
        IStream stream = new StandardStream(this, synStream);
        logger.debug("Opening {}", stream);
        int streamId = synStream.getStreamId();
        Stream existing = streams.putIfAbsent(streamId, stream);
        if (existing != null)
        {
            logger.debug("Detected duplicate {}, resetting", stream);
            rst(existing.getVersion(), new RstInfo(streamId, StreamStatus.PROTOCOL_ERROR));
        }
        else
        {
            stream.handle(synStream);
            Stream.FrameListener frameListener = notifyOnSyn(stream, synStream);
            stream.setFrameListener(frameListener);

            flush();

            // The onSyn() listener may have sent a frame that closed the stream
            if (stream.isClosed())
                removeStream(stream);
        }
    }

    private IStream createStream(SynStreamFrame synStream, Stream.FrameListener frameListener)
    {
        IStream stream = new StandardStream(this, synStream);
        stream.setFrameListener(frameListener);
        if (streams.putIfAbsent(synStream.getStreamId(), stream) != null)
        {
            // If this happens we have a bug since we did not check that the peer's streamId was valid
            // (if we're on server, then the client sent an odd streamId and we did not check that)
            throw new IllegalStateException();
        }

        logger.debug("Created {}", stream);
        notifyStreamCreated(stream);

        return stream;
    }

    private void notifyStreamCreated(IStream stream)
    {
        for (Listener listener : listeners)
        {
            if (listener instanceof StreamListener)
            {
                try
                {
                    ((StreamListener)listener).onStreamCreated(stream);
                }
                catch (Exception x)
                {
                    logger.info("Exception while notifying listener " + listener, x);
                }
            }
        }
    }

    private void removeStream(IStream stream)
    {
        IStream removed = streams.remove(stream.getId());
        if (removed != null)
        {
            assert removed == stream;
            logger.debug("Removed {}", stream);
            notifyStreamClosed(stream);
        }
    }

    private void notifyStreamClosed(IStream stream)
    {
        for (Listener listener : listeners)
        {
            if (listener instanceof StreamListener)
            {
                try
                {
                    ((StreamListener)listener).onStreamClosed(stream);
                }
                catch (Exception x)
                {
                    logger.info("Exception while notifying listener " + listener, x);
                }
            }
        }
    }

    private void onReply(SynReplyFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        stream.handle(frame);
        flush();
        if (stream.isClosed())
            removeStream(stream);
    }

    private void onRst(RstStreamFrame frame)
    {
        // TODO: implement logic to clean up unidirectional streams associated with this stream

        notifyOnRst(frame);

        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        if (stream != null)
            removeStream(stream);
    }

    private void onSettings(SettingsFrame frame)
    {
        notifyOnSettings(frame);
        flush();
    }

    private void onPing(PingFrame frame)
    {
        int pingId = frame.getPingId();
        if (pingId % 2 == pingIds.get() % 2)
            notifyOnPing(frame);
        else
            ping(frame);
        flush();
    }

    private void onGoAway(GoAwayFrame frame)
    {
        rejected = true;
        notifyOnGoAway(frame);
        flush();
    }

    private void onHeaders(HeadersFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        stream.handle(frame);
        flush();
        if (stream.isClosed())
            removeStream(stream);
    }

    private void onWindowUpdate(WindowUpdateFrame frame)
    {
        int streamId = frame.getStreamId();
        IStream stream = streams.get(streamId);
        if (stream != null)
            stream.handle(frame);
        flush();
    }

    private Stream.FrameListener notifyOnSyn(Stream stream, SynStreamFrame frame)
    {
        try
        {
            if (frameListener != null)
            {
                logger.debug("Invoking syn callback with frame {} on listener {}", frame, frameListener);
                SynInfo synInfo = new SynInfo(frame.getHeaders(), frame.isClose(), frame.isUnidirectional(), frame.getAssociatedStreamId(), frame.getPriority());
                return frameListener.onSyn(stream, synInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + frameListener, x);
        }
        return null;
    }

    private void notifyOnRst(RstStreamFrame frame)
    {
        try
        {
            if (frameListener != null)
            {
                logger.debug("Invoking rst callback with frame {} on listener {}", frame, frameListener);
                RstInfo rstInfo = new RstInfo(frame.getStreamId(), StreamStatus.from(frame.getVersion(), frame.getStatusCode()));
                frameListener.onRst(this, rstInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + frameListener, x);
        }
    }

    private void notifyOnSettings(SettingsFrame frame)
    {
        try
        {
            if (frameListener != null)
            {
                logger.debug("Invoking settings callback with frame {} on listener {}", frame, frameListener);
                SettingsInfo settingsInfo = new SettingsInfo(frame.getSettings(), frame.isClearPersisted());
                frameListener.onSettings(this, settingsInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + frameListener, x);
        }
    }

    private void notifyOnPing(final PingFrame frame)
    {
        try
        {
            if (frameListener != null)
            {
                logger.debug("Invoking ping callback with frame {} on listener {}", frame, frameListener);
                PingInfo pingInfo = new PingInfo(frame.getPingId());
                frameListener.onPing(this, pingInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + frameListener, x);
        }
    }

    private void notifyOnGoAway(GoAwayFrame frame)
    {
        try
        {
            if (frameListener != null)
            {
                logger.debug("Invoking go away callback with frame {} on listener {}", frame, frameListener);
                GoAwayInfo goAwayInfo = new GoAwayInfo(frame.getLastStreamId(), SessionStatus.from(frame.getStatusCode()));
                frameListener.onGoAway(this, goAwayInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + frameListener, x);
        }
    }

    @Override
    public void control(IStream stream, ControlFrame frame) throws StreamException
    {
        if (stream != null)
            updateLastStreamId(stream);
        ByteBuffer buffer = generator.control(frame);
        logger.debug("Posting {} on {}", frame, stream);
        enqueue(new ControlFrameBytes(frame, buffer));
    }

    private void updateLastStreamId(IStream stream)
    {
        int streamId = stream.getId();
        if (stream.isClosed() && streamId % 2 != streamIds.get() % 2)
        {
            // TODO: perhaps we need a non-blocking updateMax()
            // to avoid that concurrent updates overwrites
            // the lastStreamId with lower values
            lastStreamId = streamId;
        }
    }

    @Override
    public void data(IStream stream, DataInfo dataInfo)
    {
        logger.debug("Posting {} on {}", dataInfo, stream);
        enqueue(new DataFrameBytes(stream, dataInfo));
        flush();
    }

    @Override
    public void flush()
    {
        FrameBytes frameBytes;
        synchronized (queue)
        {
            if (flushing)
                return;
            frameBytes = queue.poll();
            if (frameBytes == null)
                return;
            flushing = true;
            logger.debug("Flushing {}, {} frame(s) queued", frameBytes, queue.size());
        }

        ByteBuffer buffer = frameBytes.getByteBuffer();
        if (buffer == null)
        {
            enqueue(frameBytes);
            return;
        }

        logger.debug("Writing {} frame bytes of {}", buffer.remaining(), frameBytes);
        write(buffer, this);

        frameBytes.complete();
    }

    private void enqueue(FrameBytes frameBytes)
    {
        // TODO: handle priority; e.g. use queues to prioritize the buffers ?
        synchronized (queue)
        {
            queue.offer(frameBytes);
        }
    }

    @Override
    public void complete()
    {
        synchronized (queue)
        {
            flushing = false;
        }
        flush();
    }

    protected void write(final ByteBuffer buffer, Controller.Handler handler)
    {
        controller.write(buffer, handler);
    }

    private abstract class FrameBytes
    {
        protected abstract ByteBuffer getByteBuffer();

        public abstract void complete();
    }

    private class ControlFrameBytes extends FrameBytes
    {
        private final ControlFrame frame;
        private final ByteBuffer buffer;

        private ControlFrameBytes(ControlFrame frame, ByteBuffer buffer)
        {
            this.frame = frame;
            this.buffer = buffer;
        }

        @Override
        protected ByteBuffer getByteBuffer()
        {
            return buffer;
        }

        @Override
        public void complete()
        {
            if (frame.getType() == ControlFrameType.GO_AWAY)
                controller.close(true);
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class DataFrameBytes extends FrameBytes
    {
        private final IStream stream;
        private final DataInfo data;
        private int dataLength;

        private DataFrameBytes(IStream stream, DataInfo data)
        {
            this.stream = stream;
            this.data = data;
        }

        @Override
        protected ByteBuffer getByteBuffer()
        {
            int windowSize = stream.getWindowSize();
            if (windowSize <= 0)
                return null;

            ByteBuffer buffer = generator.data(stream.getId(), windowSize, data);
            dataLength = buffer.remaining() - DataFrame.HEADER_LENGTH;

            return buffer;
        }

        @Override
        public void complete()
        {
            stream.updateWindowSize(-dataLength);

            if (!data.isConsumed())
            {
                enqueue(this);
            }
            else
            {
                stream.updateCloseState(data.isClose());
                if (stream.isClosed())
                    removeStream(stream);
            }
        }

        @Override
        public String toString()
        {
            return "data on " + stream;
        }
    }
}
