package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.zip.ZipException;

import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.StreamStatus;

public abstract class HeadersBlockParser
{
    private final CompressionFactory.Decompressor decompressor;
    private byte[] data;

    protected HeadersBlockParser(CompressionFactory.Decompressor decompressor)
    {
        this.decompressor = decompressor;
    }

    public boolean parse(int version, int length, ByteBuffer buffer) throws StreamException
    {
        // Need to be sure that all the compressed data has arrived
        // Because SPDY uses SYNC_FLUSH mode, and the Java API
        // does not expose when decompression is finished with this mode
        // (but only when using NO_FLUSH), then we need to
        // accumulate the compressed bytes until we have all of them

        boolean accumulated = accumulate(length, buffer);
        if (!accumulated)
            return false;

        byte[] compressedHeaders = data;
        data = null;
        ByteBuffer decompressedHeaders = decompress(compressedHeaders);

        Charset iso1 = Charset.forName("ISO-8859-1");

        // We know the decoded bytes contain the full headers,
        // so optimize instead of looping byte by byte
        int count = readCount(version, decompressedHeaders);
        for (int i = 0; i < count; ++i)
        {
            int nameLength = readNameLength(version, decompressedHeaders);
            if (nameLength == 0)
                throw new StreamException(StreamStatus.PROTOCOL_ERROR, "Invalid header name length");
            byte[] nameBytes = new byte[nameLength];
            decompressedHeaders.get(nameBytes);
            String name = new String(nameBytes, iso1);

            int valueLength = readValueLength(version, decompressedHeaders);
            if (valueLength == 0)
                throw new StreamException(StreamStatus.PROTOCOL_ERROR, "Invalid header value length");
            byte[] valueBytes = new byte[valueLength];
            decompressedHeaders.get(valueBytes);
            String value = new String(valueBytes, iso1);
            // Multi valued headers are separate by NUL
            String[] values = value.split("\u0000");
            // Check if there are multiple NULs (section 2.6.9)
            for (String v : values)
                if (v.length() == 0)
                    throw new StreamException(StreamStatus.PROTOCOL_ERROR, "Invalid multi valued header");

            onHeader(name, values);
        }

        return true;
    }

    private boolean accumulate(int length, ByteBuffer buffer)
    {
        int remaining = buffer.remaining();
        if (data == null)
        {
            if (remaining < length)
            {
                data = new byte[remaining];
                buffer.get(data);
                return false;
            }
            else
            {
                data = new byte[length];
                buffer.get(data);
                return true;
            }
        }
        else
        {
            int accumulated = data.length;
            int needed = length - accumulated;
            if (remaining < needed)
            {
                byte[] local = new byte[accumulated + remaining];
                System.arraycopy(data, 0, local, 0, accumulated);
                buffer.get(local, accumulated, remaining);
                data = local;
                return false;
            }
            else
            {
                byte[] local = new byte[length];
                System.arraycopy(data, 0, local, 0, accumulated);
                buffer.get(local, accumulated, needed);
                data = local;
                return true;
            }
        }
    }

    private int readCount(int version, ByteBuffer buffer) throws StreamException
    {
        if (version == 2)
            return buffer.getShort();
        else if (version == 3)
            return buffer.getInt();
        else
            throw new IllegalStateException();
    }

    private int readNameLength(int version, ByteBuffer buffer) throws StreamException
    {
        return readCount(version, buffer);
    }

    private int readValueLength(int version, ByteBuffer buffer) throws StreamException
    {
        return readCount(version, buffer);
    }

    protected abstract void onHeader(String name, String[] values);

    private ByteBuffer decompress(byte[] compressed) throws StreamException
    {
        try
        {
            byte[] decompressed = null;
            byte[] buffer = new byte[compressed.length * 2];
            decompressor.setInput(compressed);

            while (true)
            {
                int count = decompressor.decompress(buffer);
                if (count == 0)
                {
                    if (decompressed != null)
                        return ByteBuffer.wrap(decompressed);
                    else
                        decompressor.setDictionary(Headers.DICTIONARY);
                }
                else
                {
                    if (count < buffer.length)
                    {
                        if (decompressed == null)
                        {
                            // Only one pass was needed to decompress
                            return ByteBuffer.wrap(buffer, 0, count);
                        }
                        else
                        {
                            // Last pass needed to decompress, merge decompressed bytes
                            byte[] result = new byte[decompressed.length + count];
                            System.arraycopy(decompressed, 0, result, 0, decompressed.length);
                            System.arraycopy(buffer, 0, result, decompressed.length, count);
                            return ByteBuffer.wrap(result);
                        }
                    }
                    else
                    {
                        if (decompressed == null)
                        {
                            decompressed = buffer;
                            buffer = new byte[buffer.length];
                        }
                        else
                        {
                            byte[] result = new byte[decompressed.length + buffer.length];
                            System.arraycopy(decompressed, 0, result, 0, decompressed.length);
                            System.arraycopy(buffer, 0, result, decompressed.length, buffer.length);
                            decompressed = result;
                        }
                    }
                }
            }
        }
        catch (ZipException x)
        {
            throw new StreamException(StreamStatus.PROTOCOL_ERROR, x);
        }
    }
}
