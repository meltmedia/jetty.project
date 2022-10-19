//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HttpContent.Factory} implementation that wraps any other {@link HttpContent.Factory} instance
 * using it as a caching authority.
 * Only HttpContent instances whose path is not a directory are cached.
 */
public class CachingHttpContentFactory implements HttpContent.Factory
{
    private static final Logger LOG = LoggerFactory.getLogger(CachingHttpContentFactory.class);

    private final HttpContent.Factory _authority;
    private final ConcurrentMap<String, CachingHttpContent> _cache = new ConcurrentHashMap<>();
    private final AtomicLong _cachedSize = new AtomicLong();
    private int _maxCachedFileSize = 128 * 1024 * 1024;
    private int _maxCachedFiles = 2048;
    private int _maxCacheSize = 256 * 1024 * 1024;

    public CachingHttpContentFactory(HttpContent.Factory authority)
    {
        _authority = authority;
    }

    protected ConcurrentMap<String, CachingHttpContent> getCache()
    {
        return _cache;
    }

    public long getCachedSize()
    {
        return _cachedSize.get();
    }

    public int getCachedFiles()
    {
        return _cache.size();
    }

    public int getMaxCachedFileSize()
    {
        return _maxCachedFileSize;
    }

    public void setMaxCachedFileSize(int maxCachedFileSize)
    {
        _maxCachedFileSize = maxCachedFileSize;
        shrinkCache();
    }

    public int getMaxCacheSize()
    {
        return _maxCacheSize;
    }

    public void setMaxCacheSize(int maxCacheSize)
    {
        _maxCacheSize = maxCacheSize;
        shrinkCache();
    }

    /**
     * @return the max number of cached files.
     */
    public int getMaxCachedFiles()
    {
        return _maxCachedFiles;
    }

    /**
     * @param maxCachedFiles the max number of cached files.
     */
    public void setMaxCachedFiles(int maxCachedFiles)
    {
        _maxCachedFiles = maxCachedFiles;
        shrinkCache();
    }

    private void shrinkCache()
    {
        // While we need to shrink
        while (_cache.size() > 0 && (_cache.size() > _maxCachedFiles || _cachedSize.get() > _maxCacheSize))
        {
            // Scan the entire cache and generate an ordered list by last accessed time.
            SortedSet<CachingHttpContent> sorted = new TreeSet<>((c1, c2) ->
            {
                long delta = NanoTime.elapsed(c2.getLastAccessedNanos(), c1.getLastAccessedNanos());
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

                delta = c1.getContentLengthValue() - c2.getContentLengthValue();
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

                return c1.getKey().compareTo(c2.getKey());
            });
            sorted.addAll(_cache.values());

            // Invalidate least recently used first
            for (CachingHttpContent content : sorted)
            {
                if (_cache.size() <= _maxCachedFiles && _cachedSize.get() <= _maxCacheSize)
                    break;
                removeFromCache(content);
            }
        }
    }

    protected void removeFromCache(CachingHttpContent content)
    {
        CachingHttpContent removed = _cache.remove(content.getKey());
        if (removed != null)
        {
            removed.release();
            _cachedSize.addAndGet(-removed.getContentLengthValue());
        }
    }

    public void flushCache()
    {
        for (CachingHttpContent content : _cache.values())
        {
            removeFromCache(content);
        }
    }

    /**
     * Tests whether the given HttpContent is cacheable, and if there is enough room to fit it in the cache.
     *
     * @param httpContent the HttpContent to test.
     * @return whether the HttpContent is cacheable.
     */
    protected boolean isCacheable(HttpContent httpContent)
    {
        if (httpContent == null)
            return true;

        if (httpContent.getResource().isDirectory())
            return false;

        if (_maxCachedFiles <= 0)
            return false;

        // Will it fit in the cache?
        long len = httpContent.getContentLengthValue();
        if (len <= 0)
            return false;
        if (httpContent instanceof FileMappedHttpContentFactory.FileMappedContent)
             return true;
        return (len <= _maxCachedFileSize && len <= _maxCacheSize);
    }

    @Override
    public HttpContent getContent(String path) throws IOException
    {
        CachingHttpContent cachingHttpContent = _cache.get(path);
        if (cachingHttpContent != null)
        {
            cachingHttpContent.setLastAccessedNanos(NanoTime.now());
            if (cachingHttpContent.isValid())
                return (cachingHttpContent instanceof NotFoundHttpContent) ? null : cachingHttpContent;
            else
                removeFromCache(cachingHttpContent);
        }

        HttpContent httpContent = _authority.getContent(path);
        if (isCacheable(httpContent))
        {
            AtomicBoolean wasAdded = new AtomicBoolean(false);
            cachingHttpContent = _cache.computeIfAbsent(path, p ->
            {
                wasAdded.set(true);
                CachingHttpContent cachingContent = (httpContent == null) ? newNotFoundContent(p) : newCachedContent(p, httpContent);
                _cachedSize.addAndGet(cachingContent.getContentLengthValue());
                return cachingContent;
            });

            if (wasAdded.get())
                shrinkCache();
            return cachingHttpContent;
        }
        return httpContent;
    }

    protected CachingHttpContent newCachedContent(String p, HttpContent httpContent)
    {
        return new CachedHttpContent(p, httpContent);
    }

    protected CachingHttpContent newNotFoundContent(String p)
    {
        return new NotFoundHttpContent(p);
    }

    protected interface CachingHttpContent extends HttpContent
    {
        long getLastAccessedNanos();

        void setLastAccessedNanos(long nanosTime);

        String getKey();

        boolean isValid();
    }

    protected static class CachedHttpContent extends HttpContentWrapper implements CachingHttpContent
    {
        private final ByteBuffer _buffer;
        private final String _cacheKey;
        private final HttpField _etagField;
        private final long _contentLengthValue;
        private final AtomicLong _lastAccessed = new AtomicLong();
        private final Set<CompressedContentFormat> _compressedFormats;
        private final String _lastModifiedValue;
        private final String _characterEncoding;
        private final MimeTypes.Type _mimeType;
        private final HttpField _contentLength;
        private final Instant _lastModifiedInstant;
        private final HttpField _lastModified;

        public CachedHttpContent(String key, HttpContent httpContent)
        {
            super(httpContent);
            _cacheKey = key;

            // TODO: do all the following lazily and asynchronously.
            HttpField etagField = httpContent.getETag();
            String eTagValue = httpContent.getETagValue();
            if (StringUtil.isNotBlank(eTagValue))
            {
                etagField = new PreEncodedHttpField(HttpHeader.ETAG, eTagValue);
            }
            _etagField = etagField;

            // Map the content into memory if possible.
            _buffer = httpContent.getBuffer();
            _contentLengthValue = httpContent.getContentLengthValue();
            _lastModifiedValue = httpContent.getLastModifiedValue();
            _characterEncoding = httpContent.getCharacterEncoding();
            _compressedFormats = httpContent.getPreCompressedContentFormats();
            _mimeType = httpContent.getMimeType();
            _contentLength = httpContent.getContentLength();
            _lastModifiedInstant = httpContent.getLastModifiedInstant();
            _lastModified = httpContent.getLastModified();

            _lastAccessed.set(NanoTime.now());
        }

        @Override
        public long getContentLengthValue()
        {
            return _contentLengthValue;
        }

        @Override
        public ByteBuffer getBuffer()
        {
            // TODO this should return a RetainableByteBuffer otherwise there is a race between
            //  threads serving the buffer while another thread invalidates it. That's going to
            //  be a lot of fun since RetainableByteBuffer is only meant to be acquired from a pool
            //  but the byte buffer here could be coming from a mmap'ed file.
            return _buffer.slice();
        }

        @Override
        public long getLastAccessedNanos()
        {
            return _lastAccessed.get();
        }

        @Override
        public void setLastAccessedNanos(long nanosTime)
        {
            _lastAccessed.set(nanosTime);
        }

        @Override
        public String getKey()
        {
            return _cacheKey;
        }

        @Override
        public void release()
        {
            // TODO re-pool buffer and release precompressed contents
        }

        @Override
        public Set<CompressedContentFormat> getPreCompressedContentFormats()
        {
            return _compressedFormats;
        }

        @Override
        public HttpField getETag()
        {
            return _etagField;
        }

        @Override
        public String getETagValue()
        {
            return _etagField == null ? null : _etagField.getValue();
        }

        @Override
        public String getCharacterEncoding()
        {
            return _characterEncoding;
        }

        @Override
        public MimeTypes.Type getMimeType()
        {
            return _mimeType;
        }

        @Override
        public HttpField getContentLength()
        {
            return _contentLength;
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return _lastModifiedInstant;
        }

        @Override
        public HttpField getLastModified()
        {
            return _lastModified;
        }

        @Override
        public String getLastModifiedValue()
        {
            return _lastModifiedValue;
        }

        @Override
        public boolean isValid()
        {
            return true;
        }
    }

    protected static class NotFoundHttpContent implements CachingHttpContent
    {
        private final AtomicLong _lastAccessed = new AtomicLong();

        private final String _key;

        public NotFoundHttpContent(String key)
        {
            _key = key;
            _lastAccessed.set(NanoTime.now());
        }

        @Override
        public String getKey()
        {
            return _key;
        }

        @Override
        public long getLastAccessedNanos()
        {
            return _lastAccessed.get();
        }

        @Override
        public void setLastAccessedNanos(long nanosTime)
        {
            _lastAccessed.set(nanosTime);
        }

        @Override
        public HttpField getContentType()
        {
            return null;
        }

        @Override
        public String getContentTypeValue()
        {
            return null;
        }

        @Override
        public String getCharacterEncoding()
        {
            return null;
        }

        @Override
        public MimeTypes.Type getMimeType()
        {
            return null;
        }

        @Override
        public HttpField getContentEncoding()
        {
            return null;
        }

        @Override
        public String getContentEncodingValue()
        {
            return null;
        }

        @Override
        public HttpField getContentLength()
        {
            return null;
        }

        @Override
        public long getContentLengthValue()
        {
            return 0;
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return null;
        }

        @Override
        public HttpField getLastModified()
        {
            return null;
        }

        @Override
        public String getLastModifiedValue()
        {
            return null;
        }

        @Override
        public HttpField getETag()
        {
            return null;
        }

        @Override
        public String getETagValue()
        {
            return null;
        }

        @Override
        public Resource getResource()
        {
            return null;
        }

        @Override
        public ByteBuffer getBuffer()
        {
            return null;
        }

        @Override
        public Set<CompressedContentFormat> getPreCompressedContentFormats()
        {
            return null;
        }

        @Override
        public void release()
        {
        }

        @Override
        public boolean isValid()
        {
            return true;
        }
    }
}
