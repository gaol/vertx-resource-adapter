/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.vertx.java.resourceadapter.cluster;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.vertx.java.core.spi.VertxSPI;
import org.vertx.java.core.spi.cluster.AsyncMap;
import org.vertx.java.core.spi.cluster.AsyncMultiMap;
import org.vertx.java.core.spi.cluster.ClusterManager;
import org.vertx.java.core.spi.cluster.NodeListener;

import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.Member;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;

/**
 * A cluster manager that uses Hazelcast
 * 
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
class HazelcastClusterManager implements ClusterManager, MembershipListener
{

   private static final Logger log = LoggerFactory.getLogger(HazelcastClusterManager.class);

   // Hazelcast config file

   private static final String DEFAULT_CONFIG_FILE = "default-cluster.xml";

   private static final String CONFIG_FILE = "cluster.xml";

   private final VertxSPI vertx;

   private HazelcastInstance hazelcast;

   private String nodeID;

   private NodeListener nodeListener;

   private boolean active;

   /**
    * Constructor
    */
   public HazelcastClusterManager(final VertxSPI vertx)
   {
      this.vertx = vertx;
      // We have our own shutdown hook and need to ensure ours runs before Hazelcast is shutdown
      System.setProperty("hazelcast.shutdownhook.enabled", "false");
   }

   public synchronized void join()
   {
      if (active)
      {
         return;
      }
      Config cfg = getConfig();
      if (cfg == null)
      {
         log.warn("Cannot find cluster configuration on classpath. Using default hazelcast configuration");
      }
      hazelcast = Hazelcast.newHazelcastInstance(cfg);
      nodeID = hazelcast.getCluster().getLocalMember().getUuid();
      hazelcast.getCluster().addMembershipListener(this);

      active = true;
   }

   /**
    * Every eventbus handler has an ID. SubsMap (subscriber map) is a MultiMap which 
    * maps handler-IDs with server-IDs and thus allows the eventbus to determine where 
    * to send messages.
    * 
    * @param name A unique name by which the the MultiMap can be identified within the cluster. 
    *     See the cluster config file (e.g. cluster.xml in case of HazelcastClusterManager) for
    *     additional MultiMap config parameters.
    * @return subscription map
    */
   public <K, V> AsyncMultiMap<K, V> getAsyncMultiMap(final String name)
   {
      com.hazelcast.core.MultiMap map = hazelcast.getMultiMap(name);
      return new HazelcastAsyncMultiMap(vertx, map);
   }

   @Override
   public String getNodeID()
   {
      return nodeID;
   }

   @Override
   public List<String> getNodes()
   {
      Set<Member> members = hazelcast.getCluster().getMembers();
      List<String> lMembers = new ArrayList<>();
      for (Member member : members)
      {
         lMembers.add(member.getUuid());
      }
      return lMembers;
   }

   @Override
   public void nodeListener(NodeListener listener)
   {
      this.nodeListener = listener;
   }

   @Override
   public <K, V> AsyncMap<K, V> getAsyncMap(String name)
   {
      IMap<K, V> map = hazelcast.getMap(name);
      return new HazelcastAsyncMap(vertx, map);
   }

   @Override
   public <K, V> Map<K, V> getSyncMap(String name)
   {
      IMap<K, V> map = hazelcast.getMap(name);
      return map;
   }

   public synchronized void leave()
   {
      if (!active)
      {
         return;
      }
      hazelcast.getCluster().removeMembershipListener(this);
      hazelcast.getLifecycleService().shutdown();
      active = false;
   }

   @Override
   public synchronized void memberAdded(MembershipEvent membershipEvent)
   {
      if (!active)
      {
         return;
      }
      try
      {
         if (nodeListener != null)
         {
            Member member = membershipEvent.getMember();
            nodeListener.nodeAdded(member.getUuid());
         }
      }
      catch (Throwable t)
      {
         log.error("Failed to handle memberAdded", t);
      }
   }

   @Override
   public synchronized void memberRemoved(MembershipEvent membershipEvent)
   {
      if (!active)
      {
         return;
      }
      try
      {
         if (nodeListener != null)
         {
            Member member = membershipEvent.getMember();
            nodeListener.nodeLeft(member.getUuid());
         }
      }
      catch (Throwable t)
      {
         log.error("Failed to handle memberRemoved", t);
      }
   }

   private InputStream getConfigStream()
   {
      InputStream is = readFromClusterFile();
      if (is == null)
      {
         is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE);
         if (is == null)
         {
            is = getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIG_FILE);
         }
      }
      return is;
   }

   private InputStream readFromClusterFile()
   {
      String clusterFile = System.getProperty("vertx.ra.cluster.file");
      if (clusterFile == null || clusterFile.trim().length() == 0)
      {
         return null;
      }
      InputStream is = getClass().getClassLoader().getResourceAsStream(clusterFile);
      if (is == null)
      {
         try
         {
            is = new FileInputStream(clusterFile);
         }
         catch (FileNotFoundException e)
         {
            log.warn("Can not find cluster file: " + clusterFile + ", use cluster.xml or default-cluster.xml instead.");
            return null;
         }
      }
      return is;
   }

   /**
      * Get the Hazelcast config
      * @return a config object
      */
   private Config getConfig()
   {
      Config cfg = null;
      try (InputStream is = getConfigStream(); InputStream bis = new BufferedInputStream(is))
      {
         if (is != null)
         {
            cfg = new XmlConfigBuilder(bis).build();
         }
      }
      catch (IOException ex)
      {
         log.error("Failed to read config", ex);
      }
      return cfg;
   }

}