/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cluster;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.neo4j.cluster.com.NetworkInstance;
import org.neo4j.cluster.protocol.atomicbroadcast.multipaxos.AcceptorInstanceStore;
import org.neo4j.cluster.protocol.election.ElectionCredentialsProvider;
import org.neo4j.cluster.statemachine.StateTransitionLogger;
import org.neo4j.cluster.timeout.TimeoutStrategy;
import org.neo4j.helpers.DaemonThreadFactory;
import org.neo4j.helpers.Factory;
import org.neo4j.helpers.HostnamePort;
import org.neo4j.helpers.NamedThreadFactory;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.logging.Logging;

/**
 * TODO
 */
public class NetworkedServerFactory
{
    private LifeSupport life;
    private ProtocolServerFactory protocolServerFactory;
    private TimeoutStrategy timeoutStrategy;
    private Logging logging;

    public NetworkedServerFactory( LifeSupport life, ProtocolServerFactory protocolServerFactory,
                                   TimeoutStrategy timeoutStrategy, Logging logging )
    {
        this.life = life;
        this.protocolServerFactory = protocolServerFactory;
        this.timeoutStrategy = timeoutStrategy;
        this.logging = logging;
    }

    public ProtocolServer newNetworkedServer( final Config config, AcceptorInstanceStore acceptorInstanceStore,
                                              ElectionCredentialsProvider electionCredentialsProvider )
    {
        final NetworkInstance node = new NetworkInstance( new NetworkInstance.Configuration()
        {
            @Override
            public HostnamePort clusterServer()
            {
                return config.get( ClusterSettings.cluster_server );
            }

            @Override
            public int defaultPort()
            {
                return 5001;
            }
        }, logging );

        ExecutorLifecycleAdapter stateMachineExecutor = new ExecutorLifecycleAdapter( new Factory<ExecutorService>()
        {
            @Override
            public ExecutorService newInstance()
            {
                return Executors.newSingleThreadExecutor( new NamedThreadFactory( "State machine" ) );
            }
        } );

        final ProtocolServer protocolServer = protocolServerFactory.newProtocolServer(
                new InstanceId( config.get( ClusterSettings.server_id ) ), timeoutStrategy, node, node,
                acceptorInstanceStore, electionCredentialsProvider, stateMachineExecutor );
        node.addNetworkChannelsListener( new NetworkInstance.NetworkChannelsListener()
        {
            @Override
            public void listeningAt( URI me )
            {
                protocolServer.listeningAt( me );
                protocolServer.addStateTransitionListener( new StateTransitionLogger( logging ) );
            }

            @Override
            public void channelOpened( URI to )
            {
            }

            @Override
            public void channelClosed( URI to )
            {
            }
        } );

        life.add( stateMachineExecutor );

        // Timeout timer - triggers every 10 ms
        life.add( new Lifecycle()
        {
            private ScheduledExecutorService scheduler;

            @Override
            public void init()
                    throws Throwable
            {
                protocolServer.getTimeouts().tick( System.currentTimeMillis() );
            }

            @Override
            public void start()
                    throws Throwable
            {
                scheduler = Executors.newSingleThreadScheduledExecutor( new DaemonThreadFactory( "timeout" ) );

                scheduler.scheduleWithFixedDelay( new Runnable()
                {
                    @Override
                    public void run()
                    {
                        long now = System.currentTimeMillis();

                        protocolServer.getTimeouts().tick( now );
                    }
                }, 0, 10, TimeUnit.MILLISECONDS );
            }

            @Override
            public void stop()
                    throws Throwable
            {
                scheduler.shutdownNow();
            }

            @Override
            public void shutdown()
                    throws Throwable
            {
            }
        } );

        // Add this last to ensure that timeout service is setup first
        life.add( node );

        return protocolServer;
    }
}
