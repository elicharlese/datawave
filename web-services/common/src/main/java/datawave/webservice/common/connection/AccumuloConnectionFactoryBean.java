package datawave.webservice.common.connection;

import datawave.accumulo.inmemory.InMemoryAccumuloClient;
import datawave.configuration.DatawaveEmbeddedProjectStageHolder;
import datawave.security.authorization.DatawavePrincipal;
import datawave.webservice.common.cache.AccumuloTableCache;
import datawave.webservice.common.connection.config.ConnectionPoolConfiguration;
import datawave.webservice.common.connection.config.ConnectionPoolsConfiguration;
import datawave.webservice.common.result.Connection;
import datawave.webservice.common.result.ConnectionFactoryResponse;
import datawave.webservice.common.result.ConnectionPool;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.admin.SecurityOperations;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.util.Pair;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.deltaspike.core.api.exclude.Exclude;
import org.apache.deltaspike.core.api.jmx.JmxManaged;
import org.apache.deltaspike.core.api.jmx.MBean;

import org.apache.log4j.Logger;
import org.jboss.resteasy.annotations.GZIP;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.annotation.security.DeclareRoles;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.annotation.security.RunAs;
import javax.ejb.EJBContext;
import javax.ejb.Local;
import javax.ejb.LocalBean;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import java.security.Principal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

@Path("/Common/AccumuloConnectionFactory")
@Produces({"application/xml", "text/xml", "application/json", "text/yaml", "text/x-yaml", "application/x-yaml", "text/html"})
@GZIP
@RunAs("InternalUser")
@RolesAllowed({"AuthorizedUser", "AuthorizedQueryServer", "AuthorizedServer", "InternalUser", "Administrator", "JBossAdministrator"})
@DeclareRoles({"AuthorizedUser", "AuthorizedQueryServer", "AuthorizedServer", "InternalUser", "Administrator", "JBossAdministrator"})
@Local(AccumuloConnectionFactory.class)
// declare a local EJB interface for users of this bean (@LocalBean would otherwise prevent using the interface)
@LocalBean
// declare a no-interface view so the JAX-RS annotations are honored for the singleton (otherwise a new instance will be created per-request)
@Startup
// tells the container to initialize on startup
@Singleton
// this is a singleton bean in the container
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
// transactions not supported directly by this bean
@Lock(LockType.READ)
@MBean
@Exclude(ifProjectStage = DatawaveEmbeddedProjectStageHolder.DatawaveEmbedded.class)
public class AccumuloConnectionFactoryBean implements AccumuloConnectionFactory {
    
    private Logger log = Logger.getLogger(this.getClass());
    
    @Resource
    private EJBContext context;
    
    @Inject
    private AccumuloTableCache cache;
    
    private Map<String,Map<Priority,AccumuloClientPool>> pools;
    
    @Inject
    private ConnectionPoolsConfiguration connectionPoolsConfiguration;
    
    private String defaultPoolName = null;
    
    @PostConstruct
    public void init() {
        this.pools = new HashMap<>();
        
        if (this.connectionPoolsConfiguration == null) {
            log.error("connectionPoolsConfiguration was null - aborting init()");
            return;
        }
        HashMap<String,Pair<String,PasswordToken>> instances = new HashMap<>();
        this.defaultPoolName = connectionPoolsConfiguration.getDefaultPool();
        for (Entry<String,ConnectionPoolConfiguration> entry : connectionPoolsConfiguration.getPools().entrySet()) {
            Map<Priority,AccumuloClientPool> p = new HashMap<>();
            ConnectionPoolConfiguration conf = entry.getValue();
            p.put(Priority.ADMIN, createConnectionPool(conf, conf.getAdminPriorityPoolSize()));
            p.put(Priority.HIGH, createConnectionPool(conf, conf.getHighPriorityPoolSize()));
            p.put(Priority.NORMAL, createConnectionPool(conf, conf.getNormalPriorityPoolSize()));
            p.put(Priority.LOW, createConnectionPool(conf, conf.getLowPriorityPoolSize()));
            this.pools.put(entry.getKey(), Collections.unmodifiableMap(p));
            try {
                setupMockAccumuloUser(conf, p.get(Priority.NORMAL), instances);
            } catch (Exception e) {
                log.error("Error configuring mock accumulo user for AccumuloConnectionFactoryBean.", e);
            }
            
            // Initialize the distributed tracing system. This needs to be done once at application startup. Since
            // it is tied to Accumulo connections, we do it here in this singleton bean.
            String appName = "datawave_ws";
            try {
                appName = System.getProperty("app", "datawave_ws");
            } catch (SecurityException e) {
                log.warn("Unable to retrieve system property \"app\": " + e.getMessage());
            }
        }
        
        cache.setConnectionFactory(this);
    }
    
    private AccumuloClientPool createConnectionPool(ConnectionPoolConfiguration conf, int limit) {
        AccumuloClientPoolFactory factory = new AccumuloClientPoolFactory(conf.getUsername(), conf.getPassword(), conf.getZookeepers(), conf.getInstance());
        AccumuloClientPool pool = new AccumuloClientPool(factory);
        pool.setTestOnBorrow(true);
        pool.setTestOnReturn(true);
        pool.setMaxTotal(limit);
        pool.setMaxIdle(-1);
        
        try {
            pool.addObject();
        } catch (Exception e) {
            log.error("Error pre-populating connection pool", e);
        }
        
        return pool;
    }
    
    private void setupMockAccumuloUser(ConnectionPoolConfiguration conf, AccumuloClientPool pool, HashMap<String,Pair<String,PasswordToken>> instances)
                    throws Exception {
        AccumuloClient c = null;
        try {
            c = pool.borrowObject(new HashMap<>());
            
            Pair<String,PasswordToken> pair = instances.get(cache.getInstance().getInstanceID());
            String user = "root";
            PasswordToken password = new PasswordToken(new byte[0]);
            if (pair != null && user.equals(pair.getFirst()))
                password = pair.getSecond();
            SecurityOperations security = cache.getInstance().getConnector(user, password).securityOperations();
            Set<String> users = security.listLocalUsers();
            if (!users.contains(conf.getUsername())) {
                security.createLocalUser(conf.getUsername(), new PasswordToken(conf.getPassword()));
                security.changeUserAuthorizations(conf.getUsername(), c.securityOperations().getUserAuthorizations(conf.getUsername()));
            } else {
                PasswordToken newPassword = new PasswordToken(conf.getPassword());
                // If we're changing root's password, and trying to change then keep track of that. If we have multiple instances
                // that specify mismatching passwords, then throw an error.
                if (user.equals(conf.getUsername())) {
                    if (pair != null && !newPassword.equals(pair.getSecond()))
                        throw new IllegalStateException(
                                        "Invalid AccumuloConnectionFactoryBean configuration--multiple pools are configured with different root passwords!");
                    instances.put(cache.getInstance().getInstanceID(), new Pair<>(conf.getUsername(), newPassword));
                }
                // match root's password on mock to the password on the actual Accumulo instance
                security.changeLocalUserPassword(conf.getUsername(), newPassword);
            }
        } finally {
            pool.returnObject(c);
        }
    }
    
    @PreDestroy
    public void tearDown() {
        for (Entry<String,Map<Priority,AccumuloClientPool>> entry : this.pools.entrySet()) {
            for (Entry<Priority,AccumuloClientPool> poolEntry : entry.getValue().entrySet()) {
                try {
                    poolEntry.getValue().close();
                } catch (Exception e) {
                    log.error("Error closing Accumulo Connection Pool: " + e);
                }
            }
        }
    }
    
    /**
     * @param poolName
     *            the name of the pool to query
     * @return name of the user used in the connection pools
     */
    public String getConnectionUserName(String poolName) {
        return connectionPoolsConfiguration.getPools().get(poolName).getUsername();
    }
    
    /**
     * Gets a client from the pool with the assigned priority
     *
     * Deprecated in 2.2.3, use getClient(String poolName, Priority priority, {@code Map<String, String> trackingMap)}
     *
     * @param priority
     *            the client's Priority
     * @return accumulo client
     * @throws Exception
     *             if there are issues
     */
    public AccumuloClient getClient(Priority priority, Map<String,String> trackingMap) throws Exception {
        return getClient(null, priority, trackingMap);
    }
    
    /**
     * Gets a client from the named pool with the assigned priority
     *
     * @param cpn
     *            the name of the pool to retrieve the client from
     * @param priority
     *            the priority of the client
     * @param tm
     *            the tracking map
     * @return Accumulo client
     * @throws Exception
     *             if there are issues
     */
    public AccumuloClient getClient(final String cpn, final Priority priority, final Map<String,String> tm) throws Exception {
        final Map<String,String> trackingMap = (tm != null) ? tm : new HashMap<>();
        final String poolName = (cpn != null) ? cpn : defaultPoolName;
        
        if (!priority.equals(Priority.ADMIN)) {
            final String userDN = getCurrentUserDN();
            if (userDN != null)
                trackingMap.put("user.dn", userDN);
            
            final Collection<String> proxyServers = getCurrentProxyServers();
            if (proxyServers != null)
                trackingMap.put("proxyServers", StringUtils.join(proxyServers, " -> "));
        }
        AccumuloClientPool pool = pools.get(poolName).get(priority);
        AccumuloClient c = pool.borrowObject(trackingMap);
        AccumuloClient mock = new InMemoryAccumuloClient(pool.getFactory().getUsername(), cache.getInstance());
        mock.securityOperations().changeLocalUserPassword(pool.getFactory().getUsername(), new PasswordToken(pool.getFactory().getPassword()));
        WrappedAccumuloClient wrappedAccumuloClient = new WrappedAccumuloClient(c, mock);
        String classLoaderContext = System.getProperty("dw.accumulo.classLoader.context");
        if (classLoaderContext != null) {
            wrappedAccumuloClient.setScannerClassLoaderContext(classLoaderContext);
        }
        String timeout = System.getProperty("dw.accumulo.scan.batch.timeout.seconds");
        if (timeout != null) {
            wrappedAccumuloClient.setScanBatchTimeoutSeconds(Long.parseLong(timeout));
        }
        return wrappedAccumuloClient;
    }
    
    /**
     * Returns the client to the pool with the associated priority.
     *
     * @param client
     *            The client to return
     */
    @PermitAll
    // permit anyone to return a connection
    public void returnClient(AccumuloClient client) {
        if (client instanceof WrappedAccumuloClient) {
            WrappedAccumuloClient wrappedAccumuloClient = (WrappedAccumuloClient) client;
            wrappedAccumuloClient.clearScannerClassLoaderContext();
            client = wrappedAccumuloClient.getReal();
        }
        for (Entry<String,Map<Priority,AccumuloClientPool>> entry : this.pools.entrySet()) {
            for (Entry<Priority,AccumuloClientPool> poolEntry : entry.getValue().entrySet()) {
                if (poolEntry.getValue().connectorCameFromHere(client)) {
                    poolEntry.getValue().returnObject(client);
                    return;
                }
            }
        }
        log.info("returnConnection called with connection that did not come from any AccumuloConnectionPool");
    }
    
    @PermitAll
    // permit anyone to get the report
    @JmxManaged
    public String report() {
        StringBuilder buf = new StringBuilder();
        for (Entry<String,Map<Priority,AccumuloClientPool>> entry : this.pools.entrySet()) {
            buf.append("**** ").append(entry.getKey()).append(" ****\n");
            buf.append("ADMIN: ").append(entry.getValue().get(Priority.ADMIN)).append("\n");
            buf.append("HIGH: ").append(entry.getValue().get(Priority.HIGH)).append("\n");
            buf.append("NORMAL: ").append(entry.getValue().get(Priority.NORMAL)).append("\n");
            buf.append("LOW: ").append(entry.getValue().get(Priority.LOW)).append("\n");
        }
        
        return buf.toString();
    }
    
    /**
     * <strong>JBossAdministrator or Administrator credentials required.</strong> Returns metrics for the AccumuloConnectionFactoryBean
     *
     * @return datawave.webservice.common.ConnectionFactoryResponse
     * @RequestHeader X-ProxiedEntitiesChain use when proxying request for user
     * @RequestHeader X-ProxiedIssuersChain required when using X-ProxiedEntitiesChain, specify one issuer DN per subject DN listed in X-ProxiedEntitiesChain
     * @HTTP 200 success
     * @HTTP 500 internal server error
     */
    @GET
    @Path("/stats")
    @RolesAllowed({"Administrator", "JBossAdministrator", "InternalUser"})
    public ConnectionFactoryResponse getConnectionFactoryMetrics() {
        ConnectionFactoryResponse response = new ConnectionFactoryResponse();
        ArrayList<ConnectionPool> connectionPools = new ArrayList<>();
        
        Set<String> exclude = new HashSet<>();
        exclude.add("connection.state.start");
        exclude.add("state");
        exclude.add("request.location");
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
        
        for (Entry<String,Map<Priority,AccumuloClientPool>> entry : this.pools.entrySet()) {
            for (Entry<Priority,AccumuloClientPool> entry2 : entry.getValue().entrySet()) {
                String poolName = entry.getKey();
                Priority priority = entry2.getKey();
                AccumuloClientPool p = entry2.getValue();
                
                long now = System.currentTimeMillis();
                MutableInt maxActive = new MutableInt();
                MutableInt numActive = new MutableInt();
                MutableInt maxIdle = new MutableInt();
                MutableInt numIdle = new MutableInt();
                MutableInt numWaiting = new MutableInt();
                // getConnectionPoolStats will collect the tracking maps and maxActive, numActive, maxIdle, numIdle while synchronized
                // to ensure consistency between the GenericObjectPool and the tracking maps
                List<Map<String,String>> requestingConnectionsMap = p.getConnectionPoolStats(maxActive, numActive, maxIdle, numIdle, numWaiting);
                
                ConnectionPool poolInfo = new ConnectionPool();
                poolInfo.setPriority(priority.name());
                poolInfo.setMaxActive(maxActive.toInteger());
                poolInfo.setNumActive(numActive.toInteger());
                poolInfo.setNumWaiting(numWaiting.toInteger());
                poolInfo.setMaxIdle(maxIdle.toInteger());
                poolInfo.setNumIdle(numIdle.toInteger());
                poolInfo.setPoolName(poolName);
                
                List<Connection> requestingConnections = new ArrayList<>();
                for (Map<String,String> m : requestingConnectionsMap) {
                    Connection c = new Connection();
                    String state = m.get("state");
                    if (state != null) {
                        c.setState(state);
                    }
                    String requestLocation = m.get("request.location");
                    if (requestLocation != null) {
                        c.setRequestLocation(requestLocation);
                    }
                    String stateStart = m.get("connection.state.start");
                    if (stateStart != null) {
                        long stateStartLong = Long.parseLong(stateStart);
                        c.setTimeInState((now - stateStartLong));
                        Date stateStartDate = new Date(stateStartLong);
                        c.addProperty("connection.state.start", sdf.format(stateStartDate));
                    }
                    for (Map.Entry<String,String> e : m.entrySet()) {
                        if (!exclude.contains(e.getKey())) {
                            c.addProperty(e.getKey(), e.getValue());
                        }
                    }
                    requestingConnections.add(c);
                }
                Collections.sort(requestingConnections);
                poolInfo.setConnectionRequests(requestingConnections);
                connectionPools.add(poolInfo);
            }
        }
        response.setConnectionPools(connectionPools);
        return response;
    }
    
    @PermitAll
    @JmxManaged
    public int getConnectionUsagePercent() {
        double maxPercentage = 0.0;
        for (Entry<String,Map<Priority,AccumuloClientPool>> entry : pools.entrySet()) {
            for (Entry<Priority,AccumuloClientPool> poolEntry : entry.getValue().entrySet()) {
                // Don't include ADMIN priority connections when computing a usage percentage
                if (Priority.ADMIN.equals(poolEntry.getKey()))
                    continue;
                
                MutableInt maxActive = new MutableInt();
                MutableInt numActive = new MutableInt();
                MutableInt numWaiting = new MutableInt();
                MutableInt unused = new MutableInt();
                poolEntry.getValue().getConnectionPoolStats(maxActive, numActive, unused, unused, numWaiting);
                
                double percentage = (numActive.doubleValue() + numWaiting.doubleValue()) / maxActive.doubleValue();
                if (percentage > maxPercentage) {
                    maxPercentage = percentage;
                }
            }
        }
        return (int) (maxPercentage * 100);
    }
    
    @Override
    @PermitAll
    public Map<String,String> getTrackingMap(StackTraceElement[] stackTrace) {
        HashMap<String,String> trackingMap = new HashMap<>();
        if (stackTrace != null) {
            StackTraceElement ste = stackTrace[1];
            trackingMap.put("request.location", ste.getClassName() + "." + ste.getMethodName() + ":" + ste.getLineNumber());
        }
        
        return trackingMap;
    }
    
    public String getCurrentUserDN() {
        
        String currentUserDN = null;
        Principal p = context.getCallerPrincipal();
        
        if (p instanceof DatawavePrincipal) {
            currentUserDN = ((DatawavePrincipal) p).getUserDN().subjectDN();
        }
        
        return currentUserDN;
    }
    
    public Collection<String> getCurrentProxyServers() {
        List<String> currentProxyServers = null;
        Principal p = context.getCallerPrincipal();
        
        if (p instanceof DatawavePrincipal) {
            currentProxyServers = ((DatawavePrincipal) p).getProxyServers();
        }
        
        return currentProxyServers;
    }
}
