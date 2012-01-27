package org.eclipse.jetty.spdy.api;

import java.util.Map;

public class SettingsInfo
{
    public static final byte CLEAR_PERSISTED = 1;

    private final Map<Key, Integer> settings;
    private final boolean clearPersisted;

    public SettingsInfo(Map<Key, Integer> settings)
    {
        this(settings, false);
    }

    public SettingsInfo(Map<Key, Integer> settings, boolean clearPersisted)
    {
        this.settings = settings;
        this.clearPersisted = clearPersisted;
    }

    public boolean isClearPersisted()
    {
        return clearPersisted;
    }

    public byte getFlags()
    {
        return isClearPersisted() ? CLEAR_PERSISTED : 0;
    }

    public Map<Key, Integer> getSettings()
    {
        return settings;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        SettingsInfo that = (SettingsInfo)obj;
        return settings.equals(that.settings) && clearPersisted == that.clearPersisted;
    }

    @Override
    public int hashCode()
    {
        int result = settings.hashCode();
        result = 31 * result + (clearPersisted ? 1 : 0);
        return result;
    }

    public static class Key
    {
        public static final int UPLOAD_BANDWIDTH = 1;
        public static final int DOWNLOAD_BANDWIDTH = 2;
        public static final int ROUND_TRIP_TIME = 3;
        public static final int MAX_STREAMS = 4;
        public static final int CONGESTION_WINDOW = 5;

        public static final int FLAG_PERSIST = 1 << 24;
        public static final int FLAG_PERSISTED = 2 << 24;

        private final int key;

        public Key(int key)
        {
            this.key = key;
        }

        public int getKey()
        {
            return key;
        }

        public boolean isPersist()
        {
            return (key & FLAG_PERSIST) == FLAG_PERSIST;
        }

        public boolean isPersisted()
        {
            return (key & FLAG_PERSISTED) == FLAG_PERSISTED;
        }

        public byte getFlags()
        {
            return (byte)(key >>> 24);
        }

        public int getId()
        {
            return key & 0xFF_FF_FF;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Key that = (Key)obj;
            return key == that.key;
        }

        @Override
        public int hashCode()
        {
            return key;
        }

        @Override
        public String toString()
        {
            return "[" + getFlags() + "," + getId() + "]";
        }
    }
}
