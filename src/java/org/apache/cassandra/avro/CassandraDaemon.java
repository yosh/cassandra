/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.avro;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.apache.avro.ipc.SocketServer;
import org.apache.avro.specific.SpecificResponder;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.CompactionManager;
import org.apache.cassandra.db.SystemTable;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

/**
 * The Avro analogue to org.apache.cassandra.service.CassandraDaemon.
 *
 */
public class CassandraDaemon {
    private static Logger logger = Logger.getLogger(CassandraDaemon.class);
    private SocketServer server;
    private InetAddress listenAddr;
    private int listenPort;
    
    private void setup() throws IOException
    {
        // log4j
        String file = System.getProperty("storage-config") + File.separator + "log4j.properties";
        PropertyConfigurator.configure(file);

        listenPort = DatabaseDescriptor.getThriftPort();
        listenAddr = DatabaseDescriptor.getThriftAddress();
        
        /* 
         * If ThriftAddress was left completely unconfigured, then assume
         * the same default as ListenAddress
         */
        if (listenAddr == null)
            listenAddr = FBUtilities.getLocalAddress();
        
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler()
        {
            public void uncaughtException(Thread t, Throwable e)
            {
                logger.error("Fatal exception in thread " + t, e);
                if (e instanceof OutOfMemoryError)
                {
                    System.exit(100);
                }
            }
        });
        
        // check the system table for mismatched partitioner.
        try
        {
            SystemTable.checkHealth();
        }
        catch (IOException e)
        {
            logger.error("Fatal exception during initialization", e);
            System.exit(100);
        }

        // initialize keyspaces
        for (String table : DatabaseDescriptor.getTables())
        {
            if (logger.isDebugEnabled())
                logger.debug("opening keyspace " + table);
            Table.open(table);
        }

        // replay the log if necessary and check for compaction candidates
        CommitLog.recover();
        CompactionManager.instance.checkAllColumnFamilies();

        // start server internals
        StorageService.instance.initServer();

    }
    
    /** hook for JSVC */
    public void load(String[] arguments) throws IOException
    {
        setup();
    }
    
    /** hook for JSVC */
    public void start() throws IOException
    {
        if (logger.isDebugEnabled())
            logger.debug(String.format("Binding avro service to %s:%s", listenAddr, listenPort));
        InetSocketAddress socketAddress = new InetSocketAddress(listenAddr, listenPort);
        SpecificResponder responder = new SpecificResponder(Cassandra.class, new CassandraServer());
        
        logger.info("Cassandra starting up...");
        server = new SocketServer(responder, socketAddress);
    }
    
    /** hook for JSVC */
    public void stop()
    {
        logger.info("Cassandra shutting down...");
        server.close();
    }
    
    /** hook for JSVC */
    public void destroy()
    {
    }
    
    public static void main(String[] args) {
        CassandraDaemon daemon = new CassandraDaemon();
        String pidFile = System.getProperty("cassandra-pidfile");
        
        try
        {   
            daemon.setup();

            if (pidFile != null)
            {
                new File(pidFile).deleteOnExit();
            }

            if (System.getProperty("cassandra-foreground") == null)
            {
                System.out.close();
                System.err.close();
            }

            daemon.start();
        }
        catch (Throwable e)
        {
            String msg = "Exception encountered during startup.";
            logger.error(msg, e);

            // try to warn user on stdout too, if we haven't already detached
            System.out.println(msg);
            e.printStackTrace();

            System.exit(3);
        }
    }

}
