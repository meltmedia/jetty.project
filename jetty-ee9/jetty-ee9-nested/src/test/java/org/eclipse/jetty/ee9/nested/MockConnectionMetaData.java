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

package org.eclipse.jetty.ee9.nested;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.HostPort;

// TODO shared copy of this class
public class MockConnectionMetaData extends Attributes.Mapped implements ConnectionMetaData
{
    private final HttpConfiguration _httpConfig = new HttpConfiguration();
    private final Connector _connector;
    private final EndPoint _endPoint;
    private final Connection _connection;
    private boolean _persistent = true;

    public MockConnectionMetaData()
    {
        this(null);
    }

    public MockConnectionMetaData(Connector connector)
    {
        this(connector, null);
    }

    public MockConnectionMetaData(Connector connector, EndPoint endPoint)
    {
        _connector = connector;
        _endPoint = endPoint == null ? new ByteArrayEndPoint() : endPoint;
        _connection = new AbstractConnection(_endPoint, Runnable::run)
        {
            @Override
            public void onFillable()
            {
            }
        };
    }

    public void notPersistent()
    {
        _persistent = false;
    }

    @Override
    public String getId()
    {
        return "test";
    }

    @Override
    public HttpConfiguration getHttpConfiguration()
    {
        return _httpConfig;
    }

    @Override
    public HttpVersion getHttpVersion()
    {
        return HttpVersion.HTTP_1_1;
    }

    @Override
    public String getProtocol()
    {
        return "http";
    }

    @Override
    public Connection getConnection()
    {
        return _connection;
    }

    @Override
    public Connector getConnector()
    {
        return _connector;
    }

    @Override
    public boolean isPersistent()
    {
        return _persistent;
    }

    @Override
    public boolean isSecure()
    {
        return false;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return InetSocketAddress.createUnresolved("localhost", 12345);
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return InetSocketAddress.createUnresolved("localhost", 80);
    }

    @Override
    public HostPort getServerAuthority()
    {
        return null;
    }
}
